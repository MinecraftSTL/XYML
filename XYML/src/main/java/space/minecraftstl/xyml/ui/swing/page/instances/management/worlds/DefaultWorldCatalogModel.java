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
package space.minecraftstl.xyml.ui.swing.page.instances.management.worlds;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import space.minecraftstl.xyml.game.GameInstanceID;
import space.minecraftstl.xyml.game.GameRepository;
import space.minecraftstl.xyml.observable.Subscription;
import space.minecraftstl.xyml.observable.ValueChangeListener;
import space.minecraftstl.xyml.observable.ValueChangeSupport;
import space.minecraftstl.xyml.ui.swing.choice.ChoicePage;
import space.minecraftstl.xyml.ui.swing.choice.IndexRange;
import space.minecraftstl.xyml.ui.swing.choice.LoadCancellation;

import javax.swing.SwingUtilities;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;

/// Asynchronous instance-world catalog with a shallow path index and incremental NBT parsing.
///
/// Opening the World tab enumerates direct child directories and publishes their exact count. It
/// does not instantiate `World` for every save. Instead, `ViewportChoiceList` asks `load` for a
/// short range. Show All materializes only those rows; the legacy current-version filter scans and
/// caches only far enough to satisfy the currently requested filtered range.
@NotNullByDefault
public final class DefaultWorldCatalogModel implements WorldCatalogModel {
    /// Serializes index ownership, mutations, closure, and atomic state replacement.
    private final Object stateLock = new Object();

    /// Serializes incremental filtered scans without holding [#stateLock] during NBT I/O.
    private final Object filterScanLock = new Object();

    /// Blocking repository and Core World boundary.
    private final WorldCatalogAccess access;

    /// Caller-owned executor for every blocking path, archive, and NBT operation.
    private final Executor executor;

    /// Stable status and action text.
    private final WorldCatalogStrings strings;

    /// Whether the source belongs to an instance whose current version can filter worlds.
    private final boolean versionFiltering;

    /// Thread-safe synchronous snapshot publisher.
    private final ValueChangeSupport<WorldCatalogSnapshot> changes = new ValueChangeSupport<>(this);

    /// Volatile immutable source paths paired with their visible snapshot.
    private volatile ModelState state;

    /// Monotonically increasing ownership generation for refreshes and mutations.
    private long generation;

    /// Current shallow-index cancellation signal, or `null` without an active index.
    private @Nullable LoadCancellation activeRefreshCancellation;

    /// Current serialized import/delete operation, or `null` when idle.
    private @Nullable MutationOperation activeMutation;

    /// Whether all subsequent model operations must fail immediately.
    private boolean closed;

    /// Whether every shallow-index entry is visible instead of applying the current-version filter.
    private boolean showAll;

    /// Normalized current instance version resolved during the latest background refresh.
    private Optional<String> currentGameVersion = Optional.empty();

    /// Source revision represented by the incremental filtered cache, or negative before first use.
    private long filterCacheRevision = -1L;

    /// Matching paths already discovered in stable source order; guarded by [#filterScanLock].
    private final List<Path> filteredDirectories = new ArrayList<>();

    /// Number of shallow source directories examined for [#filterCacheRevision].
    private int filteredScanCount;

    /// Whether every shallow source directory has been examined for [#filterCacheRevision].
    private boolean filteredScanComplete;

    /// Creates a production model without resolving or enumerating the saves directory.
    ///
    /// @param repository managed game repository
    /// @param instanceId stable managed instance identifier
    /// @param executor caller-owned executor for blocking operations
    /// @param strings status and action strings
    public DefaultWorldCatalogModel(
            GameRepository repository,
            GameInstanceID instanceId,
            Executor executor,
            WorldCatalogStrings strings) {
        this(new FileSystemWorldCatalogAccess(repository, instanceId), executor, strings, false, true);
    }

    /// Creates a deterministic model with an injected blocking source for focused tests.
    ///
    /// @param access source adapter whose shallow scan and viewport loads can be observed
    /// @param executor caller-owned executor for source work
    /// @param strings status and action strings
    DefaultWorldCatalogModel(
            WorldCatalogAccess access,
            Executor executor,
            WorldCatalogStrings strings) {
        this(access, executor, strings, true, false);
    }

    /// Creates a deterministic model with explicit legacy-filter availability and initial state.
    ///
    /// @param access source adapter whose shallow scan and viewport loads can be observed
    /// @param executor caller-owned executor for source work
    /// @param strings status and action strings
    /// @param showAll whether every indexed world is initially visible
    /// @param versionFiltering whether current-instance filtering is available
    DefaultWorldCatalogModel(
            WorldCatalogAccess access,
            Executor executor,
            WorldCatalogStrings strings,
            boolean showAll,
            boolean versionFiltering) {
        this.access = Objects.requireNonNull(access, "access");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.strings = Objects.requireNonNull(strings, "strings");
        this.showAll = showAll;
        this.versionFiltering = versionFiltering;
        state = initialState();
    }

