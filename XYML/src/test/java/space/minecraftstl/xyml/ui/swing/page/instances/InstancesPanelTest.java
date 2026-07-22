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
import space.minecraftstl.xyml.observable.Subscription;
import space.minecraftstl.xyml.observable.ValueChangeListener;
import space.minecraftstl.xyml.observable.ValueChangeSupport;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;
import space.minecraftstl.xyml.ui.swing.choice.ChoiceListEntry;
import space.minecraftstl.xyml.ui.swing.choice.ChoicePage;
import space.minecraftstl.xyml.ui.swing.choice.IndexRange;
import space.minecraftstl.xyml.ui.swing.choice.LoadCancellation;

import javax.swing.AbstractButton;
import javax.swing.JList;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests installed-instance commands, placeholder selection, dynamic reload, and viewport-sized demand.
@NotNullByDefault
public final class InstancesPanelTest {
    /// Localized strings used by the focused page tests.
    private static final InstancesStrings STRINGS = new InstancesStrings(
            "Instances", "Refresh", "Refreshing", "Add", "Manage", "No installed instances");

    /// Loaded rows delegate commands once and request only the rows measured as visible.
    @Test
    public void delegatesCommandsAndUsesMeasuredVisibleRange() {
        FakeInstancesModel model = FakeInstancesModel.immediate(items(1_000), snapshot(0, 0L));
        InstancesPanel panel = onEventDispatchThread(() -> new InstancesPanel(model, STRINGS));

        onEventDispatchThread(() -> {
            panel.setSize(new Dimension(820, 520));
            layoutRecursively(panel);
            panel.choiceList().refreshLoadPlan();

            JList<ChoiceListEntry<InstanceListItem>> list = panel.choiceList().getList();
            IndexRange requested = model.requestedRanges().get(0);
            int viewportHeight = panel.choiceList().getViewport().getExtentSize().height;
            int measuredRowHeight = list.getFixedCellHeight();
            int expectedVisibleRows = (viewportHeight + measuredRowHeight - 1) / measuredRowHeight;

            list.setSelectedIndex(1);
            findButton(panel, "instancesRefresh").doClick();
            findButton(panel, "instancesAdd").doClick();
            findButton(panel, "instancesManage").doClick();

            assertAll(
                    () -> assertEquals(javax.swing.ListSelectionModel.SINGLE_SELECTION,
                            list.getSelectionMode()),
                    () -> assertEquals(expectedVisibleRows, requested.length()),
                    () -> assertTrue(requested.length() < model.exactItemCount().orElseThrow()),
                    () -> assertEquals(List.of("instance-1"), model.selectedIds()),
                    () -> assertEquals(1, model.refreshes.get()),
                    () -> assertEquals(1, model.additions.get()),
                    () -> assertEquals(1, model.managementRequests.get()));
            panel.close();
        });
    }

    /// A user-selected placeholder is committed exactly once after its sparse row finishes loading.
    @Test
    public void commitsPlaceholderSelectionAfterLoad() {
        FakeInstancesModel model = FakeInstancesModel.controlled(items(40), snapshot(-1, 0L));
        InstancesPanel panel = onEventDispatchThread(() -> new InstancesPanel(model, STRINGS));

        onEventDispatchThread(() -> {
            panel.setSize(new Dimension(820, 360));
            layoutRecursively(panel);
            panel.choiceList().refreshLoadPlan();
            panel.choiceList().getList().setSelectedIndex(2);
            assertEquals(List.of(), model.selectedIds());
        });

        model.completePendingLoads();
        EdtDispatcher.executeAndWait(() -> { });

        onEventDispatchThread(() -> {
            assertEquals(List.of("instance-2"), model.selectedIds());
            panel.choiceList().refreshLoadPlan();
            assertEquals(List.of("instance-2"), model.selectedIds());
            panel.close();
        });
    }

