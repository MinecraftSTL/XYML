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
package space.minecraftstl.xyml.ui.swing.page.instances;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import space.minecraftstl.xyml.event.Event;
import space.minecraftstl.xyml.event.EventManager;
import space.minecraftstl.xyml.event.RefreshedGameInstancesEvent;
import space.minecraftstl.xyml.game.GameInstanceID;
import space.minecraftstl.xyml.image.InstanceIconData;
import space.minecraftstl.xyml.observable.Subscription;
import space.minecraftstl.xyml.observable.ValueChangeListener;
import space.minecraftstl.xyml.observable.ValueChangeSupport;
import space.minecraftstl.xyml.setting.GameInstanceIconType;
import space.minecraftstl.xyml.ui.swing.choice.ChoicePage;
import space.minecraftstl.xyml.ui.swing.choice.IndexRange;
import space.minecraftstl.xyml.ui.swing.choice.LoadCancellation;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests the real-repository adapter boundary with deterministic background execution and refresh events.
@NotNullByDefault
public final class RepositoryInstancesModelTest {
    /// Localized repository text used by the adapter tests.
    private static final RepositoryInstancesStatusStrings STATUS_STRINGS =
            new RepositoryInstancesStatusStrings(
                    "Loading", "Ready", "Refreshing", "Refresh failed", "Unknown version");

    /// A viewport request resolves only its captured range and delegates stable-ID commands.
    @Test
    public void resolvesOnlyRequestedRowsAndDelegatesCommands() {
        EventManager<RefreshedGameInstancesEvent> events = new EventManager<>();
        QueuedExecutor executor = new QueuedExecutor();
        FakeRepository repository = new FakeRepository(
                events,
                List.of(instanceId("alpha"), instanceId("beta"), instanceId("gamma")),
                instanceId("beta"));
        repository.setDetail(instanceId("alpha"), "1.20.1");
        repository.setDetail(instanceId("beta"), "1.21.1");
        repository.setDetail(instanceId("gamma"), "1.19.4");
        AtomicInteger additions = new AtomicInteger();
        List<GameInstanceID> managedIds = new ArrayList<>();
        RepositoryInstancesModel model = new RepositoryInstancesModel(
                repository, events, executor, additions::incrementAndGet, managedIds::add, STATUS_STRINGS);

        CompletionStage<ChoicePage<InstanceListItem>> stage = model.load(
                new IndexRange(1, 3), new LoadCancellation());
        assertAll(
                () -> assertEquals(3, model.exactItemCount().orElseThrow()),
                () -> assertEquals(OptionalInt.of(1), model.snapshot().selectedIndex()),
                () -> assertEquals(List.of(), repository.resolvedIds()),
                () -> assertEquals(List.of(), repository.resolvedIconIds()),
                () -> assertFalse(stage.toCompletableFuture().isDone()));

        executor.runNext();
        ChoicePage<InstanceListItem> page = stage.toCompletableFuture().join();
        model.selectInstance(instanceId("alpha"));
        model.addInstance();
        model.manageSelectedInstance();

        assertAll(
                () -> assertEquals(new IndexRange(1, 3), page.range()),
                () -> assertEquals(List.of(instanceId("beta"), instanceId("gamma")),
                        page.items().stream().map(InstanceListItem::id).toList()),
                () -> assertEquals(List.of(instanceId("beta"), instanceId("gamma")), repository.resolvedIds()),
                () -> assertEquals(
                        List.of(instanceId("beta"), instanceId("gamma")),
                        repository.resolvedIconIds()),
                () -> assertEquals(instanceId("alpha"), repository.selectedInstanceId()),
                () -> assertEquals(OptionalInt.of(0), model.snapshot().selectedIndex()),
                () -> assertEquals(1, additions.get()),
                () -> assertEquals(List.of(instanceId("alpha")), managedIds));
        model.close();
    }

