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
package space.minecraftstl.xyml.ui.swing.page.mods;

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
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;

/// Asynchronous installed-Mod model backed by the real Core manager and exact viewport slices.
///
/// Full `ModManager` refreshes and every mutation run on the caller-owned executor. Search and
/// enabled-state filtering use the immutable latest index without disk access. Public row objects
/// are materialized only for the exact range requested by `ViewportChoiceList`. Content revisions
/// invalidate all superseded range results, while rename-stable local keys preserve selection
/// across `.disabled` path changes.
@NotNullByDefault
public final class DefaultModCatalogModel implements ModCatalogModel {
    /// Serializes model transitions, operation ownership, selection, and closure.
    private final Object stateLock = new Object();

    /// Blocking Core access invoked exclusively on the injected executor.
    private final ModCatalogAccess access;

    /// Caller-owned executor for every operation that may touch disk.
    private final Executor executor;

    /// Localized state presentation.
    private final ModCatalogStatusStrings strings;

    /// Thread-safe synchronous snapshot publisher.
    private final ValueChangeSupport<ModCatalogSnapshot> changes = new ValueChangeSupport<>(this);

    /// Atomically published complete index, filtered index, and matching snapshot.
    private volatile ModelState state;

    /// Rename-stable selection retained while a refresh temporarily has no exact index.
    private @Nullable String selectedLocalKey;

    /// Monotonically increasing owner generation for refreshes and writes.
    private long generation;

    /// Latest active full-index cancellation signal, or `null` without a refresh.
    private @Nullable LoadCancellation activeRefreshCancellation;

    /// Latest active serialized write, or `null` without one.
    private @Nullable MutationOperation activeMutation;

    /// Whether commands, subscriptions, loads, and publication have been permanently closed.
    private boolean closed;

    /// Creates a production model using the real repository and Core Mod manager.
    ///
    /// @param repository real game repository
    /// @param instanceId managed instance identifier
    /// @param executor caller-owned executor that must run work outside the EDT
    /// @param strings localized status presentation
    public DefaultModCatalogModel(
            GameRepository repository,
            GameInstanceID instanceId,
            Executor executor,
            ModCatalogStatusStrings strings) {
        this(new ModManagerCatalogAccess(repository, instanceId), executor, strings);
    }

    /// Creates a model with deterministic blocking access for headless tests.
    ///
    /// @param access blocking Mod source
    /// @param executor caller-owned background executor
    /// @param strings localized status presentation
    DefaultModCatalogModel(
            ModCatalogAccess access,
            Executor executor,
            ModCatalogStatusStrings strings) {
        this.access = Objects.requireNonNull(access, "access");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.strings = Objects.requireNonNull(strings, "strings");
        state = initialState();
    }

    /// Returns the latest immutable state without blocking.
    @Override
    public ModCatalogSnapshot snapshot() {
        return state.snapshot();
    }

    /// Registers a listener while this model remains open.
    @Override
    public Subscription subscribe(ValueChangeListener<ModCatalogSnapshot> listener) {
        Objects.requireNonNull(listener, "listener");
        synchronized (stateLock) {
            requireOpen();
            return changes.subscribe(listener);
        }
    }

    /// Returns the normalized path supplied by the real access adapter.
    @Override
    public Path modsDirectory() {
        return access.modsDirectory();
    }

    /// Returns the exact filtered count only after a successful refresh.
    @Override
    public OptionalInt exactItemCount() {
        return state.snapshot().itemCount();
    }

    /// Returns the exact content revision used to reject late viewport results.
    @Override
    public OptionalLong sourceRevision() {
        return OptionalLong.of(state.snapshot().contentRevision());
    }

    /// Starts the first full refresh only from the initial idle state.
    @Override
    public void loadIfNeeded() {
        startRefresh(true);
    }

    /// Supersedes a current refresh unless a serialized write owns source access.
    @Override
    public void refresh() {
        startRefresh(false);
    }

