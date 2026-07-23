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
package space.minecraftstl.xyml.ui.swing.page.schematics;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import space.minecraftstl.xyml.observable.Subscription;
import space.minecraftstl.xyml.observable.ValueChange;
import space.minecraftstl.xyml.observable.ValueChangeListener;
import space.minecraftstl.xyml.schematic.LitematicFile;
import space.minecraftstl.xyml.ui.swing.choice.ChoicePage;
import space.minecraftstl.xyml.ui.swing.choice.IndexRange;
import space.minecraftstl.xyml.ui.swing.choice.LoadCancellation;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;

/// Default read-only schematic browser with shallow scanning and viewport-only metadata parsing.
///
/// Construction normalizes paths but performs no file-system access. Every directory scan and
/// Litematic parse runs on the caller-owned executor. Successful scans publish an exact immutable
/// index, while range loads parse only file rows inside their clamped requested range. Generation
/// ownership and cooperative cancellation prevent superseded scans or range loads from committing.
@NotNullByDefault
public final class DefaultSchematicBrowserModel implements SchematicBrowserModel {
    /// Production no-op used at the externally observable completion boundary.
    private static final Runnable NO_COMPLETION_HOOK = () -> {
    };

    /// No-op used where a path validation has no caller cancellation signal.
    private static final Runnable NO_CANCELLATION_CHECK = () -> {
    };

    /// Deterministic ordering with directories first and file-name case as the final tie breaker.
    private static final Comparator<DiscoveredEntry> ENTRY_ORDER = Comparator
            .comparing(DiscoveredEntry::directory).reversed()
            .thenComparing(DiscoveredEntry::fileName, String.CASE_INSENSITIVE_ORDER)
            .thenComparing(DiscoveredEntry::fileName);

    /// Lock protecting generation, active work, closure, and immutable state replacement.
    private final Object stateLock = new Object();

    /// Lock preventing listener publication from crossing the close return boundary.
    private final Object publicationLock = new Object();

    /// Root path used as the immutable lexical navigation boundary.
    private final Path rootDirectory;

    /// Caller-owned executor used for every scan, navigation completion, and metadata parse.
    private final Executor executor;

    /// Shallow directory reader, injectable for deterministic tests.
    private final DirectoryReader directoryReader;

    /// Litematic metadata reader, injectable for deterministic tests.
    private final MetadataReader metadataReader;

    /// Package-private test hook invoked outside model locks immediately before a terminal future CAS.
    private final Runnable beforeTerminalCompletion;

    /// Independently removable snapshot listeners.
    private final CopyOnWriteArrayList<ValueChangeListener<SchematicBrowserSnapshot>> listeners =
            new CopyOnWriteArrayList<>();

    /// Active viewport range operations in the current content generation.
    private final Set<RangeOperation> activeRangeLoads = new HashSet<>();

    /// Snapshot-only completions that must lose to a newer generation or close.
    private final Set<SnapshotCompletion> activeSnapshotCompletions = new HashSet<>();

    /// Latest atomically published directory descriptors and snapshot.
    private volatile BrowserState state;

    /// Monotonically increasing ownership token for scans and range requests.
    private long generation;

    /// Latest directory scan allowed to commit, or null while no scan is active.
    private @Nullable ScanOperation activeScan;

    /// Whether all future commands and loads must be rejected.
    private volatile boolean closed;

    /// Creates an idle browser rooted at the given directory without performing I/O.
    ///
    /// @param rootDirectory immutable browser root
    /// @param executor caller-owned I/O executor
    public DefaultSchematicBrowserModel(Path rootDirectory, Executor executor) {
        this(
                rootDirectory,
                executor,
                productionDirectoryReader(rootDirectory),
                productionMetadataReader(rootDirectory),
                NO_COMPLETION_HOOK);
    }

    /// Creates an idle browser with injected I/O boundaries for deterministic tests.
    ///
    /// @param rootDirectory immutable browser root
    /// @param executor caller-owned I/O executor
    /// @param directoryReader shallow directory reader
    /// @param metadataReader Litematic metadata reader
    DefaultSchematicBrowserModel(
            Path rootDirectory,
            Executor executor,
            DirectoryReader directoryReader,
            MetadataReader metadataReader) {
        this(rootDirectory, executor, directoryReader, metadataReader, NO_COMPLETION_HOOK);
    }