    /// Returns the latest state without touching the filesystem.
    ///
    /// @return immutable latest snapshot
    @Override
    public WorldCatalogSnapshot snapshot() {
        return state.snapshot();
    }

    /// Confirms that production instances retain the legacy current-version filter.
    ///
    /// @return configured version-filter availability
    @Override
    public boolean supportsVersionFiltering() {
        return versionFiltering;
    }

    /// Returns the current unfiltered-list mode under the model state lock.
    ///
    /// @return whether every indexed world is visible
    @Override
    public boolean showAll() {
        synchronized (stateLock) {
            return showAll;
        }
    }

    /// Invalidates viewport rows when the legacy current-version filter changes.
    ///
    /// @param replacement whether every indexed world should be visible
    @Override
    public void setShowAll(boolean replacement) {
        if (!versionFiltering) {
            return;
        }
        SnapshotTransition transition;
        synchronized (stateLock) {
            requireOpen();
            if (showAll == replacement) {
                return;
            }
            showAll = replacement;
            WorldCatalogSnapshot previous = state.snapshot();
            WorldCatalogSnapshot refreshed = new WorldCatalogSnapshot(
                    previous.itemCount(),
                    nextRevision(previous.contentRevision()),
                    previous.status(),
                    previous.statusText(),
                    previous.operationText(),
                    previous.listEnabled(),
                    previous.refreshEnabled(),
                    previous.operationPending());
            transition = replaceStateLocked(state.directories(), refreshed);
        }
        publish(transition);
    }

    /// Registers a future listener while the model is open.
    ///
    /// @param listener snapshot listener
    /// @return independently closable subscription
    @Override
    public Subscription subscribe(ValueChangeListener<WorldCatalogSnapshot> listener) {
        Objects.requireNonNull(listener, "listener");
        synchronized (stateLock) {
            requireOpen();
            return changes.subscribe(listener);
        }
    }

    /// Resolves the saves path through the adapter without triggering an index.
    ///
    /// @return normalized saves path
    @Override
    public Path savesDirectory() {
        synchronized (stateLock) {
            requireOpen();
        }
        return access.savesDirectory();
    }

    /// Exposes an exact row count only after a shallow index has completed.
    ///
    /// @return exact current count, or empty while idle/loading/failed
    @Override
    public OptionalInt exactItemCount() {
        ModelState captured;
        boolean filtered;
        synchronized (stateLock) {
            captured = state;
            filtered = versionFiltering && !showAll;
        }
        if (!filtered) {
            return captured.snapshot().itemCount();
        }
        synchronized (filterScanLock) {
            if (filterCacheRevision == captured.snapshot().contentRevision() && filteredScanComplete) {
                return OptionalInt.of(filteredDirectories.size());
            }
        }
        return OptionalInt.empty();
    }

    /// Exposes the current source revision used to discard superseded viewport rows.
    ///
    /// @return immutable source revision
    @Override
    public OptionalLong sourceRevision() {
        return OptionalLong.of(state.snapshot().contentRevision());
    }

    /// Starts one first-time shallow index from the idle state.
    @Override
    public void loadIfNeeded() {
        startRefresh(true);
    }

    /// Starts a user-requested shallow index refresh.
    @Override
    public void refresh() {
        startRefresh(false);
    }

    /// Validates an archive on the supplied background executor without changing list state.
    ///
    /// @param archive selected local archive
    /// @return asynchronously validated import candidate
    @Override
    public CompletionStage<WorldCatalogImport> inspectImport(Path archive) {
        Path normalizedArchive;
        try {
            normalizedArchive = Objects.requireNonNull(archive, "archive").toAbsolutePath().normalize();
        } catch (RuntimeException failure) {
            return CompletableFuture.failedFuture(failure);
        }
        synchronized (stateLock) {
            if (closed) {
                return CompletableFuture.failedFuture(new IllegalStateException("World catalog model is closed"));
            }
        }
        CompletableFuture<WorldCatalogImport> result = new CompletableFuture<>();
        LoadCancellation cancellation = new LoadCancellation();
        try {
            executor.execute(() -> inspectImportOnExecutor(normalizedArchive, cancellation, result));
        } catch (RuntimeException failure) {
            result.completeExceptionally(failure);
        }
        return result;
    }

