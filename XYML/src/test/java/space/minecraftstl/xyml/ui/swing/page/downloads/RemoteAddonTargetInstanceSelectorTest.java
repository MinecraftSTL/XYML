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
import org.junit.jupiter.api.Test;
import space.minecraftstl.xyml.game.GameInstanceID;
import space.minecraftstl.xyml.observable.Subscription;
import space.minecraftstl.xyml.observable.ValueChange;
import space.minecraftstl.xyml.observable.ValueChangeListener;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;
import space.minecraftstl.xyml.ui.swing.choice.ChoicePage;
import space.minecraftstl.xyml.ui.swing.choice.IndexRange;
import space.minecraftstl.xyml.ui.swing.choice.LoadCancellation;
import space.minecraftstl.xyml.ui.swing.page.instances.InstanceListItem;
import space.minecraftstl.xyml.ui.swing.page.instances.InstanceSearchEntry;
import space.minecraftstl.xyml.ui.swing.page.instances.InstancesModel;
import space.minecraftstl.xyml.ui.swing.page.instances.InstancesSnapshot;

import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/// Tests the download page's local target choice independently from global instance selection.
@NotNullByDefault
public final class RemoteAddonTargetInstanceSelectorTest {
    /// First stable instance used by selection transitions.
    private static final GameInstanceID FIRST_ID = new GameInstanceID("first");

    /// Second stable instance used by selection transitions.
    private static final GameInstanceID SECOND_ID = new GameInstanceID("second");

    /// Uses the model-selected index when the local selector is first created.
    @Test
    public void initiallyUsesModelSelectedIndex() {
        EdtDispatcher.executeAndWait(() -> {
            FakeInstancesModel model = new FakeInstancesModel(entries(FIRST_ID, SECOND_ID), 1);
            try (RemoteAddonTargetInstanceSelector selector = new RemoteAddonTargetInstanceSelector(model)) {
                selector.start();
                assertEquals(SECOND_ID, selector.selectedInstanceId());
                assertEquals(SECOND_ID, selectedEntry(selector).stableId());
            }
        });
    }

    /// Keeps an explicit page-local selection out of the shared instance model.
    @Test
    public void userSelectionDoesNotChangeGlobalModelSelection() {
        EdtDispatcher.executeAndWait(() -> {
            FakeInstancesModel model = new FakeInstancesModel(entries(FIRST_ID, SECOND_ID), 1);
            try (RemoteAddonTargetInstanceSelector selector = new RemoteAddonTargetInstanceSelector(model)) {
                selector.start();
                selector.component().setSelectedIndex(0);

                assertEquals(FIRST_ID, selector.selectedInstanceId());
                assertEquals(0, model.selectCalls());
                assertEquals(OptionalInt.of(1), model.snapshot().selectedIndex());
            }
        });
    }

    /// Retains a still-present local selection across content refreshes in the same directory context.
    @Test
    public void sameContextRefreshRetainsLocalSelection() {
        EdtDispatcher.executeAndWait(() -> {
            FakeInstancesModel model = new FakeInstancesModel(entries(FIRST_ID, SECOND_ID), 1);
            try (RemoteAddonTargetInstanceSelector selector = new RemoteAddonTargetInstanceSelector(model)) {
                selector.start();
                selector.component().setSelectedIndex(0);

                model.publish(List.of(entry(SECOND_ID, "Second"), entry(FIRST_ID, "First")), 0, 0L);

                assertEquals(FIRST_ID, selector.selectedInstanceId());
                assertEquals(FIRST_ID, selectedEntry(selector).stableId());
            }
        });
    }

    /// Falls back to the model selection when an ordinary refresh removes the local target.
    @Test
    public void removedLocalSelectionFallsBackToModelSelection() {
        EdtDispatcher.executeAndWait(() -> {
            FakeInstancesModel model = new FakeInstancesModel(entries(FIRST_ID, SECOND_ID), 1);
            try (RemoteAddonTargetInstanceSelector selector = new RemoteAddonTargetInstanceSelector(model)) {
                selector.start();
                selector.component().setSelectedIndex(0);

                model.publish(List.of(entry(SECOND_ID, "Second")), 0, 0L);

                assertEquals(SECOND_ID, selector.selectedInstanceId());
                assertEquals(SECOND_ID, selectedEntry(selector).stableId());
            }
        });
    }

