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
package space.minecraftstl.xyml.ui.swing.page.downloads;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import space.minecraftstl.xyml.observable.Subscription;
import space.minecraftstl.xyml.observable.ValueChange;
import space.minecraftstl.xyml.observable.ValueChangeListener;
import space.minecraftstl.xyml.ui.swing.choice.ChoicePage;
import space.minecraftstl.xyml.ui.swing.choice.IndexRange;
import space.minecraftstl.xyml.ui.swing.choice.LoadCancellation;
import space.minecraftstl.xyml.util.Lang;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;

/// Default lazy, viewport-driven Minecraft game-version catalog model.
///
/// A source generation loads the complete remote or cached catalog once. Query and kind changes
/// derive a new immutable local filtered snapshot, while viewport requests only slice that snapshot.
/// Source calls and listener publication never occur while the model state lock is held.
@NotNullByDefault
public final class DefaultGameVersionCatalogModel implements GameVersionCatalogModel, AutoCloseable {
    /// Lock protecting state replacement, source generation ownership, and closure.
    private final Object stateLock = new Object();

    /// Lock serializing publication with close and dropping superseded transitions.
    private final Object publicationLock = new Object();

    /// Lock preventing a new external source invocation from crossing the close return boundary.
    private final Object sourceInvocationLock = new Object();

    /// Complete-catalog source invoked only after lazy loading or refresh is requested.
    private final GameVersionCatalogSource source;

    /// Localized lifecycle text.
    private final GameVersionCatalogStatusStrings statusStrings;

    /// Snapshot listeners kept independently removable and runtime-failure isolated.
    private final CopyOnWriteArrayList<IsolatedListenerSlot> listeners = new CopyOnWriteArrayList<>();

    /// Atomically published immutable master content, filtered content, and matching state.
    private volatile ModelState state;

    /// Stable selected version ID, or null while no loaded version is selected.
    private @Nullable String selectedVersionId;

    /// Monotonically increasing source generation used to reject late results.
    private long sourceGeneration;

    /// Latest source generation still allowed to commit, or null while no load is active.
    private @Nullable LoadOperation activeLoad;

    /// Monotonically increasing snapshot transition sequence used to coalesce stale publications.
    private long snapshotSequence;

    /// Whether commands, loads, and new subscriptions are rejected.
    private volatile boolean closed;

    /// Creates an idle catalog that performs no source work until explicitly requested.
    ///
    /// @param source complete-catalog source
    /// @param statusStrings localized lifecycle text
    public DefaultGameVersionCatalogModel(
            GameVersionCatalogSource source,
            GameVersionCatalogStatusStrings statusStrings) {
        this.source = Objects.requireNonNull(source, "source");
        this.statusStrings = Objects.requireNonNull(statusStrings, "statusStrings");
        CatalogContent emptyContent = new CatalogContent(List.of(), List.of());
        state = new ModelState(emptyContent, new GameVersionCatalogSnapshot(
                OptionalInt.empty(),
                0,
                0L,
                GameVersionCatalogStatus.IDLE,
                statusStrings.idleStatus(),
                "",
                GameVersionFilter.RELEASE,
                false,
                true));
    }

    /// Returns the latest immutable catalog state.
    @Override
    public GameVersionCatalogSnapshot snapshot() {
        return state.snapshot();
    }

    /// Registers a listener for future coalescible catalog transitions.
    @Override
    public Subscription subscribe(ValueChangeListener<GameVersionCatalogSnapshot> listener) {
        Objects.requireNonNull(listener, "listener");
        synchronized (stateLock) {
            requireOpen();
            IsolatedListenerSlot slot = new IsolatedListenerSlot(listener);
            listeners.add(slot);
            return Subscription.create(() -> listeners.remove(slot));
        }
    }

    /// Returns the exact visible count from the latest immutable filtered snapshot.
    @Override
    public OptionalInt exactItemCount() {
        return OptionalInt.of(state.content().filteredItems().size());
    }

