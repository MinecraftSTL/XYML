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
package space.minecraftstl.xyml.ui.swing.page.resourcepacks;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import space.minecraftstl.xyml.addon.LocalAddonFile;
import space.minecraftstl.xyml.addon.resourcepack.ResourcePackFile;
import space.minecraftstl.xyml.addon.resourcepack.ResourcePackManager;
import space.minecraftstl.xyml.game.GameRepository;
import space.minecraftstl.xyml.observable.Subscription;
import space.minecraftstl.xyml.observable.ValueChange;
import space.minecraftstl.xyml.observable.ValueChangeListener;
import space.minecraftstl.xyml.ui.swing.choice.ChoicePage;
import space.minecraftstl.xyml.ui.swing.choice.IndexRange;
import space.minecraftstl.xyml.ui.swing.choice.LoadCancellation;
import space.minecraftstl.xyml.util.Lang;
import space.minecraftstl.xyml.util.Pair;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;

/// Two-stage viewport-driven catalog of installed resource packs.
///
/// An index generation performs only a shallow candidate-path scan. Metadata, compatibility, and
/// enabled state are resolved later for the exact range requested by the measured viewport. Refresh
/// and closure cancel every still-active range from a superseded generation. A terminal outcome
/// decided immediately before invalidation is rejected by the viewport request coordinator through
/// [#sourceRevision()], while no model cache or arbitrary page size widens the requested range.
@NotNullByDefault
public final class DefaultResourcePackCatalogModel implements ResourcePackCatalogModel {
    /// Deterministic file-name order used by every completed candidate index.
    private static final Comparator<Path> PATH_ORDER = Comparator.comparing(
            DefaultResourcePackCatalogModel::fileName);

    /// Lock protecting index state, operation ownership, selection, sequence, and closure.
    private final Object stateLock = new Object();

    /// Lock preventing executor submission from crossing the close return boundary.
    private final Object sourceInvocationLock = new Object();

    /// Blocking two-stage access invoked only by [#backgroundExecutor].
    private final CatalogAccess catalogAccess;

    /// Caller-owned executor for shallow indexing and exact-range parsing.
    private final Executor backgroundExecutor;

    /// Localized lifecycle text.
    private final ResourcePackCatalogStatusStrings statusStrings;

    /// Test hook run after row resolution and before range terminal arbitration.
    private final Runnable beforeRangeTerminalCompletion;

    /// Test hook run after terminal decision and before lock-free Future publication.
    private final Runnable afterRangeTerminalDecision;

    /// Listener registrations in subscription order.
    private final CopyOnWriteArrayList<ListenerSlot> listeners = new CopyOnWriteArrayList<>();

    /// Lock protecting listener queue ownership without covering callback execution.
    private final Object listenerQueueLock = new Object();

    /// Listener slots awaiting lock-free callback delivery.
    private final ArrayDeque<ListenerSlot> listenerQueue = new ArrayDeque<>();

    /// Identity set preventing duplicate queue entries for one listener slot.
    private final Set<ListenerSlot> queuedListeners = new HashSet<>();

    /// Whether one thread currently owns listener queue draining.
    private boolean listenerQueueDraining;

    /// Current listener queue owner, or null while no drain is active.
    private @Nullable Thread listenerQueueThread;

    /// Active exact-range operations owned by the current index generation.
    private final Set<RangeOperation> activeRanges = new HashSet<>();

    /// Decided range outcomes whose externally visible futures may still need publication.
    private final Map<RangeOperation, RangeTerminalOutcome> pendingTerminalPublications =
            new IdentityHashMap<>();

    /// Atomically published path index and exactly matching snapshot.
    private volatile ModelState state;

    /// Stable selected path retained while a refreshed index is unknown.
    private @Nullable Path selectedPath;

    /// Monotonic ownership generation shared by index and range work.
    private long generation;

    /// Current shallow index operation, or null outside active indexing.
    private @Nullable IndexOperation activeIndex;

    /// Monotonic snapshot commit sequence used for publication coalescing.
    private long snapshotSequence;

    /// Whether future commands, loads, and subscriptions are rejected.
    private volatile boolean closed;

    /// Creates an idle production catalog for one repository instance.
    ///
    /// Construction performs no repository or file-system work.
    ///
    /// @param repository repository containing the managed instance
    /// @param instanceId stable non-blank repository instance identifier
    /// @param backgroundExecutor caller-owned executor that must dispatch asynchronously and must
    ///                           never run submitted blocking work inline on the caller or Swing EDT
    /// @param statusStrings localized lifecycle text
    public DefaultResourcePackCatalogModel(
            GameRepository repository,
            String instanceId,
            Executor backgroundExecutor,
            ResourcePackCatalogStatusStrings statusStrings) {
        this(
                new ManagerCatalogAccess(repository, instanceId),
                backgroundExecutor,
                statusStrings,
                () -> { },
                () -> { });
    }

    /// Creates an idle catalog around an injected blocking access boundary.
    ///
    /// @param catalogAccess blocking two-stage source
    /// @param backgroundExecutor caller-owned executor that dispatches every source invocation
    ///                           asynchronously rather than inline on the calling thread
    /// @param statusStrings localized lifecycle text
    DefaultResourcePackCatalogModel(
            CatalogAccess catalogAccess,
            Executor backgroundExecutor,
            ResourcePackCatalogStatusStrings statusStrings) {
        this(catalogAccess, backgroundExecutor, statusStrings, () -> { }, () -> { });
    }

    /// Creates an idle catalog with a deterministic range-terminal test hook.
    ///
    /// @param catalogAccess blocking two-stage source
    /// @param backgroundExecutor caller-owned executor for source invocation
    /// @param statusStrings localized lifecycle text
    /// @param beforeRangeTerminalCompletion test hook before terminal arbitration
    DefaultResourcePackCatalogModel(
            CatalogAccess catalogAccess,
            Executor backgroundExecutor,
            ResourcePackCatalogStatusStrings statusStrings,
            Runnable beforeRangeTerminalCompletion) {
        this(
                catalogAccess,
                backgroundExecutor,
                statusStrings,
                beforeRangeTerminalCompletion,
                () -> { });
    }