    /// Starts one serialized Core archive installation followed by a shallow index refresh.
    ///
    /// @param world preflighted archive source
    /// @param targetName user-confirmed target name
    /// @return terminal catalog snapshot
    @Override
    public CompletionStage<WorldCatalogSnapshot> installWorld(WorldCatalogImport world, String targetName) {
        WorldCatalogImport importWorld;
        String normalizedTargetName;
        try {
            importWorld = Objects.requireNonNull(world, "world");
            normalizedTargetName = requireNonBlank(targetName, "targetName");
        } catch (RuntimeException failure) {
            return CompletableFuture.failedFuture(failure);
        }
        return startMutation(
                strings.importingText(),
                (source, cancellation) -> source.install(importWorld, normalizedTargetName, cancellation));
    }

    /// Starts one serialized Core deletion followed by a shallow index refresh.
    ///
    /// @param world exact materialized current row
    /// @return terminal catalog snapshot
    @Override
    public CompletionStage<WorldCatalogSnapshot> deleteWorld(WorldCatalogItem world) {
        WorldCatalogItem selectedWorld;
        try {
            selectedWorld = Objects.requireNonNull(world, "world");
        } catch (RuntimeException failure) {
            return CompletableFuture.failedFuture(failure);
        }
        return startMutation(
                strings.deletingText(),
                (source, cancellation) -> source.delete(selectedWorld, cancellation),
                selectedWorld);
    }

    /// Starts one serialized Core copy followed by a fresh shallow index.
    ///
    /// @param world exact materialized current row
    /// @param targetName requested sibling directory and stored level name
    /// @return terminal catalog snapshot
    @Override
    public CompletionStage<WorldCatalogSnapshot> copyWorld(WorldCatalogItem world, String targetName) {
        WorldCatalogItem selectedWorld;
        String normalizedTargetName;
        try {
            selectedWorld = Objects.requireNonNull(world, "world");
            normalizedTargetName = requireNonBlank(targetName, "targetName");
        } catch (RuntimeException failure) {
            return CompletableFuture.failedFuture(failure);
        }
        return startMutation(
                strings.copyingText(),
                (source, cancellation) -> source.copy(selectedWorld, normalizedTargetName, cancellation),
                selectedWorld);
    }

    /// Starts one serialized atomic ZIP export followed by a fresh shallow index.
    ///
    /// The reindex keeps action ownership identical to copy, import, and delete while preserving
    /// the viewport-only metadata contract.
    ///
    /// @param world exact materialized current row
    /// @param archive requested ZIP destination
    /// @return terminal catalog snapshot
    @Override
    public CompletionStage<WorldCatalogSnapshot> exportWorld(WorldCatalogItem world, Path archive) {
        WorldCatalogItem selectedWorld;
        Path normalizedArchive;
        try {
            selectedWorld = Objects.requireNonNull(world, "world");
            normalizedArchive = Objects.requireNonNull(archive, "archive").toAbsolutePath().normalize();
        } catch (RuntimeException failure) {
            return CompletableFuture.failedFuture(failure);
        }
        return startMutation(
                strings.exportingText(),
                (source, cancellation) -> source.export(selectedWorld, normalizedArchive, cancellation),
                selectedWorld);
    }

    /// Schedules one exact range of NBT-backed rows after the shallow index is ready.
    ///
    /// @param desiredRange requested logical viewport range
    /// @param cancellation viewport-owned cancellation signal
    /// @return page matching the effective current range
    @Override
    public CompletionStage<ChoicePage<WorldCatalogItem>> load(
            IndexRange desiredRange,
            LoadCancellation cancellation) {
        Objects.requireNonNull(desiredRange, "desiredRange");
        Objects.requireNonNull(cancellation, "cancellation");
        ModelState captured;
        boolean filtered;
        Optional<String> targetVersion;
        synchronized (stateLock) {
            if (closed) {
                return CompletableFuture.failedFuture(new IllegalStateException("World catalog model is closed"));
            }
            captured = state;
            filtered = versionFiltering && !showAll;
            targetVersion = currentGameVersion;
        }
        OptionalInt itemCount = captured.snapshot().itemCount();
        if (itemCount.isEmpty()) {
            return CompletableFuture.failedFuture(new IllegalStateException("World directory index is not ready"));
        }
        IndexRange effectiveRange = desiredRange.clampToItemCount(itemCount.getAsInt());
        if (filtered) {
            CompletableFuture<ChoicePage<WorldCatalogItem>> result = new CompletableFuture<>();
            FilteredRangeOperation operation = new FilteredRangeOperation(
                    captured.snapshot().contentRevision(),
                    effectiveRange,
                    captured.directories(),
                    targetVersion,
                    cancellation,
                    result);
            try {
                executor.execute(() -> executeFilteredRange(operation));
            } catch (RuntimeException failure) {
                result.completeExceptionally(failure);
            }
            return result;
        }
        @Unmodifiable List<Path> directories = List.copyOf(captured.directories().subList(
                effectiveRange.startInclusive(),
                effectiveRange.endExclusive()));
        CompletableFuture<ChoicePage<WorldCatalogItem>> result = new CompletableFuture<>();
        RangeOperation operation = new RangeOperation(
                captured.snapshot().contentRevision(),
                effectiveRange,
                itemCount.getAsInt(),
                directories,
                cancellation,
                result);
        try {
            executor.execute(() -> executeRange(operation));
        } catch (RuntimeException failure) {
            result.completeExceptionally(failure);
        }
        return result;
    }