    /// Slices the exact desired portion of the current immutable filtered snapshot.
    @Override
    public CompletionStage<ChoicePage<GameVersionCatalogItem>> load(
            IndexRange desiredRange,
            LoadCancellation cancellation) {
        Objects.requireNonNull(desiredRange, "desiredRange");
        Objects.requireNonNull(cancellation, "cancellation");
        CatalogContent requestContent;
        synchronized (stateLock) {
            requireOpen();
            requestContent = state.content();
        }

        try {
            cancellation.throwIfCancelled();
            int itemCount = requestContent.filteredItems().size();
            IndexRange actualRange = desiredRange.clampToItemCount(itemCount);
            @Unmodifiable List<GameVersionCatalogItem> items = List.copyOf(
                    requestContent.filteredItems().subList(
                            actualRange.startInclusive(),
                            actualRange.endExclusive()));
            cancellation.throwIfCancelled();
            return CompletableFuture.completedFuture(new ChoicePage<>(
                    actualRange,
                    items,
                    OptionalInt.of(itemCount),
                    actualRange.endExclusive() == itemCount));
        } catch (RuntimeException failure) {
            return CompletableFuture.failedFuture(failure);
        }
    }

    /// Starts the first source generation only while no load has been attempted.
    @Override
    public void loadIfNeeded() {
        startLoad(true);
    }

    /// Starts a fresh generation and cancels any source request it supersedes.
    @Override
    public void refresh() {
        startLoad(false);
    }

    /// Replaces the retained query and derives matching local content.
    @Override
    public void setQuery(String query) {
        Objects.requireNonNull(query, "query");
        @Nullable SnapshotTransition transition;
        synchronized (stateLock) {
            requireOpen();
            ModelState current = state;
            if (current.snapshot().query().equals(query)) {
                return;
            }
            transition = replaceFilterStateLocked(query, current.snapshot().filter());
        }
        publish(transition);
    }

    /// Replaces the kind filter and derives matching local content.
    @Override
    public void setFilter(GameVersionFilter filter) {
        Objects.requireNonNull(filter, "filter");
        @Nullable SnapshotTransition transition;
        synchronized (stateLock) {
            requireOpen();
            ModelState current = state;
            if (current.snapshot().filter() == filter) {
                return;
            }
            transition = replaceFilterStateLocked(current.snapshot().query(), filter);
        }
        publish(transition);
    }

    /// Selects one stable ID from the loaded master catalog.
    @Override
    public void selectVersion(String versionId) {
        Objects.requireNonNull(versionId, "versionId");
        @Nullable SnapshotTransition transition;
        synchronized (stateLock) {
            requireOpen();
            ModelState current = state;
            if (indexOf(current.content().masterItems(), versionId) < 0) {
                throw new IllegalArgumentException("Unknown game version: " + versionId);
            }
            if (versionId.equals(selectedVersionId)) {
                return;
            }
            selectedVersionId = versionId;
            GameVersionCatalogSnapshot previous = current.snapshot();
            GameVersionCatalogSnapshot replacement = copySnapshot(
                    previous,
                    selectedIndex(current.content().filteredItems()),
                    previous.itemCount(),
                    previous.contentRevision(),
                    previous.status(),
                    previous.statusText(),
                    previous.query(),
                    previous.filter(),
                    previous.listEnabled(),
                    previous.refreshEnabled());
            transition = replaceStateLocked(current.content(), replacement);
        }
        publish(transition);
    }

    /// Cancels an active source request and prevents late source or listener effects.
    @Override
    public void close() {
        synchronized (stateLock) {
            if (!closed) {
                closed = true;
                sourceGeneration++;
                @Nullable LoadOperation operation = activeLoad;
                activeLoad = null;
                if (operation != null) {
                    operation.cancellation().cancel();
                }
            }
        }

        // Every concurrent caller independently crosses both idempotent barriers. This avoids an
        // early-return race without making a listener re-entering close wait on its own publication.
        synchronized (sourceInvocationLock) {
            // Acquiring this monitor waits for any source invocation that began before close.
        }
        synchronized (publicationLock) {
            listeners.clear();
        }
    }

