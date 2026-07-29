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
import org.junit.jupiter.api.Test;
import space.minecraftstl.xyml.image.InstanceIconData;
import space.minecraftstl.xyml.observable.Subscription;
import space.minecraftstl.xyml.observable.ValueChangeListener;
import space.minecraftstl.xyml.observable.ValueChangeSupport;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;
import space.minecraftstl.xyml.ui.swing.choice.ChoicePage;
import space.minecraftstl.xyml.ui.swing.choice.IndexRange;
import space.minecraftstl.xyml.ui.swing.choice.LoadCancellation;

import java.util.List;
import java.util.OptionalInt;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies selected-directory repository replacement, cancellation, and command routing.
@NotNullByDefault
public final class SelectedRepositoryInstancesModelTest {
    /// Neutral fixed-size icon used by model-only fixtures.
    private static final InstanceIconData TEST_ICON = new InstanceIconData(
            new int[InstanceIconData.PIXEL_COUNT]);

    /// A directory switch replaces all visible state and closes the previous repository model.
    @Test
    public void switchesRepositoryStateAndRoutesCommandsToNewDelegate() {
        FakeInstancesModel first = new FakeInstancesModel("First", "first-instance");
        FakeInstancesModel second = new FakeInstancesModel("Second", "second-instance");
        MutableModelSource source = new MutableModelSource(first);
        AtomicReference<SelectedRepositoryInstancesModel> modelReference = new AtomicReference<>();
        AtomicInteger transitions = new AtomicInteger();

        EdtDispatcher.executeAndWait(() -> {
            SelectedRepositoryInstancesModel model = new SelectedRepositoryInstancesModel(source);
            model.subscribe(change -> transitions.incrementAndGet());
            modelReference.set(model);
            assertAll(
                    () -> assertEquals("First", model.snapshot().statusText()),
                    () -> assertEquals(1L, model.selectionContextRevision()),
                    () -> assertEquals(1L, model.snapshot().contentRevision()));
            source.switchTo(second);
            model.selectInstance("second-instance");
            model.manageSelectedInstance();
        });

        SelectedRepositoryInstancesModel model = modelReference.get();
        assertAll(
                () -> assertEquals("Second", model.snapshot().statusText()),
                () -> assertEquals(2L, model.selectionContextRevision()),
                () -> assertEquals(2L, model.snapshot().contentRevision()),
                () -> assertEquals(1, transitions.get()),
                () -> assertEquals(1, first.closeCalls.get()),
                () -> assertEquals(List.of("second-instance"), second.selectedIds),
                () -> assertEquals(1, second.manageCalls.get()));

        EdtDispatcher.executeAndWait(model::close);
        assertAll(
                () -> assertEquals(1, second.closeCalls.get()),
                () -> assertFalse(source.hasSubscribers()));
    }

    /// An asynchronous page from the old repository cannot leak across a directory switch.
    @Test
    public void cancelsOldViewportAfterRepositorySwitch() {
        FakeInstancesModel first = new FakeInstancesModel("Pending", "pending-instance");
        first.deferLoads = true;
        FakeInstancesModel second = new FakeInstancesModel("Replacement", "replacement-instance");
        MutableModelSource source = new MutableModelSource(first);
        AtomicReference<SelectedRepositoryInstancesModel> modelReference = new AtomicReference<>();
        AtomicReference<CompletionStage<ChoicePage<InstanceListItem>>> loadReference = new AtomicReference<>();

        EdtDispatcher.executeAndWait(() -> {
            SelectedRepositoryInstancesModel model = new SelectedRepositoryInstancesModel(source);
            modelReference.set(model);
            loadReference.set(model.load(new IndexRange(0, 1), new LoadCancellation()));
            source.switchTo(second);
        });
        first.completeDeferredLoad();

        CompletionException failure = assertThrows(
                CompletionException.class,
                () -> loadReference.get().toCompletableFuture().join());
        assertInstanceOf(java.util.concurrent.CancellationException.class, failure.getCause());
        assertTrue(first.lastCancellation.isCancelled());
        EdtDispatcher.executeAndWait(modelReference.get()::close);
    }