    /// Resets to the new directory's model selection even when the old ID and display name still exist.
    @Test
    public void contextRevisionResetsAnOtherwiseRetainableSelection() {
        EdtDispatcher.executeAndWait(() -> {
            FakeInstancesModel model = new FakeInstancesModel(
                    List.of(entry(FIRST_ID, "Same name"), entry(SECOND_ID, "Same name")),
                    1);
            try (RemoteAddonTargetInstanceSelector selector = new RemoteAddonTargetInstanceSelector(model)) {
                selector.start();
                selector.component().setSelectedIndex(0);

                model.publish(
                        List.of(entry(FIRST_ID, "Same name"), entry(SECOND_ID, "Same name")),
                        1,
                        1L);

                assertEquals(SECOND_ID, selector.selectedInstanceId());
                assertEquals(SECOND_ID, selectedEntry(selector).stableId());
            }
        });
    }

    /// Ignores an already captured model callback that arrives after selector cleanup.
    @Test
    public void closeRejectsLateModelNotification() {
        EdtDispatcher.executeAndWait(() -> {
            FakeInstancesModel model = new FakeInstancesModel(entries(FIRST_ID, SECOND_ID), 1);
            RemoteAddonTargetInstanceSelector selector = new RemoteAddonTargetInstanceSelector(model);
            selector.start();
            selector.close();

            model.publishLate(List.of(entry(FIRST_ID, "First")), 0, 1L);

            assertEquals(SECOND_ID, selector.selectedInstanceId());
            assertEquals(SECOND_ID, selectedEntry(selector).stableId());
            assertEquals(2, selector.component().getItemCount());
            assertFalse(model.isSubscribed());
        });
    }

    /// Returns the required selected combo-box entry.
    ///
    /// @param selector selector under test
    /// @return selected instance entry
    private static InstanceSearchEntry selectedEntry(RemoteAddonTargetInstanceSelector selector) {
        return (InstanceSearchEntry) Objects.requireNonNull(selector.component().getSelectedItem());
    }

    /// Creates two named entries in stable source order.
    ///
    /// @param first first stable instance ID
    /// @param second second stable instance ID
    /// @return immutable two-entry list
    private static @Unmodifiable List<InstanceSearchEntry> entries(
            GameInstanceID first,
            GameInstanceID second) {
        return List.of(entry(first, "First"), entry(second, "Second"));
    }

    /// Creates one cheap instance identity.
    ///
    /// @param id stable instance ID
    /// @param name visible instance name
    /// @return immutable search entry
    private static InstanceSearchEntry entry(GameInstanceID id, String name) {
        return new InstanceSearchEntry(Objects.requireNonNull(id, "id"), Objects.requireNonNull(name, "name"));
    }

    /// Mutable exact-count model with explicitly delivered snapshot transitions.
    @NotNullByDefault
    private static final class FakeInstancesModel implements InstancesModel {
        /// Current source-aligned search entries.
        private @Unmodifiable List<InstanceSearchEntry> entries;

        /// Current immutable instance snapshot.
        private InstancesSnapshot snapshot;

        /// Current directory-selection context revision.
        private long selectionContextRevision;

        /// Captured selector listener retained to emulate an already in-flight callback.
        private @Nullable ValueChangeListener<InstancesSnapshot> listener;

        /// Whether the returned subscription remains active.
        private boolean subscribed;

        /// Number of forbidden shared selection mutations.
        private int selectCalls;

        /// Monotonic content revision used for replacement snapshots.
        private long contentRevision;

        /// Creates a model with one current global selection.
        ///
        /// @param entries initial immutable entries
        /// @param selectedIndex initially selected source index
        private FakeInstancesModel(
                @Unmodifiable List<InstanceSearchEntry> entries,
                int selectedIndex) {
            this.entries = List.copyOf(entries);
            snapshot = snapshot(selectedIndex, this.entries.size(), contentRevision);
        }

        /// Returns the latest exact snapshot.
        @Override
        public InstancesSnapshot snapshot() {
            return snapshot;
        }

        /// Captures the selector listener and records subscription cleanup.
        ///
        /// @param listener snapshot transition listener
        /// @return independently cancellable registration
        @Override
        public Subscription subscribe(ValueChangeListener<InstancesSnapshot> listener) {
            this.listener = Objects.requireNonNull(listener, "listener");
            subscribed = true;
            return Subscription.create(() -> subscribed = false);
        }