    /// Creates an idle browser with an explicit terminal-completion test boundary.
    ///
    /// @param rootDirectory immutable browser root
    /// @param executor caller-owned I/O executor
    /// @param directoryReader shallow directory reader
    /// @param metadataReader Litematic metadata reader
    /// @param beforeTerminalCompletion test hook run outside locks before terminal future completion
    DefaultSchematicBrowserModel(
            Path rootDirectory,
            Executor executor,
            DirectoryReader directoryReader,
            MetadataReader metadataReader,
            Runnable beforeTerminalCompletion) {
        this.rootDirectory = Objects.requireNonNull(rootDirectory, "rootDirectory")
                .toAbsolutePath().normalize();
        this.executor = Objects.requireNonNull(executor, "executor");
        this.directoryReader = Objects.requireNonNull(directoryReader, "directoryReader");
        this.metadataReader = Objects.requireNonNull(metadataReader, "metadataReader");
        this.beforeTerminalCompletion = Objects.requireNonNull(
                beforeTerminalCompletion, "beforeTerminalCompletion");
        state = new BrowserState(List.of(), new SchematicBrowserSnapshot(
                this.rootDirectory,
                this.rootDirectory,
                OptionalInt.empty(),
                0L,
                SchematicBrowserStatus.IDLE,
                null,
                false));
    }

    /// Returns the latest immutable browser state.
    @Override
    public SchematicBrowserSnapshot snapshot() {
        return state.snapshot();
    }

    /// Registers one listener while the model is open.
    @Override
    public Subscription subscribe(ValueChangeListener<SchematicBrowserSnapshot> listener) {
        Objects.requireNonNull(listener, "listener");
        synchronized (stateLock) {
            requireOpen();
            listeners.add(listener);
        }
        return Subscription.create(() -> listeners.remove(listener));
    }

    /// Returns the exact current row count after a successful scan.
    @Override
    public OptionalInt exactItemCount() {
        return state.snapshot().itemCount();
    }

    /// Parses only the descriptors intersecting the clamped viewport range on the caller executor.
    @Override
    public CompletionStage<ChoicePage<SchematicBrowserItem>> load(
            IndexRange desiredRange,
            LoadCancellation cancellation) {
        Objects.requireNonNull(desiredRange, "desiredRange");
        Objects.requireNonNull(cancellation, "cancellation");
        RangeOperation operation;
        synchronized (stateLock) {
            requireOpen();
            BrowserState current = state;
            if (current.snapshot().itemCount().isEmpty()) {
                return CompletableFuture.failedFuture(
                        new IllegalStateException("The schematic directory has not been scanned"));
            }
            operation = new RangeOperation(
                    generation,
                    desiredRange,
                    current.entries(),
                    cancellation,
                    new LoadCancellation(),
                    new CompletableFuture<>());
            activeRangeLoads.add(operation);
        }
        submitRange(operation);
        return operation.result();
    }

    /// Schedules the initial scan or asynchronously returns the already attempted state.
    @Override
    public CompletionStage<SchematicBrowserSnapshot> loadIfNeeded() {
        @Nullable ScanPreparation preparation;
        @Nullable CompletionStage<SchematicBrowserSnapshot> activeResult = null;
        @Nullable SchematicBrowserSnapshot existing = null;
        long existingGeneration = 0L;
        synchronized (stateLock) {
            requireOpen();
            if (state.snapshot().status() == SchematicBrowserStatus.IDLE) {
                preparation = prepareScanLocked(state.snapshot().currentDirectory());
            } else if (activeScan != null) {
                preparation = null;
                activeResult = activeScan.result();
            } else {
                preparation = null;
                existing = state.snapshot();
                existingGeneration = generation;
            }
        }
        if (activeResult != null) {
            return activeResult;
        }
        if (preparation == null) {
            return completeOnExecutor(Objects.requireNonNull(existing), existingGeneration);
        }
        return activateScan(preparation);
    }

    /// Cancels current generation work and schedules a shallow replacement scan.
    @Override
    public CompletionStage<SchematicBrowserSnapshot> refresh() {
        ScanPreparation preparation;
        synchronized (stateLock) {
            requireOpen();
            preparation = prepareScanLocked(state.snapshot().currentDirectory());
        }
        return activateScan(preparation);
    }

    /// Navigates only to a directory descriptor from the current stable index.
    @Override
    public CompletionStage<SchematicBrowserSnapshot> openDirectory(Path directory) {
        Objects.requireNonNull(directory, "directory");
        Path normalized = directory.toAbsolutePath().normalize();
        @Nullable ScanPreparation preparation = null;
        @Nullable IllegalArgumentException failure = null;
        synchronized (stateLock) {
            requireOpen();
            boolean knownDirectory = state.entries().stream()
                    .anyMatch(entry -> entry.directory() && entry.path().equals(normalized));
            if (knownDirectory) {
                preparation = prepareScanLocked(normalized);
            } else {
                failure = new IllegalArgumentException("Unknown schematic child directory: " + normalized);
            }
        }
        if (preparation != null) {
            return activateScan(preparation);
        }
        return failOnExecutor(Objects.requireNonNull(failure));
    }