    /// Refilters the immutable current index using normalized case-insensitive text.
    @Override
    public void setSearchQuery(String query) {
        String normalized = Objects.requireNonNull(query, "query").trim();
        SnapshotTransition transition;
        synchronized (stateLock) {
            requireOpen();
            ModCatalogSnapshot previous = state.snapshot();
            if (previous.writeStatus() == ModCatalogWriteStatus.BUSY
                    || previous.searchQuery().equals(normalized)) {
                return;
            }
            transition = refilterLocked(normalized, previous.filter());
        }
        publish(transition);
    }

    /// Refilters the immutable current index by actual disabled-suffix state.
    @Override
    public void setFilter(ModCatalogFilter filter) {
        Objects.requireNonNull(filter, "filter");
        SnapshotTransition transition;
        synchronized (stateLock) {
            requireOpen();
            ModCatalogSnapshot previous = state.snapshot();
            if (previous.writeStatus() == ModCatalogWriteStatus.BUSY
                    || previous.filter() == filter) {
                return;
            }
            transition = refilterLocked(previous.searchQuery(), filter);
        }
        publish(transition);
    }

    /// Selects one exact current filtered row by its rename-stable key.
    @Override
    public void selectMod(String localKey) {
        Objects.requireNonNull(localKey, "localKey");
        @Nullable SnapshotTransition transition;
        synchronized (stateLock) {
            requireOpen();
            int index = indexOf(state.filteredEntries(), localKey);
            if (index < 0) {
                throw new IllegalArgumentException("Unknown filtered Mod: " + localKey);
            }
            if (Objects.equals(selectedLocalKey, localKey)
                    && state.snapshot().selectedIndex().orElse(-1) == index) {
                return;
            }
            selectedLocalKey = localKey;
            transition = replaceSelectionLocked(OptionalInt.of(index));
        }
        publish(transition);
    }

    /// Clears stable selection without replacing indexed content.
    @Override
    public void clearSelection() {
        @Nullable SnapshotTransition transition;
        synchronized (stateLock) {
            requireOpen();
            if (selectedLocalKey == null && state.snapshot().selectedIndex().isEmpty()) {
                return;
            }
            selectedLocalKey = null;
            transition = replaceSelectionLocked(OptionalInt.empty());
        }
        publish(transition);
    }

    /// Starts one serialized Core enabled-state rename.
    @Override
    public CompletionStage<ModCatalogSnapshot> setModEnabled(String localKey, boolean enabled) {
        ModCatalogMutation mutation;
        try {
            mutation = new ModCatalogMutation.Enabled(localKey, enabled);
        } catch (RuntimeException failure) {
            return CompletableFuture.failedFuture(failure);
        }
        return startMutation(
                mutation,
                enabled ? strings.enablingText() : strings.disablingText());
    }

    /// Starts one serialized preflighted Core import.
    @Override
    public CompletionStage<ModCatalogSnapshot> importMods(@Unmodifiable List<Path> sources) {
        ModCatalogMutation mutation;
        try {
            mutation = new ModCatalogMutation.Import(List.copyOf(sources));
        } catch (RuntimeException failure) {
            return CompletableFuture.failedFuture(failure);
        }
        return startMutation(mutation, strings.importingText());
    }

    /// Starts one serialized permanent Core deletion.
    @Override
    public CompletionStage<ModCatalogSnapshot> deleteMod(String localKey) {
        ModCatalogMutation mutation;
        try {
            mutation = new ModCatalogMutation.Delete(localKey);
        } catch (RuntimeException failure) {
            return CompletableFuture.failedFuture(failure);
        }
        return startMutation(mutation, strings.deletingText());
    }