        /// Reports the current exact item count.
        @Override
        public OptionalInt exactItemCount() {
            return OptionalInt.of(entries.size());
        }

        /// Returns current cheap identities in exact source order.
        @Override
        public @Unmodifiable List<InstanceSearchEntry> searchEntries() {
            return entries;
        }

        /// Returns an unused failed row-load stage for this selector-only fake.
        ///
        /// @param desiredRange unused requested range
        /// @param cancellation unused cancellation signal
        /// @return failed stage because this fake supplies identities only
        @Override
        public CompletionStage<ChoicePage<InstanceListItem>> load(
                IndexRange desiredRange,
                LoadCancellation cancellation) {
            Objects.requireNonNull(desiredRange, "desiredRange");
            Objects.requireNonNull(cancellation, "cancellation");
            return CompletableFuture.failedFuture(new UnsupportedOperationException("Rows are not loaded"));
        }

        /// Records an unexpected mutation of the shared global selection.
        ///
        /// @param instanceId selected stable identifier
        @Override
        public void selectInstance(GameInstanceID instanceId) {
            Objects.requireNonNull(instanceId, "instanceId");
            selectCalls++;
        }

        /// Accepts an unused refresh command.
        @Override
        public void refreshInstances() {
        }

        /// Accepts an unused add command.
        @Override
        public void addInstance() {
        }

        /// Accepts an unused management command.
        @Override
        public void manageSelectedInstance() {
        }

        /// Returns the current directory-selection context revision.
        @Override
        public long selectionContextRevision() {
            return selectionContextRevision;
        }

        /// Publishes a normal subscribed transition.
        ///
        /// @param replacement replacement identities
        /// @param selectedIndex model-selected source index
        /// @param contextRevision replacement directory context revision
        private void publish(
                @Unmodifiable List<InstanceSearchEntry> replacement,
                int selectedIndex,
                long contextRevision) {
            replace(replacement, selectedIndex, contextRevision, false);
        }

        /// Delivers a captured callback even after its subscription has been cancelled.
        ///
        /// @param replacement replacement identities
        /// @param selectedIndex model-selected source index
        /// @param contextRevision replacement directory context revision
        private void publishLate(
                @Unmodifiable List<InstanceSearchEntry> replacement,
                int selectedIndex,
                long contextRevision) {
            replace(replacement, selectedIndex, contextRevision, true);
        }

        /// Replaces exact state and optionally delivers a cancelled in-flight callback.
        ///
        /// @param replacement replacement identities
        /// @param selectedIndex model-selected source index
        /// @param contextRevision replacement directory context revision
        /// @param deliverWhenClosed whether to invoke the captured listener after unsubscription
        private void replace(
                @Unmodifiable List<InstanceSearchEntry> replacement,
                int selectedIndex,
                long contextRevision,
                boolean deliverWhenClosed) {
            InstancesSnapshot previous = snapshot;
            entries = List.copyOf(replacement);
            contentRevision++;
            snapshot = snapshot(selectedIndex, entries.size(), contentRevision);
            selectionContextRevision = contextRevision;
            @Nullable ValueChangeListener<InstancesSnapshot> captured = listener;
            if (captured != null && (subscribed || deliverWhenClosed)) {
                captured.onChange(new ValueChange<>(this, previous, snapshot));
            }
        }

        /// Returns how many shared-selection commands were received.
        ///
        /// @return selection command count
        private int selectCalls() {
            return selectCalls;
        }

        /// Returns whether the selector listener remains subscribed.
        ///
        /// @return true while subscribed
        private boolean isSubscribed() {
            return subscribed;
        }

        /// Creates one enabled exact-count snapshot.
        ///
        /// @param selectedIndex selected source index
        /// @param itemCount exact item count
        /// @param contentRevision matching content revision
        /// @return immutable enabled snapshot
        private static InstancesSnapshot snapshot(
                int selectedIndex,
                int itemCount,
                long contentRevision) {
            return new InstancesSnapshot(
                    OptionalInt.of(selectedIndex),
                    itemCount,
                    contentRevision,
                    "Ready",
                    false,
                    true,
                    true,
                    true,
                    true);
        }
    }
}