    /// An icon event invalidates sparse rows without eagerly resolving icons outside a requested range.
    @Test
    public void iconChangeReloadsOnlyLaterViewportDemand() {
        EventManager<RefreshedGameInstancesEvent> events = new EventManager<>();
        QueuedExecutor executor = new QueuedExecutor();
        FakeRepository repository = new FakeRepository(
                events,
                List.of(instanceId("alpha"), instanceId("beta"), instanceId("gamma")),
                instanceId("alpha"));
        RepositoryInstancesModel model = new RepositoryInstancesModel(
                repository, events, executor, () -> { }, ignored -> { }, STATUS_STRINGS);

        CompletionStage<ChoicePage<InstanceListItem>> initialLoad = model.load(
                new IndexRange(0, 1), new LoadCancellation());
        executor.runNext();
        InstanceIconData initialIcon = initialLoad.toCompletableFuture().join().items().get(0).icon();

        repository.fireIconChanged();
        CompletionStage<ChoicePage<InstanceListItem>> replacementLoad = model.load(
                new IndexRange(1, 2), new LoadCancellation());
        executor.runNext();
        InstanceIconData replacementIcon = replacementLoad.toCompletableFuture().join().items().get(0).icon();

        assertAll(
                () -> assertEquals(1L, model.snapshot().contentRevision()),
                () -> assertEquals(
                        List.of(instanceId("alpha"), instanceId("beta")),
                        repository.resolvedIconIds()),
                () -> assertFalse(initialIcon.equals(replacementIcon)));
        model.close();
    }

    /// A successful refresh event replaces exact source order and increments one content revision.
    @Test
    public void appliesRefreshEventAndNewSourceBoundary() {
        EventManager<RefreshedGameInstancesEvent> events = new EventManager<>();
        QueuedExecutor executor = new QueuedExecutor();
        FakeRepository repository = new FakeRepository(
                events,
                List.of(instanceId("alpha"), instanceId("beta")),
                instanceId("alpha"));
        repository.prepareRefresh(
                List.of(instanceId("beta"), instanceId("gamma"), instanceId("delta")),
                instanceId("gamma"),
                true,
                false);
        RepositoryInstancesModel model = new RepositoryInstancesModel(
                repository, events, executor, () -> { }, ignored -> { }, STATUS_STRINGS);
        List<InstancesSnapshot> transitions = new ArrayList<>();
        Subscription listener = model.subscribe(change -> transitions.add(change.currentValue()));

        model.refreshInstances();
        assertAll(
                () -> assertTrue(model.snapshot().refreshing()),
                () -> assertFalse(model.snapshot().refreshEnabled()),
                () -> assertEquals("Refreshing", model.snapshot().statusText()));

        executor.runNext();

        assertAll(
                () -> assertEquals(3, model.exactItemCount().orElseThrow()),
                () -> assertEquals(1L, model.snapshot().contentRevision()),
                () -> assertEquals(OptionalInt.of(1), model.snapshot().selectedIndex()),
                () -> assertEquals("Ready", model.snapshot().statusText()),
                () -> assertFalse(model.snapshot().refreshing()),
                () -> assertTrue(model.snapshot().refreshEnabled()),
                () -> assertEquals(3, transitions.size()));
        listener.unsubscribe();
        model.close();
    }

    /// A refresh denied without an event still rebuilds from repository state on successful completion.
    @Test
    public void fallsBackWhenSuccessfulRefreshPublishesNoEvent() {
        EventManager<RefreshedGameInstancesEvent> events = new EventManager<>();
        QueuedExecutor executor = new QueuedExecutor();
        FakeRepository repository = new FakeRepository(
                events,
                List.of(instanceId("alpha")),
                instanceId("alpha"));
        repository.prepareRefresh(
                List.of(instanceId("alpha"), instanceId("beta")),
                instanceId("beta"),
                false,
                false);
        RepositoryInstancesModel model = new RepositoryInstancesModel(
                repository, events, executor, () -> { }, ignored -> { }, STATUS_STRINGS);

        model.refreshInstances();
        executor.runNext();

        assertAll(
                () -> assertEquals(2, model.exactItemCount().orElseThrow()),
                () -> assertEquals(1L, model.snapshot().contentRevision()),
                () -> assertEquals(OptionalInt.of(1), model.snapshot().selectedIndex()),
                () -> assertEquals("Ready", model.snapshot().statusText()));
        model.close();
    }