    /// Starts one source generation outside the model lock after committing its loading state.
    ///
    /// @param onlyIfIdle whether a previous attempt makes this request a no-op
    private void startLoad(boolean onlyIfIdle) {
        @Nullable LoadOperation previousOperation;
        LoadOperation operation;
        @Nullable SnapshotTransition transition;
        synchronized (stateLock) {
            requireOpen();
            ModelState current = state;
            if (onlyIfIdle && current.snapshot().status() != GameVersionCatalogStatus.IDLE) {
                return;
            }
            previousOperation = activeLoad;
            operation = new LoadOperation(++sourceGeneration, new LoadCancellation());
            activeLoad = operation;
            GameVersionCatalogSnapshot previous = current.snapshot();
            GameVersionCatalogSnapshot loading = copySnapshot(
                    previous,
                    previous.selectedIndex(),
                    previous.itemCount(),
                    previous.contentRevision(),
                    GameVersionCatalogStatus.LOADING,
                    statusStrings.loadingStatus(),
                    previous.query(),
                    previous.filter(),
                    false,
                    false);
            transition = replaceStateLocked(current.content(), loading);
        }

        if (previousOperation != null) {
            previousOperation.cancellation().cancel();
        }
        requestSource(operation);
        publish(transition);
    }

    /// Invokes the external source and attaches completion without holding [#stateLock].
    ///
    /// The source-invocation monitor forms a close barrier around the invocation. A concurrent close may
    /// cancel an invocation already in progress, but no new source call can begin after close returns.
    ///
    /// @param operation current source generation
    private void requestSource(LoadOperation operation) {
        @Nullable CompletionStage<@Unmodifiable List<GameVersionCatalogItem>> stage = null;
        @Nullable Throwable invocationFailure = null;
        synchronized (sourceInvocationLock) {
            synchronized (stateLock) {
                if (closed || activeLoad != operation || operation.generation() != sourceGeneration) {
                    return;
                }
            }

            try {
                stage = Objects.requireNonNull(
                        source.load(operation.cancellation()),
                        "catalog source returned null stage");
            } catch (RuntimeException | Error sourceFailure) {
                invocationFailure = sourceFailure;
            }
        }

        if (invocationFailure != null) {
            completeLoad(operation, null, invocationFailure);
            if (invocationFailure instanceof Error error) {
                throw error;
            }
            return;
        }

        CompletionStage<@Unmodifiable List<GameVersionCatalogItem>> resolvedStage = Objects.requireNonNull(stage);
        try {
            resolvedStage.whenComplete((items, failure) -> completeLoad(operation, items, failure));
        } catch (RuntimeException | Error registrationFailure) {
            completeLoad(operation, null, registrationFailure);
            if (registrationFailure instanceof Error error) {
                throw error;
            }
        }
    }

    /// Commits a current source result or failure and ignores every superseded generation.
    ///
    /// @param operation generation that produced the result
    /// @param loadedItems returned catalog, or null when loading failed
    /// @param failure asynchronous failure, or null after apparent success
    private void completeLoad(
            LoadOperation operation,
            @Nullable @Unmodifiable List<GameVersionCatalogItem> loadedItems,
            @Nullable Throwable failure) {
        @Nullable Throwable resolvedFailure = failure;
        @Unmodifiable List<GameVersionCatalogItem> immutableItems = List.of();
        if (resolvedFailure == null) {
            try {
                immutableItems = immutableCatalog(Objects.requireNonNull(
                        loadedItems,
                        "catalog source returned null items"));
                operation.cancellation().throwIfCancelled();
            } catch (RuntimeException validationFailure) {
                resolvedFailure = validationFailure;
            }
        }

        @Nullable SnapshotTransition transition;
        synchronized (stateLock) {
            if (closed || activeLoad != operation || operation.generation() != sourceGeneration) {
                return;
            }
            activeLoad = null;
            ModelState current = state;
            if (resolvedFailure == null) {
                transition = commitSuccessfulLoadLocked(current, immutableItems);
            } else {
                GameVersionCatalogSnapshot previous = current.snapshot();
                boolean listEnabled = !current.content().filteredItems().isEmpty();
                GameVersionCatalogSnapshot failed = copySnapshot(
                        previous,
                        previous.selectedIndex(),
                        previous.itemCount(),
                        previous.contentRevision(),
                        GameVersionCatalogStatus.FAILED,
                        statusStrings.failedStatus(),
                        previous.query(),
                        previous.filter(),
                        listEnabled,
                        true);
                transition = replaceStateLocked(current.content(), failed);
            }
        }
        publish(transition);
    }