    /// Navigates to the parent, or asynchronously retains the root snapshot at the boundary.
    @Override
    public CompletionStage<SchematicBrowserSnapshot> returnToParent() {
        @Nullable ScanPreparation preparation;
        @Nullable CompletionStage<SchematicBrowserSnapshot> activeResult = null;
        @Nullable SchematicBrowserSnapshot rootSnapshot = null;
        long rootGeneration = 0L;
        synchronized (stateLock) {
            requireOpen();
            Path current = state.snapshot().currentDirectory();
            if (current.equals(rootDirectory)) {
                if (activeScan != null && activeScan.targetDirectory().equals(rootDirectory)) {
                    preparation = null;
                    activeResult = activeScan.result();
                } else if (activeScan != null) {
                    preparation = prepareScanLocked(rootDirectory);
                } else {
                    preparation = null;
                    rootSnapshot = state.snapshot();
                    rootGeneration = generation;
                }
            } else {
                Path parent = Objects.requireNonNull(current.getParent(), "A non-root directory must have a parent");
                if (!parent.startsWith(rootDirectory)) {
                    throw new IllegalStateException("Current schematic directory escaped its root boundary");
                }
                preparation = prepareScanLocked(parent);
            }
        }
        if (activeResult != null) {
            return activeResult;
        }
        if (preparation == null) {
            return completeOnExecutor(Objects.requireNonNull(rootSnapshot), rootGeneration);
        }
        return activateScan(preparation);
    }

    /// Cancels every active operation and prevents late state or listener effects.
    @Override
    public void close() {
        @Nullable ScanOperation scanToCancel;
        @Unmodifiable List<RangeOperation> rangesToCancel;
        @Unmodifiable List<SnapshotCompletion> snapshotsToCancel;
        synchronized (stateLock) {
            if (!closed) {
                closed = true;
                generation++;
                scanToCancel = activeScan;
                activeScan = null;
                rangesToCancel = List.copyOf(activeRangeLoads);
                activeRangeLoads.clear();
                snapshotsToCancel = List.copyOf(activeSnapshotCompletions);
                activeSnapshotCompletions.clear();
            } else {
                scanToCancel = null;
                rangesToCancel = List.of();
                snapshotsToCancel = List.of();
            }
        }
        if (scanToCancel != null) {
            cancelScan(scanToCancel, "Schematic browser was closed");
        }
        rangesToCancel.forEach(operation -> cancelRange(operation, "Schematic browser was closed"));
        snapshotsToCancel.forEach(
                operation -> cancelSnapshotCompletion(operation, "Schematic browser was closed"));
        synchronized (publicationLock) {
            listeners.clear();
        }
    }

    /// Builds and commits a new loading generation while holding [#stateLock].
    ///
    /// @param targetDirectory directory to scan
    /// @return immutable activation data
    private ScanPreparation prepareScanLocked(Path targetDirectory) {
        @Nullable ScanOperation previousScan = activeScan;
        @Unmodifiable List<RangeOperation> previousRanges = List.copyOf(activeRangeLoads);
        activeRangeLoads.clear();
        @Unmodifiable List<SnapshotCompletion> previousSnapshots = List.copyOf(activeSnapshotCompletions);
        activeSnapshotCompletions.clear();
        ScanOperation operation = new ScanOperation(
                ++generation,
                targetDirectory,
                new LoadCancellation(),
                new CompletableFuture<>());
        activeScan = operation;
        BrowserState previousState = state;
        SchematicBrowserSnapshot previous = previousState.snapshot();
        SchematicBrowserSnapshot loading = new SchematicBrowserSnapshot(
                rootDirectory,
                previous.currentDirectory(),
                previous.itemCount(),
                previous.contentRevision(),
                SchematicBrowserStatus.LOADING,
                null,
                previous.canReturnToParent());
        state = new BrowserState(previousState.entries(), loading);
        return new ScanPreparation(
                operation,
                previousScan,
                previousRanges,
                previousSnapshots,
                new SnapshotTransition(previous, loading));
    }

    /// Cancels superseded work, publishes loading state, and submits the current scan.
    ///
    /// @param preparation committed scan activation
    /// @return scan completion
    private CompletionStage<SchematicBrowserSnapshot> activateScan(ScanPreparation preparation) {
        if (preparation.previousScan() != null) {
            cancelScan(preparation.previousScan(), "Schematic scan was superseded");
        }
        preparation.previousRanges().forEach(
                operation -> cancelRange(operation, "Schematic content was superseded"));
        preparation.previousSnapshots().forEach(
                operation -> cancelSnapshotCompletion(operation, "Schematic content was superseded"));
        publish(preparation.transition());

        boolean submit;
        synchronized (stateLock) {
            submit = !closed && activeScan == preparation.operation();
        }
        if (submit) {
            submitScan(preparation.operation());
        }
        return preparation.operation().result();
    }