    /// Cancels owned refreshes and mutations, then rejects all later work.
    @Override
    public void close() {
        @Nullable MutationOperation mutation;
        synchronized (stateLock) {
            if (closed) {
                return;
            }
            closed = true;
            generation++;
            if (activeRefreshCancellation != null) {
                activeRefreshCancellation.cancel();
                activeRefreshCancellation = null;
            }
            mutation = activeMutation;
            activeMutation = null;
            if (mutation != null) {
                mutation.cancellation().cancel();
            }
        }
        if (mutation != null) {
            mutation.result().completeExceptionally(new CancellationException("World catalog model was closed"));
        }
    }

    /// Prepares and submits one shallow index refresh.
    ///
    /// @param onlyIfIdle whether non-idle models suppress this request
    private void startRefresh(boolean onlyIfIdle) {
        RefreshOperation operation;
        SnapshotTransition transition;
        synchronized (stateLock) {
            requireOpen();
            WorldCatalogSnapshot previous = state.snapshot();
            if (activeMutation != null || onlyIfIdle && previous.status() != WorldCatalogStatus.IDLE) {
                return;
            }
            if (activeRefreshCancellation != null) {
                activeRefreshCancellation.cancel();
            }
            LoadCancellation cancellation = new LoadCancellation();
            activeRefreshCancellation = cancellation;
            operation = new RefreshOperation(++generation, cancellation);
            WorldCatalogSnapshot loading = new WorldCatalogSnapshot(
                    OptionalInt.empty(),
                    nextRevision(previous.contentRevision()),
                    WorldCatalogStatus.LOADING,
                    strings.loadingText(),
                    "",
                    false,
                    false,
                    false);
            transition = replaceStateLocked(List.of(), loading);
        }
        publish(transition);
        try {
            executor.execute(() -> executeRefresh(operation));
        } catch (RuntimeException failure) {
            commitRefreshFailure(operation, failure);
        }
    }

    /// Runs the shallow directory index outside the EDT.
    ///
    /// @param operation current refresh owner
    private void executeRefresh(RefreshOperation operation) {
        try {
            requireBackgroundThread();
            Optional<String> resolvedGameVersion = versionFiltering
                    ? access.instanceGameVersion()
                    : Optional.empty();
            operation.cancellation().throwIfCancelled();
            @Unmodifiable List<Path> directories = access.indexWorldDirectories(operation.cancellation());
            operation.cancellation().throwIfCancelled();
            commitRefresh(operation, directories, resolvedGameVersion);
        } catch (IOException | RuntimeException failure) {
            commitRefreshFailure(operation, failure);
        }
    }

    /// Commits one current complete shallow index.
    ///
    /// @param operation current refresh owner
    /// @param directories immutable direct-child paths
    /// @param resolvedGameVersion normalized current instance game version, when available
    private void commitRefresh(
            RefreshOperation operation,
            @Unmodifiable List<Path> directories,
            Optional<String> resolvedGameVersion) {
        SnapshotTransition transition;
        synchronized (stateLock) {
            if (!ownsRefresh(operation)) {
                return;
            }
            activeRefreshCancellation = null;
            currentGameVersion = Objects.requireNonNull(resolvedGameVersion, "resolvedGameVersion");
            WorldCatalogSnapshot previous = state.snapshot();
            WorldCatalogSnapshot ready = readySnapshot(
                    directories.size(),
                    nextRevision(previous.contentRevision()),
                    "");
            transition = replaceStateLocked(directories, ready);
        }
        publish(transition);
    }