    /// Mutable selected-model source used without process-wide settings initialization.
    @NotNullByDefault
    private static final class MutableModelSource
            implements SelectedRepositoryInstancesModel.SelectedModelSource {
        /// Selected-model transition publisher.
        private final ValueChangeSupport<InstancesModel> changes = new ValueChangeSupport<>(this);

        /// Current selected model.
        private InstancesModel current;

        /// Creates a source with one initial model.
        ///
        /// @param initialModel initial selected model
        private MutableModelSource(InstancesModel initialModel) {
            current = initialModel;
        }

        /// Returns the current selected model.
        @Override
        public InstancesModel current() {
            return current;
        }

        /// Registers one selected-model listener.
        ///
        /// @param listener transition listener
        /// @return removable listener registration
        @Override
        public Subscription subscribe(ValueChangeListener<InstancesModel> listener) {
            return changes.subscribe(listener);
        }

        /// Publishes a newly selected model.
        ///
        /// @param model replacement selected model
        private void switchTo(InstancesModel model) {
            InstancesModel previous = current;
            current = model;
            changes.fireChange(previous, model);
        }

        /// Returns the current subscriber count.
        ///
        /// @return exact listener count
        private boolean hasSubscribers() {
            return changes.hasSubscribers();
        }
    }

    /// Repository-specific fake model recording ownership and pending viewport behavior.
    @NotNullByDefault
    private static final class FakeInstancesModel implements InstancesModel, AutoCloseable {
        /// Stable item returned by this repository.
        private final InstanceListItem item;

        /// Stable repository snapshot.
        private final InstancesSnapshot snapshot;

        /// Selected identifiers received by this model.
        private final List<String> selectedIds = new java.util.ArrayList<>();

        /// Management invocation count.
        private final AtomicInteger manageCalls = new AtomicInteger();

        /// Close invocation count.
        private final AtomicInteger closeCalls = new AtomicInteger();

        /// Pending page completion used when loads are deferred.
        private final CompletableFuture<ChoicePage<InstanceListItem>> pendingLoad = new CompletableFuture<>();

        /// Cancellation linked to the most recent request.
        private LoadCancellation lastCancellation = new LoadCancellation();

        /// Whether viewport requests wait for explicit completion.
        private boolean deferLoads;

        /// Creates a fake model with one selected manageable item.
        ///
        /// @param status stable status text
        /// @param itemId stable item identifier
        private FakeInstancesModel(String status, String itemId) {
            item = new InstanceListItem(itemId, itemId, "Minecraft test", TEST_ICON);
            snapshot = new InstancesSnapshot(
                    OptionalInt.of(0), 1, 0L, status,
                    false, true, true, true, true);
        }

        /// Returns the stable snapshot.
        @Override
        public InstancesSnapshot snapshot() {
            return snapshot;
        }

        /// Returns an inert subscription because this fake snapshot never changes.
        ///
        /// @param listener unused listener
        /// @return removable inert registration
        @Override
        public Subscription subscribe(ValueChangeListener<InstancesSnapshot> listener) {
            return Subscription.create(() -> { });
        }

        /// Returns the exact single-item count.
        @Override
        public OptionalInt exactItemCount() {
            return OptionalInt.of(1);
        }

        /// Returns or defers the only row while retaining the linked cancellation.
        ///
        /// @param desiredRange exact requested range
        /// @param cancellation linked cancellation signal
        /// @return immediate or test-controlled page completion
        @Override
        public CompletionStage<ChoicePage<InstanceListItem>> load(
                IndexRange desiredRange,
                LoadCancellation cancellation) {
            lastCancellation = cancellation;
            if (deferLoads) {
                return pendingLoad;
            }
            return CompletableFuture.completedFuture(page());
        }

        /// Records one selected identifier.
        ///
        /// @param instanceId selected identifier
        @Override
        public void selectInstance(String instanceId) {
            selectedIds.add(instanceId);
        }

        /// Accepts a refresh command without changing state.
        @Override
        public void refreshInstances() {
        }

        /// Accepts an add command without changing state.
        @Override
        public void addInstance() {
        }

        /// Records one management command.
        @Override
        public void manageSelectedInstance() {
            manageCalls.incrementAndGet();
        }

        /// Records one model close.
        @Override
        public void close() {
            closeCalls.incrementAndGet();
        }

        /// Completes one deferred page request.
        private void completeDeferredLoad() {
            pendingLoad.complete(page());
        }

        /// Creates this model's exact single-row page.
        ///
        /// @return immutable page
        private ChoicePage<InstanceListItem> page() {
            return new ChoicePage<>(new IndexRange(0, 1), List.of(item), OptionalInt.of(1), true);
        }
    }
}