    /// Submits one scan and converts executor rejection into the current error state.
    ///
    /// @param operation scan to submit
    private void submitScan(ScanOperation operation) {
        try {
            executor.execute(() -> runScan(operation));
        } catch (RuntimeException failure) {
            completeScanFailure(operation, failure);
        } catch (Error failure) {
            completeScanFailure(operation, failure);
            throw failure;
        }
    }

    /// Performs one shallow scan without holding the model state lock.
    ///
    /// @param operation owned scan generation
    private void runScan(ScanOperation operation) {
        try {
            ensureScanCurrent(operation);
            @Unmodifiable List<DiscoveredEntry> entries = directoryReader.read(
                    operation.targetDirectory(), operation.cancellation());
            ensureScanCurrent(operation);
            completeScanSuccess(operation, entries);
        } catch (CancellationException failure) {
            cancelScan(operation, failure.getMessage() == null
                    ? "Schematic scan was cancelled"
                    : failure.getMessage());
        } catch (IOException | RuntimeException failure) {
            completeScanFailure(operation, failure);
        } catch (Error failure) {
            completeScanFailure(operation, failure);
            throw failure;
        }
    }

    /// Commits a current sorted shallow listing and exact item count.
    ///
    /// @param operation owned scan generation
    /// @param discoveredEntries discovered direct children
    private void completeScanSuccess(
            ScanOperation operation,
            @Unmodifiable List<DiscoveredEntry> discoveredEntries) {
        @Unmodifiable List<DiscoveredEntry> entries = discoveredEntries.stream()
                .sorted(ENTRY_ORDER)
                .toList();
        @Nullable SnapshotTransition transition = null;
        @Unmodifiable List<RangeOperation> rangesToCancel = List.of();
        boolean superseded;
        synchronized (stateLock) {
            superseded = !isScanCurrentLocked(operation);
            if (!superseded) {
                BrowserState previousState = state;
                SchematicBrowserSnapshot previous = previousState.snapshot();
                Path currentDirectory = operation.targetDirectory();
                SchematicBrowserSnapshot ready = new SchematicBrowserSnapshot(
                        rootDirectory,
                        currentDirectory,
                        OptionalInt.of(entries.size()),
                        previous.contentRevision() + 1L,
                        SchematicBrowserStatus.READY,
                        null,
                        !currentDirectory.equals(rootDirectory));
                state = new BrowserState(entries, ready);
                transition = new SnapshotTransition(previous, ready);
                rangesToCancel = List.copyOf(activeRangeLoads);
                activeRangeLoads.clear();
            }
        }
        if (superseded) {
            cancelScan(operation, "Schematic scan result was superseded");
            return;
        }
        rangesToCancel.forEach(range -> cancelRange(range, "Schematic content was replaced"));
        publish(transition);
        beforeTerminalCompletion.run();
        operation.result().complete(Objects.requireNonNull(transition).current());
        clearCompletedScan(operation);
    }

    /// Commits an error only when the failing scan still owns the generation.
    ///
    /// @param operation failing scan
    /// @param failure scan or executor failure
    private void completeScanFailure(ScanOperation operation, Throwable failure) {
        @Nullable SnapshotTransition transition = null;
        boolean superseded;
        synchronized (stateLock) {
            superseded = !isScanCurrentLocked(operation);
            if (!superseded) {
                BrowserState previousState = state;
                SchematicBrowserSnapshot previous = previousState.snapshot();
                SchematicBrowserSnapshot failed = new SchematicBrowserSnapshot(
                        rootDirectory,
                        previous.currentDirectory(),
                        previous.itemCount(),
                        previous.contentRevision(),
                        SchematicBrowserStatus.ERROR,
                        failureText(failure),
                        previous.canReturnToParent());
                state = new BrowserState(previousState.entries(), failed);
                transition = new SnapshotTransition(previous, failed);
            }
        }
        if (superseded) {
            cancelScan(operation, "Schematic scan failure was superseded");
            return;
        }
        publish(transition);
        beforeTerminalCompletion.run();
        operation.result().completeExceptionally(failure);
        clearCompletedScan(operation);
    }

    /// Throws when a scan lost ownership or received cooperative cancellation.
    ///
    /// @param operation scan to validate
    private void ensureScanCurrent(ScanOperation operation) {
        operation.cancellation().throwIfCancelled();
        synchronized (stateLock) {
            if (!isScanCurrentLocked(operation)) {
                throw new CancellationException("Schematic scan was superseded");
            }
        }
    }

    /// Returns whether one scan still owns the current generation.
    ///
    /// @param operation scan to test
    /// @return whether the scan may commit
    private boolean isScanCurrentLocked(ScanOperation operation) {
        return !closed
                && activeScan == operation
                && generation == operation.generation()
                && !operation.cancellation().isCancelled();
    }

