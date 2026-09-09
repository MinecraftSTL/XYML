/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2026 huangyuhui <huanghongxun2008@126.com> and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package space.minecraftstl.xyml.nbt;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import space.minecraftstl.xyml.library.nbt.NBTElement;
import space.minecraftstl.xyml.library.nbt.io.NBTFile;
import space.minecraftstl.xyml.library.nbt.io.NBTReadLimits;
import space.minecraftstl.xyml.library.nbt.io.NBTSaveOptions;
import space.minecraftstl.xyml.task.CompletableFutureTask;
import space.minecraftstl.xyml.task.Schedulers;
import space.minecraftstl.xyml.task.Task;
import space.minecraftstl.xyml.task.TaskCompletableFuture;
import space.minecraftstl.xyml.task.TaskExecutor;
import space.minecraftstl.xyml.task.TaskListener;
import space.minecraftstl.xyml.task.TaskResource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Callable;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static space.minecraftstl.xyml.util.logging.Logger.LOG;

/// Dispatches safe XoyzNBT file-session operations through the task resource arbiter.
///
/// A document session is represented by one [CompletableFutureTask] whose resource lease remains held from the
/// asynchronous open through [#close(NBTDocument)]. Saves are serialized behind that session owner, so a region
/// channel, external companion, backup, or staging sibling cannot race another launcher task. Pure
/// in-memory editor operations continue to use [#supplyAsync(Supplier)] and do not claim a filesystem resource.
///
/// Compression detection, tolerant/strict parsing, structural validation, staging, region copy-on-write publication,
/// and savepoint handling remain inside [NBTFile]. This adapter only chooses the file entry point and launcher backup
/// policy.
@NotNullByDefault
public final class NBTDocumentService {
    /// Executor that owns all blocking NBT and filesystem operations.
    private final Executor ioExecutor;

    /// Process-wide open sessions indexed by document object so any service facade reuses the owning task token.
    private static final Map<NBTDocument, DocumentSession> SESSIONS = new ConcurrentHashMap<>();

    /// Creates a service using the launcher's shared I/O scheduler.
    public NBTDocumentService() {
        this(Schedulers.io());
    }

    /// Creates a service whose operations are dispatched to the supplied executor.
    ///
    /// The executor remains caller-owned and is never shut down by this service. It must continue executing every
    /// accepted command until documents opened through this service have been closed; discarding an accepted open or
    /// save command can leave that caller-visible operation pending because the generic [Executor] API reports no such
    /// discard to this service.
    ///
    /// @param ioExecutor executor for blocking file-session work
    public NBTDocumentService(Executor ioExecutor) {
        this.ioExecutor = Objects.requireNonNull(ioExecutor, "ioExecutor");
    }

    /// Opens one supported NBT file on the configured background executor.
    ///
    /// The returned future is completed as soon as the document is available, while the underlying task keeps its
    /// resource lease until the document is closed. Cancelling the future cancels the task and closes a session which
    /// races publication.
    ///
    /// @param file candidate source path
    /// @return cancellable future loaded document
    public CompletableFuture<NBTDocument> open(Path file) {
        Path normalized = Objects.requireNonNull(file, "file").toAbsolutePath().normalize();
        return new DocumentSession(normalized).start();
    }

    /// Reopens a document within its existing resource-owning session.
    ///
    /// The replacement is opened while the current session lease remains held, then the session switches to the new
    /// document and closes the old handle. This is the resource-safe form of an editor reload: an independent
    /// [#open(Path)] for the same file must still wait until the session closes, but a replacement cannot deadlock
    /// behind the document it is about to replace. When the document is not managed by this service, this method
    /// opens a new independent session using the document's normalized source path.
    ///
    /// @param document currently open document
    /// @return cancellable future containing the replacement document
    public CompletableFuture<NBTDocument> reload(NBTDocument document) {
        NBTDocument selected = Objects.requireNonNull(document, "document");
        @Nullable DocumentSession session = SESSIONS.get(selected);
        if (session != null) {
            return session.reload(selected, ioExecutor);
        }
        return open(selected.file());
    }

    /// Saves one document through its owning session task.
    ///
    /// Every standalone TAG file receives a single rolling sibling backup ending in `.xyml_old`. When the source
    /// itself ends in `.xyml_old`, the next generation is retained as `<source>.xyml_old` as well. Region sessions
    /// claim their containing region directory because publication may touch external `.mcc` companions, temporary
    /// siblings.
    ///
    /// @param document open document
    /// @return future completed after publication finishes
    public CompletableFuture<Void> save(NBTDocument document) {
        NBTDocument selected = Objects.requireNonNull(document, "document");
        @Nullable DocumentSession session = SESSIONS.get(selected);
        if (session != null) {
            return session.save(selected, ioExecutor);
        }
        if (selected.isClosed()) {
            return CompletableFuture.failedFuture(new IllegalStateException("NBT document is closed"));
        }

        NBTSaveOptions options = saveOptions(selected);
        Task<@Nullable Void> task = Task.runAsync("Save NBT document", ioExecutor, () ->
                selected.saveFromService(options))
                .setSignificance(Task.TaskSignificance.MINOR);
        return executeOneShot(withResources(task, resourcesFor(selected.file(), selected.fileType(), options)));
    }

    /// Closes one document through its owning session task without publishing pending edits.
    ///
    /// Closure is idempotent. A session task retains its resource lease until this operation has closed all library
    /// channels and removed its owned publication sidecars.
    ///
    /// @param document document whose file session must be released
    /// @return future completed after all owned file handles are closed
    public CompletableFuture<Void> close(NBTDocument document) {
        NBTDocument selected = Objects.requireNonNull(document, "document");
        @Nullable DocumentSession session = SESSIONS.get(selected);
        if (session != null) {
            return session.close(ioExecutor);
        }
        if (selected.isClosed()) {
            return CompletableFuture.completedFuture(null);
        }

        Task<@Nullable Void> task = Task.runAsync("Close NBT document", ioExecutor, selected::close)
                .setSignificance(Task.TaskSignificance.MINOR);
        return executeOneShot(withResources(task, resourcesFor(selected.file(), selected.fileType(), null)));
    }

    /// Runs one non-null in-memory document operation on the configured background executor.
    ///
    /// The operation remains responsible for synchronizing access to its document. This scheduling boundary is
    /// intentionally not a filesystem task: callers use it for detached snapshots, validation, and editor-memory
    /// mutations only. Any future file I/O must be added as a resource-bearing operation above.
    ///
    /// @param operation non-null result supplier
    /// @param <T> result type
    /// @return future operation result
    public <T> CompletableFuture<T> supplyAsync(Supplier<? extends T> operation) {
        Supplier<? extends T> selected = Objects.requireNonNull(operation, "operation");
        return CompletableFuture.supplyAsync(
                () -> Objects.requireNonNull(selected.get(), "operation result"),
                ioExecutor);
    }