    /// A worker-published content revision re-reads the exact count and restores its matching selection.
    @Test
    public void reloadsChangedContentAndAppliesWorkerState() throws InterruptedException {
        FakeInstancesModel model = FakeInstancesModel.immediate(items(3), snapshot(1, 0L));
        InstancesPanel panel = onEventDispatchThread(() -> new InstancesPanel(model, STRINGS));
        onEventDispatchThread(() -> {
            panel.setSize(new Dimension(820, 420));
            layoutRecursively(panel);
            panel.choiceList().refreshLoadPlan();
        });
        int requestsBeforeRevision = model.requestedRanges().size();

        InstancesSnapshot refreshing = new InstancesSnapshot(
                OptionalInt.of(4), 1L, "Scanning instances", true,
                false, false, false, false);
        Thread publisher = new Thread(
                () -> model.replaceItemsAndPublish(items(5), refreshing),
                "instances-panel-test-publisher");
        publisher.start();
        publisher.join();
        EdtDispatcher.executeAndWait(() -> { });

        onEventDispatchThread(() -> {
            assertAll(
                    () -> assertEquals(refreshing, panel.displayedSnapshot()),
                    () -> assertEquals(5, panel.choiceList().getChoiceModel().getSize()),
                    () -> assertEquals(4, panel.choiceList().getList().getSelectedIndex()),
                    () -> assertTrue(model.requestedRanges().size() > requestsBeforeRevision),
                    () -> assertEquals("Refreshing", findButton(panel, "instancesRefresh").getText()),
                    () -> assertFalse(findButton(panel, "instancesRefresh").isEnabled()),
                    () -> assertFalse(findButton(panel, "instancesManage").isEnabled()));
            panel.close();
            assertFalse(model.hasSubscribers());
        });
    }

    /// The exact empty state and long row text paint a non-blank constrained page surface.
    @Test
    public void paintsConstrainedAndEmptySurfaces() {
        FakeInstancesModel populated = FakeInstancesModel.immediate(
                List.of(new InstanceListItem(
                        "long-instance",
                        "A very long modded instance name that must remain inside its viewport row",
                        "Minecraft 1.21.1 with a long loader description")),
                snapshot(0, 0L));
        InstancesPanel panel = onEventDispatchThread(() -> new InstancesPanel(populated, STRINGS));

        BufferedImage image = onEventDispatchThread(() -> {
            Dimension size = new Dimension(720, 420);
            panel.setSize(size);
            layoutRecursively(panel);
            panel.choiceList().refreshLoadPlan();
            BufferedImage rendered = new BufferedImage(size.width, size.height, BufferedImage.TYPE_INT_ARGB);
            Graphics2D graphics = rendered.createGraphics();
            try {
                panel.printAll(graphics);
            } finally {
                graphics.dispose();
            }
            panel.close();
            return rendered;
        });
        assertTrue(distinctColors(image).size() > 4);

        FakeInstancesModel empty = FakeInstancesModel.immediate(List.of(), snapshot(-1, 0L));
        InstancesPanel emptyPanel = onEventDispatchThread(() -> new InstancesPanel(empty, STRINGS));
        onEventDispatchThread(() -> {
            Component emptyLabel = findComponent(emptyPanel, "instancesEmpty");
            assertTrue(emptyLabel.isVisible());
            emptyPanel.close();
        });
    }