    /// Creates an idle catalog with deterministic hooks around range-terminal decision.
    ///
    /// @param catalogAccess blocking two-stage source
    /// @param backgroundExecutor caller-owned executor for source invocation
    /// @param statusStrings localized lifecycle text
    /// @param beforeRangeTerminalCompletion test hook before terminal arbitration
    /// @param afterRangeTerminalDecision test hook before lock-free Future publication
    DefaultResourcePackCatalogModel(
            CatalogAccess catalogAccess,
            Executor backgroundExecutor,
            ResourcePackCatalogStatusStrings statusStrings,
            Runnable beforeRangeTerminalCompletion,
            Runnable afterRangeTerminalDecision) {
        this.catalogAccess = Objects.requireNonNull(catalogAccess, "catalogAccess");
        this.backgroundExecutor = Objects.requireNonNull(backgroundExecutor, "backgroundExecutor");
        this.statusStrings = Objects.requireNonNull(statusStrings, "statusStrings");
        this.beforeRangeTerminalCompletion = Objects.requireNonNull(
                beforeRangeTerminalCompletion,
                "beforeRangeTerminalCompletion");
        this.afterRangeTerminalDecision = Objects.requireNonNull(
                afterRangeTerminalDecision,
                "afterRangeTerminalDecision");
        CatalogContent initialContent = new CatalogContent(0L, false, List.of());
        state = new ModelState(initialContent, new ResourcePackCatalogSnapshot(
                OptionalInt.empty(),
                OptionalInt.empty(),
                0L,
                ResourcePackCatalogStatus.IDLE,
                statusStrings.idleStatus(),
                false,
                true));
    }

    /// Returns the latest immutable catalog state.
    ///
    /// @return current catalog snapshot
    @Override
    public ResourcePackCatalogSnapshot snapshot() {
        return state.snapshot();
    }

    /// Registers a listener whose first eligible transition is committed after this call.
    ///
    /// Unsubscription synchronously crosses the slot's delivery gate, so no callback can begin or
    /// remain active after `unsubscribe()` returns.
    ///
    /// @param listener snapshot transition listener
    /// @return independently cancellable registration
    @Override
    public Subscription subscribe(ValueChangeListener<ResourcePackCatalogSnapshot> listener) {
        Objects.requireNonNull(listener, "listener");
        ListenerSlot slot;
        synchronized (stateLock) {
            requireOpen();
            slot = new ListenerSlot(listener, snapshotSequence + 1L);
            listeners.add(slot);
        }
        return Subscription.create(() -> terminateListener(slot));
    }

    /// Returns the exact indexed count only after a supported or unsupported index succeeds.
    ///
    /// @return exact count, or empty while the index is idle, loading, or failed
    @Override
    public OptionalInt exactItemCount() {
        return state.snapshot().itemCount();
    }

    /// Returns the revision that invalidates delayed viewport successes and failures.
    ///
    /// @return current resource-pack index revision
    @Override
    public OptionalLong sourceRevision() {
        return OptionalLong.of(state.snapshot().contentRevision());
    }

    /// Resolves only the actual requested paths from the current exact index on the background executor.
    ///
    /// Consumers must discard a page when the catalog's `contentRevision` changes before that page
    /// is applied. Terminal outcomes are decided atomically with generation state, while Future
    /// publication deliberately occurs outside model locks so synchronous dependents cannot deadlock
    /// refresh or close.
    ///
    /// @param desiredRange viewport-derived desired range
    /// @param cancellation caller-owned cancellation signal
    /// @return exact asynchronous range result
    @Override
    public CompletionStage<ChoicePage<ResourcePackCatalogItem>> load(
            IndexRange desiredRange,
            LoadCancellation cancellation) {
        Objects.requireNonNull(desiredRange, "desiredRange");
        Objects.requireNonNull(cancellation, "cancellation");
        RangeOperation operation;
        synchronized (stateLock) {
            requireOpen();
            ModelState current = state;
            if (!current.content().indexReady()
                    || current.snapshot().status() != ResourcePackCatalogStatus.READY) {
                return CompletableFuture.failedFuture(
                        new IllegalStateException("A ready resource-pack index is required before range loading"));
            }
            try {
                cancellation.throwIfCancelled();
            } catch (CancellationException failure) {
                return CompletableFuture.failedFuture(failure);
            }
            int itemCount = current.content().paths().size();
            IndexRange actualRange = desiredRange.clampToItemCount(itemCount);
            if (actualRange.isEmpty()) {
                return CompletableFuture.completedFuture(new ChoicePage<>(
                        actualRange,
                        List.of(),
                        OptionalInt.of(itemCount),
                        actualRange.endExclusive() == itemCount));
            }
            @Unmodifiable List<Path> paths = List.copyOf(current.content().paths().subList(
                    actualRange.startInclusive(),
                    actualRange.endExclusive()));
            LoadCancellation lifecycleCancellation = new LoadCancellation();
            operation = new RangeOperation(
                    current.content().generation(),
                    actualRange,
                    paths,
                    itemCount,
                    cancellation,
                    lifecycleCancellation,
                    LoadCancellation.linkedTo(cancellation, lifecycleCancellation),
                    new CompletableFuture<>());
            activeRanges.add(operation);
        }
        submitRange(operation);
        return operation.result();
    }

    /// Starts the first shallow path index only while no attempt has begun.
    @Override
    public void loadIfNeeded() {
        startIndex(true);
    }

    /// Invalidates indexed content and starts one fresh shallow path generation.
    @Override
    public void refresh() {
        startIndex(false);
    }

    /// Selects one normalized path from the current exact index.
    ///
    /// @param path path belonging to the current index
    @Override
    public void selectResourcePack(Path path) {
        Path normalizedPath = Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
        @Nullable SnapshotTransition transition;
        synchronized (stateLock) {
            requireOpen();
            ModelState current = state;
            if (!current.content().indexReady()
                    || indexOf(current.content().paths(), normalizedPath) < 0) {
                throw new IllegalArgumentException("Unknown resource pack: " + normalizedPath);
            }
            if (normalizedPath.equals(selectedPath)) {
                return;
            }
            selectedPath = normalizedPath;
            ResourcePackCatalogSnapshot previous = current.snapshot();
            ResourcePackCatalogSnapshot replacement = copySnapshot(
                    previous,
                    selectedIndex(current.content().paths()),
                    previous.itemCount(),
                    previous.contentRevision(),
                    previous.status(),
                    previous.statusText(),
                    previous.listEnabled(),
                    previous.refreshEnabled());
            transition = replaceStateLocked(current.content(), replacement);
        }
        publish(transition);
    }