    /// Commits a retryable shallow-index failure only when its owner remains current.
    ///
    /// @param operation current refresh owner
    /// @param failure source or executor failure
    private void commitRefreshFailure(RefreshOperation operation, Throwable failure) {
        SnapshotTransition transition;
        synchronized (stateLock) {
            if (!ownsRefresh(operation)) {
                return;
            }
            activeRefreshCancellation = null;
            WorldCatalogSnapshot previous = state.snapshot();
            WorldCatalogSnapshot failed = new WorldCatalogSnapshot(
                    OptionalInt.empty(),
                    nextRevision(previous.contentRevision()),
                    WorldCatalogStatus.FAILED,
                    strings.loadFailureText(failureDetail(failure)),
                    "",
                    false,
                    true,
                    false);
            transition = replaceStateLocked(List.of(), failed);
        }
        publish(transition);
    }

    /// Runs archive preflight outside the EDT and preserves its original failure.
    ///
    /// @param archive normalized selected archive
    /// @param cancellation owned preflight cancellation
    /// @param result externally visible preflight completion
    private void inspectImportOnExecutor(
            Path archive,
            LoadCancellation cancellation,
            CompletableFuture<WorldCatalogImport> result) {
        try {
            requireBackgroundThread();
            WorldCatalogImport candidate = access.inspectImport(archive, cancellation);
            synchronized (stateLock) {
                if (closed || cancellation.isCancelled()) {
                    throw new CancellationException("World import inspection was cancelled");
                }
            }
            result.complete(candidate);
        } catch (IOException | RuntimeException failure) {
            result.completeExceptionally(failure);
        }
    }

    /// Starts a serialized mutation without an additional stale-row identity check.
    ///
    /// @param operationText localized active status
    /// @param action blocking Core mutation
    /// @return terminal catalog snapshot
    private CompletionStage<WorldCatalogSnapshot> startMutation(
            String operationText,
            MutationAction action) {
        return startMutation(operationText, action, null);
    }

    /// Starts a serialized mutation and optionally checks its selected row against the current index.
    ///
    /// @param operationText localized active status
    /// @param action blocking Core mutation
    /// @param requiredCurrentWorld selected world required to remain in the current index, or null
    /// @return terminal catalog snapshot
    private CompletionStage<WorldCatalogSnapshot> startMutation(
            String operationText,
            MutationAction action,
            @Nullable WorldCatalogItem requiredCurrentWorld) {
        MutationOperation operation;
        SnapshotTransition transition;
        try {
            operationText = requireNonBlank(operationText, "operationText");
            action = Objects.requireNonNull(action, "action");
        } catch (RuntimeException failure) {
            return CompletableFuture.failedFuture(failure);
        }
        synchronized (stateLock) {
            if (closed) {
                return CompletableFuture.failedFuture(new IllegalStateException("World catalog model is closed"));
            }
            WorldCatalogSnapshot previous = state.snapshot();
            if (previous.status() != WorldCatalogStatus.READY) {
                return CompletableFuture.failedFuture(new IllegalStateException("World directory index is not ready"));
            }
            if (activeMutation != null) {
                return CompletableFuture.failedFuture(new IllegalStateException("Another world operation is already running"));
            }
            if (requiredCurrentWorld != null && !state.directories().contains(requiredCurrentWorld.path())) {
                return CompletableFuture.failedFuture(new IllegalArgumentException("Selected world is no longer current"));
            }
            CompletableFuture<WorldCatalogSnapshot> result = new CompletableFuture<>();
            operation = new MutationOperation(++generation, new LoadCancellation(), result, action);
            activeMutation = operation;
            WorldCatalogSnapshot busy = new WorldCatalogSnapshot(
                    previous.itemCount(),
                    previous.contentRevision(),
                    WorldCatalogStatus.READY,
                    previous.statusText(),
                    operationText,
                    false,
                    false,
                    true);
            transition = replaceStateLocked(state.directories(), busy);
        }
        publish(transition);
        try {
            executor.execute(() -> executeMutation(operation));
        } catch (RuntimeException failure) {
            commitMutationFailure(operation, failure);
        }
        return operation.result();
    }

    /// Runs one Core mutation and mandatory fresh shallow index outside the EDT.
    ///
    /// @param operation active serialized mutation
    private void executeMutation(MutationOperation operation) {
        try {
            requireBackgroundThread();
            operation.action().run(access, operation.cancellation());
            operation.cancellation().throwIfCancelled();
            @Unmodifiable List<Path> directories = access.indexWorldDirectories(operation.cancellation());
            operation.cancellation().throwIfCancelled();
            commitMutation(operation, directories);
        } catch (IOException | RuntimeException failure) {
            commitMutationFailure(operation, failure);
        }
    }