    /// Installs one validated master catalog and its current filtered projection.
    ///
    /// @param current state preceding the successful source result
    /// @param masterItems validated immutable master items
    /// @return committed snapshot transition
    private @Nullable SnapshotTransition commitSuccessfulLoadLocked(
            ModelState current,
            @Unmodifiable List<GameVersionCatalogItem> masterItems) {
        GameVersionCatalogSnapshot previous = current.snapshot();
        if (selectedVersionId != null && indexOf(masterItems, selectedVersionId) < 0) {
            selectedVersionId = null;
        }
        @Unmodifiable List<GameVersionCatalogItem> filteredItems = filter(
                masterItems,
                previous.query(),
                previous.filter());
        CatalogContent replacementContent = new CatalogContent(masterItems, filteredItems);
        long contentRevision = nextContentRevision(
                previous.contentRevision(),
                current.content().filteredItems(),
                filteredItems);
        GameVersionCatalogSnapshot ready = copySnapshot(
                previous,
                selectedIndex(filteredItems),
                filteredItems.size(),
                contentRevision,
                GameVersionCatalogStatus.READY,
                readyStatus(filteredItems),
                previous.query(),
                previous.filter(),
                !filteredItems.isEmpty(),
                true);
        return replaceStateLocked(replacementContent, ready);
    }

    /// Derives and commits filtered content for a replacement query and kind filter.
    ///
    /// @param query replacement retained query
    /// @param filter replacement kind filter
    /// @return committed snapshot transition
    private @Nullable SnapshotTransition replaceFilterStateLocked(
            String query,
            GameVersionFilter filter) {
        ModelState current = state;
        GameVersionCatalogSnapshot previous = current.snapshot();
        @Unmodifiable List<GameVersionCatalogItem> filteredItems = filter(
                current.content().masterItems(),
                query,
                filter);
        CatalogContent replacementContent = new CatalogContent(
                current.content().masterItems(),
                filteredItems);
        long contentRevision = nextContentRevision(
                previous.contentRevision(),
                current.content().filteredItems(),
                filteredItems);
        String statusText = previous.status() == GameVersionCatalogStatus.READY
                ? readyStatus(filteredItems)
                : previous.statusText();
        boolean listEnabled = isContentSelectable(previous.status(), filteredItems);
        GameVersionCatalogSnapshot replacement = copySnapshot(
                previous,
                selectedIndex(filteredItems),
                filteredItems.size(),
                contentRevision,
                previous.status(),
                statusText,
                query,
                filter,
                listEnabled,
                previous.refreshEnabled());
        return replaceStateLocked(replacementContent, replacement);
    }

    /// Commits immutable content and matching state under [#stateLock].
    ///
    /// @param content replacement immutable content
    /// @param snapshot replacement matching snapshot
    /// @return sequenced transition, or null when the snapshot is equal
    private @Nullable SnapshotTransition replaceStateLocked(
            CatalogContent content,
            GameVersionCatalogSnapshot snapshot) {
        GameVersionCatalogSnapshot previous = state.snapshot();
        state = new ModelState(content, snapshot);
        if (previous.equals(snapshot)) {
            return null;
        }
        return new SnapshotTransition(previous, snapshot, ++snapshotSequence);
    }

