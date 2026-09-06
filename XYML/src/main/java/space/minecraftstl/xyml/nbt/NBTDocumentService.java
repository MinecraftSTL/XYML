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
import java.util.Locale;
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
/// channel, identity link, external companion, backup, or staging sibling cannot race another launcher task. Pure
/// in-memory editor operations continue to use [#supplyAsync(Supplier)] and do not claim a filesystem resource.
///
/// Compression detection, strict parsing, structural validation, fingerprints, staging, region copy-on-write
/// publication, and savepoint handling remain inside [NBTFile]. This adapter only chooses the file entry point and
/// launcher backup policy.
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
    /// Standalone files ending in `.dat` receive a single rolling sibling backup ending in `.dat_old`. Region
    /// sessions claim their containing region directory because publication may touch external `.mcc` companions,
    /// temporary siblings, and the session identity link.
    ///
    /// @param document open document
    /// @return future completed after publication and force operations finish
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
    /// channels and deleted its identity link.
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

    /// Selects the semantic resource set for one NBT file transaction.
    ///
    /// Region publication is directory-scoped because the library may create or replace files whose names are not
    /// known before reading the region header. Standalone publication names every deterministic source and backup;
    /// its temporary sibling is private to that exact publication transaction.
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
        if (options != null && options.backupPath() != null) {
            resources.add(TaskResource.nbtFile(options.backupPath()));
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
    /// @throws IOException if type detection or the selected strict open fails
    private static NBTDocument openOnExecutor(Path file) throws IOException {
        @Nullable NBTFileType fileType = NBTFileType.detect(file);
        if (fileType == null) {
            throw new IOException("Unsupported NBT file extension: " + file);
        }
        validateExistingSource(file);
        NBTFile<? extends NBTElement> session = switch (fileType) {
            case TAG -> NBTFile.openTag(file);
            case ANVIL, REGION -> NBTFile.openRegion(file);
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
        if (!name.toLowerCase(Locale.ROOT).endsWith(".dat")) {
            return NBTSaveOptions.withoutBackup();
        }
        @Nullable Path parent = file.getParent();
        if (parent == null) {
            return NBTSaveOptions.withoutBackup();
        }
        return NBTSaveOptions.withBackup(parent.resolve(name + "_old"));
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

        /// Physical close failure reported by the library session, or null after a successful close.
        private @Nullable Throwable physicalCloseFailure;

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
                selected.saveFromService(options);
                return null;
            }, operationExecutor);
        }

        /// Reopens the current source under this session's already-held resource lease.
        ///
        /// The old document remains available if opening the replacement fails. On success the current pointer is
        /// switched before the old handle is closed, so its close callback is treated as a non-terminal replacement.
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
                NBTDocument created = NBTDocumentService.openOnExecutor(path);
                replacement = created;
                created.setClosedListener(failure -> documentClosed(created, failure));
                SESSIONS.put(created, this);
                synchronized (operationLock) {
                    if (closeRequested
                            || document != selected
                            || physicalCloseObserved
                            || !visible.beginCommit()) {
                        throw new CancellationException("NBT document reload was cancelled");
                    }
                    document = created;
                    installed = true;
                }
                try {
                    selected.close();
                } catch (IOException | RuntimeException closeFailure) {
                    // The old handle is already marked closed by NBTDocument. Preserve the usable replacement and
                    // retain the cleanup failure for the session's eventual close result.
                    synchronized (operationLock) {
                        replacementCloseFailure = mergeFailures(replacementCloseFailure, closeFailure);
                    }
                    LOG.warning("NBT reload replaced a document whose old handle reported a close failure",
                            closeFailure);
                } catch (Error closeFailure) {
                    // Fatal failures still propagate, but queue replacement cleanup so the session lease cannot leak.
                    synchronized (operationLock) {
                        replacementCloseFailure = mergeFailures(replacementCloseFailure, closeFailure);
                    }
                    close(ioExecutor);
                    throw closeFailure;
                }
                return created;
            } catch (IOException | RuntimeException | Error failure) {
                if (replacement != null && !installed) {
                    SESSIONS.remove(replacement, this);
                    closeAfterCancelledOpen(replacement);
                }
                throw failure;
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
                        cancellation.addSuppressed(closeFailure);
                    }
                    openFailed(cancellation);
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
                    SESSIONS.remove(opened, this);
                    synchronized (operationLock) {
                        if (document == opened) {
                            document = null;
                        }
                    }
                    cleanupFailure = closeAfterCancelledOpen(opened);
                }
                if (cleanupFailure != null && cleanupFailure != failure) {
                    failure.addSuppressed(cleanupFailure);
                }
                openFailed(failure);
            }
        }

        /// Closes the owned document after all queued saves have terminated.
        private void closeOnExecutor() {
            @Nullable NBTDocument selected;
            boolean alreadyClosed;
            synchronized (operationLock) {
                selected = document;
                document = null;
                closingDocument = selected;
                alreadyClosed = physicalCloseObserved;
            }
            if (selected == null) {
                if (!alreadyClosed) {
                    closeFailure(new CancellationException("NBT document was not opened"));
                }
                return;
            }
            try {
                selected.close();
            } catch (Throwable failure) {
                boolean observed;
                synchronized (operationLock) {
                    observed = physicalCloseObserved;
                }
                if (!observed) {
                    // NBTDocument normally reports the physical outcome through its listener before rethrowing. Only
                    // synthesize terminal failure when that callback itself could not record the close.
                    closeFailure(failure);
                }
            }
        }

        /// Retains the session lease until every save queued before a direct or service-managed close terminates.
        ///
        /// @param closedDocument document whose physical file session has closed
        /// @param failure physical close failure, or null
        private void documentClosed(NBTDocument closedDocument, @Nullable Throwable failure) {
            SESSIONS.remove(closedDocument, this);
            CompletableFuture<Void> tail;
            @Nullable Throwable terminalFailure;
            synchronized (operationLock) {
                if (document != closedDocument && closingDocument != closedDocument) {
                    // A replacement or a cancelled replacement closes a stale handle after the new/current pointer has
                    // moved. That callback never terminates the session lease, but its physical failure still belongs
                    // to the eventual session-close result.
                    if (failure != null) {
                        replacementCloseFailure = mergeFailures(replacementCloseFailure, failure);
                    }
                    return;
                }
                if (document == closedDocument) {
                    document = null;
                }
                if (closingDocument == closedDocument) {
                    closingDocument = null;
                }
                closeRequested = true;
                physicalCloseObserved = true;
                terminalFailure = mergeFailures(replacementCloseFailure, failure);
                replacementCloseFailure = null;
                physicalCloseFailure = terminalFailure;
                tail = operationTail;
            }
            tail.whenComplete((@Nullable Void ignored, @Nullable Throwable operationFailure) -> {
                if (terminalFailure == null) {
                    terminal.complete(closedDocument);
                } else {
                    terminal.completeExceptionally(terminalFailure);
                }
            });
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
            openReady.completeExceptionally(failure);
            openResult.completeExceptionally(failure);
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
                document = null;
            }
            if (leaked == null) {
                return;
            }
            SESSIONS.remove(leaked, this);
            try {
                leaked.close();
            } catch (Throwable failure) {
                LOG.warning("Failed to close an NBT document after task termination", failure);
            }
        }

        /// Completes all open-side futures exceptionally and stops the internal task source.
        private void openFailed(Throwable failure) {
            terminal.completeExceptionally(failure);
            openReady.completeExceptionally(failure);
            openResult.completeExceptionally(failure);
        }

        /// Completes close and terminal futures after a scheduling or close failure.
        private void closeFailure(Throwable failure) {
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