    /// Creates deterministic installed-instance rows.
    ///
    /// @param count item count
    /// @return immutable ordered rows
    private static @Unmodifiable List<InstanceListItem> items(int count) {
        List<InstanceListItem> result = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            result.add(new InstanceListItem(
                    "instance-" + index,
                    "Instance " + index,
                    "Minecraft " + (index % 2 == 0 ? "1.21.1" : "1.20.1")));
        }
        return List.copyOf(result);
    }

    /// Creates a normal command-enabled snapshot.
    ///
    /// @param selectedIndex selected source index, or -1 for no selection
    /// @param revision content revision
    /// @return enabled snapshot
    private static InstancesSnapshot snapshot(int selectedIndex, long revision) {
        OptionalInt selected = selectedIndex < 0
                ? OptionalInt.empty()
                : OptionalInt.of(selectedIndex);
        return new InstancesSnapshot(selected, revision, "Ready", false, true, true, true, true);
    }

    /// Finds a named button in a Swing hierarchy.
    ///
    /// @param root hierarchy root
    /// @param name stable component name
    /// @return matching command button
    private static AbstractButton findButton(Container root, String name) {
        Component component = findComponent(root, name);
        if (component instanceof AbstractButton button) {
            return button;
        }
        throw new IllegalArgumentException("Named component is not a button: " + name);
    }

    /// Finds a named component in a Swing hierarchy.
    ///
    /// @param root hierarchy root
    /// @param name stable component name
    /// @return matching component
    private static Component findComponent(Container root, String name) {
        for (Component child : root.getComponents()) {
            if (Objects.equals(name, child.getName())) {
                return child;
            }
            if (child instanceof Container nested) {
                try {
                    return findComponent(nested, name);
                } catch (IllegalArgumentException ignored) {
                }
            }
        }
        throw new IllegalArgumentException("Missing component: " + name);
    }

    /// Runs a value-producing operation synchronously on the EDT.
    ///
    /// @param operation operation to run
    /// @param <T> non-null result type
    /// @return operation result
    private static <T extends Object> T onEventDispatchThread(Supplier<T> operation) {
        AtomicReference<@Nullable T> result = new AtomicReference<>();
        EdtDispatcher.executeAndWait(() -> result.set(operation.get()));
        return Objects.requireNonNull(result.get(), "EDT operation did not return a result");
    }

    /// Runs an operation synchronously on the EDT.
    ///
    /// @param operation operation to run
    private static void onEventDispatchThread(Runnable operation) {
        EdtDispatcher.executeAndWait(operation);
    }

    /// Recursively lays out a component hierarchy before measurement or off-screen painting.
    ///
    /// @param container hierarchy root
    private static void layoutRecursively(Container container) {
        container.doLayout();
        for (Component child : container.getComponents()) {
            if (child instanceof Container nested) {
                layoutRecursively(nested);
            }
        }
    }

    /// Collects all pixel colors painted into an image.
    ///
    /// @param image rendered instance page
    /// @return mutable distinct-color set
    private static Set<Integer> distinctColors(BufferedImage image) {
        Set<Integer> colors = new HashSet<>();
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                colors.add(image.getRGB(x, y));
            }
        }
        return colors;
    }

    /// A captured viewport load whose completion can be controlled by a test.
    ///
    /// @param range requested source range
    /// @param itemSnapshot immutable source snapshot captured at request time
    /// @param future request completion
    @NotNullByDefault
    private record PendingLoad(
            IndexRange range,
            @Unmodifiable List<InstanceListItem> itemSnapshot,
            CompletableFuture<ChoicePage<InstanceListItem>> future) {
    }

    /// Thread-safe fake model supporting immediate and explicitly controlled viewport loads.
    @NotNullByDefault
    private static final class FakeInstancesModel implements InstancesModel {
        /// Latest immutable source rows.
        private volatile @Unmodifiable List<InstanceListItem> items;

        /// Latest immutable page state.
        private final AtomicReference<InstancesSnapshot> current;

        /// Page-state transition publisher.
        private final ValueChangeSupport<InstancesSnapshot> changes = new ValueChangeSupport<>(this);

        /// Whether load requests complete immediately.
        private final boolean immediateLoads;

        /// Requested viewport ranges in invocation order.
        private final List<IndexRange> requestedRanges = new ArrayList<>();

        /// Controlled requests awaiting explicit completion.
        private final List<PendingLoad> pendingLoads = new ArrayList<>();

        /// Selected stable instance identifiers.
        private final List<String> selectedIds = new ArrayList<>();

        /// Refresh command count.
        private final AtomicInteger refreshes = new AtomicInteger();

        /// Add command count.
        private final AtomicInteger additions = new AtomicInteger();

        /// Manage command count.
        private final AtomicInteger managementRequests = new AtomicInteger();

        /// Creates a fake model.
        ///
        /// @param items initial immutable rows
        /// @param initialSnapshot initial page state
        /// @param immediateLoads whether viewport requests complete immediately
        private FakeInstancesModel(
                @Unmodifiable List<InstanceListItem> items,
                InstancesSnapshot initialSnapshot,
                boolean immediateLoads) {
            this.items = List.copyOf(items);
            current = new AtomicReference<>(initialSnapshot);
            this.immediateLoads = immediateLoads;
        }

        /// Creates a source whose viewport loads complete immediately.
        ///
        /// @param items initial rows
        /// @param snapshot initial state
        /// @return immediate fake model
        private static FakeInstancesModel immediate(
                @Unmodifiable List<InstanceListItem> items,
                InstancesSnapshot snapshot) {
            return new FakeInstancesModel(items, snapshot, true);
        }

        /// Creates a source whose viewport loads require explicit completion.
        ///
        /// @param items initial rows
        /// @param snapshot initial state
        /// @return controlled fake model
        private static FakeInstancesModel controlled(
                @Unmodifiable List<InstanceListItem> items,
                InstancesSnapshot snapshot) {
            return new FakeInstancesModel(items, snapshot, false);
        }

        /// Returns the latest fake page state.
        @Override
        public InstancesSnapshot snapshot() {
            return current.get();
        }

        /// Registers a fake page-state listener.
        @Override
        public Subscription subscribe(ValueChangeListener<InstancesSnapshot> listener) {
            return changes.subscribe(listener);
        }

        /// Returns the exact immutable source count.
        @Override
        public OptionalInt exactItemCount() {
            return OptionalInt.of(items.size());
        }

        /// Captures and optionally completes a viewport request.
        @Override
        public synchronized CompletionStage<ChoicePage<InstanceListItem>> load(
                IndexRange desiredRange,
                LoadCancellation cancellation) {
            requestedRanges.add(desiredRange);
            @Unmodifiable List<InstanceListItem> itemSnapshot = items;
            if (immediateLoads) {
                return CompletableFuture.completedFuture(page(desiredRange, itemSnapshot));
            }

            CompletableFuture<ChoicePage<InstanceListItem>> future = new CompletableFuture<>();
            pendingLoads.add(new PendingLoad(desiredRange, itemSnapshot, future));
            return future;
        }

        /// Records one selected stable instance identifier.
        @Override
        public synchronized void selectInstance(String instanceId) {
            selectedIds.add(instanceId);
        }

        /// Records one refresh command.
        @Override
        public void refreshInstances() {
            refreshes.incrementAndGet();
        }

        /// Records one add command.
        @Override
        public void addInstance() {
            additions.incrementAndGet();
        }

        /// Records one management command.
        @Override
        public void manageSelectedInstance() {
            managementRequests.incrementAndGet();
        }

        /// Returns a snapshot of captured request ranges.
        ///
        /// @return immutable ranges in invocation order
        private synchronized @Unmodifiable List<IndexRange> requestedRanges() {
            return List.copyOf(requestedRanges);
        }

        /// Returns a snapshot of selected instance identifiers.
        ///
        /// @return immutable selected identifiers in command order
        private synchronized @Unmodifiable List<String> selectedIds() {
            return List.copyOf(selectedIds);
        }

        /// Completes all currently pending viewport loads from their captured source snapshots.
        private void completePendingLoads() {
            @Unmodifiable List<PendingLoad> loads;
            synchronized (this) {
                loads = List.copyOf(pendingLoads);
                pendingLoads.clear();
            }
            for (PendingLoad load : loads) {
                load.future().complete(page(load.range(), load.itemSnapshot()));
            }
        }

        /// Replaces indexed content before publishing its matching page state.
        ///
        /// @param replacement replacement immutable rows
        /// @param snapshot matching page state
        private void replaceItemsAndPublish(
                @Unmodifiable List<InstanceListItem> replacement,
                InstancesSnapshot snapshot) {
            items = List.copyOf(replacement);
            InstancesSnapshot previous = current.getAndSet(snapshot);
            changes.fireChange(previous, snapshot);
        }

        /// Returns whether a panel listener remains registered.
        ///
        /// @return whether this fake has at least one subscriber
        private boolean hasSubscribers() {
            return changes.hasSubscribers();
        }

        /// Creates one source-aligned exact page.
        ///
        /// @param desiredRange requested range
        /// @param itemSnapshot immutable source rows captured for the request
        /// @return exact choice page
        private static ChoicePage<InstanceListItem> page(
                IndexRange desiredRange,
                @Unmodifiable List<InstanceListItem> itemSnapshot) {
            IndexRange actualRange = desiredRange.clampToItemCount(itemSnapshot.size());
            List<InstanceListItem> values = itemSnapshot.subList(
                    actualRange.startInclusive(),
                    actualRange.endExclusive());
            return new ChoicePage<>(
                    actualRange,
                    List.copyOf(values),
                    OptionalInt.of(itemSnapshot.size()),
                    actualRange.endExclusive() == itemSnapshot.size());
        }
    }
}