    /// Publishes one current transition outside [#stateLock] with per-listener runtime isolation.
    ///
    /// @param transition committed transition, or null when no snapshot value changed
    private void publish(@Nullable SnapshotTransition transition) {
        if (transition == null) {
            return;
        }
        synchronized (publicationLock) {
            synchronized (stateLock) {
                if (closed || transition.sequence() != snapshotSequence) {
                    return;
                }
            }
            ValueChange<GameVersionCatalogSnapshot> change = new ValueChange<>(
                    this,
                    transition.previous(),
                    transition.current());
            for (IsolatedListenerSlot listener : listeners) {
                synchronized (stateLock) {
                    if (closed || transition.sequence() != snapshotSequence) {
                        break;
                    }
                }
                listener.notifySafely(change);
            }
        }
    }

    /// Returns a validated immutable copy and rejects duplicate stable IDs.
    ///
    /// @param loadedItems source-returned catalog
    /// @return validated immutable catalog preserving source order
    private static @Unmodifiable List<GameVersionCatalogItem> immutableCatalog(
            @Unmodifiable List<GameVersionCatalogItem> loadedItems) {
        @Unmodifiable List<GameVersionCatalogItem> copy = List.copyOf(loadedItems);
        Set<String> versionIds = new HashSet<>();
        for (GameVersionCatalogItem item : copy) {
            if (!versionIds.add(item.versionId())) {
                throw new IllegalArgumentException("Duplicate game version ID: " + item.versionId());
            }
        }
        return copy;
    }

    /// Filters immutable master content by kind and a case-insensitive stable-ID query.
    ///
    /// @param masterItems immutable master content
    /// @param query retained query text
    /// @param filter selected kind filter
    /// @return immutable filtered content preserving master order
    private static @Unmodifiable List<GameVersionCatalogItem> filter(
            @Unmodifiable List<GameVersionCatalogItem> masterItems,
            String query,
            GameVersionFilter filter) {
        String normalizedQuery = query.toLowerCase(Locale.ROOT);
        List<GameVersionCatalogItem> matches = new ArrayList<>();
        for (GameVersionCatalogItem item : masterItems) {
            if (filter.includes(item.kind())
                    && item.versionId().toLowerCase(Locale.ROOT).contains(normalizedQuery)) {
                matches.add(item);
            }
        }
        return List.copyOf(matches);
    }

    /// Returns the selected stable ID's current filtered index.
    ///
    /// @param filteredItems immutable filtered content
    /// @return selected index, or empty while hidden or absent
    private OptionalInt selectedIndex(@Unmodifiable List<GameVersionCatalogItem> filteredItems) {
        if (selectedVersionId == null) {
            return OptionalInt.empty();
        }
        int index = indexOf(filteredItems, selectedVersionId);
        return index < 0 ? OptionalInt.empty() : OptionalInt.of(index);
    }

    /// Finds one stable version ID in an immutable catalog.
    ///
    /// @param items immutable catalog items
    /// @param versionId stable version ID
    /// @return matching index, or -1 when absent
    private static int indexOf(
            @Unmodifiable List<GameVersionCatalogItem> items,
            String versionId) {
        for (int index = 0; index < items.size(); index++) {
            if (items.get(index).versionId().equals(versionId)) {
                return index;
            }
        }
        return -1;
    }

    /// Increments the visible-content revision only when item content or order changes.
    ///
    /// @param currentRevision previous visible revision
    /// @param previousItems previous immutable filtered items
    /// @param replacementItems replacement immutable filtered items
    /// @return unchanged or incremented revision
    private static long nextContentRevision(
            long currentRevision,
            @Unmodifiable List<GameVersionCatalogItem> previousItems,
            @Unmodifiable List<GameVersionCatalogItem> replacementItems) {
        return previousItems.equals(replacementItems) ? currentRevision : Math.addExact(currentRevision, 1L);
    }

    /// Returns localized ready or empty text for current visible content.
    ///
    /// @param filteredItems immutable visible items
    /// @return localized successful status
    private String readyStatus(@Unmodifiable List<GameVersionCatalogItem> filteredItems) {
        return filteredItems.isEmpty() ? statusStrings.emptyStatus() : statusStrings.readyStatus();
    }