    /// Commits one successfully mutated and freshly indexed directory source.
    ///
    /// @param operation active serialized mutation
    /// @param directories fresh shallow source
    private void commitMutation(MutationOperation operation, @Unmodifiable List<Path> directories) {
        WorldCatalogSnapshot terminal;
        SnapshotTransition transition;
        synchronized (stateLock) {
            if (!ownsMutation(operation)) {
                return;
            }
            activeMutation = null;
            WorldCatalogSnapshot previous = state.snapshot();
            terminal = readySnapshot(directories.size(), nextRevision(previous.contentRevision()), "");
            transition = replaceStateLocked(directories, terminal);
        }
        publish(transition);
        operation.result().complete(terminal);
    }

    /// Invalidates the current index after a failed or partially completed mutation.
    ///
    /// The failed Core operation can have changed disk state before reporting an error. Clearing the
    /// source avoids presenting stale files; the user can retry refresh to observe the actual state.
    ///
    /// @param operation active serialized mutation
    /// @param failure original Core, filesystem, or executor failure
    private void commitMutationFailure(MutationOperation operation, Throwable failure) {
        SnapshotTransition transition;
        synchronized (stateLock) {
            if (!ownsMutation(operation)) {
                return;
            }
            activeMutation = null;
            WorldCatalogSnapshot previous = state.snapshot();
            String detail = failureDetail(failure);
            WorldCatalogSnapshot failed = new WorldCatalogSnapshot(
                    OptionalInt.empty(),
                    nextRevision(previous.contentRevision()),
                    WorldCatalogStatus.FAILED,
                    strings.loadFailureText(detail),
                    strings.failureTitle() + ": " + detail,
                    false,
                    true,
                    false);
            transition = replaceStateLocked(List.of(), failed);
        }
        publish(transition);
        operation.result().completeExceptionally(failure);
    }

    /// Materializes exactly the requested shallow-index range and checks supersession before completion.
    ///
    /// @param operation captured viewport request
    private void executeRange(RangeOperation operation) {
        try {
            requireBackgroundThread();
            operation.cancellation().throwIfCancelled();
            List<WorldCatalogItem> values = new ArrayList<>(operation.directories().size());
            for (Path directory : operation.directories()) {
                operation.cancellation().throwIfCancelled();
                values.add(access.loadItem(directory, operation.cancellation()));
            }
            synchronized (stateLock) {
                if (closed
                        || operation.cancellation().isCancelled()
                        || state.snapshot().contentRevision() != operation.contentRevision()) {
                    throw new CancellationException("World viewport result was superseded");
                }
            }
            @Unmodifiable List<WorldCatalogItem> immutableValues = List.copyOf(values);
            operation.result().complete(new ChoicePage<>(
                    operation.range(),
                    immutableValues,
                    OptionalInt.of(operation.itemCount()),
                    operation.range().endExclusive() == operation.itemCount()));
        } catch (RuntimeException failure) {
            operation.result().completeExceptionally(failure);
        }
    }

    /// Incrementally scans and caches only enough source rows to satisfy one filtered viewport range.
    ///
    /// @param operation captured current-version range request
    private void executeFilteredRange(FilteredRangeOperation operation) {
        try {
            requireBackgroundThread();
            ChoicePage<WorldCatalogItem> page;
            synchronized (filterScanLock) {
                prepareFilteredCache(operation);
                Map<Path, WorldCatalogItem> newlyScannedItems = new HashMap<>();
                while (filteredDirectories.size() < operation.range().endExclusive()
                        && filteredScanCount < operation.directories().size()) {
                    requireCurrentFilteredOperation(operation);
                    Path directory = operation.directories().get(filteredScanCount);
                    WorldCatalogItem item = access.loadItem(directory, operation.cancellation());
                    requireCurrentFilteredOperation(operation);
                    filteredScanCount++;
                    if (matchesCurrentVersion(item, operation.targetVersion())) {
                        filteredDirectories.add(directory);
                        newlyScannedItems.put(directory, item);
                    }
                }
                filteredScanComplete = filteredScanCount == operation.directories().size();
                int discoveredCount = filteredDirectories.size();
                IndexRange actualRange = operation.range().clampToItemCount(discoveredCount);
                List<WorldCatalogItem> values = new ArrayList<>(actualRange.length());
                for (Path directory : filteredDirectories.subList(
                        actualRange.startInclusive(),
                        actualRange.endExclusive())) {
                    requireCurrentFilteredOperation(operation);
                    @Nullable WorldCatalogItem item = newlyScannedItems.get(directory);
                    values.add(item == null
                            ? access.loadItem(directory, operation.cancellation())
                            : item);
                }
                OptionalInt exactCount = filteredScanComplete
                        ? OptionalInt.of(discoveredCount)
                        : OptionalInt.empty();
                page = new ChoicePage<>(
                        actualRange,
                        List.copyOf(values),
                        exactCount,
                        filteredScanComplete && actualRange.endExclusive() == discoveredCount);
            }
            requireCurrentFilteredOperation(operation);
            operation.result().complete(page);
        } catch (RuntimeException failure) {
            operation.result().completeExceptionally(failure);
        }
    }