    /// Submits one viewport parse request to the caller-owned executor.
    ///
    /// @param operation range request
    private void submitRange(RangeOperation operation) {
        try {
            executor.execute(() -> runRange(operation));
        } catch (RuntimeException failure) {
            completeRangeFailure(operation, failure);
        } catch (Error failure) {
            completeRangeFailure(operation, failure);
            throw failure;
        }
    }

    /// Parses only files inside one requested range and retains unreadable file rows.
    ///
    /// @param operation range request
    private void runRange(RangeOperation operation) {
        try {
            ensureRangeCurrent(operation);
            int itemCount = operation.entries().size();
            IndexRange actualRange = operation.desiredRange().clampToItemCount(itemCount);
            List<SchematicBrowserItem> items = new ArrayList<>(actualRange.length());
            for (int index = actualRange.startInclusive(); index < actualRange.endExclusive(); index++) {
                ensureRangeCurrent(operation);
                DiscoveredEntry entry = operation.entries().get(index);
                if (entry.directory()) {
                    items.add(new SchematicDirectoryItem(entry.path(), entry.fileName()));
                } else {
                    items.add(parseFile(operation, entry));
                }
            }
            ensureRangeCurrent(operation);
            ChoicePage<SchematicBrowserItem> page = new ChoicePage<>(
                    actualRange,
                    items,
                    OptionalInt.of(itemCount),
                    actualRange.endExclusive() == itemCount);
            completeRangeSuccess(operation, page);
        } catch (CancellationException failure) {
            cancelRange(operation, failure.getMessage() == null
                    ? "Schematic viewport load was cancelled"
                    : failure.getMessage());
        } catch (RuntimeException failure) {
            completeRangeFailure(operation, failure);
        } catch (Error failure) {
            completeRangeFailure(operation, failure);
            throw failure;
        }
    }

    /// Parses one file or converts its ordinary parse failure into an unreadable row.
    ///
    /// @param operation owning range request
    /// @param entry file descriptor
    /// @return stable parsed or unreadable file row
    private SchematicFileItem parseFile(RangeOperation operation, DiscoveredEntry entry) {
        try {
            LitematicFile metadata = Objects.requireNonNull(
                    metadataReader.read(entry.path()),
                    "metadataReader returned null");
            ensureRangeCurrent(operation);
            return new SchematicFileItem(entry.path(), entry.fileName(), metadata, null);
        } catch (CancellationException failure) {
            throw failure;
        } catch (IOException | RuntimeException failure) {
            ensureRangeCurrent(operation);
            return new SchematicFileItem(entry.path(), entry.fileName(), null, failureText(failure));
        }
    }

    /// Completes a current range request after removing its active slot.
    ///
    /// @param operation range request
    /// @param page exact range result
    private void completeRangeSuccess(
            RangeOperation operation,
            ChoicePage<SchematicBrowserItem> page) {
        boolean superseded;
        synchronized (stateLock) {
            superseded = !isRangeCurrentLocked(operation);
        }
        if (superseded) {
            cancelRange(operation, "Schematic viewport result was superseded");
            return;
        }
        beforeTerminalCompletion.run();
        operation.result().complete(page);
        clearCompletedRange(operation);
    }

    /// Completes a range request exceptionally and releases its active slot.
    ///
    /// @param operation range request
    /// @param failure range or executor failure
    private void completeRangeFailure(RangeOperation operation, Throwable failure) {
        boolean superseded;
        synchronized (stateLock) {
            superseded = !isRangeCurrentLocked(operation);
        }
        if (superseded) {
            cancelRange(operation, "Schematic viewport failure was superseded");
            return;
        }
        beforeTerminalCompletion.run();
        operation.result().completeExceptionally(failure);
        clearCompletedRange(operation);
    }

    /// Throws when range cancellation, closure, or a new scan invalidates the operation.
    ///
    /// @param operation range request
    private void ensureRangeCurrent(RangeOperation operation) {
        operation.callerCancellation().throwIfCancelled();
        operation.lifecycleCancellation().throwIfCancelled();
        synchronized (stateLock) {
            if (!isRangeCurrentLocked(operation)) {
                throw new CancellationException("Schematic viewport load was superseded");
            }
        }
    }

    /// Returns whether a range request remains owned by the current content generation.
    ///
    /// @param operation range request
    /// @return whether the request may commit
    private boolean isRangeCurrentLocked(RangeOperation operation) {
        return !closed
                && generation == operation.generation()
                && activeRangeLoads.contains(operation)
                && !operation.callerCancellation().isCancelled()
                && !operation.lifecycleCancellation().isCancelled();
    }

    /// Requests scan cancellation and completes its returned stage promptly.
    ///
    /// @param operation scan to cancel
    /// @param message cancellation explanation
    private static void cancelScan(ScanOperation operation, String message) {
        operation.cancellation().cancel();
        operation.result().completeExceptionally(new CancellationException(message));
    }