    /// Clears the current stable selection without replacing indexed content.
    @Override
    public void clearSelection() {
        @Nullable SnapshotTransition transition;
        synchronized (stateLock) {
            requireOpen();
            if (selectedPath == null) {
                return;
            }
            selectedPath = null;
            ModelState current = state;
            ResourcePackCatalogSnapshot previous = current.snapshot();
            ResourcePackCatalogSnapshot replacement = copySnapshot(
                    previous,
                    OptionalInt.empty(),
                    previous.itemCount(),
                    previous.contentRevision(),
                    previous.status(),
                    previous.statusText(),
                    previous.listEnabled(),
                    previous.refreshEnabled());
            transition = replaceStateLocked(current.content(), replacement);
        }
        publish(transition);
    }

    /// Cancels every active operation, terminates subscriptions, and disables control flags.
    @Override
    public void close() {
        @Unmodifiable List<RangeOperation> terminalPublications;
        synchronized (stateLock) {
            if (!closed) {
                closed = true;
                generation++;
                if (activeIndex != null) {
                    activeIndex.cancellation().cancel();
                    activeIndex = null;
                }
                @Unmodifiable List<RangeOperation> rangesToCancel = List.copyOf(activeRanges);
                rangesToCancel.forEach(operation -> decideRangeCancellationLocked(
                        operation,
                        "Resource-pack catalog was closed"));
                selectedPath = null;
                ModelState current = state;
                ResourcePackCatalogSnapshot previous = current.snapshot();
                ResourcePackCatalogSnapshot terminal = copySnapshot(
                        previous,
                        OptionalInt.empty(),
                        previous.itemCount(),
                        Math.addExact(previous.contentRevision(), 1L),
                        previous.status(),
                        previous.statusText(),
                        false,
                        false);
                state = new ModelState(current.content(), terminal);
                snapshotSequence++;
            }
            terminalPublications = List.copyOf(pendingTerminalPublications.keySet());
        }
        terminalPublications.forEach(this::publishRangeTerminal);

        // Repeated close callers independently cross both idempotent barriers.
        synchronized (sourceInvocationLock) {
            // Acquiring this monitor waits for submissions that began before close.
        }
        listeners.forEach(ListenerSlot::terminate);
        listeners.clear();
    }

    /// Commits one loading generation and captures operations it supersedes.
    ///
    /// @param onlyIfIdle whether a prior attempt makes this request a no-op
    private void startIndex(boolean onlyIfIdle) {
        IndexPreparation preparation;
        synchronized (stateLock) {
            requireOpen();
            ModelState current = state;
            if (onlyIfIdle && current.snapshot().status() != ResourcePackCatalogStatus.IDLE) {
                return;
            }
            @Nullable IndexOperation previousIndex = activeIndex;
            if (previousIndex != null) {
                previousIndex.cancellation().cancel();
            }
            @Unmodifiable List<RangeOperation> previousRanges = List.copyOf(activeRanges);
            previousRanges.forEach(operation -> decideRangeCancellationLocked(
                    operation,
                    "Resource-pack index was superseded"));
            long nextGeneration = ++generation;
            IndexOperation operation = new IndexOperation(
                    nextGeneration,
                    new LoadCancellation());
            activeIndex = operation;
            long contentRevision = onlyIfIdle
                    ? current.snapshot().contentRevision()
                    : Math.addExact(current.snapshot().contentRevision(), 1L);
            CatalogContent loadingContent = new CatalogContent(nextGeneration, false, List.of());
            ResourcePackCatalogSnapshot loading = copySnapshot(
                    current.snapshot(),
                    OptionalInt.empty(),
                    OptionalInt.empty(),
                    contentRevision,
                    ResourcePackCatalogStatus.LOADING,
                    statusStrings.loadingStatus(),
                    false,
                    false);
            @Nullable SnapshotTransition transition = replaceStateLocked(loadingContent, loading);
            preparation = new IndexPreparation(
                    operation,
                    List.copyOf(pendingTerminalPublications.keySet()),
                    Objects.requireNonNull(transition, "Loading transition must change snapshot"));
        }
        publish(preparation.transition());
        preparation.terminalPublications().forEach(this::publishRangeTerminal);
        submitIndex(preparation.operation());
    }

    /// Submits one shallow index operation to the caller-owned executor.
    ///
    /// @param operation current index operation
    private void submitIndex(IndexOperation operation) {
        @Nullable Throwable schedulingFailure = null;
        synchronized (sourceInvocationLock) {
            synchronized (stateLock) {
                if (!isIndexCurrentLocked(operation)) {
                    return;
                }
            }
            try {
                backgroundExecutor.execute(() -> runIndex(operation));
            } catch (RuntimeException | Error failure) {
                schedulingFailure = failure;
            }
        }
        if (schedulingFailure != null) {
            completeIndexFailure(operation, schedulingFailure);
            if (schedulingFailure instanceof Error error) {
                throw error;
            }
        }
    }

    /// Performs one lightweight candidate-path scan.
    ///
    /// @param operation current index operation
    private void runIndex(IndexOperation operation) {
        try {
            ensureIndexCurrent(operation);
            CatalogIndex sourceIndex = Objects.requireNonNull(
                    catalogAccess.loadIndex(operation.cancellation()),
                    "resource-pack access returned null index");
            ensureIndexCurrent(operation);
            CatalogIndex immutableIndex = new CatalogIndex(
                    sourceIndex.supported(),
                    immutableIndexPaths(sourceIndex.paths()));
            ensureIndexCurrent(operation);
            completeIndexSuccess(operation, immutableIndex);
        } catch (CancellationException failure) {
            operation.cancellation().cancel();
        } catch (IOException | RuntimeException failure) {
            completeIndexFailure(operation, failure);
        } catch (Error failure) {
            completeIndexFailure(operation, failure);
            throw failure;
        }
    }

    /// Commits one current supported or unsupported exact path index.
    ///
    /// @param operation owning index operation
    /// @param loadedIndex immutable source index
    private void completeIndexSuccess(IndexOperation operation, CatalogIndex loadedIndex) {
        @Nullable SnapshotTransition transition = null;
        synchronized (stateLock) {
            if (isIndexCurrentLocked(operation)) {
                activeIndex = null;
                ModelState current = state;
                @Unmodifiable List<Path> paths = loadedIndex.supported()
                        ? loadedIndex.paths()
                        : List.of();
                if (selectedPath != null && indexOf(paths, selectedPath) < 0) {
                    selectedPath = null;
                }
                CatalogContent readyContent = new CatalogContent(
                        operation.generation(),
                        true,
                        paths);
                ResourcePackCatalogStatus status = loadedIndex.supported()
                        ? ResourcePackCatalogStatus.READY
                        : ResourcePackCatalogStatus.UNSUPPORTED;
                String statusText = loadedIndex.supported()
                        ? readyStatus(paths.size())
                        : statusStrings.unsupportedStatus();
                ResourcePackCatalogSnapshot ready = copySnapshot(
                        current.snapshot(),
                        selectedIndex(paths),
                        OptionalInt.of(paths.size()),
                        Math.addExact(current.snapshot().contentRevision(), 1L),
                        status,
                        statusText,
                        loadedIndex.supported() && !paths.isEmpty(),
                        true);
                transition = replaceStateLocked(readyContent, ready);
            }
        }
        publish(transition);
    }