    /// Refresh failure restores retry controls without mutating indexed content.
    @Test
    public void exposesRefreshFailureWithoutChangingRevision() {
        EventManager<RefreshedGameInstancesEvent> events = new EventManager<>();
        QueuedExecutor executor = new QueuedExecutor();
        FakeRepository repository = new FakeRepository(
                events,
                List.of(instanceId("alpha"), instanceId("beta")),
                instanceId("beta"));
        repository.prepareRefresh(List.of(instanceId("ignored")), null, false, true);
        RepositoryInstancesModel model = new RepositoryInstancesModel(
                repository, events, executor, () -> { }, ignored -> { }, STATUS_STRINGS);

        model.refreshInstances();
        executor.runNext();

        assertAll(
                () -> assertEquals(2, model.exactItemCount().orElseThrow()),
                () -> assertEquals(0L, model.snapshot().contentRevision()),
                () -> assertEquals("Refresh failed", model.snapshot().statusText()),
                () -> assertFalse(model.snapshot().refreshing()),
                () -> assertTrue(model.snapshot().refreshEnabled()),
                () -> assertTrue(model.snapshot().manageEnabled()));
        model.close();
    }

    /// Closing removes the refresh listener, rejects commands, and makes queued range work cancellable.
    @Test
    public void closeStopsEventsAndCancelledLoads() {
        EventManager<RefreshedGameInstancesEvent> events = new EventManager<>();
        QueuedExecutor executor = new QueuedExecutor();
        FakeRepository repository = new FakeRepository(
                events,
                List.of(instanceId("alpha"), instanceId("beta")),
                instanceId("alpha"));
        RepositoryInstancesModel model = new RepositoryInstancesModel(
                repository, events, executor, () -> { }, ignored -> { }, STATUS_STRINGS);
        LoadCancellation cancellation = new LoadCancellation();
        CompletionStage<ChoicePage<InstanceListItem>> load = model.load(new IndexRange(0, 2), cancellation);
        InstancesSnapshot beforeClose = model.snapshot();

        cancellation.cancel();
        model.close();
        repository.replaceImmediately(List.of(instanceId("gamma")), instanceId("gamma"));
        events.fireEvent(new RefreshedGameInstancesEvent(repository));
        repository.fireIconChanged();
        repository.setSelectedInstanceId(instanceId("beta"));
        executor.runNext();

        assertAll(
                () -> assertEquals(beforeClose, model.snapshot()),
                () -> assertTrue(load.toCompletableFuture().isCompletedExceptionally()),
                () -> assertThrows(IllegalStateException.class, model::addInstance),
                    () -> assertThrows(CompletionException.class, () -> load.toCompletableFuture().join()));
    }

    /// An unloaded model waits for the existing initial scan instead of starting a competing scan.
    @Test
    public void waitsForExistingInitialScan() {
        EventManager<RefreshedGameInstancesEvent> events = new EventManager<>();
        QueuedExecutor executor = new QueuedExecutor();
        FakeRepository repository = new FakeRepository(events, List.of(), null);
        repository.setLoaded(false);
        RepositoryInstancesModel model = new RepositoryInstancesModel(
                repository, events, executor, () -> { }, ignored -> { }, STATUS_STRINGS);

        assertAll(
                () -> assertEquals(0, executor.pendingCount()),
                () -> assertEquals(0, repository.refreshCalls.get()),
                () -> assertEquals("Loading", model.snapshot().statusText()),
                () -> assertFalse(model.snapshot().refreshEnabled()));

        repository.replaceImmediately(
                List.of(instanceId("beta"), instanceId("gamma")),
                instanceId("missing"));
        repository.setLoaded(true);
        events.fireEvent(new RefreshedGameInstancesEvent(repository));

        assertAll(
                () -> assertEquals(2, model.snapshot().itemCount()),
                () -> assertEquals(OptionalInt.empty(), model.snapshot().selectedIndex()),
                () -> assertEquals(1L, model.snapshot().contentRevision()),
                () -> assertEquals("Ready", model.snapshot().statusText()));

        repository.setSelectedInstanceId(instanceId("beta"));
        assertEquals(OptionalInt.of(0), model.snapshot().selectedIndex());
        model.close();
    }