    /// Schedules exact materialization of one captured filtered viewport slice.
    @Override
    public CompletionStage<ChoicePage<ModCatalogItem>> load(
            IndexRange desiredRange,
            LoadCancellation cancellation) {
        Objects.requireNonNull(desiredRange, "desiredRange");
        Objects.requireNonNull(cancellation, "cancellation");
        ModelState captured = state;
        synchronized (stateLock) {
            if (closed) {
                return CompletableFuture.failedFuture(
                        new IllegalStateException("Mod catalog model is closed"));
            }
        }
        OptionalInt itemCount = captured.snapshot().itemCount();
        if (itemCount.isEmpty()) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("Mod catalog index is not ready"));
        }
        IndexRange effectiveRange = desiredRange.clampToItemCount(itemCount.getAsInt());
        @Unmodifiable List<ModCatalogEntry> entries = List.copyOf(
                captured.filteredEntries().subList(
                        effectiveRange.startInclusive(),
                        effectiveRange.endExclusive()));
        CompletableFuture<ChoicePage<ModCatalogItem>> result = new CompletableFuture<>();
        RangeOperation operation = new RangeOperation(
                captured.snapshot().contentRevision(),
                effectiveRange,
                itemCount.getAsInt(),
                entries,
                cancellation,
                result);
        try {
            executor.execute(() -> executeRange(operation));
        } catch (RuntimeException failure) {
            result.completeExceptionally(failure);
        }
        return result;
    }

    /// Permanently rejects future work and invalidates every owned operation.
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
            mutation.result().completeExceptionally(
                    new CancellationException("Mod catalog model was closed"));
        }
    }

    /// Prepares, publishes, and submits one full-index refresh.
    ///
    /// @param onlyIfIdle whether any non-idle state suppresses the request
    private void startRefresh(boolean onlyIfIdle) {
        RefreshOperation operation;
        SnapshotTransition transition;
        synchronized (stateLock) {
            requireOpen();
            ModCatalogSnapshot previous = state.snapshot();
            if (previous.writeStatus() == ModCatalogWriteStatus.BUSY
                    || onlyIfIdle && previous.status() != ModCatalogStatus.IDLE) {
                return;
            }
            if (activeRefreshCancellation != null) {
                activeRefreshCancellation.cancel();
            }
            LoadCancellation cancellation = new LoadCancellation();
            activeRefreshCancellation = cancellation;
            long operationGeneration = ++generation;
            ModCatalogSnapshot loading = new ModCatalogSnapshot(
                    OptionalInt.empty(),
                    OptionalInt.empty(),
                    nextRevision(previous.contentRevision()),
                    ModCatalogStatus.LOADING,
                    strings.loadingText(),
                    ModCatalogWriteStatus.IDLE,
                    "",
                    previous.searchQuery(),
                    previous.filter(),
                    false,
                    false);
            transition = replaceStateLocked(List.of(), List.of(), loading);
            operation = new RefreshOperation(operationGeneration, cancellation);
        }
        publish(transition);
        try {
            executor.execute(() -> executeRefresh(operation));
        } catch (RuntimeException failure) {
            commitRefreshFailure(operation, failure);
        }
    }

    /// Runs a complete blocking Core refresh on the injected executor.
    ///
    /// @param operation owned refresh
    private void executeRefresh(RefreshOperation operation) {
        try {
            requireBackgroundThread();
            ModCatalogIndex index = access.refresh(operation.cancellation());
            operation.cancellation().throwIfCancelled();
            commitRefresh(operation, index);
        } catch (IOException | RuntimeException failure) {
            commitRefreshFailure(operation, failure);
        }
    }

    /// Commits one current successful full index.
    ///
    /// @param operation owned refresh
    /// @param index complete refreshed index
    private void commitRefresh(RefreshOperation operation, ModCatalogIndex index) {
        @Nullable SnapshotTransition transition;
        synchronized (stateLock) {
            if (!ownsRefresh(operation)) {
                return;
            }
            activeRefreshCancellation = null;
            ModCatalogSnapshot previous = state.snapshot();
            @Unmodifiable List<ModCatalogEntry> filtered = filterEntries(
                    index.entries(), previous.searchQuery(), previous.filter());
            OptionalInt selectedIndex = reconcileSelectionLocked(filtered);
            ModCatalogSnapshot ready = new ModCatalogSnapshot(
                    selectedIndex,
                    OptionalInt.of(filtered.size()),
                    nextRevision(previous.contentRevision()),
                    ModCatalogStatus.READY,
                    strings.readyText(filtered.size()),
                    ModCatalogWriteStatus.IDLE,
                    "",
                    previous.searchQuery(),
                    previous.filter(),
                    !filtered.isEmpty(),
                    true);
            transition = replaceStateLocked(index.entries(), filtered, ready);
        }
        publish(transition);
    }

    /// Commits one current retryable full-index failure.
    ///
    /// @param operation owned refresh
    /// @param failure original source or executor failure
    private void commitRefreshFailure(RefreshOperation operation, Throwable failure) {
        @Nullable SnapshotTransition transition;
        synchronized (stateLock) {
            if (!ownsRefresh(operation)) {
                return;
            }
            activeRefreshCancellation = null;
            ModCatalogSnapshot previous = state.snapshot();
            ModCatalogSnapshot failed = new ModCatalogSnapshot(
                    OptionalInt.empty(),
                    OptionalInt.empty(),
                    nextRevision(previous.contentRevision()),
                    ModCatalogStatus.FAILED,
                    strings.loadFailureText(failureDetail(failure)),
                    ModCatalogWriteStatus.IDLE,
                    "",
                    previous.searchQuery(),
                    previous.filter(),
                    false,
                    true);
            transition = replaceStateLocked(List.of(), List.of(), failed);
        }
        publish(transition);
    }

    /// Starts one serialized mutation against a ready exact index.
    ///
    /// @param mutation immutable mutation
    /// @param busyText localized progress text
    /// @return asynchronous terminal state
    private CompletionStage<ModCatalogSnapshot> startMutation(
            ModCatalogMutation mutation,
            String busyText) {
        MutationOperation operation;
        SnapshotTransition transition;
        synchronized (stateLock) {
            if (closed) {
                return CompletableFuture.failedFuture(
                        new IllegalStateException("Mod catalog model is closed"));
            }
            ModCatalogSnapshot previous = state.snapshot();
            if (previous.status() != ModCatalogStatus.READY) {
                return CompletableFuture.failedFuture(
                        new IllegalStateException("Mod catalog index is not ready"));
            }
            if (previous.writeStatus() == ModCatalogWriteStatus.BUSY) {
                return CompletableFuture.failedFuture(
                        new IllegalStateException("Another Mod mutation is already running"));
            }
            if (activeRefreshCancellation != null) {
                activeRefreshCancellation.cancel();
                activeRefreshCancellation = null;
            }
            CompletableFuture<ModCatalogSnapshot> result = new CompletableFuture<>();
            operation = new MutationOperation(
                    ++generation,
                    mutation,
                    new LoadCancellation(),
                    result);
            activeMutation = operation;
            ModCatalogSnapshot busy = new ModCatalogSnapshot(
                    previous.selectedIndex(),
                    previous.itemCount(),
                    nextRevision(previous.contentRevision()),
                    previous.status(),
                    previous.statusText(),
                    ModCatalogWriteStatus.BUSY,
                    busyText,
                    previous.searchQuery(),
                    previous.filter(),
                    false,
                    false);
            transition = replaceStateLocked(state.allEntries(), state.filteredEntries(), busy);
        }
        publish(transition);
        try {
            executor.execute(() -> executeMutation(operation));
        } catch (RuntimeException failure) {
            commitMutationFailure(operation, failure);
        }
        return operation.result();
    }

    /// Runs one blocking Core mutation and mandatory follow-up refresh.
    ///
    /// @param operation owned mutation
    private void executeMutation(MutationOperation operation) {
        try {
            requireBackgroundThread();
            ModCatalogMutationResult accessResult = access.mutateAndRefresh(
                    operation.mutation(), operation.cancellation());
            commitMutation(operation, accessResult);
        } catch (IOException | RuntimeException failure) {
            commitMutationFailure(operation, failure);
        }
    }

    /// Commits the actual refreshed index after a successful or failed mutation attempt.
    ///
    /// @param operation owned mutation
    /// @param accessResult refreshed index and optional mutation failure
    private void commitMutation(
            MutationOperation operation,
            ModCatalogMutationResult accessResult) {
        @Nullable SnapshotTransition transition;
        @Nullable Throwable mutationFailure = accessResult.mutationFailure();
        ModCatalogSnapshot terminal;
        synchronized (stateLock) {
            if (!ownsMutation(operation)) {
                return;
            }
            activeMutation = null;
            ModCatalogSnapshot previous = state.snapshot();
            @Unmodifiable List<ModCatalogEntry> filtered = filterEntries(
                    accessResult.index().entries(), previous.searchQuery(), previous.filter());
            OptionalInt selectedIndex = reconcileSelectionLocked(filtered);
            terminal = new ModCatalogSnapshot(
                    selectedIndex,
                    OptionalInt.of(filtered.size()),
                    nextRevision(previous.contentRevision()),
                    ModCatalogStatus.READY,
                    strings.readyText(filtered.size()),
                    mutationFailure == null
                            ? ModCatalogWriteStatus.IDLE
                            : ModCatalogWriteStatus.ERROR,
                    mutationFailure == null
                            ? ""
                            : strings.writeFailureText(failureDetail(mutationFailure)),
                    previous.searchQuery(),
                    previous.filter(),
                    !filtered.isEmpty(),
                    true);
            transition = replaceStateLocked(accessResult.index().entries(), filtered, terminal);
        }
        publish(transition);
        if (mutationFailure == null) {
            operation.result().complete(terminal);
        } else {
            operation.result().completeExceptionally(mutationFailure);
        }
    }

    /// Invalidates the index when a mutation's mandatory follow-up refresh fails.
    ///
    /// @param operation owned mutation
    /// @param failure original failure
    private void commitMutationFailure(MutationOperation operation, Throwable failure) {
        @Nullable SnapshotTransition transition;
        synchronized (stateLock) {
            if (!ownsMutation(operation)) {
                return;
            }
            activeMutation = null;
            ModCatalogSnapshot previous = state.snapshot();
            ModCatalogSnapshot failed = new ModCatalogSnapshot(
                    OptionalInt.empty(),
                    OptionalInt.empty(),
                    nextRevision(previous.contentRevision()),
                    ModCatalogStatus.FAILED,
                    strings.loadFailureText(failureDetail(failure)),
                    ModCatalogWriteStatus.ERROR,
                    strings.writeFailureText(failureDetail(failure)),
                    previous.searchQuery(),
                    previous.filter(),
                    false,
                    true);
            transition = replaceStateLocked(List.of(), List.of(), failed);
        }
        publish(transition);
        operation.result().completeExceptionally(failure);
    }

    /// Materializes one exact range and rejects superseded content before completion.
    ///
    /// @param operation captured range operation
    private void executeRange(RangeOperation operation) {
        try {
            requireBackgroundThread();
            operation.cancellation().throwIfCancelled();
            @Unmodifiable List<ModCatalogItem> items = access.loadItems(
                    operation.entries(), operation.cancellation());
            if (items.size() != operation.range().length()) {
                throw new IllegalStateException("Mod access returned a mismatched viewport row count");
            }
            synchronized (stateLock) {
                if (closed
                        || operation.cancellation().isCancelled()
                        || state.snapshot().contentRevision() != operation.contentRevision()) {
                    throw new CancellationException("Mod viewport result was superseded");
                }
            }
            operation.result().complete(new ChoicePage<>(
                    operation.range(),
                    items,
                    OptionalInt.of(operation.itemCount()),
                    operation.range().endExclusive() == operation.itemCount()));
        } catch (RuntimeException failure) {
            operation.result().completeExceptionally(failure);
        }
    }

    /// Replaces the filtered source for a query or enabled-state filter change.
    ///
    /// @param query normalized display query
    /// @param filter new enabled-state filter
    /// @return committed snapshot transition
    private SnapshotTransition refilterLocked(String query, ModCatalogFilter filter) {
        ModCatalogSnapshot previous = state.snapshot();
        @Unmodifiable List<ModCatalogEntry> filtered = previous.status() == ModCatalogStatus.READY
                ? filterEntries(state.allEntries(), query, filter)
                : List.of();
        OptionalInt selectedIndex = previous.status() == ModCatalogStatus.READY
                ? reconcileSelectionLocked(filtered)
                : OptionalInt.empty();
        OptionalInt itemCount = previous.status() == ModCatalogStatus.READY
                ? OptionalInt.of(filtered.size())
                : OptionalInt.empty();
        String statusText = previous.status() == ModCatalogStatus.READY
                ? strings.readyText(filtered.size())
                : previous.statusText();
        ModCatalogSnapshot replacement = new ModCatalogSnapshot(
                selectedIndex,
                itemCount,
                nextRevision(previous.contentRevision()),
                previous.status(),
                statusText,
                previous.writeStatus(),
                previous.writeStatusText(),
                query,
                filter,
                previous.status() == ModCatalogStatus.READY
                        && previous.writeStatus() != ModCatalogWriteStatus.BUSY
                        && !filtered.isEmpty(),
                previous.refreshEnabled());
        return replaceStateLocked(state.allEntries(), filtered, replacement);
    }

    /// Replaces selection without changing source revision or indexed rows.
    ///
    /// @param selectedIndex new selected index
    /// @return snapshot transition
    private SnapshotTransition replaceSelectionLocked(OptionalInt selectedIndex) {
        ModCatalogSnapshot previous = state.snapshot();
        ModCatalogSnapshot replacement = new ModCatalogSnapshot(
                selectedIndex,
                previous.itemCount(),
                previous.contentRevision(),
                previous.status(),
                previous.statusText(),
                previous.writeStatus(),
                previous.writeStatusText(),
                previous.searchQuery(),
                previous.filter(),
                previous.listEnabled(),
                previous.refreshEnabled());
        return replaceStateLocked(state.allEntries(), state.filteredEntries(), replacement);
    }

    /// Filters one complete immutable index without disk access.
    ///
    /// @param entries complete index
    /// @param query normalized display query
    /// @param filter enabled-state filter
    /// @return immutable filtered order
    private static @Unmodifiable List<ModCatalogEntry> filterEntries(
            @Unmodifiable List<ModCatalogEntry> entries,
            String query,
            ModCatalogFilter filter) {
        String normalizedQuery = query.toLowerCase(Locale.ROOT);
        return entries.stream()
                .filter(entry -> entry.matches(normalizedQuery, filter))
                .toList();
    }

    /// Reconciles stable selection against one replacement filtered index.
    ///
    /// @param filtered replacement filtered rows
    /// @return selected index, or empty when the selected key left the filter
    private OptionalInt reconcileSelectionLocked(@Unmodifiable List<ModCatalogEntry> filtered) {
        @Nullable String localKey = selectedLocalKey;
        if (localKey == null) {
            return OptionalInt.empty();
        }
        int index = indexOf(filtered, localKey);
        if (index < 0) {
            selectedLocalKey = null;
            return OptionalInt.empty();
        }
        return OptionalInt.of(index);
    }

    /// Finds one stable key in the supplied filtered order.
    ///
    /// @param entries filtered rows
    /// @param localKey stable key
    /// @return zero-based index, or negative one when absent
    private static int indexOf(
            @Unmodifiable List<ModCatalogEntry> entries,
            String localKey) {
        for (int index = 0; index < entries.size(); index++) {
            if (entries.get(index).localKey().equals(localKey)) {
                return index;
            }
        }
        return -1;
    }

    /// Returns whether one refresh still owns publication rights.
    ///
    /// @param operation candidate refresh
    /// @return whether it remains current and open
    private boolean ownsRefresh(RefreshOperation operation) {
        return !closed
                && generation == operation.generation()
                && activeRefreshCancellation == operation.cancellation()
                && !operation.cancellation().isCancelled();
    }

    /// Returns whether one mutation still owns publication rights.
    ///
    /// @param operation candidate mutation
    /// @return whether it remains current and open
    private boolean ownsMutation(MutationOperation operation) {
        return !closed
                && generation == operation.generation()
                && activeMutation == operation;
    }

    /// Atomically replaces model state and captures its matching snapshot transition.
    ///
    /// @param allEntries complete current index
    /// @param filteredEntries current filtered source
    /// @param replacement matching immutable snapshot
    /// @return snapshot transition
    private SnapshotTransition replaceStateLocked(
            @Unmodifiable List<ModCatalogEntry> allEntries,
            @Unmodifiable List<ModCatalogEntry> filteredEntries,
            ModCatalogSnapshot replacement) {
        ModCatalogSnapshot previous = state.snapshot();
        state = new ModelState(allEntries, filteredEntries, replacement);
        return new SnapshotTransition(previous, replacement);
    }

    /// Publishes one committed state transition without holding the model lock.
    ///
    /// @param transition committed snapshot transition
    private void publish(SnapshotTransition transition) {
        changes.fireChange(transition.previous(), transition.replacement());
    }

    /// Creates the empty idle source and snapshot.
    ///
    /// @return initial model state
    private static ModelState initialState() {
        return new ModelState(
                List.of(),
                List.of(),
                new ModCatalogSnapshot(
                        OptionalInt.empty(),
                        OptionalInt.empty(),
                        0L,
                        ModCatalogStatus.IDLE,
                        "",
                        ModCatalogWriteStatus.IDLE,
                        "",
                        "",
                        ModCatalogFilter.ALL,
                        false,
                        true));
    }

    /// Returns concise stable failure detail for localized status text.
    ///
    /// @param failure original failure
    /// @return message or simple type name
    private static String failureDetail(Throwable failure) {
        @Nullable String message = failure.getMessage();
        return message == null || message.isBlank()
                ? failure.getClass().getSimpleName()
                : message;
    }

    /// Increments a source revision without silent overflow.
    ///
    /// @param revision current non-negative revision
    /// @return next revision
    private static long nextRevision(long revision) {
        return Math.incrementExact(revision);
    }

    /// Prevents a misconfigured executor from running blocking source work on the EDT.
    private static void requireBackgroundThread() {
        if (SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException("Mod catalog source work must not run on the EDT");
        }
    }

    /// Rejects commands and subscriptions after permanent closure.
    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("Mod catalog model is closed");
        }
    }

    /// Complete and filtered immutable indexes with their matching snapshot.
    ///
    /// @param allEntries complete current index
    /// @param filteredEntries filtered current source
    /// @param snapshot matching visible state
    @NotNullByDefault
    private record ModelState(
            @Unmodifiable List<ModCatalogEntry> allEntries,
            @Unmodifiable List<ModCatalogEntry> filteredEntries,
            ModCatalogSnapshot snapshot) {
        /// Stores defensive immutable index lists.
        private ModelState {
            allEntries = List.copyOf(allEntries);
            filteredEntries = List.copyOf(filteredEntries);
        }
    }

    /// Committed snapshot replacement ready for lock-free publication.
    ///
    /// @param previous previous snapshot
    /// @param replacement replacement snapshot
    @NotNullByDefault
    private record SnapshotTransition(
            ModCatalogSnapshot previous,
            ModCatalogSnapshot replacement) {
    }

    /// One owned full-index operation.
    ///
    /// @param generation owner generation
    /// @param cancellation cooperative cancellation signal
    @NotNullByDefault
    private record RefreshOperation(
            long generation,
            LoadCancellation cancellation) {
    }

    /// One owned serialized mutation.
    ///
    /// @param generation owner generation
    /// @param mutation immutable mutation
    /// @param cancellation cooperative pre-commit cancellation
    /// @param result externally visible terminal Future
    @NotNullByDefault
    private record MutationOperation(
            long generation,
            ModCatalogMutation mutation,
            LoadCancellation cancellation,
            CompletableFuture<ModCatalogSnapshot> result) {
    }

    /// One exact viewport materialization operation.
    ///
    /// @param contentRevision captured source revision
    /// @param range effective exact range
    /// @param itemCount captured exact count
    /// @param entries captured immutable slice
    /// @param cancellation coordinator-owned cancellation signal
    /// @param result externally visible page Future
    @NotNullByDefault
    private record RangeOperation(
            long contentRevision,
            IndexRange range,
            int itemCount,
            @Unmodifiable List<ModCatalogEntry> entries,
            LoadCancellation cancellation,
            CompletableFuture<ChoicePage<ModCatalogItem>> result) {
        /// Stores a defensive immutable viewport slice.
        private RangeOperation {
            entries = List.copyOf(entries);
        }
    }
}