    /// Commits one current retryable index failure without inventing an exact count.
    ///
    /// @param operation failing index operation
    /// @param failure scheduling, I/O, or validation failure
    private void completeIndexFailure(IndexOperation operation, Throwable failure) {
        @Nullable SnapshotTransition transition = null;
        synchronized (stateLock) {
            if (isIndexCurrentLocked(operation)) {
                activeIndex = null;
                ModelState current = state;
                ResourcePackCatalogSnapshot failed = copySnapshot(
                        current.snapshot(),
                        OptionalInt.empty(),
                        OptionalInt.empty(),
                        current.snapshot().contentRevision(),
                        ResourcePackCatalogStatus.FAILED,
                        failedStatus(failure),
                        false,
                        true);
                transition = replaceStateLocked(current.content(), failed);
            }
        }
        publish(transition);
    }

    /// Throws when closure or another generation invalidates an index operation.
    ///
    /// @param operation index operation to validate
    private void ensureIndexCurrent(IndexOperation operation) {
        operation.cancellation().throwIfCancelled();
        synchronized (stateLock) {
            if (!isIndexCurrentLocked(operation)) {
                throw new CancellationException("Resource-pack index was superseded");
            }
        }
    }

    /// Returns whether one index operation still owns the current generation.
    ///
    /// @param operation index operation to inspect
    /// @return whether the operation may continue or commit
    private boolean isIndexCurrentLocked(IndexOperation operation) {
        return !closed
                && activeIndex == operation
                && generation == operation.generation()
                && !operation.cancellation().isCancelled();
    }

    /// Submits one exact-range parse operation to the caller-owned executor.
    ///
    /// @param operation current range operation
    private void submitRange(RangeOperation operation) {
        @Nullable Throwable schedulingFailure = null;
        boolean current;
        synchronized (sourceInvocationLock) {
            synchronized (stateLock) {
                current = isRangeCurrentLocked(operation);
            }
            if (current) {
                try {
                    backgroundExecutor.execute(() -> runRange(operation));
                } catch (RuntimeException | Error failure) {
                    schedulingFailure = failure;
                }
            }
        }
        if (!current) {
            cancelRange(operation, "Resource-pack viewport load was cancelled before submission");
            return;
        }
        if (schedulingFailure != null) {
            completeRangeFailure(operation, schedulingFailure);
            if (schedulingFailure instanceof Error error) {
                throw error;
            }
        }
    }

    /// Resolves only the exact captured paths for one viewport operation.
    ///
    /// @param operation current range operation
    private void runRange(RangeOperation operation) {
        try {
            ensureRangeCurrent(operation);
            @Unmodifiable List<ResourcePackCatalogItem> items = List.copyOf(
                    Objects.requireNonNull(
                            catalogAccess.loadItems(
                                    operation.paths(),
                                    operation.sourceCancellation()),
                            "resource-pack access returned null items"));
            ensureRangeCurrent(operation);
            validateRangeItems(operation.paths(), items);
            ChoicePage<ResourcePackCatalogItem> page = new ChoicePage<>(
                    operation.actualRange(),
                    items,
                    OptionalInt.of(operation.itemCount()),
                    operation.actualRange().endExclusive() == operation.itemCount());
            completeRangeSuccess(operation, page);
        } catch (CancellationException failure) {
            cancelRange(operation, failure.getMessage() == null
                    ? "Resource-pack viewport load was cancelled"
                    : failure.getMessage());
        } catch (IOException | RuntimeException failure) {
            completeRangeFailure(operation, failure);
        } catch (Error failure) {
            completeRangeFailure(operation, failure);
            throw failure;
        }
    }

    /// Completes one current exact range after removing its active slot.
    ///
    /// @param operation range operation
    /// @param page exact immutable result
    private void completeRangeSuccess(
            RangeOperation operation,
            ChoicePage<ResourcePackCatalogItem> page) {
        beforeRangeTerminalCompletion.run();
        synchronized (stateLock) {
            if (isRangeCurrentLocked(operation)) {
                decideRangeTerminalLocked(operation, RangeTerminalOutcome.success(page));
            } else {
                decideRangeCancellationLocked(
                        operation,
                        "Resource-pack viewport result was superseded");
            }
        }
        afterRangeTerminalDecision.run();
        publishRangeTerminal(operation);
    }

    /// Completes one current range exceptionally and releases its slot.
    ///
    /// @param operation range operation
    /// @param failure range or executor failure
    private void completeRangeFailure(RangeOperation operation, Throwable failure) {
        beforeRangeTerminalCompletion.run();
        synchronized (stateLock) {
            if (isRangeCurrentLocked(operation)) {
                decideRangeTerminalLocked(operation, RangeTerminalOutcome.failure(failure));
            } else {
                decideRangeCancellationLocked(
                        operation,
                        "Resource-pack viewport failure was superseded");
            }
        }
        afterRangeTerminalDecision.run();
        publishRangeTerminal(operation);
    }

    /// Throws when caller cancellation, lifecycle cancellation, or generation replacement wins.
    ///
    /// @param operation range operation to validate
    private void ensureRangeCurrent(RangeOperation operation) {
        operation.callerCancellation().throwIfCancelled();
        operation.lifecycleCancellation().throwIfCancelled();
        synchronized (stateLock) {
            if (!isRangeCurrentLocked(operation)) {
                throw new CancellationException("Resource-pack viewport load was superseded");
            }
        }
    }

    /// Returns whether one range still belongs to the current ready index.
    ///
    /// @param operation range operation to inspect
    /// @return whether the operation may continue or commit
    private boolean isRangeCurrentLocked(RangeOperation operation) {
        return !closed
                && generation == operation.generation()
                && state.content().indexReady()
                && state.content().generation() == operation.generation()
                && activeRanges.contains(operation)
                && !operation.callerCancellation().isCancelled()
                && !operation.lifecycleCancellation().isCancelled();
    }