    /// A listener closing the model during the refreshing transition prevents repository I/O submission.
    @Test
    public void listenerClosePreventsRefreshIo() {
        EventManager<RefreshedGameInstancesEvent> events = new EventManager<>();
        QueuedExecutor executor = new QueuedExecutor();
        FakeRepository repository = new FakeRepository(
                events,
                List.of(instanceId("alpha")),
                instanceId("alpha"));
        RepositoryInstancesModel model = new RepositoryInstancesModel(
                repository, events, executor, () -> { }, ignored -> { }, STATUS_STRINGS);
        model.subscribe(change -> {
            if (change.currentValue() != null && change.currentValue().refreshing()) {
                model.close();
            }
        });

        model.refreshInstances();

        assertAll(
                () -> assertEquals(0, executor.pendingCount()),
                () -> assertEquals(0, repository.refreshCalls.get()),
                () -> assertThrows(IllegalStateException.class, model::addInstance));
    }

    /// An external event updates content but does not release a model-owned refresh slot early.
    @Test
    public void externalEventDoesNotReleaseOwnedRefresh() {
        EventManager<RefreshedGameInstancesEvent> events = new EventManager<>();
        QueuedExecutor executor = new QueuedExecutor();
        FakeRepository repository = new FakeRepository(
                events,
                List.of(instanceId("alpha")),
                instanceId("alpha"));
        repository.prepareRefresh(
                List.of(instanceId("gamma")),
                instanceId("gamma"),
                false,
                false);
        RepositoryInstancesModel model = new RepositoryInstancesModel(
                repository, events, executor, () -> { }, ignored -> { }, STATUS_STRINGS);

        model.refreshInstances();
        repository.replaceImmediately(List.of(instanceId("beta")), instanceId("beta"));
        events.fireEvent(new RefreshedGameInstancesEvent(repository));
        model.refreshInstances();

        assertAll(
                () -> assertEquals(1, executor.pendingCount()),
                () -> assertTrue(model.snapshot().refreshing()),
                () -> assertFalse(model.snapshot().refreshEnabled()),
                () -> assertEquals(1L, model.snapshot().contentRevision()));

        executor.runNext();

        assertAll(
                () -> assertEquals(1, repository.refreshCalls.get()),
                () -> assertFalse(model.snapshot().refreshing()),
                () -> assertTrue(model.snapshot().refreshEnabled()),
                () -> assertEquals(2L, model.snapshot().contentRevision()),
                () -> assertEquals(OptionalInt.of(0), model.snapshot().selectedIndex()));
        model.close();
    }

    /// An old revision load uses its captured detail even after a newer repository event replaces content.
    @Test
    public void oldRevisionLoadUsesCapturedDescriptor() {
        EventManager<RefreshedGameInstancesEvent> events = new EventManager<>();
        QueuedExecutor executor = new QueuedExecutor();
        FakeRepository repository = new FakeRepository(
                events,
                List.of(instanceId("alpha")),
                instanceId("alpha"));
        repository.setDetail(instanceId("alpha"), "1.20.1");
        RepositoryInstancesModel model = new RepositoryInstancesModel(
                repository, events, executor, () -> { }, ignored -> { }, STATUS_STRINGS);
        CompletionStage<ChoicePage<InstanceListItem>> oldLoad = model.load(
                new IndexRange(0, 1), new LoadCancellation());

        repository.setDetail(instanceId("alpha"), "1.21.1");
        events.fireEvent(new RefreshedGameInstancesEvent(repository));
        executor.runNext();

        assertAll(
                () -> assertEquals("1.20.1", oldLoad.toCompletableFuture().join().items().get(0).detail()),
                () -> assertEquals(1L, model.snapshot().contentRevision()));
        model.close();
    }

    /// Creates one stable game-instance identifier from a fixture literal.
    ///
    /// @param value serialized fixture identifier
    /// @return immutable fixture identifier
    private static GameInstanceID instanceId(String value) {
        return new GameInstanceID(value);
    }

    /// Deterministic executor that queues work until a test explicitly advances it.
    @NotNullByDefault
    private static final class QueuedExecutor implements Executor {
        /// Pending work in submission order.
        private final List<Runnable> pending = new ArrayList<>();

        /// Queues one command without running it inline.
        ///
        /// @param command submitted command
        @Override
        public synchronized void execute(Runnable command) {
            pending.add(command);
        }