    /// Requests range cancellation and completes its returned stage promptly.
    ///
    /// @param operation range to cancel
    /// @param message cancellation explanation
    private void cancelRange(RangeOperation operation, String message) {
        operation.lifecycleCancellation().cancel();
        operation.result().completeExceptionally(new CancellationException(message));
        clearCompletedRange(operation);
    }

    /// Releases a scan slot only after its future has reached an atomic terminal state.
    ///
    /// @param operation completed scan
    private void clearCompletedScan(ScanOperation operation) {
        synchronized (stateLock) {
            if (activeScan == operation) {
                activeScan = null;
            }
        }
    }

    /// Releases a range slot only after its future has reached an atomic terminal state.
    ///
    /// @param operation completed range
    private void clearCompletedRange(RangeOperation operation) {
        synchronized (stateLock) {
            activeRangeLoads.remove(operation);
        }
    }

    /// Completes a no-op command from the caller-owned executor.
    ///
    /// @param value state captured when the command was invoked
    /// @return asynchronous completion
    private CompletionStage<SchematicBrowserSnapshot> completeOnExecutor(
            SchematicBrowserSnapshot value,
            long expectedGeneration) {
        SnapshotCompletion operation = new SnapshotCompletion(
                expectedGeneration, value, new CompletableFuture<>());
        boolean accepted;
        synchronized (stateLock) {
            accepted = !closed && generation == expectedGeneration;
            if (accepted) {
                activeSnapshotCompletions.add(operation);
            }
        }
        if (!accepted) {
            operation.result().completeExceptionally(
                    new CancellationException("Schematic browser command was superseded"));
            return operation.result();
        }
        try {
            executor.execute(() -> completeSnapshotOnExecutor(operation));
        } catch (RuntimeException failure) {
            operation.result().completeExceptionally(failure);
            clearCompletedSnapshot(operation);
        } catch (Error failure) {
            operation.result().completeExceptionally(failure);
            clearCompletedSnapshot(operation);
            throw failure;
        }
        return operation.result();
    }

    /// Completes one tracked snapshot only while it still belongs to the captured generation.
    ///
    /// @param operation tracked snapshot completion
    private void completeSnapshotOnExecutor(SnapshotCompletion operation) {
        boolean current;
        synchronized (stateLock) {
            current = !closed
                    && generation == operation.generation()
                    && activeSnapshotCompletions.contains(operation);
        }
        if (!current) {
            cancelSnapshotCompletion(operation, "Schematic browser command was superseded");
            return;
        }
        beforeTerminalCompletion.run();
        operation.result().complete(operation.snapshot());
        clearCompletedSnapshot(operation);
    }

    /// Cancels a tracked snapshot before releasing its activity slot.
    ///
    /// @param operation snapshot completion to cancel
    /// @param message cancellation explanation
    private void cancelSnapshotCompletion(SnapshotCompletion operation, String message) {
        operation.result().completeExceptionally(new CancellationException(message));
        clearCompletedSnapshot(operation);
    }

    /// Releases a snapshot slot only after its future has reached an atomic terminal state.
    ///
    /// @param operation completed snapshot
    private void clearCompletedSnapshot(SnapshotCompletion operation) {
        synchronized (stateLock) {
            activeSnapshotCompletions.remove(operation);
        }
    }

    /// Completes an invalid command from the caller-owned executor.
    ///
    /// @param failure command validation failure
    /// @return asynchronous failed completion
    private CompletionStage<SchematicBrowserSnapshot> failOnExecutor(IllegalArgumentException failure) {
        CompletableFuture<SchematicBrowserSnapshot> result = new CompletableFuture<>();
        try {
            executor.execute(() -> result.completeExceptionally(failure));
        } catch (RuntimeException rejection) {
            result.completeExceptionally(rejection);
        } catch (Error rejection) {
            result.completeExceptionally(rejection);
            throw rejection;
        }
        return result;
    }

    /// Publishes one still-current transition without holding [#stateLock].
    ///
    /// Listener runtime failures are isolated so one UI consumer cannot suppress another.
    ///
    /// @param transition immutable state change
    private void publish(@Nullable SnapshotTransition transition) {
        if (transition == null) {
            return;
        }
        synchronized (publicationLock) {
            if (closed || state.snapshot() != transition.current()) {
                return;
            }
            ValueChange<SchematicBrowserSnapshot> change = new ValueChange<>(
                    this, transition.previous(), transition.current());
            for (ValueChangeListener<SchematicBrowserSnapshot> listener : listeners) {
                if (closed || state.snapshot() != transition.current()) {
                    break;
                }
                try {
                    listener.onChange(change);
                } catch (RuntimeException ignored) {
                    // Listener isolation is part of the model boundary; other listeners still run.
                }
            }
        }
    }