    /// Requests lifecycle cancellation and completes one range promptly.
    ///
    /// The linked source signal observes this lifecycle cancellation without mutating the
    /// caller-owned signal.
    ///
    /// @param operation range operation to cancel
    /// @param message cancellation explanation
    private void cancelRange(RangeOperation operation, String message) {
        synchronized (stateLock) {
            decideRangeCancellationLocked(operation, message);
        }
        publishRangeTerminal(operation);
    }

    /// Decides cancellation for one range without publishing its Future under the model lock.
    ///
    /// @param operation range operation to cancel
    /// @param message cancellation explanation
    private void decideRangeCancellationLocked(RangeOperation operation, String message) {
        operation.lifecycleCancellation().cancel();
        decideRangeTerminalLocked(
                operation,
                RangeTerminalOutcome.failure(new CancellationException(message)));
    }

    /// Records one immutable range outcome exactly once.
    ///
    /// @param operation range operation receiving the outcome
    /// @param outcome terminal success, failure, or cancellation
    private void decideRangeTerminalLocked(
            RangeOperation operation,
            RangeTerminalOutcome outcome) {
        activeRanges.remove(operation);
        if (!operation.result().isDone()) {
            pendingTerminalPublications.putIfAbsent(operation, outcome);
        }
    }

    /// Publishes one decided outcome without holding any model lock or invoking callbacks under it.
    ///
    /// @param operation operation whose outcome may need publication
    private void publishRangeTerminal(RangeOperation operation) {
        @Nullable RangeTerminalOutcome outcome;
        synchronized (stateLock) {
            outcome = pendingTerminalPublications.get(operation);
        }
        if (outcome == null) {
            return;
        }
        outcome.publish(operation.result());
        synchronized (stateLock) {
            if (operation.result().isDone()
                    && pendingTerminalPublications.get(operation) == outcome) {
                pendingTerminalPublications.remove(operation);
            }
        }
    }

    /// Validates exact result length, path identity, and source order.
    ///
    /// @param paths requested immutable paths
    /// @param items returned immutable rows
    private static void validateRangeItems(
            @Unmodifiable List<Path> paths,
            @Unmodifiable List<ResourcePackCatalogItem> items) {
        if (paths.size() != items.size()) {
            throw new IllegalArgumentException("Range items must exactly match requested paths");
        }
        for (int index = 0; index < paths.size(); index++) {
            if (!paths.get(index).equals(items.get(index).path())) {
                throw new IllegalArgumentException(
                        "Range item path or order did not match requested paths at index " + index);
            }
        }
    }

    /// Normalizes, sorts, freezes, and rejects duplicate index paths.
    ///
    /// @param sourcePaths source-returned candidate paths
    /// @return immutable deterministic path index
    private static @Unmodifiable List<Path> immutableIndexPaths(
            @Unmodifiable List<Path> sourcePaths) {
        List<Path> normalized = sourcePaths.stream()
                .map(path -> Objects.requireNonNull(path, "index contains null path"))
                .map(path -> path.toAbsolutePath().normalize())
                .sorted(PATH_ORDER)
                .toList();
        Set<Path> unique = new HashSet<>();
        for (Path path : normalized) {
            if (!unique.add(path)) {
                throw new IllegalArgumentException("Duplicate resource-pack path: " + path);
            }
        }
        return normalized;
    }

    /// Returns the selected path's index in a current immutable path index.
    ///
    /// @param paths immutable path index
    /// @return selected index, or empty while absent
    private OptionalInt selectedIndex(@Unmodifiable List<Path> paths) {
        if (selectedPath == null) {
            return OptionalInt.empty();
        }
        int index = indexOf(paths, selectedPath);
        return index < 0 ? OptionalInt.empty() : OptionalInt.of(index);
    }

    /// Finds one normalized path in an immutable index.
    ///
    /// @param paths immutable path index
    /// @param path normalized candidate path
    /// @return matching index, or -1 when absent
    private static int indexOf(@Unmodifiable List<Path> paths, Path path) {
        for (int index = 0; index < paths.size(); index++) {
            if (paths.get(index).equals(path)) {
                return index;
            }
        }
        return -1;
    }

    /// Commits immutable content and a matching snapshot under [#stateLock].
    ///
    /// @param content replacement path-index content
    /// @param snapshot replacement matching snapshot
    /// @return sequenced transition, or null when the snapshot is unchanged
    private @Nullable SnapshotTransition replaceStateLocked(
            CatalogContent content,
            ResourcePackCatalogSnapshot snapshot) {
        ResourcePackCatalogSnapshot previous = state.snapshot();
        state = new ModelState(content, snapshot);
        if (previous.equals(snapshot)) {
            return null;
        }
        return new SnapshotTransition(previous, snapshot, ++snapshotSequence);
    }

    /// Publishes one current transition with sequence-lower-bound listener filtering.
    ///
    /// @param transition committed transition, or null when no snapshot changed
    private void publish(@Nullable SnapshotTransition transition) {
        if (transition == null) {
            return;
        }
        synchronized (stateLock) {
            if (closed || transition.sequence() != snapshotSequence) {
                return;
            }
        }
        ValueChange<ResourcePackCatalogSnapshot> change = new ValueChange<>(
                this,
                transition.previous(),
                transition.current());
        for (ListenerSlot slot : listeners) {
            synchronized (stateLock) {
                if (closed || transition.sequence() != snapshotSequence) {
                    break;
                }
            }
            if (slot.enqueueIfEligible(change, transition.sequence())) {
                enqueueListener(slot);
            }
        }
    }

    /// Adds one listener slot to the global single-owner callback queue.
    ///
    /// @param slot slot with an accepted pending transition
    private void enqueueListener(ListenerSlot slot) {
        boolean ownsDrain;
        synchronized (listenerQueueLock) {
            if (queuedListeners.add(slot)) {
                listenerQueue.addLast(slot);
            }
            ownsDrain = !listenerQueueDraining;
            if (ownsDrain) {
                listenerQueueDraining = true;
                listenerQueueThread = Thread.currentThread();
            }
        }
        if (ownsDrain) {
            drainListenerQueue();
        }
    }