        /// Runs the oldest pending command on the calling test thread.
        private void runNext() {
            Runnable command;
            synchronized (this) {
                if (pending.isEmpty()) {
                    throw new IllegalStateException("No queued executor command");
                }
                command = pending.remove(0);
            }
            command.run();
        }

        /// Returns the number of commands waiting for explicit execution.
        ///
        /// @return pending command count
        private synchronized int pendingCount() {
            return pending.size();
        }
    }

    /// Mutable fake implementing only repository behavior required by the production adapter.
    @NotNullByDefault
    private static final class FakeRepository implements RepositoryInstancesModel.RepositoryAccess {
        /// Event manager used to publish configured successful refreshes.
        private final EventManager<RefreshedGameInstancesEvent> events;

        /// Current immutable displayed IDs.
        private volatile @Unmodifiable List<GameInstanceID> displayedIds;

        /// Current selected ID, or null for none.
        private volatile @Nullable GameInstanceID selectedId;

        /// Resolved game-version detail by stable instance ID.
        private final Map<GameInstanceID, String> details = new HashMap<>();

        /// Toolkit-neutral selected-instance transition publisher.
        private final ValueChangeSupport<GameInstanceID> selectionChanges = new ValueChangeSupport<>(this);

        /// Repository-local icon transition publisher.
        private final EventManager<Event> iconEvents = new EventManager<>();

        /// IDs whose details were requested.
        private final List<GameInstanceID> resolvedIds = new ArrayList<>();

        /// IDs whose normalized icons were requested.
        private final List<GameInstanceID> resolvedIconIds = new ArrayList<>();

        /// IDs installed by the next successful refresh.
        private @Unmodifiable List<GameInstanceID> nextDisplayedIds = List.of();

        /// Selection installed by the next successful refresh, or null for none.
        private @Nullable GameInstanceID nextSelectedId;

        /// Whether the next successful refresh publishes its normal event.
        private boolean publishRefreshEvent = true;

        /// Whether the next refresh fails.
        private boolean failRefresh;

        /// Number of blocking repository refresh calls.
        private final AtomicInteger refreshCalls = new AtomicInteger();

        /// Whether an initial repository scan has completed.
        private boolean loaded = true;

        /// Creates a loaded fake repository.
        ///
        /// @param events refresh event manager
        /// @param displayedIds initial immutable displayed IDs
        /// @param selectedId initial selected ID, or null for none
        private FakeRepository(
                EventManager<RefreshedGameInstancesEvent> events,
                @Unmodifiable List<GameInstanceID> displayedIds,
                @Nullable GameInstanceID selectedId) {
            this.events = events;
            this.displayedIds = List.copyOf(displayedIds);
            this.selectedId = selectedId;
        }

        /// Returns this fake as its event source identity.
        @Override
        public Object eventSource() {
            return this;
        }

        /// Returns whether the initial fake scan completed.
        @Override
        public boolean isLoaded() {
            return loaded;
        }

        /// Captures immutable displayed entries and their current detail results.
        @Override
        public synchronized @Unmodifiable List<RepositoryInstancesModel.RepositoryEntry> displayedInstances() {
            List<RepositoryInstancesModel.RepositoryEntry> entries = new ArrayList<>();
            for (GameInstanceID instanceId : displayedIds) {
                Optional<String> capturedDetail = Optional.ofNullable(details.get(instanceId));
                InstancePresentation capturedPresentation = new InstancePresentation(
                        capturedDetail.orElse(STATUS_STRINGS.unknownVersionDetail()),
                        GameInstanceIconType.DEFAULT);
                entries.add(new RepositoryInstancesModel.RepositoryEntry(
                        instanceId,
                        List.of(instanceId, capturedDetail),
                        () -> resolveCapturedPresentation(instanceId, capturedPresentation)));
            }
            return List.copyOf(entries);
        }

        /// Returns the current selected ID.
        @Override
        public @Nullable GameInstanceID selectedInstanceId() {
            return selectedId;
        }

        /// Registers for fake repository selection transitions.
        @Override
        public Subscription subscribeSelectedInstance(ValueChangeListener<GameInstanceID> listener) {
            return selectionChanges.subscribe(listener);
        }