    /// Starts one ordinary task and bridges its terminal listener to a cancellable future without exposing its lock
    /// state. The task's own future remains authoritative, so cancelling the returned view cannot suppress release.
    ///
    /// @param task task to execute
    /// @param <T> task result type
    /// @return independently cancellable result view
    private <T> CompletableFuture<T> executeOneShot(Task<T> task) {
        final TaskExecutor[] executorHolder = new TaskExecutor[1];
        CancellationAwareFuture<T> result = new CancellationAwareFuture<>(() -> {
            TaskExecutor executor = executorHolder[0];
            if (executor != null && !executor.isCancelled()) {
                executor.cancel();
            }
        });
        TaskExecutor executor = task.executor(new TaskListener() {
            @Override
            public void onStop(boolean success, TaskExecutor stoppedExecutor) {
                if (success) {
                    result.complete(task.getResult());
                } else {
                    result.completeExceptionally(terminalFailure(stoppedExecutor));
                }
            }
        });
        executorHolder[0] = executor;
        try {
            executor.start();
        } catch (RuntimeException failure) {
            result.completeExceptionally(failure);
        } catch (Error failure) {
            result.completeExceptionally(failure);
            throw failure;
        }
        return result;
    }

    /// Selects the resource declarations for one NBT file transaction.
    ///
    /// Region publication is directory-scoped because the library may create or replace files whose names are not
    /// known before reading the region header. Standalone publication names every deterministic source, staging
    /// sibling, and backup so independent sessions cannot target the same sidecar concurrently.
    ///
    /// @param file normalized NBT path
    /// @param fileType filename-derived type
    /// @param options save options, or null for open/close
    /// @return immutable resource declarations
    private static @Unmodifiable List<TaskResource> resourcesFor(
            Path file,
            NBTFileType fileType,
            @Nullable NBTSaveOptions options) {
        ArrayList<TaskResource> resources = new ArrayList<>();
        if (fileType == NBTFileType.ANVIL || fileType == NBTFileType.REGION) {
            addParentDirectoryResource(resources, file);
        }
        resources.add(TaskResource.nbtFile(file));
        if (fileType == NBTFileType.TAG && options != null) {
            resources.add(TaskResource.nbtFile(stagingPath(file)));
        }
        if (options != null && options.backupPath() != null) {
            Path backup = Objects.requireNonNull(options.backupPath(), "backupPath");
            resources.add(TaskResource.nbtFile(backup));
            resources.add(TaskResource.nbtFile(stagingPath(backup)));
        }
        return List.copyOf(resources);
    }

    /// Adds the directory containing one publication path when it has a parent.
    ///
    /// @param resources mutable resource accumulator
    /// @param file publication path
    private static void addParentDirectoryResource(ArrayList<TaskResource> resources, Path file) {
        @Nullable Path parent = file.getParent();
        if (parent != null) {
            resources.add(TaskResource.nbtDirectory(parent));
        }
    }

    /// Applies a non-empty immutable resource list to a task.
    private static <T> Task<T> withResources(Task<T> task, @Unmodifiable List<TaskResource> resources) {
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(resources, "resources");
        if (resources.isEmpty()) {
            throw new IllegalArgumentException("NBT task resources cannot be empty");
        }
        TaskResource first = resources.get(0);
        TaskResource[] additional = resources.subList(1, resources.size()).toArray(TaskResource[]::new);
        return task.setResources(first, additional);
    }

    /// Selects the library entry point for one normalized source.
    ///
    /// @param file normalized absolute path
    /// @return lifecycle-bound launcher document
    /// @throws IOException if type detection or bounded tolerant recovery fails
    private static NBTDocument openOnExecutor(Path file) throws IOException {
        @Nullable NBTFileType fileType = NBTFileType.detect(file);
        if (fileType == null) {
            throw new IOException("Unsupported NBT file extension: " + file);
        }
        validateExistingSource(file);
        NBTFile<? extends NBTElement> session = switch (fileType) {
            case TAG -> NBTFile.openTagTolerant(file);
            case ANVIL, REGION -> NBTFile.openRegionTolerant(file, NBTReadLimits.defaults());
        };
        return new NBTDocument(fileType, session);
    }