    /// Delivers queued listener transitions serially without holding any model lock.
    private void drainListenerQueue() {
        try {
            while (true) {
                @Nullable ListenerSlot slot;
                synchronized (listenerQueueLock) {
                    slot = listenerQueue.pollFirst();
                    if (slot == null) {
                        finishListenerDrainLocked();
                        return;
                    }
                    queuedListeners.remove(slot);
                }
                slot.deliverPending();
            }
        } finally {
            synchronized (listenerQueueLock) {
                if (listenerQueueDraining && Thread.currentThread() == listenerQueueThread) {
                    finishListenerDrainLocked();
                }
            }
        }
    }

    /// Releases global callback ownership and wakes diagnostic waiters.
    private void finishListenerDrainLocked() {
        listenerQueueDraining = false;
        listenerQueueThread = null;
        listenerQueueLock.notifyAll();
    }

    /// Synchronously terminates and removes one exact listener registration.
    ///
    /// @param slot listener registration to terminate
    private void terminateListener(ListenerSlot slot) {
        slot.terminate();
        listeners.remove(slot);
    }

    /// Builds one immutable snapshot while keeping transition calls explicit.
    ///
    /// @param ignoredPrevious previous snapshot documenting this copy
    /// @param selectedIndex replacement selection
    /// @param itemCount replacement exact count or unknown
    /// @param contentRevision replacement content revision
    /// @param status replacement lifecycle
    /// @param statusText replacement localized status
    /// @param listEnabled replacement list enabled flag
    /// @param refreshEnabled replacement refresh enabled flag
    /// @return replacement snapshot
    private static ResourcePackCatalogSnapshot copySnapshot(
            ResourcePackCatalogSnapshot ignoredPrevious,
            OptionalInt selectedIndex,
            OptionalInt itemCount,
            long contentRevision,
            ResourcePackCatalogStatus status,
            String statusText,
            boolean listEnabled,
            boolean refreshEnabled) {
        Objects.requireNonNull(ignoredPrevious, "ignoredPrevious");
        return new ResourcePackCatalogSnapshot(
                selectedIndex,
                itemCount,
                contentRevision,
                status,
                statusText,
                listEnabled,
                refreshEnabled);
    }

    /// Returns localized successful text for one exact count.
    ///
    /// @param itemCount exact indexed path count
    /// @return ready or empty localized text
    private String readyStatus(int itemCount) {
        return itemCount == 0 ? statusStrings.emptyStatus() : statusStrings.readyStatus();
    }

    /// Builds localized failure text with a stable unknown-detail fallback.
    ///
    /// @param failure source or executor failure
    /// @return localized status and detail
    private String failedStatus(Throwable failure) {
        @Nullable Throwable resolved = unwrapFailure(failure);
        @Nullable String message = resolved == null ? null : resolved.getMessage();
        String detail = message == null || message.isBlank()
                ? statusStrings.unknownFailure()
                : message;
        return statusStrings.failedStatus() + ": " + detail;
    }

    /// Removes asynchronous wrapper failures.
    ///
    /// @param failure source failure, possibly wrapped
    /// @return underlying failure, or null
    private static @Nullable Throwable unwrapFailure(@Nullable Throwable failure) {
        @Nullable Throwable current = failure;
        while (current instanceof CompletionException && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    /// Returns one path's exact file name.
    ///
    /// @param path candidate path
    /// @return final path component
    private static String fileName(Path path) {
        return Objects.requireNonNull(path.getFileName(), "resource-pack path has no file name")
                .toString();
    }

    /// Maps the legacy compatibility enum into the public toolkit-neutral enum.
    ///
    /// @param compatibility legacy manager compatibility
    /// @return toolkit-neutral compatibility
    private static ResourcePackCompatibility mapCompatibility(
            ResourcePackFile.Compatibility compatibility) {
        return switch (compatibility) {
            case COMPATIBLE -> ResourcePackCompatibility.COMPATIBLE;
            case TOO_NEW -> ResourcePackCompatibility.TOO_NEW;
            case TOO_OLD -> ResourcePackCompatibility.TOO_OLD;
            case INVALID -> ResourcePackCompatibility.INVALID;
            case MISSING_PACK_META -> ResourcePackCompatibility.MISSING_PACK_META;
            case MISSING_GAME_META -> ResourcePackCompatibility.MISSING_GAME_META;
        };
    }

    /// Creates a stable invalid row when one indexed candidate disappears or cannot be parsed.
    ///
    /// @param path indexed candidate path
    /// @return geometry-preserving invalid row
    private static ResourcePackCatalogItem invalidItem(Path path) {
        String exactFileName = fileName(path);
        String displayName = exactFileName.toLowerCase(Locale.ROOT).endsWith(".zip")
                ? exactFileName.substring(0, exactFileName.length() - 4)
                : exactFileName;
        return new ResourcePackCatalogItem(
                path,
                displayName,
                exactFileName,
                "",
                ResourcePackCompatibility.INVALID,
                false);
    }

    /// Reports an isolated listener failure without stopping later listeners.
    ///
    /// @param listenerFailure listener failure to report
    private static void reportListenerFailure(RuntimeException listenerFailure) {
        try {
            Lang.handleUncaughtException(listenerFailure);
        } catch (RuntimeException ignored) {
            // Listener diagnostics cannot corrupt already committed catalog state.
        }
    }

    /// Rejects operations requiring an open model.
    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("Resource-pack catalog model is closed");
        }
    }

    /// Blocking two-stage source required by the asynchronous model.
    @NotNullByDefault
    interface CatalogAccess {
        /// Loads only supported state and sorted candidate paths.
        ///
        /// @param cancellation cooperative index cancellation
        /// @return shallow supported or unsupported index
        /// @throws IOException when local index access fails
        CatalogIndex loadIndex(LoadCancellation cancellation) throws IOException;

        /// Resolves exactly the supplied paths in the supplied order.
        ///
        /// @param paths exact normalized viewport paths
        /// @param cancellation cooperative viewport cancellation
        /// @return one row per path in identical order
        /// @throws IOException when shared local state cannot be read
        @Unmodifiable List<ResourcePackCatalogItem> loadItems(
                @Unmodifiable List<Path> paths,
                LoadCancellation cancellation) throws IOException;
    }

    /// Shallow source result containing no parsed pack metadata.
    ///
    /// @param supported whether the Minecraft instance supports resource packs
    /// @param paths candidate direct children, empty when unsupported
    @NotNullByDefault
    record CatalogIndex(
            boolean supported,
            @Unmodifiable List<Path> paths) {
        /// Stores a defensive path-list copy and validates unsupported results.
        CatalogIndex {
            paths = List.copyOf(paths);
            if (!supported && !paths.isEmpty()) {
                throw new IllegalArgumentException("Unsupported index must not contain paths");
            }
        }
    }