    /// Resets the incremental filtered cache when a source or mode revision changes.
    ///
    /// @param operation current filtered request
    private void prepareFilteredCache(FilteredRangeOperation operation) {
        requireCurrentFilteredOperation(operation);
        if (filterCacheRevision != operation.contentRevision()) {
            filterCacheRevision = operation.contentRevision();
            filteredDirectories.clear();
            filteredScanCount = 0;
            filteredScanComplete = operation.directories().isEmpty();
        }
    }

    /// Rejects an incremental scan superseded by refresh, mutation, mode change, or closure.
    ///
    /// @param operation captured filtered request
    private void requireCurrentFilteredOperation(FilteredRangeOperation operation) {
        operation.cancellation().throwIfCancelled();
        synchronized (stateLock) {
            if (closed
                    || showAll
                    || state.snapshot().contentRevision() != operation.contentRevision()) {
                throw new CancellationException("Filtered world viewport result was superseded");
            }
        }
    }

    /// Applies the old UI rule: retain unknown versions and exact current-instance versions.
    ///
    /// @param item materialized world metadata
    /// @param targetVersion normalized current instance version, when known
    /// @return whether the row belongs to the filtered list
    private static boolean matchesCurrentVersion(
            WorldCatalogItem item,
            Optional<String> targetVersion) {
        @Nullable String worldVersion = Objects.requireNonNull(item, "item").gameVersion();
        return worldVersion == null
                || Objects.requireNonNull(targetVersion, "targetVersion")
                        .map(worldVersion::equals)
                        .orElse(false);
    }

    /// Creates the ready state corresponding to one exact shallow source.
    ///
    /// @param count non-negative exact direct-child directory count
    /// @param revision next source revision
    /// @param operationText optional terminal operation text
    /// @return ready immutable snapshot
    private WorldCatalogSnapshot readySnapshot(int count, long revision, String operationText) {
        if (count < 0) {
            throw new IllegalArgumentException("count must not be negative");
        }
        return new WorldCatalogSnapshot(
                OptionalInt.of(count),
                revision,
                WorldCatalogStatus.READY,
                strings.readyText(count),
                Objects.requireNonNull(operationText, "operationText"),
                count > 0,
                true,
                false);
    }

    /// Tests whether a refresh still owns publication rights.
    ///
    /// @param operation candidate refresh
    /// @return whether the operation is current and active
    private boolean ownsRefresh(RefreshOperation operation) {
        return !closed
                && generation == operation.generation()
                && activeRefreshCancellation == operation.cancellation()
                && !operation.cancellation().isCancelled();
    }

    /// Tests whether a mutation still owns publication rights.
    ///
    /// @param operation candidate mutation
    /// @return whether the operation is current and active
    private boolean ownsMutation(MutationOperation operation) {
        return !closed && generation == operation.generation() && activeMutation == operation;
    }

    /// Replaces paths and snapshot atomically, retaining the previous snapshot for publication.
    ///
    /// @param directories complete immutable shallow source
    /// @param replacement matching new snapshot
    /// @return transition ready for lock-free publishing
    private SnapshotTransition replaceStateLocked(
            @Unmodifiable List<Path> directories,
            WorldCatalogSnapshot replacement) {
        WorldCatalogSnapshot previous = state.snapshot();
        state = new ModelState(directories, replacement);
        return new SnapshotTransition(previous, replacement);
    }

    /// Publishes one state transition after the model lock is released.
    ///
    /// @param transition committed transition
    private void publish(SnapshotTransition transition) {
        changes.fireChange(transition.previous(), transition.replacement());
    }

    /// Creates the non-enumerating idle state used during page construction.
    ///
    /// @return initial empty model state
    private static ModelState initialState() {
        return new ModelState(List.of(), new WorldCatalogSnapshot(
                OptionalInt.empty(),
                0L,
                WorldCatalogStatus.IDLE,
                "",
                "",
                false,
                true,
                false));
    }

    /// Extracts concise failure detail from a checked or asynchronous exception.
    ///
    /// @param failure original failure
    /// @return non-blank exception message or type name
    private static String failureDetail(Throwable failure) {
        Throwable checkedFailure = Objects.requireNonNull(failure, "failure");
        @Nullable String message = checkedFailure.getMessage();
        return message == null || message.isBlank()
                ? checkedFailure.getClass().getSimpleName()
                : message;
    }