        /// Registers for fake repository icon transitions.
        ///
        /// @param listener no-argument icon transition listener
        /// @return independently cancellable listener registration
        @Override
        public Subscription subscribeIconChanges(Runnable listener) {
            return iconEvents.subscribe(listener);
        }

        /// Records and returns deterministic non-transparent pixels for one demanded row.
        ///
        /// @param instanceId stable instance ID
        /// @param defaultIconType automatic icon derived from the captured presentation
        /// @return immutable normalized icon pixels
        @Override
        public synchronized InstanceIconData resolveIcon(
                GameInstanceID instanceId,
                GameInstanceIconType defaultIconType) {
            resolvedIconIds.add(instanceId);
            int[] pixels = new int[InstanceIconData.PIXEL_COUNT];
            int color = 0xFF000000 | (instanceId.hashCode() & 0x00FFFFFF);
            for (int index = 0; index < pixels.length; index++) {
                pixels[index] = color;
            }
            return new InstanceIconData(pixels);
        }

        /// Stores the selected ID.
        @Override
        public void setSelectedInstanceId(GameInstanceID instanceId) {
            @Nullable GameInstanceID previous = selectedId;
            selectedId = instanceId;
            selectionChanges.fireChange(previous, instanceId);
        }

        /// Records and returns one revision-captured instance presentation.
        ///
        /// @param instanceId stable instance ID
        /// @param capturedPresentation presentation captured with the source revision
        /// @return captured presentation
        private synchronized InstancePresentation resolveCapturedPresentation(
                GameInstanceID instanceId,
                InstancePresentation capturedPresentation) {
            resolvedIds.add(instanceId);
            return capturedPresentation;
        }

        /// Applies the configured refresh or throws its configured failure.
        @Override
        public void refresh() {
            refreshCalls.incrementAndGet();
            if (failRefresh) {
                throw new CompletionException(new IOException("scan failed"));
            }
            replaceImmediately(nextDisplayedIds, nextSelectedId);
            loaded = true;
            if (publishRefreshEvent) {
                events.fireEvent(new RefreshedGameInstancesEvent(this));
            }
        }

        /// Configures resolved detail for one stable ID.
        ///
        /// @param instanceId stable instance ID
        /// @param detail resolved game version
        private void setDetail(GameInstanceID instanceId, String detail) {
            details.put(instanceId, detail);
        }

        /// Replaces whether the fake has completed its initial scan.
        ///
        /// @param loaded whether initial content is available
        private void setLoaded(boolean loaded) {
            this.loaded = loaded;
        }

        /// Returns an immutable snapshot of detail-resolution calls.
        ///
        /// @return resolved stable IDs in call order
        private synchronized @Unmodifiable List<GameInstanceID> resolvedIds() {
            return List.copyOf(resolvedIds);
        }

        /// Returns an immutable snapshot of icon-resolution calls.
        ///
        /// @return resolved stable IDs in call order
        private synchronized @Unmodifiable List<GameInstanceID> resolvedIconIds() {
            return List.copyOf(resolvedIconIds);
        }

        /// Publishes one repository-local icon transition.
        private void fireIconChanged() {
            iconEvents.fireEvent(new Event(this));
        }

        /// Configures the next refresh outcome.
        ///
        /// @param displayedIds replacement displayed IDs
        /// @param selectedId replacement selected ID, or null for none
        /// @param publishEvent whether success publishes a refresh event
        /// @param fail whether refresh throws
        private void prepareRefresh(
                @Unmodifiable List<GameInstanceID> displayedIds,
                @Nullable GameInstanceID selectedId,
                boolean publishEvent,
                boolean fail) {
            nextDisplayedIds = List.copyOf(displayedIds);
            nextSelectedId = selectedId;
            publishRefreshEvent = publishEvent;
            failRefresh = fail;
        }

        /// Replaces current repository state without publishing an event.
        ///
        /// @param displayedIds replacement immutable displayed IDs
        /// @param selectedId replacement selected ID, or null for none
        private void replaceImmediately(
                @Unmodifiable List<GameInstanceID> displayedIds,
                @Nullable GameInstanceID selectedId) {
            this.displayedIds = List.copyOf(displayedIds);
            this.selectedId = selectedId;
        }
    }
}