    /// Atomically published path index and ownership generation.
    ///
    /// @param generation generation owning the content
    /// @param indexReady whether paths represent a completed exact index
    /// @param paths normalized sorted exact candidate paths
    @NotNullByDefault
    private record CatalogContent(
            long generation,
            boolean indexReady,
            @Unmodifiable List<Path> paths) {
        /// Freezes paths and validates generation state.
        private CatalogContent {
            if (generation < 0L) {
                throw new IllegalArgumentException("generation must not be negative");
            }
            paths = List.copyOf(paths);
            if (!indexReady && !paths.isEmpty()) {
                throw new IllegalArgumentException("Unknown index cannot contain paths");
            }
        }
    }

    /// Atomically published index content and matching snapshot.
    ///
    /// @param content path-index content
    /// @param snapshot exactly matching presentation state
    @NotNullByDefault
    private record ModelState(
            CatalogContent content,
            ResourcePackCatalogSnapshot snapshot) {
        /// Validates exact-count agreement.
        private ModelState {
            OptionalInt expected = content.indexReady()
                    ? OptionalInt.of(content.paths().size())
                    : OptionalInt.empty();
            if (!expected.equals(snapshot.itemCount())) {
                throw new IllegalArgumentException("Snapshot item count must match index readiness");
            }
        }
    }

    /// One cancellable shallow index generation.
    ///
    /// @param generation ownership generation
    /// @param cancellation model-owned cancellation signal
    @NotNullByDefault
    private record IndexOperation(
            long generation,
            LoadCancellation cancellation) {
    }

    /// One exact-range parse operation over a captured immutable path sublist.
    ///
    /// @param generation owning index generation
    /// @param actualRange clamped exact range
    /// @param paths exact captured paths
    /// @param itemCount exact complete index count
    /// @param callerCancellation caller-owned signal
    /// @param lifecycleCancellation model-owned refresh and close signal
    /// @param sourceCancellation linked signal observed by blocking source work
    /// @param result externally observed result
    @NotNullByDefault
    private record RangeOperation(
            long generation,
            IndexRange actualRange,
            @Unmodifiable List<Path> paths,
            int itemCount,
            LoadCancellation callerCancellation,
            LoadCancellation lifecycleCancellation,
            LoadCancellation sourceCancellation,
            CompletableFuture<ChoicePage<ResourcePackCatalogItem>> result) {
        /// Freezes captured paths and validates their exact geometry.
        private RangeOperation {
            paths = List.copyOf(paths);
            if (itemCount < 0 || paths.size() != actualRange.length()) {
                throw new IllegalArgumentException("Range paths must exactly cover actualRange");
            }
        }
    }

    /// Immutable terminal value decided for one range before lock-free Future publication.
    ///
    /// Exactly one of `page` and `failure` is present.
    ///
    /// @param page successful exact page, or null for failure and cancellation
    /// @param failure source failure or cancellation, or null for success
    @NotNullByDefault
    private record RangeTerminalOutcome(
            @Nullable ChoicePage<ResourcePackCatalogItem> page,
            @Nullable Throwable failure) {
        /// Validates that the outcome has exactly one terminal value.
        private RangeTerminalOutcome {
            if ((page == null) == (failure == null)) {
                throw new IllegalArgumentException("Range outcome requires exactly one terminal value");
            }
        }

        /// Creates one successful terminal outcome.
        ///
        /// @param page exact page to publish
        /// @return successful outcome
        private static RangeTerminalOutcome success(ChoicePage<ResourcePackCatalogItem> page) {
            return new RangeTerminalOutcome(Objects.requireNonNull(page, "page"), null);
        }

        /// Creates one exceptional terminal outcome.
        ///
        /// @param failure failure or cancellation to publish
        /// @return exceptional outcome
        private static RangeTerminalOutcome failure(Throwable failure) {
            return new RangeTerminalOutcome(null, Objects.requireNonNull(failure, "failure"));
        }

        /// Completes one externally visible Future without any model lock held.
        ///
        /// @param result Future receiving this outcome
        private void publish(CompletableFuture<ChoicePage<ResourcePackCatalogItem>> result) {
            if (page != null) {
                result.complete(page);
            } else {
                result.completeExceptionally(Objects.requireNonNull(failure, "failure"));
            }
        }
    }

    /// Work cancelled and transition published when a new index activates.
    ///
    /// @param operation new index operation
    /// @param terminalPublications decided range operations to publish
    /// @param transition committed loading transition
    @NotNullByDefault
    private record IndexPreparation(
            IndexOperation operation,
            @Unmodifiable List<RangeOperation> terminalPublications,
            SnapshotTransition transition) {
        /// Freezes pending terminal publications.
        private IndexPreparation {
            terminalPublications = List.copyOf(terminalPublications);
        }
    }

    /// One sequenced immutable snapshot transition.
    ///
    /// @param previous state before replacement
    /// @param current committed replacement
    /// @param sequence monotonic commit sequence
    @NotNullByDefault
    private record SnapshotTransition(
            ResourcePackCatalogSnapshot previous,
            ResourcePackCatalogSnapshot current,
            long sequence) {
    }

    /// Real local manager access with shallow indexing and exact-range parsing.
    @NotNullByDefault
    static final class ManagerCatalogAccess implements CatalogAccess {
        /// Manager bound to one stable repository instance.
        private final ResourcePackManager manager;

        /// Direct resource-pack directory used by the shallow index.
        private final Path directory;

        /// Creates a manager adapter without starting any I/O.
        ///
        /// @param repository repository containing the managed instance
        /// @param instanceId stable non-blank repository instance identifier
        ManagerCatalogAccess(GameRepository repository, String instanceId) {
            Objects.requireNonNull(repository, "repository");
            Objects.requireNonNull(instanceId, "instanceId");
            if (instanceId.isBlank()) {
                throw new IllegalArgumentException("instanceId must not be blank");
            }
            manager = new ResourcePackManager(repository, instanceId);
            directory = manager.getDirectory().toAbsolutePath().normalize();
        }