    /// Advances a revision while preserving an explicit overflow failure.
    ///
    /// @param current current non-negative revision
    /// @return next revision
    private static long nextRevision(long current) {
        return Math.incrementExact(current);
    }

    /// Rejects source work incorrectly scheduled on the Swing event dispatch thread.
    private static void requireBackgroundThread() {
        if (SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException("World catalog source work must not run on the EDT");
        }
    }

    /// Rejects commands or subscriptions after lifecycle closure.
    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("World catalog model is closed");
        }
    }

    /// Validates user-entered world target names before Core receives them.
    ///
    /// @param value candidate target name
    /// @param name parameter name for diagnostics
    /// @return validated name
    private static String requireNonBlank(String value, String name) {
        String checkedValue = Objects.requireNonNull(value, name).trim();
        if (checkedValue.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return checkedValue;
    }

    /// Immutable shallow path source paired with its exact visible state.
    ///
    /// @param directories ordered direct-child directories
    /// @param snapshot matching visible state
    @NotNullByDefault
    private record ModelState(
            @Unmodifiable List<Path> directories,
            WorldCatalogSnapshot snapshot) {
        /// Stores a defensive immutable directory list.
        private ModelState {
            directories = List.copyOf(directories);
            Objects.requireNonNull(snapshot, "snapshot");
            if (snapshot.itemCount().isPresent()
                    && snapshot.itemCount().getAsInt() != directories.size()) {
                throw new IllegalArgumentException("World snapshot count must match shallow directory index");
            }
        }
    }

    /// Immutable state replacement retained for synchronous listener publication.
    ///
    /// @param previous prior snapshot
    /// @param replacement new snapshot
    @NotNullByDefault
    private record SnapshotTransition(
            WorldCatalogSnapshot previous,
            WorldCatalogSnapshot replacement) {
    }

    /// Active shallow-index ownership metadata.
    ///
    /// @param generation operation generation
    /// @param cancellation cooperative cancellation signal
    @NotNullByDefault
    private record RefreshOperation(long generation, LoadCancellation cancellation) {
    }

    /// Active import/delete ownership metadata.
    ///
    /// @param generation operation generation
    /// @param cancellation cooperative cancellation signal
    /// @param result externally visible terminal completion
    /// @param action blocking Core operation
    @NotNullByDefault
    private record MutationOperation(
            long generation,
            LoadCancellation cancellation,
            CompletableFuture<WorldCatalogSnapshot> result,
            MutationAction action) {
    }

    /// Blocking operation performed before mandatory reindexing.
    @FunctionalInterface
    @NotNullByDefault
    private interface MutationAction {
        /// Applies a Core world mutation outside the EDT.
        ///
        /// @param access blocking Core source
        /// @param cancellation cooperative cancellation signal
        /// @throws IOException when Core or the filesystem rejects the mutation
        void run(WorldCatalogAccess access, LoadCancellation cancellation) throws IOException;
    }

    /// Captures one narrow viewport range and its source revision.
    ///
    /// @param contentRevision source revision captured when scheduled
    /// @param range effective exact range
    /// @param itemCount exact current source count
    /// @param directories captured paths for this request only
    /// @param cancellation viewport-owned cancellation signal
    /// @param result externally visible page result
    @NotNullByDefault
    private record RangeOperation(
            long contentRevision,
            IndexRange range,
            int itemCount,
            @Unmodifiable List<Path> directories,
            LoadCancellation cancellation,
            CompletableFuture<ChoicePage<WorldCatalogItem>> result) {
        /// Stores a defensive narrow directory slice.
        private RangeOperation {
            directories = List.copyOf(directories);
        }
    }

    /// Captures one incrementally resolved current-version viewport range.
    ///
    /// @param contentRevision source revision captured when scheduled
    /// @param range requested filtered range bounded by the shallow source count
    /// @param directories complete immutable shallow source used until enough matches are found
    /// @param targetVersion normalized current instance version, when known
    /// @param cancellation viewport-owned cancellation signal
    /// @param result externally visible filtered page result
    @NotNullByDefault
    private record FilteredRangeOperation(
            long contentRevision,
            IndexRange range,
            @Unmodifiable List<Path> directories,
            Optional<String> targetVersion,
            LoadCancellation cancellation,
            CompletableFuture<ChoicePage<WorldCatalogItem>> result) {
        /// Stores a defensive complete path snapshot and validates request values.
        private FilteredRangeOperation {
            directories = List.copyOf(directories);
            Objects.requireNonNull(targetVersion, "targetVersion");
        }
    }
}