    /// Rejects missing, special, and symbolic-link sources before a region open can create or follow them.
    ///
    /// @param file normalized candidate source
    /// @throws IOException if the source is not an existing regular non-link file
    private static void validateExistingSource(Path file) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(
                file,
                BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile() || attributes.isSymbolicLink()) {
            throw new IOException("NBT source is not a regular non-link file: " + file);
        }
    }

    /// Selects the rolling-backup policy for one launcher document.
    ///
    /// @param document document about to be saved
    /// @return immutable generic XoyzNBT save options
    private static NBTSaveOptions saveOptions(NBTDocument document) {
        if (document.fileType() != NBTFileType.TAG) {
            return NBTSaveOptions.withoutBackup();
        }
        return saveOptions(document.file());
    }

    /// Selects a deterministic standalone backup from one normalized source path.
    ///
    /// @param file standalone NBT source
    /// @return immutable generic XoyzNBT save options
    private static NBTSaveOptions saveOptions(Path file) {
        @Nullable Path fileName = file.getFileName();
        if (fileName == null) {
            return NBTSaveOptions.withoutBackup();
        }
        String name = fileName.toString();
        @Nullable Path parent = file.getParent();
        if (parent == null) {
            return NBTSaveOptions.withoutBackup();
        }
        return NBTSaveOptions.withBackup(parent.resolve(name + ".xyml_old"));
    }

    /// Returns the deterministic standalone staging sibling used by the library publication path.
    ///
    /// The library owns creation and cleanup of this path. The service declares it as a task resource so a second
    /// process-local session cannot observe or overwrite an in-flight staged document.
    ///
    /// @param file normalized standalone source
    /// @return deterministic staging sibling
    private static Path stagingPath(Path file) {
        @Nullable Path fileName = Objects.requireNonNull(file, "file").getFileName();
        if (fileName == null) {
            throw new IllegalArgumentException("NBT source must have a filename: " + file);
        }
        @Nullable Path parent = file.getParent();
        if (parent == null) {
            throw new IllegalArgumentException("NBT source must have a parent directory: " + file);
        }
        return parent.resolve(fileName.toString() + ".xyml_new");
    }

    /// Returns the terminal failure recorded by one task executor, preserving cancellation classification.
    private static Throwable terminalFailure(TaskExecutor executor) {
        @Nullable Throwable failure = executor.getFailure();
        return failure == null ? new CancellationException("NBT task was cancelled") : failure;
    }

    /// Delivers a caller-visible completion away from the operation worker.
    ///
    /// CompletableFuture invokes synchronous dependents on the thread that calls `complete`. Keeping those callbacks
    /// off a caller-owned I/O worker prevents a continuation such as `save().thenRun(() -> close().join())` from
    /// waiting for work queued behind the same worker. The inline fallback is used only when the common scheduler is
    /// itself unavailable during process shutdown.
    ///
    /// @param action completion action
    private static void completeOnContinuation(Runnable action) {
        Runnable selected = Objects.requireNonNull(action, "action");
        try {
            Schedulers.defaultScheduler().execute(selected);
        } catch (RuntimeException | Error dispatchFailure) {
            LOG.warning("Completion scheduler rejected an NBT continuation; completing inline", dispatchFailure);
            selected.run();
        }
    }

    /// Keeps one document's resource owner alive from open publication until explicit close.
    @NotNullByDefault
    private final class DocumentSession {
        /// Normalized source path captured at session creation.
        private final Path path;

        /// Resource declarations covering the session and all known publication sidecars.
        private final @Unmodifiable List<TaskResource> resources;

        /// Future completed when open has either published a document or failed.
        private final CompletableFuture<Void> openReady = new CompletableFuture<>();

        /// Internal task result; completion releases the session lease.
        private final CompletableFuture<NBTDocument> terminal = new CompletableFuture<>();

        /// Caller-visible open result whose cancellation requests session shutdown.
        private final CancellationAwareFuture<NBTDocument> openResult;

        /// Serializes operation queue state and document ownership.
        private final Object operationLock = new Object();

        /// Tail of save/close operations, initialized to the open barrier.
        private CompletableFuture<Void> operationTail = openReady;

        /// Session task carrying the actual resource lease.
        private final SessionTask task;

        /// Executor started for this session, or null before startup.
        private @Nullable TaskExecutor executor;

        /// Open document, or null before publication/after closure.
        private @Nullable NBTDocument document;

        /// Document whose physical close call is currently in progress.
        ///
        /// A replacement closes its former handle after the new handle becomes current. Tracking the explicit close
        /// target lets the callback distinguish that stale replacement from a service-owned close whose current pointer
        /// was cleared before invoking the library.
        private @Nullable NBTDocument closingDocument;

        /// Document being closed as part of a reload before a replacement is published.
        ///
        /// Its physical close callback must not terminate the session: the original document remains the current
        /// pointer until the close succeeds and the replacement is installed atomically.
        private @Nullable NBTDocument reloadingDocument;

        /// Replacement or late-open documents whose physical cleanup failed and remains retryable in this session.
        ///
        /// Keeping the actual document object here is important: a failed close may still own a region channel or a
        /// publication sidecar. The session lease must not be released, and a later close/reload/save must retry this
        /// exact handle instead of opening a second untracked handle for the same path.
        private final List<NBTDocument> pendingCleanupDocuments = new ArrayList<>();

        /// Current document whose close completed while one or more pending cleanup documents remained.
        ///
        /// Physical session completion is deferred until [#pendingCleanupDocuments] becomes empty.
        private @Nullable NBTDocument deferredClosedDocument;

        /// Whether cancellation or close has been requested.
        private boolean closeRequested;

        /// Whether the accepted open command has entered its protected execution boundary.
        private boolean openStarted;

        /// Internal idempotent close result, or null before the first close request.
        private @Nullable CompletableFuture<Void> closeResult;

        /// Whether the library session has already reported physical closure.
        private boolean physicalCloseObserved;

        /// Cleanup failure from an old handle which was replaced successfully.
        ///
        /// The replacement remains usable, but the failure is retained and reported together with the eventual session
        /// close instead of being discarded by the reload boundary.
        private @Nullable Throwable replacementCloseFailure;

        /// Most recent physical close failure, or null after a successful close/while no attempt failed.
        private @Nullable Throwable physicalCloseFailure;

        /// Terminal failure visible to the open caller but deferred until physical document closure releases the lease.
        private @Nullable Throwable deferredTerminalFailure;

        /// Creates one session with a stable resource snapshot.
        ///
        /// @param path normalized source path
        private DocumentSession(Path path) {
            this.path = Objects.requireNonNull(path, "path");
            @Nullable NBTFileType detected = NBTFileType.detect(path);
            @Nullable NBTSaveOptions options = detected == NBTFileType.TAG
                    ? saveOptions(path)
                    : null;
            this.resources = resourcesFor(
                    path,
                    detected == null ? NBTFileType.TAG : detected,
                    options);
            this.openResult = new CancellationAwareFuture<>(this::requestCancel);
            this.task = new SessionTask();
        }

        /// Starts the session task and returns its early document-publication view.
        private CompletableFuture<NBTDocument> start() {
            TaskExecutor startedExecutor = task.executor(new TaskListener() {
                @Override
                public void onStop(boolean success, TaskExecutor stoppedExecutor) {
                    stopped(success, stoppedExecutor);
                }
            });
            synchronized (operationLock) {
                executor = startedExecutor;
            }
            try {
                startedExecutor.start();
            } catch (RuntimeException failure) {
                openFailed(failure);
            } catch (Error failure) {
                openFailed(failure);
                throw failure;
            }
            return openResult;
        }

        /// Queues one save behind the session open barrier and every earlier publication operation.
        private CompletableFuture<Void> save(NBTDocument selected, Executor operationExecutor) {
            NBTSaveOptions options;
            try {
                options = saveOptions(Objects.requireNonNull(selected, "document"));
            } catch (RuntimeException failure) {
                return CompletableFuture.failedFuture(failure);
            }
            return enqueue(() -> {
                retryPendingCleanup();
                selected.saveFromService(options);
                return null;
            }, operationExecutor);
        }

        /// Reopens the current source under this session's already-held resource lease.
        ///
        /// The old document remains available if opening or closing the replacement fails. The old handle is closed
        /// while the session lease remains held, and the current pointer is switched only after physical closure
        /// succeeds; a failed close therefore leaves the exact old handle available for a later retry.
        ///
        /// @param selected document expected to be the current session document
        /// @param operationExecutor executor for blocking replacement work
        /// @return cancellable replacement result
        private CompletableFuture<NBTDocument> reload(NBTDocument selected, Executor operationExecutor) {
            Objects.requireNonNull(selected, "document");
            Objects.requireNonNull(operationExecutor, "operationExecutor");
            ReloadFuture visible = new ReloadFuture();
            CompletableFuture<NBTDocument> operation = enqueue(
                    () -> reloadOnExecutor(selected, visible),
                    operationExecutor);
            operation.whenComplete((@Nullable NBTDocument replacement, @Nullable Throwable failure) -> {
                if (failure != null) {
                    visible.completeFailure(failure);
                } else if (replacement == null) {
                    visible.completeFailure(new IllegalStateException("NBT reload produced no replacement document"));
                } else if (!visible.completeCommitted(replacement)) {
                    closeReplacementIfCurrent(replacement, operationExecutor);
                }
            });
            return visible;
        }

        /// Closes a replacement whose caller-visible future was cancelled after installation.
        ///
        /// @param replacement replacement document
        /// @param operationExecutor executor preferred for physical closure
        private void closeReplacementIfCurrent(NBTDocument replacement, Executor operationExecutor) {
            synchronized (operationLock) {
                if (document != replacement || closeRequested) {
                    return;
                }
            }
            close(operationExecutor);
        }

        /// Opens and installs one replacement document without releasing the session lease.
        ///
        /// @param selected current document
        /// @param visible caller-visible result which arbitrates cancellation against replacement installation
        /// @return newly opened document
        private NBTDocument reloadOnExecutor(NBTDocument selected, ReloadFuture visible) throws IOException {
            synchronized (operationLock) {
                if (closeRequested || document != selected || physicalCloseObserved || visible.isCancelled()) {
                    throw new CancellationException("NBT document reload was cancelled");
                }
            }

            @Nullable NBTDocument replacement = null;
            boolean installed = false;
            try {
                retryPendingCleanup();
                NBTDocument created = NBTDocumentService.openOnExecutor(path);
                replacement = created;
                synchronized (operationLock) {
                    if (closeRequested
                            || document != selected
                            || physicalCloseObserved
                            || !visible.beginCommit()) {
                        throw new CancellationException("NBT document reload was cancelled");
                    }
                    reloadingDocument = selected;
                }
                Throwable closeFailure = null;
                try {
                    selected.close();
                } catch (Throwable failure) {
                    closeFailure = failure;
                }
                if (closeFailure != null && !selected.isClosed()) {
                    synchronized (operationLock) {
                        reloadingDocument = null;
                    }
                    if (closeFailure instanceof IOException) {
                        throw (IOException) closeFailure;
                    }
                    if (closeFailure instanceof RuntimeException) {
                        throw (RuntimeException) closeFailure;
                    }
                    if (closeFailure instanceof Error) {
                        throw (Error) closeFailure;
                    }
                    throw new IOException("NBT reload could not close the old document", closeFailure);
                }
                if (closeFailure != null) {
                    // A listener can throw after the library has physically closed the old handle. The replacement
                    // is still safe to publish, while the callback failure remains visible at session close.
                    synchronized (operationLock) {
                        replacementCloseFailure = mergeFailures(replacementCloseFailure, closeFailure);
                    }
                    LOG.warning("NBT reload replaced a document whose old handle reported a close failure",
                            closeFailure);
                }
                synchronized (operationLock) {
                    if (!selected.isClosed()) {
                        reloadingDocument = null;
                        throw new IOException("NBT reload closed the old document without observing closure");
                    }
                    created.setClosedListener(failure -> documentClosed(created, failure));
                    SESSIONS.put(created, this);
                    document = created;
                    installed = true;
                    reloadingDocument = null;
                }
                if (closeFailure instanceof Error) {
                    throw (Error) closeFailure;
                }
                if (closeFailure != null) {
                    // Keep the replacement usable; the physical close already succeeded and only its callback
                    // presentation failed.
                    return created;
                }
                return created;
            } catch (IOException | RuntimeException | Error failure) {
                boolean selectedClosed = false;
                synchronized (operationLock) {
                    if (reloadingDocument == selected) {
                        reloadingDocument = null;
                    }
                    if (!installed && document == selected && selected.isClosed()) {
                        // The old handle can be physically closed before a replacement-installation callback throws.
                        // Mark it as the close target so the normal completion path cannot mistake it for a live
                        // current document and leave the session future pending forever.
                        closingDocument = selected;
                        selectedClosed = true;
                    }
                }
                if (replacement != null && !installed) {
                    @Nullable Throwable cleanupFailure = closeAfterCancelledOpen(replacement);
                    if (cleanupFailure != null && cleanupFailure != failure) {
                        failure.addSuppressed(cleanupFailure);
                        retainPendingCleanup(replacement, cleanupFailure);
                    }
                }
                if (selectedClosed) {
                    documentClosed(selected, null);
                }
                throw failure;
            }
        }

        /// Retries every retained replacement cleanup before another operation can touch the source.
        ///
        /// A failed cleanup is deliberately surfaced to the caller. The corresponding document remains in the
        /// pending list, so a later retry uses the same handle and does not release the task resource lease early.
        ///
        /// @throws IOException when a retained document cannot be closed
        private void retryPendingCleanup() throws IOException {
            @Unmodifiable List<NBTDocument> pending;
            synchronized (operationLock) {
                pending = List.copyOf(pendingCleanupDocuments);
            }
            @Nullable Throwable firstFailure = null;
            for (NBTDocument pendingDocument : pending) {
                try {
                    pendingDocument.close();
                } catch (Throwable failure) {
                    if (firstFailure == null) {
                        firstFailure = failure;
                    } else if (firstFailure != failure) {
                        firstFailure.addSuppressed(failure);
                    }
                }
                if (pendingDocument.isClosed()) {
                    documentClosed(pendingDocument, null);
                }
            }
            if (firstFailure == null) {
                synchronized (operationLock) {
                    if (!pendingCleanupDocuments.isEmpty()) {
                        firstFailure = new IOException("NBT replacement cleanup remains pending");
                    }
                }
            }
            if (firstFailure != null) {
                if (firstFailure instanceof IOException failure) {
                    throw failure;
                }
                if (firstFailure instanceof RuntimeException failure) {
                    throw failure;
                }
                if (firstFailure instanceof Error failure) {
                    throw failure;
                }
                throw new IOException("NBT replacement cleanup failed", firstFailure);
            }
        }

        /// Requests idempotent closure and returns a view that cannot cancel the internal close task.
        private CompletableFuture<Void> close(Executor operationExecutor) {
            CompletableFuture<Void> visible;
            @Nullable CompletableFuture<Void> predecessor = null;
            @Nullable CompletableFuture<Void> closeStage = null;
            @Nullable Throwable observedFailure = null;
            boolean alreadyClosed;
            synchronized (operationLock) {
                if (closeResult != null) {
                    return closeResult.copy();
                }
                closeRequested = true;
                closeResult = new CompletableFuture<>();
                visible = closeResult;
                alreadyClosed = physicalCloseObserved;
                if (alreadyClosed) {
                    observedFailure = physicalCloseFailure;
                } else {
                    physicalCloseFailure = null;
                }
                if (!alreadyClosed) {
                    predecessor = operationTail;
                    closeStage = new CompletableFuture<>();
                    operationTail = closeStage.handle((@Nullable Void ignored, @Nullable Throwable failure) -> null);
                } else {
                    predecessor = null;
                    closeStage = null;
                }
            }
            if (alreadyClosed) {
                completeCloseResult(visible, observedFailure);
                return visible.copy();
            }
            CompletableFuture<Void> queuedPredecessor = Objects.requireNonNull(predecessor, "close predecessor");
            CompletableFuture<Void> queuedStage = Objects.requireNonNull(closeStage, "close stage");
            queuedPredecessor.whenComplete((@Nullable Void ignored, @Nullable Throwable failure) ->
                    dispatchClose(queuedStage, operationExecutor));
            return visible.copy();
        }

        /// Dispatches physical closure without blocking the completion or UI thread that ended the predecessor.
        ///
        /// A caller-owned executor may shut down between queue construction and predecessor completion. In that case,
        /// the launcher's shared I/O scheduler performs the already-authorized close so the session lease and region
        /// channel cannot leak. A rejection from the shared I/O scheduler falls back once more to the common scheduler;
        /// if all existing schedulers reject, an emergency daemon performs closure before any lease can terminate.
        ///
        /// @param closeStage internal stage controlling the operation tail
        /// @param operationExecutor preferred caller-owned executor
        private void dispatchClose(CompletableFuture<Void> closeStage, Executor operationExecutor) {
            Runnable action = () -> {
                try {
                    closeOnExecutor();
                } catch (Throwable failure) {
                    closeFailure(failure);
                } finally {
                    closeStage.complete(null);
                }
            };
            try {
                operationExecutor.execute(action);
                return;
            } catch (RuntimeException | Error preferredFailure) {
                LOG.warning("NBT close executor rejected work; falling back to the shared I/O scheduler",
                        preferredFailure);
            }

            try {
                Schedulers.io().execute(action);
            } catch (RuntimeException | Error ioFailure) {
                LOG.warning("Shared I/O scheduler rejected NBT close; falling back to the common scheduler", ioFailure);
                try {
                    Schedulers.defaultScheduler().execute(action);
                } catch (RuntimeException | Error terminalFailure) {
                    LOG.warning("Common scheduler rejected NBT close; starting an emergency close worker",
                            terminalFailure);
                    startEmergencyClose(action);
                }
            }
        }

        /// Starts the last-resort close worker, falling back synchronously only if the JVM rejects thread creation.
        ///
        /// This path is reserved for simultaneous rejection by the caller, shared I/O, and common schedulers. It keeps
        /// physical closure ahead of lease termination even during process shutdown or severe executor failure.
        ///
        /// @param action idempotent physical close action
        private void startEmergencyClose(Runnable action) {
            try {
                Thread emergencyWorker = new Thread(action, "NBT emergency close");
                emergencyWorker.setDaemon(true);
                emergencyWorker.start();
            } catch (RuntimeException | Error threadFailure) {
                LOG.warning("Failed to start the NBT emergency close worker; closing on the current thread",
                        threadFailure);
                action.run();
            }
        }

        /// Queues one cancellable operation while preserving a successful tail after ordinary operation failure.
        private <T> CompletableFuture<T> enqueue(Callable<? extends T> operation, Executor operationExecutor) {
            CancellationAwareFuture<T> result = new CancellationAwareFuture<>(() -> {
            });
            CompletableFuture<Void> predecessor;
            CompletableFuture<Void> operationStage = new CompletableFuture<>();
            synchronized (operationLock) {
                if (closeRequested) {
                    result.completeExceptionally(new CancellationException("NBT document is closing"));
                    return result;
                }
                predecessor = operationTail;
                operationTail = operationStage;
            }

            // Register the successor only after leaving operationLock. A synchronous executor is allowed to invoke
            // the command inline; doing so here cannot invert the document monitor and operationLock order used by
            // NBTDocument.close().
            predecessor.<Void>handle((@Nullable Void ignored, @Nullable Throwable failure) -> null)
                    .whenComplete((@Nullable Void ignored, @Nullable Throwable failure) ->
                            dispatchOperation(operationStage, result, operation, operationExecutor));
            return result;
        }

        /// Dispatches one reserved operation without holding the session queue lock.
        ///
        /// The internal stage is completed before the caller-visible result. This lets a synchronous result
        /// continuation enqueue a close after the operation slot has become available. The visible completion itself
        /// is delivered on the common scheduler so a continuation that waits for a subsequently queued operation
        /// cannot block the caller-owned single-thread executor that is still returning from this command.
        ///
        /// @param operationStage reserved queue slot
        /// @param result caller-visible result
        /// @param operation operation body
        /// @param operationExecutor caller-owned operation executor
        private <T> void dispatchOperation(
                CompletableFuture<Void> operationStage,
                CancellationAwareFuture<T> result,
                Callable<? extends T> operation,
                Executor operationExecutor) {
            Runnable command = () -> {
                if (result.isCancelled()) {
                    operationStage.complete(null);
                    return;
                }
                @Nullable T value = null;
                @Nullable Throwable failure = null;
                try {
                    value = operation.call();
                } catch (Throwable operationFailure) {
                    failure = operationFailure;
                } finally {
                    operationStage.complete(null);
                }
                @Nullable T completedValue = value;
                @Nullable Throwable completedFailure = failure;
                completeOnContinuation(() -> {
                    if (completedFailure == null) {
                        result.complete(completedValue);
                    } else {
                        result.completeExceptionally(completedFailure);
                    }
                });
            };
            try {
                operationExecutor.execute(command);
            } catch (Throwable dispatchFailure) {
                operationStage.complete(null);
                completeOnContinuation(() -> result.completeExceptionally(dispatchFailure));
            }
        }

        /// Opens the library session on the blocking executor and publishes its document before user continuations.
        private void openOnExecutor() {
            boolean cancelledBeforeStart;
            synchronized (operationLock) {
                cancelledBeforeStart = closeRequested;
                if (!cancelledBeforeStart) {
                    openStarted = true;
                }
            }
            if (cancelledBeforeStart) {
                openFailed(new CancellationException("NBT document open was cancelled"));
                return;
            }
            @Nullable NBTDocument opened = null;
            try {
                NBTDocument created = NBTDocumentService.openOnExecutor(path);
                opened = created;
                boolean reject;
                synchronized (operationLock) {
                    reject = closeRequested;
                    if (!reject) {
                        document = created;
                    }
                }
                if (reject) {
                    CancellationException cancellation = new CancellationException("NBT document open was cancelled");
                    @Nullable Throwable closeFailure = closeAfterCancelledOpen(created);
                    if (closeFailure != null) {
                        deferFailureUntilDocumentClosed(created, cancellation, closeFailure);
                    } else {
                        openFailed(cancellation);
                    }
                    return;
                }
                created.setClosedListener(failure -> documentClosed(created, failure));
                SESSIONS.put(created, this);
                openReady.complete(null);
                if (!openResult.complete(created)) {
                    close(ioExecutor);
                }
            } catch (Throwable failure) {
                @Nullable Throwable cleanupFailure = null;
                if (opened != null) {
                    cleanupFailure = closeAfterCancelledOpen(opened);
                    if (cleanupFailure != null) {
                        deferFailureUntilDocumentClosed(opened, failure, cleanupFailure);
                        return;
                    }
                    SESSIONS.remove(opened, this);
                    synchronized (operationLock) {
                        if (document == opened) {
                            document = null;
                        }
                    }
                }
                openFailed(failure);
            }
        }

        /// Closes the owned document after all queued saves have terminated.
        private void closeOnExecutor() throws IOException {
            @Nullable NBTDocument selected;
            @Unmodifiable List<NBTDocument> pending;
            boolean alreadyClosed;
            synchronized (operationLock) {
                selected = document;
                pending = List.copyOf(pendingCleanupDocuments);
                closingDocument = selected;
                alreadyClosed = physicalCloseObserved;
            }
            @Nullable Throwable firstFailure = null;
            for (NBTDocument pendingDocument : pending) {
                try {
                    pendingDocument.close();
                } catch (Throwable failure) {
                    retainPendingCleanup(pendingDocument, failure);
                    if (firstFailure == null) {
                        firstFailure = failure;
                    } else if (firstFailure != failure) {
                        firstFailure.addSuppressed(failure);
                    }
                }
                if (pendingDocument.isClosed()) {
                    documentClosed(pendingDocument, null);
                }
            }
            if (selected == null) {
                boolean observedAfterPending;
                synchronized (operationLock) {
                    observedAfterPending = physicalCloseObserved;
                }
                if (!alreadyClosed && !observedAfterPending) {
                    if (firstFailure == null) {
                        closeFailure(new CancellationException("NBT document was not opened"));
                    } else {
                        closeFailure(firstFailure);
                    }
                }
                return;
            }
            try {
                selected.close();
            } catch (Throwable failure) {
                closeAttemptFailed(selected, failure);
                if (firstFailure == null) {
                    firstFailure = failure;
                } else if (firstFailure != failure) {
                    firstFailure.addSuppressed(failure);
                }
            }
            if (selected.isClosed()) {
                // A callback can be absent after a late installation failure. Reconcile the closed handle explicitly;
                // pending cleanup keeps physical session completion deferred until every sidecar is gone.
                documentClosed(selected, null);
            }
            boolean pendingRemaining;
            synchronized (operationLock) {
                pendingRemaining = !pendingCleanupDocuments.isEmpty();
            }
            if (firstFailure != null && pendingRemaining && !physicalCloseObserved) {
                if (firstFailure instanceof IOException failure) {
                    throw failure;
                }
                if (firstFailure instanceof RuntimeException failure) {
                    throw failure;
                }
                if (firstFailure instanceof Error failure) {
                    throw failure;
                }
                throw new IOException("NBT pending cleanup failed", firstFailure);
            }
        }

        /// Records a retryable physical-close failure without terminating the lease-owning session task.
        ///
        /// The library document deliberately remains open when its region channel or publication sidecar cannot be
        /// closed. Keeping it as the current document lets a later [#close(Executor)] retry the exact same handle;
        /// completing only the caller-visible close future prevents one failed attempt from being mistaken for a
        /// successful terminal cleanup.
        ///
        /// @param attempted document whose close failed
        /// @param failure physical close failure
        private void closeAttemptFailed(NBTDocument attempted, Throwable failure) {
            Objects.requireNonNull(attempted, "attempted");
            Throwable checkedFailure = Objects.requireNonNull(failure, "failure");
            @Nullable CompletableFuture<Void> result;
            Throwable terminalFailure;
            synchronized (operationLock) {
                if (physicalCloseObserved) {
                    // A listener may have completed a successful close before propagating a presentation callback
                    // failure. The successful physical outcome remains authoritative in that race.
                    return;
                }
                if (document == null) {
                    document = attempted;
                }
                if (closingDocument == attempted) {
                    closingDocument = null;
                }
                terminalFailure = mergeFailures(replacementCloseFailure, checkedFailure);
                replacementCloseFailure = null;
                physicalCloseFailure = terminalFailure;
                result = closeResult;
                // A retry must create a fresh visible future while the internal operation tail remains serialized.
                closeResult = null;
            }
            if (result != null) {
                completeCloseResult(result, terminalFailure);
            }
            LOG.warning("NBT document physical close failed; the session remains retryable", terminalFailure);
        }

        /// Retains the session lease until every save queued before a direct or service-managed close terminates.
        ///
        /// @param closedDocument document whose physical file session has closed
        /// @param failure physical close failure, or null
        private void documentClosed(NBTDocument closedDocument, @Nullable Throwable failure) {
            boolean reloadClose;
            boolean pendingClose;
            @Nullable NBTDocument deferredDocument = null;
            boolean schedulePendingClose = false;
            boolean staleClose = false;
            boolean deferredForPending = false;
            boolean scheduleDeferredCleanup = false;
            @Nullable CompletableFuture<Void> pendingOnlyTail = null;
            @Nullable Throwable pendingOnlyFailure = null;
            synchronized (operationLock) {
                pendingClose = pendingCleanupDocuments.remove(closedDocument);
                if (pendingClose && failure != null) {
                    replacementCloseFailure = mergeFailures(replacementCloseFailure, failure);
                }
                reloadClose = reloadingDocument == closedDocument;
                if (reloadClose) {
                    reloadingDocument = null;
                    if (failure != null) {
                        replacementCloseFailure = mergeFailures(replacementCloseFailure, failure);
                    }
                }
            }
            if (pendingClose) {
                SESSIONS.remove(closedDocument, this);
                synchronized (operationLock) {
                    if (pendingCleanupDocuments.isEmpty() && deferredClosedDocument != null) {
                        deferredDocument = deferredClosedDocument;
                        deferredClosedDocument = null;
                    }
                    if (!closeRequested && document != null && document.isClosed()) {
                        closeRequested = true;
                        schedulePendingClose = !pendingCleanupDocuments.isEmpty();
                    }
                    if (pendingCleanupDocuments.isEmpty()
                            && deferredClosedDocument == null
                            && document == null
                            && closeRequested
                            && !physicalCloseObserved) {
                        physicalCloseObserved = true;
                        @Nullable Throwable deferredFailure = deferredTerminalFailure;
                        deferredTerminalFailure = null;
                        if (deferredFailure == null) {
                            pendingOnlyFailure = failure == null
                                    ? replacementCloseFailure
                                    : mergeFailures(replacementCloseFailure, failure);
                        } else if (failure == null) {
                            pendingOnlyFailure = mergeFailures(deferredFailure, replacementCloseFailure);
                        } else {
                            pendingOnlyFailure = mergeFailures(deferredFailure,
                                    mergeFailures(replacementCloseFailure, failure));
                        }
                        replacementCloseFailure = null;
                        physicalCloseFailure = pendingOnlyFailure;
                        pendingOnlyTail = operationTail;
                    }
                }
                if (deferredDocument != null) {
                    documentClosed(deferredDocument, null);
                } else if (schedulePendingClose) {
                    close(ioExecutor);
                } else if (pendingOnlyTail != null) {
                    @Nullable Throwable completedFailure = pendingOnlyFailure;
                    CompletableFuture<Void> completedTail = pendingOnlyTail;
                    completedTail.whenComplete((@Nullable Void ignored, @Nullable Throwable operationFailure) -> {
                        if (completedFailure == null && operationFailure == null) {
                            terminal.complete(null);
                        } else if (completedFailure == null) {
                            terminal.completeExceptionally(operationFailure);
                        } else if (operationFailure == null) {
                            terminal.completeExceptionally(completedFailure);
                        } else {
                            completedFailure.addSuppressed(operationFailure);
                            terminal.completeExceptionally(completedFailure);
                        }
                    });
                }
                return;
            }
            if (reloadClose) {
                SESSIONS.remove(closedDocument, this);
                return;
            }
            @Nullable CompletableFuture<Void> tail = null;
            @Nullable Throwable terminalFailure = null;
            synchronized (operationLock) {
                if (document != closedDocument && closingDocument != closedDocument) {
                    // A replacement or a cancelled replacement closes a stale handle after the new/current pointer has
                    // moved. That callback never terminates the session lease, but its physical failure still belongs
                    // to the eventual session-close result.
                    if (failure != null) {
                        replacementCloseFailure = mergeFailures(replacementCloseFailure, failure);
                    }
                    staleClose = true;
                } else if (!pendingCleanupDocuments.isEmpty()) {
                    // Keep the lease and remember the closed current handle. The pending replacement cleanup is
                    // attempted first by closeOnExecutor; its callback will re-enter this method once the list empties.
                    // Preserve the document-to-session mapping so a failed attempt remains explicitly retryable.
                    deferredClosedDocument = closedDocument;
                    deferredForPending = true;
                    scheduleDeferredCleanup = !closeRequested;
                    closeRequested = true;
                } else {
                    if (document == closedDocument) {
                        document = null;
                    }
                    if (closingDocument == closedDocument) {
                        closingDocument = null;
                    }
                    closeRequested = true;
                    physicalCloseObserved = true;
                    @Nullable Throwable deferredFailure = deferredTerminalFailure;
                    deferredTerminalFailure = null;
                    if (deferredFailure == null) {
                        terminalFailure = failure == null
                                ? replacementCloseFailure
                                : mergeFailures(replacementCloseFailure, failure);
                    } else if (failure == null) {
                        terminalFailure = mergeFailures(deferredFailure, replacementCloseFailure);
                    } else {
                        terminalFailure = mergeFailures(deferredFailure,
                                mergeFailures(replacementCloseFailure, failure));
                    }
                    replacementCloseFailure = null;
                    physicalCloseFailure = terminalFailure;
                    tail = operationTail;
                }
            }
            if (staleClose) {
                SESSIONS.remove(closedDocument, this);
                return;
            }
            if (deferredForPending) {
                if (scheduleDeferredCleanup) {
                    close(ioExecutor);
                }
                return;
            }
            SESSIONS.remove(closedDocument, this);
            @Nullable Throwable completedFailure = terminalFailure;
            Objects.requireNonNull(tail, "terminal operation tail")
                    .whenComplete((@Nullable Void ignored, @Nullable Throwable operationFailure) -> {
                if (completedFailure == null) {
                    terminal.complete(closedDocument);
                } else {
                    terminal.completeExceptionally(completedFailure);
                }
            });
        }

        /// Keeps a document-created failure pending until its physical close callback releases the task lease.
        ///
        /// Open cancellation and late setup failures can occur after the library has created a file handle. Completing
        /// the task future at that point would release its resource lease while the handle is still usable. The caller
        /// sees the original failure immediately, while the internal terminal future remains pending until a close
        /// succeeds (or a later close failure is retried).
        ///
        /// @param leaked document which still owns the file session
        /// @param failure primary operation failure
        /// @param cleanupFailure first physical cleanup failure, or null when no close was attempted
        private void deferFailureUntilDocumentClosed(
                NBTDocument leaked,
                Throwable failure,
                @Nullable Throwable cleanupFailure) {
            Objects.requireNonNull(leaked, "leaked");
            Objects.requireNonNull(failure, "failure");
            Throwable combined = cleanupFailure == null ? failure : mergeFailures(failure, cleanupFailure);
            boolean alreadyObserved;
            synchronized (operationLock) {
                alreadyObserved = physicalCloseObserved;
                if (!alreadyObserved) {
                    document = leaked;
                    closeRequested = true;
                    deferredTerminalFailure = mergeFailures(deferredTerminalFailure, combined);
                }
            }
            if (alreadyObserved) {
                completeOpenFailure(combined);
                return;
            }

            ensureSessionCloseListener(leaked);
            completeOpenFailure(combined);
            if (leaked.isClosed()) {
                // A listener may have been absent when a previous close succeeded. Reconcile that state explicitly so
                // the deferred terminal future cannot remain pending forever.
                documentClosed(leaked, null);
            } else {
                close(ioExecutor);
            }
        }

        /// Ensures that a retained document reports its eventual physical close to this session.
        ///
        /// @param document retained document
        private void ensureSessionCloseListener(NBTDocument document) {
            @Nullable DocumentSession owner = SESSIONS.get(document);
            if (owner != null) {
                if (owner != this) {
                    throw new IllegalStateException("NBT document is already managed by another session");
                }
                return;
            }
            try {
                document.setClosedListener(failure -> documentClosed(document, failure));
            } catch (IllegalStateException expected) {
                // The document already has a listener, or it closed between the state check and registration. The
                // subsequent isClosed reconciliation handles the latter case.
            }
            SESSIONS.put(document, this);
        }

        /// Retains one document whose cleanup failed, including its original failure for the eventual close result.
        ///
        /// @param pending document that still owns a physical file session
        /// @param failure cleanup failure
        private void retainPendingCleanup(NBTDocument pending, Throwable failure) {
            Objects.requireNonNull(pending, "pending");
            Objects.requireNonNull(failure, "failure");
            synchronized (operationLock) {
                if (!pendingCleanupDocuments.contains(pending)) {
                    pendingCleanupDocuments.add(pending);
                }
                replacementCloseFailure = mergeFailures(replacementCloseFailure, failure);
            }
            ensureSessionCloseListener(pending);
            if (pending.isClosed()) {
                documentClosed(pending, null);
            }
        }

        /// Retains two cleanup failures without replacing the first failure identity.
        ///
        /// @param first earlier failure, or null
        /// @param second later failure
        /// @return first failure with the later one suppressed, or the later failure when no first exists
        private static @Nullable Throwable mergeFailures(@Nullable Throwable first, Throwable second) {
            if (first == null) {
                return second;
            }
            if (first != second) {
                first.addSuppressed(second);
            }
            return first;
        }

        /// Completes a close result from the physical close outcome without running callbacks under operationLock.
        ///
        /// @param result close result owned by this session
        /// @param failure physical close failure, or null after successful closure
        private void completeCloseResult(CompletableFuture<Void> result, @Nullable Throwable failure) {
            completeOnContinuation(() -> {
                if (failure == null) {
                    result.complete(null);
                } else {
                    result.completeExceptionally(failure);
                }
            });
        }

        /// Requests task cancellation and schedules close without blocking the caller.
        private void requestCancel() {
            boolean cancelBeforeOpen;
            @Nullable TaskExecutor current;
            synchronized (operationLock) {
                closeRequested = true;
                cancelBeforeOpen = !openStarted && document == null;
                current = executor;
            }
            @Nullable Throwable cancellationFailure = null;
            if (current != null && !current.isCancelled()) {
                try {
                    current.cancel();
                } catch (Throwable failure) {
                    cancellationFailure = failure;
                    LOG.warning("Failed to cancel the NBT session task; continuing close cleanup", failure);
                }
            }
            if (cancelBeforeOpen) {
                CancellationException cancellation = new CancellationException("NBT document open was cancelled");
                if (cancellationFailure != null) {
                    cancellation.addSuppressed(cancellationFailure);
                }
                openFailed(cancellation);
            } else {
                close(ioExecutor);
            }
        }

        /// Handles terminal task completion, including cancellation before resource acquisition.
        private void stopped(boolean success, TaskExecutor stoppedExecutor) {
            if (success) {
                @Nullable NBTDocument completedDocument;
                @Nullable CompletableFuture<Void> result;
                @Nullable Throwable closeFailure;
                synchronized (operationLock) {
                    completedDocument = document;
                    result = closeResult;
                    closeFailure = physicalCloseFailure;
                }
                if (!openResult.isDone() && completedDocument != null) {
                    if (!openResult.complete(completedDocument)) {
                        close(ioExecutor);
                    }
                }
                if (result != null) {
                    completeCloseResult(result, closeFailure);
                }
                return;
            }

            Throwable failure = terminalFailure(stoppedExecutor);
            closeLeakedDocument();
            completeOpenFailure(failure);
            @Nullable CompletableFuture<Void> result;
            synchronized (operationLock) {
                result = closeResult;
            }
            if (result != null) {
                completeOnContinuation(() -> result.completeExceptionally(failure));
            }
        }

        /// Closes a document left open after an Error or cancellation bypassed the normal close operation.
        private void closeLeakedDocument() {
            @Nullable NBTDocument leaked;
            synchronized (operationLock) {
                leaked = document;
                closingDocument = leaked;
            }
            if (leaked == null) {
                return;
            }
            try {
                ensureSessionCloseListener(leaked);
                leaked.close();
            } catch (Throwable failure) {
                synchronized (operationLock) {
                    if (!physicalCloseObserved) {
                        document = leaked;
                        closingDocument = null;
                        physicalCloseFailure = mergeFailures(replacementCloseFailure, failure);
                        deferredTerminalFailure = mergeFailures(deferredTerminalFailure, failure);
                        replacementCloseFailure = null;
                    }
                }
                LOG.warning("Failed to close an NBT document after task termination", failure);
            }
            if (leaked.isClosed()) {
                SESSIONS.remove(leaked, this);
            }
        }

        /// Completes open-side futures exceptionally, retaining a live document until physical cleanup if necessary.
        private void openFailed(Throwable failure) {
            Objects.requireNonNull(failure, "failure");
            @Nullable NBTDocument current;
            synchronized (operationLock) {
                current = physicalCloseObserved ? null : document;
            }
            if (current != null) {
                deferFailureUntilDocumentClosed(current, failure, null);
                return;
            }
            terminal.completeExceptionally(failure);
            completeOpenFailure(failure);
        }

        /// Completes the caller-visible open views without releasing the internal session lease.
        ///
        /// @param failure open-side failure
        private void completeOpenFailure(Throwable failure) {
            openReady.completeExceptionally(Objects.requireNonNull(failure, "failure"));
            openResult.completeExceptionally(failure);
        }

        /// Completes close and terminal futures after a scheduling or close failure.
        private void closeFailure(Throwable failure) {
            @Nullable NBTDocument current;
            boolean pendingCleanup;
            synchronized (operationLock) {
                current = physicalCloseObserved ? null : document;
                pendingCleanup = !pendingCleanupDocuments.isEmpty();
            }
            if (current != null) {
                closeAttemptFailed(current, failure);
                return;
            }
            if (pendingCleanup) {
                @Nullable CompletableFuture<Void> result;
                Throwable retryableFailure;
                synchronized (operationLock) {
                    retryableFailure = mergeFailures(replacementCloseFailure, Objects.requireNonNull(failure, "failure"));
                    replacementCloseFailure = null;
                    physicalCloseFailure = retryableFailure;
                    result = closeResult;
                    // A retry must create a fresh visible future while the pending document remains owned by this
                    // session. The terminal task stays pending until documentClosed observes its cleanup.
                    closeResult = null;
                }
                if (result != null) {
                    completeCloseResult(result, retryableFailure);
                }
                LOG.warning("NBT pending document cleanup failed; the session remains retryable", retryableFailure);
                return;
            }
            @Nullable CompletableFuture<Void> result;
            Throwable terminalFailure;
            synchronized (operationLock) {
                terminalFailure = mergeFailures(replacementCloseFailure, Objects.requireNonNull(failure, "failure"));
                replacementCloseFailure = null;
                physicalCloseFailure = terminalFailure;
                result = closeResult;
            }
            terminal.completeExceptionally(terminalFailure);
            if (result != null) {
                completeOnContinuation(() -> result.completeExceptionally(terminalFailure));
            }
        }

        /// Creates the task whose future remains pending until explicit close.
        @NotNullByDefault
        private final class SessionTask extends CompletableFutureTask<NBTDocument> {
            /// Creates one resource-bearing document session task.
            private SessionTask() {
                setExecutor(ioExecutor);
                setName("NBT document session");
                setSignificance(Task.TaskSignificance.MINOR);
                TaskResource first = resources.get(0);
                TaskResource[] additional = resources.subList(1, resources.size())
                        .toArray(TaskResource[]::new);
                setResources(first, additional);
            }

            /// Schedules open while retaining the future and its resource lease until close.
            @Override
            public CompletableFuture<NBTDocument> getFuture(TaskCompletableFuture context) {
                Objects.requireNonNull(context, "context");
                try {
                    ioExecutor.execute(DocumentSession.this::openOnExecutor);
                } catch (RuntimeException failure) {
                    openFailed(failure);
                } catch (Error failure) {
                    openFailed(failure);
                    throw failure;
                }
                return terminal;
            }
        }
    }

    /// Future whose cancellation invokes a non-blocking owner action exactly after the visible state changes.
    @NotNullByDefault
    private static final class CancellationAwareFuture<T> extends CompletableFuture<T> {
        /// Action that cancels or marks the internal source.
        private final Runnable cancellationAction;

        /// Creates a future with one cancellation callback.
        ///
        /// @param cancellationAction callback invoked after successful cancellation
        private CancellationAwareFuture(Runnable cancellationAction) {
            this.cancellationAction = Objects.requireNonNull(cancellationAction, "cancellationAction");
        }

        /// Cancels this visible view and then requests cancellation of its internal source.
        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            boolean changed = super.cancel(mayInterruptIfRunning);
            if (changed) {
                cancellationAction.run();
            }
            return changed;
        }
    }

    /// Caller-visible reload result with an atomic cancellation-versus-installation commit point.
    ///
    /// Once the worker claims replacement installation, cancellation returns false and the future is completed with
    /// that committed replacement or its installation failure. If cancellation wins first, the worker leaves the old
    /// document installed and closes only the uncommitted replacement.
    @NotNullByDefault
    private static final class ReloadFuture extends CompletableFuture<NBTDocument> {
        /// Initial state in which either cancellation or the reload worker may claim completion.
        private static final int PENDING = 0;

        /// State owned by the reload worker after it commits to replacement installation.
        private static final int COMMITTING = 1;

        /// State owned by the caller after successful cancellation.
        private static final int CANCELLED = 2;

        /// Atomic completion owner state.
        private final AtomicInteger completionOwner = new AtomicInteger(PENDING);

        /// Claims the replacement commit before changing the current session document.
        ///
        /// @return whether installation won the race with cancellation
        private boolean beginCommit() {
            return completionOwner.compareAndSet(PENDING, COMMITTING);
        }

        /// Publishes the replacement after the worker has committed its installation.
        ///
        /// @param replacement installed replacement document
        /// @return whether the caller-visible future accepted the result
        private boolean completeCommitted(NBTDocument replacement) {
            if (completionOwner.get() != COMMITTING) {
                return false;
            }
            return super.complete(Objects.requireNonNull(replacement, "replacement"));
        }

        /// Publishes a failure unless cancellation already owns completion.
        ///
        /// A failure after [#beginCommit()] remains authoritative because the old document may already have been
        /// replaced. A pre-commit failure atomically claims the same completion state before publication.
        ///
        /// @param failure reload failure
        private void completeFailure(Throwable failure) {
            Throwable selected = Objects.requireNonNull(failure, "failure");
            int owner = completionOwner.get();
            if (owner == CANCELLED) {
                return;
            }
            if (owner == PENDING && !completionOwner.compareAndSet(PENDING, COMMITTING)) {
                if (completionOwner.get() == CANCELLED) {
                    return;
                }
            }
            super.completeExceptionally(selected);
        }

        /// Cancels only before the reload worker claims replacement installation.
        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            if (!completionOwner.compareAndSet(PENDING, CANCELLED)) {
                return false;
            }
            return super.cancel(mayInterruptIfRunning);
        }
    }

    /// Unwraps one completion wrapper without replacing its original failure.
    private static Throwable resolveCompletionFailure(Throwable failure) {
        Throwable current = Objects.requireNonNull(failure, "failure");
        while (current instanceof CompletionException && current.getCause() != null) {
            current = Objects.requireNonNull(current.getCause(), "completion cause");
        }
        return current;
    }

    /// Closes a document when an open result was cancelled after the library session was created.
    private static @Nullable Throwable closeAfterCancelledOpen(NBTDocument document) {
        Objects.requireNonNull(document, "document");
        try {
            document.close();
            return null;
        } catch (Throwable failure) {
            LOG.warning("Failed to close an NBT document after its open operation was cancelled", failure);
            return failure;
        }
    }
}