    /// Throws when a command is issued after closure.
    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("Schematic browser is closed");
        }
    }

    /// Produces stable non-null text for an ordinary I/O or parse failure.
    ///
    /// @param failure source failure
    /// @return non-empty failure text
    private static String failureText(Throwable failure) {
        @Nullable String message = failure.getMessage();
        return message == null || message.isBlank()
                ? failure.getClass().getSimpleName()
                : message;
    }

    /// Performs a single-level, no-follow directory scan for directories and Litematic files.
    ///
    /// @param directory directory to scan
    /// @param cancellation cooperative cancellation signal
    /// @return immutable direct-child descriptors
    /// @throws IOException when the directory itself cannot be read
    private static @Unmodifiable List<DiscoveredEntry> scanDirectory(
            Path rootDirectory,
            Path directory,
            LoadCancellation cancellation) throws IOException {
        cancellation.throwIfCancelled();
        validateDirectoryChain(rootDirectory, directory, cancellation::throwIfCancelled);

        List<DiscoveredEntry> entries = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
            validateDirectory(directory);
            for (Path child : stream) {
                cancellation.throwIfCancelled();
                try {
                    BasicFileAttributes attributes = Files.readAttributes(
                            child, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                    if (attributes.isSymbolicLink()) {
                        continue;
                    }
                    String fileName = child.getFileName().toString();
                    if (attributes.isDirectory()) {
                        entries.add(new DiscoveredEntry(child.toAbsolutePath().normalize(), fileName, true));
                    } else if (attributes.isRegularFile()
                            && fileName.toLowerCase(Locale.ROOT).endsWith(".litematic")) {
                        entries.add(new DiscoveredEntry(child.toAbsolutePath().normalize(), fileName, false));
                    }
                } catch (IOException | SecurityException ignored) {
                    // One inaccessible child must not destabilize the indexes of readable siblings.
                }
            }
        }
        validateDirectoryChain(rootDirectory, directory, cancellation::throwIfCancelled);
        cancellation.throwIfCancelled();
        return List.copyOf(entries);
    }

    /// Creates the production shallow reader without touching the file system during construction.
    ///
    /// @param rootDirectory configured lexical root
    /// @return reader that validates every descendant component before scanning
    private static DirectoryReader productionDirectoryReader(Path rootDirectory) {
        Path normalizedRoot = Objects.requireNonNull(rootDirectory, "rootDirectory")
                .toAbsolutePath().normalize();
        return (directory, cancellation) -> scanDirectory(normalizedRoot, directory, cancellation);
    }

    /// Creates the production metadata reader with fresh root and link validation for every lazy row.
    ///
    /// @param rootDirectory configured lexical root
    /// @return metadata reader that rejects replaced ancestors and final symbolic links
    private static MetadataReader productionMetadataReader(Path rootDirectory) {
        Path normalizedRoot = Objects.requireNonNull(rootDirectory, "rootDirectory")
                .toAbsolutePath().normalize();
        return path -> {
            Path normalizedFile = path.toAbsolutePath().normalize();
            @Nullable Path parent = normalizedFile.getParent();
            if (parent == null) {
                throw new IOException("Litematic path has no parent directory: " + normalizedFile);
            }
            validateDirectoryChain(normalizedRoot, parent, NO_CANCELLATION_CHECK);
            validateRegularFile(normalizedFile);
            LitematicFile metadata = LitematicFile.load(normalizedFile);
            validateDirectoryChain(normalizedRoot, parent, NO_CANCELLATION_CHECK);
            validateRegularFile(normalizedFile);
            return metadata;
        };
    }

    /// Rejects a target outside the root or containing a symbolic-link directory component.
    ///
    /// The check is repeated for the target after opening and after reading its directory stream.
    /// This portable validation prevents stable ancestor-link traversal on every supported file
    /// system and narrows replacement races around the scan.
    ///
    /// @param rootDirectory immutable normalized navigation root
    /// @param directory normalized target directory
    /// @param cancellationCheck cooperative cancellation check invoked between components
    /// @throws IOException when the target escapes the root or any component is not a real directory
    private static void validateDirectoryChain(
            Path rootDirectory,
            Path directory,
            Runnable cancellationCheck) throws IOException {
        Path normalizedDirectory = directory.toAbsolutePath().normalize();
        if (!normalizedDirectory.startsWith(rootDirectory)) {
            throw new IOException("Schematic path escaped its root directory: " + normalizedDirectory);
        }

        Path current = rootDirectory;
        cancellationCheck.run();
        validateDirectory(current);
        for (Path component : rootDirectory.relativize(normalizedDirectory)) {
            cancellationCheck.run();
            current = current.resolve(component);
            validateDirectory(current);
        }
    }

    /// Validates one directory component without following a symbolic link in that component.
    ///
    /// @param directory component to validate
    /// @throws IOException when the component is not a real directory
    private static void validateDirectory(Path directory) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(
                directory, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isDirectory() || attributes.isSymbolicLink()) {
            throw new IOException("Schematic path is not a real directory: " + directory);
        }
    }

    /// Validates one lazy-load target without following a symbolic link in the final component.
    ///
    /// @param file Litematic path to validate
    /// @throws IOException when the final component is not a real regular file
    private static void validateRegularFile(Path file) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(
                file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile() || attributes.isSymbolicLink()) {
            throw new IOException("Schematic path is not a real Litematic file: " + file);
        }
    }

    /// Reads one shallow directory listing for a model-owned scan operation.
    @FunctionalInterface
    @NotNullByDefault
    interface DirectoryReader {
        /// Returns direct child descriptors without following symbolic links.
        ///
        /// @param directory directory to inspect
        /// @param cancellation cooperative cancellation signal
        /// @return immutable direct child descriptors
        /// @throws IOException when the directory itself cannot be read
        @Unmodifiable List<DiscoveredEntry> read(
                Path directory,
                LoadCancellation cancellation) throws IOException;
    }

    /// Reads toolkit-neutral metadata from one known Litematic file.
    @FunctionalInterface
    @NotNullByDefault
    interface MetadataReader {
        /// Parses one Litematic source.
        ///
        /// @param path source path
        /// @return parsed core metadata
        /// @throws IOException when metadata cannot be parsed
        LitematicFile read(Path path) throws IOException;
    }

    /// One scan-time direct child descriptor without parsed file metadata.
    ///
    /// @param path exact normalized child path
    /// @param fileName stable source file name
    /// @param directory whether the child is a directory
    @NotNullByDefault
    record DiscoveredEntry(Path path, String fileName, boolean directory) {
    }

    /// Immutable atomically published indexed content and presentation snapshot.
    ///
    /// @param entries sorted shallow descriptors
    /// @param snapshot matching presentation state
    @NotNullByDefault
    private record BrowserState(
            @Unmodifiable List<DiscoveredEntry> entries,
            SchematicBrowserSnapshot snapshot) {
        /// Defensively freezes descriptors with the matching snapshot.
        private BrowserState {
            entries = List.copyOf(entries);
        }
    }

    /// One cancellable directory scan generation.
    ///
    /// @param generation ownership token
    /// @param targetDirectory directory to scan
    /// @param cancellation cooperative cancellation signal
    /// @param result externally observed completion
    @NotNullByDefault
    private record ScanOperation(
            long generation,
            Path targetDirectory,
            LoadCancellation cancellation,
            CompletableFuture<SchematicBrowserSnapshot> result) {
    }

    /// One cancellable viewport parse operation over a captured immutable descriptor index.
    ///
    /// @param generation ownership token
    /// @param desiredRange requested viewport range
    /// @param entries captured stable descriptors
    /// @param callerCancellation caller-owned cancellation signal
    /// @param lifecycleCancellation model-owned cancellation signal
    /// @param result externally observed completion
    @NotNullByDefault
    private record RangeOperation(
            long generation,
            IndexRange desiredRange,
            @Unmodifiable List<DiscoveredEntry> entries,
            LoadCancellation callerCancellation,
            LoadCancellation lifecycleCancellation,
            CompletableFuture<ChoicePage<SchematicBrowserItem>> result) {
        /// Defensively freezes the captured index.
        private RangeOperation {
            entries = List.copyOf(entries);
        }
    }

    /// One tracked asynchronous completion of an already captured immutable snapshot.
    ///
    /// @param generation captured ownership generation
    /// @param snapshot immutable value to complete when still current
    /// @param result externally observed completion
    @NotNullByDefault
    private record SnapshotCompletion(
            long generation,
            SchematicBrowserSnapshot snapshot,
            CompletableFuture<SchematicBrowserSnapshot> result) {
    }

    /// Work cancelled and state published when activating a prepared scan.
    ///
    /// @param operation new scan
    /// @param previousScan superseded scan, or null
    /// @param previousRanges superseded viewport requests
    /// @param previousSnapshots superseded no-op snapshot completions
    /// @param transition committed loading transition
    @NotNullByDefault
    private record ScanPreparation(
            ScanOperation operation,
            @Nullable ScanOperation previousScan,
            @Unmodifiable List<RangeOperation> previousRanges,
            @Unmodifiable List<SnapshotCompletion> previousSnapshots,
            SnapshotTransition transition) {
        /// Defensively freezes superseded operations.
        private ScanPreparation {
            previousRanges = List.copyOf(previousRanges);
            previousSnapshots = List.copyOf(previousSnapshots);
        }
    }

    /// Immutable state transition published outside [#stateLock].
    ///
    /// @param previous state before replacement
    /// @param current committed replacement
    @NotNullByDefault
    private record SnapshotTransition(
            SchematicBrowserSnapshot previous,
            SchematicBrowserSnapshot current) {
    }
}