        /// Enumerates only supported direct-child shapes and sorts by exact file name.
        ///
        /// @param cancellation cooperative index cancellation
        /// @return shallow exact path index
        /// @throws IOException when directory enumeration fails
        @Override
        public CatalogIndex loadIndex(LoadCancellation cancellation) throws IOException {
            cancellation.throwIfCancelled();
            if (!ResourcePackManager.isMcVersionSupported(manager.getMinecraftVersion())) {
                return new CatalogIndex(false, List.of());
            }
            if (!Files.isDirectory(directory)) {
                return new CatalogIndex(true, List.of());
            }
            List<Path> paths = new ArrayList<>();
            try (DirectoryStream<Path> children = Files.newDirectoryStream(directory)) {
                for (Path child : children) {
                    cancellation.throwIfCancelled();
                    Path normalized = child.toAbsolutePath().normalize();
                    if (ResourcePackFile.isFileResourcePack(normalized)) {
                        paths.add(normalized);
                    }
                }
            }
            cancellation.throwIfCancelled();
            paths.sort(PATH_ORDER);
            return new CatalogIndex(true, paths);
        }

        /// Parses only requested paths and isolates ordinary per-pack I/O failures.
        ///
        /// @param paths exact normalized viewport paths
        /// @param cancellation cooperative viewport cancellation
        /// @return exact rows in identical order
        /// @throws IOException when shared enabled-state access fails
        @Override
        public @Unmodifiable List<ResourcePackCatalogItem> loadItems(
                @Unmodifiable List<Path> paths,
                LoadCancellation cancellation) throws IOException {
            List<@Nullable ResourcePackFile> resolvedFiles = new ArrayList<>(paths.size());
            List<ResourcePackFile> validFiles = new ArrayList<>(paths.size());
            for (Path path : paths) {
                cancellation.throwIfCancelled();
                try {
                    @Nullable ResourcePackFile file = ResourcePackFile.fromFile(manager, path);
                    resolvedFiles.add(file);
                    if (file != null) {
                        validFiles.add(file);
                    }
                } catch (IOException failure) {
                    cancellation.throwIfCancelled();
                    resolvedFiles.add(null);
                }
            }

            Map<ResourcePackFile, Boolean> enabledStates = new IdentityHashMap<>();
            @Unmodifiable List<Pair<ResourcePackFile, Boolean>> states = manager
                    .arePacksEnabled(validFiles.stream())
                    .toList();
            for (Pair<ResourcePackFile, Boolean> state : states) {
                enabledStates.put(
                        Objects.requireNonNull(state.key(), "resource-pack file"),
                        Objects.requireNonNull(state.value(), "resource-pack enabled state"));
            }

            List<ResourcePackCatalogItem> items = new ArrayList<>(paths.size());
            for (int index = 0; index < paths.size(); index++) {
                cancellation.throwIfCancelled();
                Path path = paths.get(index);
                @Nullable ResourcePackFile file = resolvedFiles.get(index);
                if (file == null) {
                    items.add(invalidItem(path));
                    continue;
                }
                @Nullable LocalAddonFile.Description description = file.getDescription();
                items.add(new ResourcePackCatalogItem(
                        path,
                        file.getFileName(),
                        file.getFileNameWithExtension(),
                        description == null ? "" : description.toString(),
                        mapCompatibility(file.getCompatibility()),
                        Boolean.TRUE.equals(enabledStates.get(file))));
            }
            cancellation.throwIfCancelled();
            return List.copyOf(items);
        }
    }

    /// Immutable listener delivery retained while a callback owner drains the latest transition.
    ///
    /// @param change immutable snapshot transition
    /// @param sequence transition commit sequence
    @NotNullByDefault
    private record ListenerDelivery(
            ValueChange<ResourcePackCatalogSnapshot> change,
            long sequence) {
    }

    /// Listener registration with sequence coalescing and a lock-free callback gate.
    @NotNullByDefault
    private static final class ListenerSlot {
        /// Listener owned by this exact registration.
        private final ValueChangeListener<ResourcePackCatalogSnapshot> listener;

        /// First transition sequence eligible for delivery.
        private final long minimumSequence;

        /// Whether delivery remains permitted under this slot monitor.
        private boolean active = true;

        /// Whether this slot currently has one callback executing outside the monitor.
        private boolean callbackActive;

        /// Thread currently executing this slot's callback, or null while idle.
        private @Nullable Thread callbackThread;

        /// Latest accepted delivery not yet taken by the owner, or null when none is pending.
        private @Nullable ListenerDelivery pendingDelivery;

        /// Greatest sequence accepted for this slot.
        private long lastAcceptedSequence;

        /// Creates one listener slot.
        ///
        /// @param listener listener to own
        /// @param minimumSequence first eligible committed sequence
        private ListenerSlot(
                ValueChangeListener<ResourcePackCatalogSnapshot> listener,
                long minimumSequence) {
            this.listener = Objects.requireNonNull(listener, "listener");
            this.minimumSequence = minimumSequence;
            lastAcceptedSequence = minimumSequence - 1L;
        }

        /// Enqueues an eligible transition for the model-level single callback owner.
        ///
        /// @param change immutable snapshot transition
        /// @param sequence transition commit sequence
        /// @return whether the model-level queue should include this slot
        private synchronized boolean enqueueIfEligible(
                ValueChange<ResourcePackCatalogSnapshot> change,
                long sequence) {
            if (!active
                    || sequence < minimumSequence
                    || sequence <= lastAcceptedSequence) {
                return false;
            }
            lastAcceptedSequence = sequence;
            pendingDelivery = new ListenerDelivery(change, sequence);
            return true;
        }

        /// Delivers this slot's latest pending transition without holding its monitor.
        private void deliverPending() {
            @Nullable ListenerDelivery delivery;
            synchronized (this) {
                if (!active) {
                    pendingDelivery = null;
                    return;
                }
                delivery = pendingDelivery;
                pendingDelivery = null;
                if (delivery == null) {
                    return;
                }
                callbackActive = true;
                callbackThread = Thread.currentThread();
            }
            try {
                try {
                    listener.onChange(delivery.change());
                } catch (RuntimeException listenerFailure) {
                    reportListenerFailure(listenerFailure);
                }
            } finally {
                synchronized (this) {
                    callbackActive = false;
                    callbackThread = null;
                    notifyAll();
                }
            }
        }

        /// Synchronously prevents later delivery and waits for another callback owner to exit.
        private void terminate() {
            boolean interrupted = false;
            synchronized (this) {
                active = false;
                pendingDelivery = null;
                if (Thread.currentThread() == callbackThread) {
                    return;
                }
                while (callbackActive) {
                    try {
                        wait();
                    } catch (InterruptedException interruption) {
                        interrupted = true;
                    }
                }
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