    /// Returns whether visible items may be selected in the current lifecycle.
    ///
    /// @param status current lifecycle
    /// @param filteredItems immutable visible items
    /// @return whether the list is interactive
    private static boolean isContentSelectable(
            GameVersionCatalogStatus status,
            @Unmodifiable List<GameVersionCatalogItem> filteredItems) {
        return !filteredItems.isEmpty()
                && (status == GameVersionCatalogStatus.READY || status == GameVersionCatalogStatus.FAILED);
    }

    /// Creates one catalog snapshot while retaining explicit argument names at transition sites.
    ///
    /// @param ignoredPrevious previous snapshot documenting this copy operation
    /// @param selectedIndex replacement selected index
    /// @param itemCount replacement exact item count
    /// @param contentRevision replacement content revision
    /// @param status replacement lifecycle
    /// @param statusText replacement localized status
    /// @param query replacement query
    /// @param filter replacement filter
    /// @param listEnabled replacement list enabled state
    /// @param refreshEnabled replacement refresh enabled state
    /// @return replacement immutable snapshot
    private static GameVersionCatalogSnapshot copySnapshot(
            GameVersionCatalogSnapshot ignoredPrevious,
            OptionalInt selectedIndex,
            int itemCount,
            long contentRevision,
            GameVersionCatalogStatus status,
            String statusText,
            String query,
            GameVersionFilter filter,
            boolean listEnabled,
            boolean refreshEnabled) {
        Objects.requireNonNull(ignoredPrevious, "ignoredPrevious");
        return new GameVersionCatalogSnapshot(
                selectedIndex,
                itemCount,
                contentRevision,
                status,
                statusText,
                query,
                filter,
                listEnabled,
                refreshEnabled);
    }

    /// Reports one isolated listener runtime failure without stopping later listeners.
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
            throw new IllegalStateException("Game version catalog model is closed");
        }
    }

    /// Immutable master and filtered content published as one atomic value.
    ///
    /// @param masterItems complete validated source catalog
    /// @param filteredItems query- and kind-filtered visible catalog
    @NotNullByDefault
    private record CatalogContent(
            @Unmodifiable List<GameVersionCatalogItem> masterItems,
            @Unmodifiable List<GameVersionCatalogItem> filteredItems) {
        /// Stores defensive immutable list copies.
        private CatalogContent {
            masterItems = List.copyOf(masterItems);
            filteredItems = List.copyOf(filteredItems);
        }
    }

    /// Atomically published catalog content and matching presentation state.
    ///
    /// @param content immutable master and filtered content
    /// @param snapshot matching presentation state
    @NotNullByDefault
    private record ModelState(CatalogContent content, GameVersionCatalogSnapshot snapshot) {
    }

    /// One source generation and its cooperative cancellation signal.
    ///
    /// @param generation monotonically increasing source generation
    /// @param cancellation model-owned cancellation signal
    @NotNullByDefault
    private record LoadOperation(long generation, LoadCancellation cancellation) {
    }

    /// One committed snapshot transition eligible for coalesced publication.
    ///
    /// @param previous snapshot before the transition
    /// @param current snapshot after the transition
    /// @param sequence monotonically increasing publication sequence
    @NotNullByDefault
    private record SnapshotTransition(
            GameVersionCatalogSnapshot previous,
            GameVersionCatalogSnapshot current,
            long sequence) {
    }

    /// Independently removable listener registration with runtime-failure isolation.
    @NotNullByDefault
    private static final class IsolatedListenerSlot {
        /// Listener owned by this exact registration.
        private final ValueChangeListener<GameVersionCatalogSnapshot> listener;

        /// Creates one isolated listener registration.
        ///
        /// @param listener listener to own
        private IsolatedListenerSlot(ValueChangeListener<GameVersionCatalogSnapshot> listener) {
            this.listener = Objects.requireNonNull(listener, "listener");
        }

        /// Delivers one transition while allowing later listeners to survive a runtime failure.
        ///
        /// @param change immutable catalog transition
        private void notifySafely(ValueChange<GameVersionCatalogSnapshot> change) {
            try {
                listener.onChange(change);
            } catch (RuntimeException listenerFailure) {
                reportListenerFailure(listenerFailure);
            }
        }
    }
}
