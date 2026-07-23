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
import space.minecraftstl.xyml.observable.Subscription;
import space.minecraftstl.xyml.observable.ValueChangeListener;
import space.minecraftstl.xyml.observable.ValueChangeSupport;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;
import space.minecraftstl.xyml.ui.swing.choice.ChoiceListEntry;
import space.minecraftstl.xyml.ui.swing.choice.ChoicePage;
import space.minecraftstl.xyml.ui.swing.choice.IndexRange;
import space.minecraftstl.xyml.ui.swing.choice.LoadCancellation;

import javax.swing.AbstractButton;
import javax.swing.JComboBox;
import javax.swing.JList;
import javax.swing.JTextField;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests lazy display loading, measured viewport demand, controls, state cards, and cleanup.
@NotNullByDefault
public final class GameVersionCatalogPanelTest {
    /// Localized strings used by focused page tests.
    private static final GameVersionCatalogStrings STRINGS = new GameVersionCatalogStrings(
            "Game versions",
            "Search",
            "Kind",
            "All",
            "Release",
            "Snapshot",
            "April Fools",
            "Old",
            "Refresh",
            "Refreshing");

    /// Construction performs no source load and the first display delegates lazy loading.
    @Test
    public void startsLoadingOnlyAfterDisplayNotification() {
        FakeCatalogModel model = FakeCatalogModel.immediate(List.of(), snapshot(
                -1, 0, 0L, GameVersionCatalogStatus.IDLE, "Waiting", false, true));
        GameVersionCatalogPanel panel = onEventDispatchThread(
                () -> new GameVersionCatalogPanel(model, STRINGS));

        assertEquals(0, model.lazyLoads.get());
        onEventDispatchThread(() -> {
            panel.addNotify();
            panel.removeNotify();
            panel.addNotify();
            assertEquals(1, model.lazyLoads.get());
            panel.close();
            panel.removeNotify();
        });
    }

    /// Controls delegate stable values and the first demand equals measured visible rows.
    @Test
    public void delegatesControlsAndUsesMeasuredVisibleRange() {
        FakeCatalogModel model = FakeCatalogModel.immediate(
                items(1_000),
                snapshot(-1, 1_000, 1L, GameVersionCatalogStatus.READY, "Ready", true, true));
        GameVersionCatalogPanel panel = onEventDispatchThread(
                () -> new GameVersionCatalogPanel(model, STRINGS));

        onEventDispatchThread(() -> {
            panel.setSize(new Dimension(820, 520));
            layoutRecursively(panel);
            panel.choiceList().refreshLoadPlan();

            JList<ChoiceListEntry<GameVersionCatalogItem>> list = panel.choiceList().getList();
            IndexRange requested = model.requestedRanges().get(0);
            int viewportHeight = panel.choiceList().getViewport().getExtentSize().height;
            int rowHeight = list.getFixedCellHeight();
            int visibleRows = (viewportHeight + rowHeight - 1) / rowHeight;

            findTextField(panel, "gameVersionsSearch").setText("version-9");
            findFilterBox(panel).setSelectedItem(GameVersionFilter.SNAPSHOT);
            findButton(panel, "gameVersionsRefresh").doClick();
            list.setSelectedIndex(1);

            assertAll(
                    () -> assertEquals(javax.swing.ListSelectionModel.SINGLE_SELECTION,
                            list.getSelectionMode()),
                    () -> assertEquals(visibleRows, requested.length()),
                    () -> assertTrue(requested.length() < model.exactItemCount().orElseThrow()),
                    () -> assertEquals(List.of("version-9"), model.queries()),
                    () -> assertEquals(List.of(GameVersionFilter.SNAPSHOT), model.filters()),
                    () -> assertEquals(1, model.refreshes.get()),
                    () -> assertEquals(List.of("version-1"), model.selectedIds()));
            panel.close();
        });
    }

    /// A placeholder click commits exactly once after its viewport range finishes loading.
    @Test
    public void commitsPlaceholderSelectionAfterLoad() {
        FakeCatalogModel model = FakeCatalogModel.controlled(
                items(40),
                snapshot(-1, 40, 1L, GameVersionCatalogStatus.READY, "Ready", true, true));
        GameVersionCatalogPanel panel = onEventDispatchThread(
                () -> new GameVersionCatalogPanel(model, STRINGS));

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
            assertEquals(List.of("version-2"), model.selectedIds());
            panel.choiceList().refreshLoadPlan();
            assertEquals(List.of("version-2"), model.selectedIds());
            panel.close();
        });
    }

    /// Worker transitions reload exact content, select state cards, and stop after close.
    @Test
    public void appliesWorkerStateAndClosesWithQueuedUpdateBarrier() throws InterruptedException {
        FakeCatalogModel model = FakeCatalogModel.immediate(
                items(3),
                snapshot(1, 3, 1L, GameVersionCatalogStatus.READY, "Ready", true, true));
        GameVersionCatalogPanel panel = onEventDispatchThread(
                () -> new GameVersionCatalogPanel(model, STRINGS));
        onEventDispatchThread(() -> {
            panel.setSize(new Dimension(820, 420));
            layoutRecursively(panel);
            panel.choiceList().refreshLoadPlan();
        });

        GameVersionCatalogSnapshot replacement = snapshot(
                4, 5, 2L, GameVersionCatalogStatus.READY, "Five versions", true, true);
        Thread publisher = new Thread(
                () -> model.replaceItemsAndPublish(items(5), replacement),
                "game-version-panel-publisher");
        publisher.start();
        publisher.join();
        EdtDispatcher.executeAndWait(() -> { });

        onEventDispatchThread(() -> assertAll(
                () -> assertEquals(replacement, panel.displayedSnapshot()),
                () -> assertEquals(5, panel.choiceList().getChoiceModel().getSize()),
                () -> assertEquals(4, panel.choiceList().getList().getSelectedIndex()),
                () -> assertTrue(findComponent(panel, "gameVersionsList").isVisible())));

        GameVersionCatalogSnapshot failed = snapshot(
                -1, 0, 3L, GameVersionCatalogStatus.FAILED, "Network unavailable", false, true);
        model.replaceItemsAndPublish(List.of(), failed);
        EdtDispatcher.executeAndWait(() -> { });
        onEventDispatchThread(() -> assertAll(
                () -> assertEquals(failed, panel.displayedSnapshot()),
                () -> assertTrue(findComponent(panel, "gameVersionsFailed").isVisible()),
                () -> assertEquals("Network unavailable",
                        findComponent(panel, "gameVersionsFailed", javax.swing.JLabel.class).getText())));

        CountDownLatch eventDispatchThreadBlocked = new CountDownLatch(1);
        CountDownLatch releaseEventDispatchThread = new CountDownLatch(1);
        EdtDispatcher.execute(() -> {
            eventDispatchThreadBlocked.countDown();
            awaitLatch(releaseEventDispatchThread);
        });
        assertTrue(eventDispatchThreadBlocked.await(5L, TimeUnit.SECONDS));

        GameVersionCatalogSnapshot ignored = snapshot(
                -1, 1, 4L, GameVersionCatalogStatus.READY, "Late", true, true);
        model.replaceItemsAndPublish(items(1), ignored);
        CountDownLatch firstCloseStarted = new CountDownLatch(1);
        CountDownLatch firstCloseReturned = new CountDownLatch(1);
        Thread firstCloser = new Thread(() -> {
            firstCloseStarted.countDown();
            try {
                panel.close();
            } finally {
                firstCloseReturned.countDown();
            }
        }, "game-version-panel-first-closer");
        firstCloser.start();
        assertTrue(firstCloseStarted.await(5L, TimeUnit.SECONDS));
        assertFalse(firstCloseReturned.await(200L, TimeUnit.MILLISECONDS));

        CountDownLatch secondCloseStarted = new CountDownLatch(1);
        CountDownLatch secondCloseReturned = new CountDownLatch(1);
        Thread secondCloser = new Thread(() -> {
            secondCloseStarted.countDown();
            try {
                panel.close();
            } finally {
                secondCloseReturned.countDown();
            }
        }, "game-version-panel-second-closer");
        secondCloser.start();
        assertTrue(secondCloseStarted.await(5L, TimeUnit.SECONDS));
        assertFalse(secondCloseReturned.await(200L, TimeUnit.MILLISECONDS));

        releaseEventDispatchThread.countDown();
        firstCloser.join();
        secondCloser.join();
        assertTrue(model.awaitUnsubscribed());
        assertFalse(model.hasSubscribers());

        EdtDispatcher.executeAndWait(() -> { });
        onEventDispatchThread(() -> {
            findButton(panel, "gameVersionsRefresh").doClick();
            findTextField(panel, "gameVersionsSearch").setText("after-close");
            findFilterBox(panel).setSelectedItem(GameVersionFilter.RELEASE);
            assertAll(
                    () -> assertEquals(failed, panel.displayedSnapshot()),
                    () -> assertEquals(0, model.refreshes.get()),
                    () -> assertEquals(List.of(), model.queries()),
                    () -> assertEquals(List.of(), model.filters()));
        });
    }

    /// Creates deterministic catalog rows.
    ///
    /// @param count item count
    /// @return immutable ordered rows
    private static @Unmodifiable List<GameVersionCatalogItem> items(int count) {
        List<GameVersionCatalogItem> result = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            result.add(new GameVersionCatalogItem(
                    "version-" + index,
                    index % 2 == 0 ? GameVersionKind.RELEASE : GameVersionKind.SNAPSHOT,
                    Optional.of(Instant.ofEpochSecond(index + 1L))));
        }
        return List.copyOf(result);
    }

    /// Creates one exact catalog snapshot.
    ///
    /// @param selectedIndex selected index, or -1 for none
    /// @param itemCount exact visible item count
    /// @param revision visible content revision
    /// @param status catalog lifecycle
    /// @param statusText visible status text
    /// @param listEnabled whether selection is enabled
    /// @param refreshEnabled whether refresh is enabled
    /// @return immutable catalog snapshot
    private static GameVersionCatalogSnapshot snapshot(
            int selectedIndex,
            int itemCount,
            long revision,
            GameVersionCatalogStatus status,
            String statusText,
            boolean listEnabled,
            boolean refreshEnabled) {
        OptionalInt selected = selectedIndex < 0
                ? OptionalInt.empty()
                : OptionalInt.of(selectedIndex);
        return new GameVersionCatalogSnapshot(
                selected,
                itemCount,
                revision,
                status,
                statusText,
                "",
                GameVersionFilter.ALL,
                listEnabled,
                refreshEnabled);
    }

    /// Finds a named button in a Swing hierarchy.
    ///
    /// @param root hierarchy root
    /// @param name stable component name
    /// @return matching command button
    private static AbstractButton findButton(Container root, String name) {
        return findComponent(root, name, AbstractButton.class);
    }

    /// Finds a named text field in a Swing hierarchy.
    ///
    /// @param root hierarchy root
    /// @param name stable component name
    /// @return matching text field
    private static JTextField findTextField(Container root, String name) {
        return findComponent(root, name, JTextField.class);
    }

    /// Finds the game-version filter combo box.
    ///
    /// @param root hierarchy root
    /// @return typed filter combo box
    @SuppressWarnings("unchecked")
    private static JComboBox<GameVersionFilter> findFilterBox(Container root) {
        return (JComboBox<GameVersionFilter>) findComponent(root, "gameVersionsFilter");
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

    /// Finds and casts a named component in a Swing hierarchy.
    ///
    /// @param root hierarchy root
    /// @param name stable component name
    /// @param type required component type
    /// @param <T> component result type
    /// @return matching typed component
    private static <T extends Component> T findComponent(
            Container root,
            String name,
            Class<T> type) {
        Component component = findComponent(root, name);
        if (type.isInstance(component)) {
            return type.cast(component);
        }
        throw new IllegalArgumentException("Named component has the wrong type: " + name);
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

    /// Recursively lays out a component hierarchy before viewport measurement.
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

    /// Waits for a deterministic test latch while preserving interruption as a test failure.
    ///
    /// @param latch latch to await
    private static void awaitLatch(CountDownLatch latch) {
        try {
            if (!latch.await(5L, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out waiting for test latch");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while waiting for test latch", exception);
        }
    }

    /// A captured viewport load awaiting explicit completion.
    ///
    /// @param range requested source range
    /// @param itemSnapshot immutable source snapshot captured at request time
    /// @param future request completion
    @NotNullByDefault
    private record PendingLoad(
            IndexRange range,
            @Unmodifiable List<GameVersionCatalogItem> itemSnapshot,
            CompletableFuture<ChoicePage<GameVersionCatalogItem>> future) {
    }

    /// Thread-safe fake model supporting immediate and controlled viewport loads.
    @NotNullByDefault
    private static final class FakeCatalogModel implements GameVersionCatalogModel {
        /// Latest immutable visible rows.
        private volatile @Unmodifiable List<GameVersionCatalogItem> items;

        /// Latest immutable catalog snapshot.
        private final AtomicReference<GameVersionCatalogSnapshot> current;

        /// Snapshot transition publisher.
        private final ValueChangeSupport<GameVersionCatalogSnapshot> changes = new ValueChangeSupport<>(this);

        /// Whether viewport requests complete immediately.
        private final boolean immediateLoads;

        /// Requested viewport ranges in invocation order.
        private final List<IndexRange> requestedRanges = new ArrayList<>();

        /// Controlled requests awaiting explicit completion.
        private final List<PendingLoad> pendingLoads = new ArrayList<>();

        /// Stable version IDs selected through the page.
        private final List<String> selectedIds = new ArrayList<>();

        /// Query values delegated through the page.
        private final List<String> queryValues = new ArrayList<>();

        /// Filter values delegated through the page.
        private final List<GameVersionFilter> filterValues = new ArrayList<>();

        /// Lazy-load command count.
        private final AtomicInteger lazyLoads = new AtomicInteger();

        /// Refresh command count.
        private final AtomicInteger refreshes = new AtomicInteger();

        /// Signals when the panel-owned subscription has been removed.
        private final CountDownLatch unsubscribed = new CountDownLatch(1);

        /// Creates a fake catalog model.
        ///
        /// @param items initial immutable rows
        /// @param initialSnapshot matching initial state
        /// @param immediateLoads whether viewport requests complete immediately
        private FakeCatalogModel(
                @Unmodifiable List<GameVersionCatalogItem> items,
                GameVersionCatalogSnapshot initialSnapshot,
                boolean immediateLoads) {
            this.items = List.copyOf(items);
            current = new AtomicReference<>(initialSnapshot);
            this.immediateLoads = immediateLoads;
        }

        /// Creates a source whose viewport requests complete immediately.
        ///
        /// @param items initial immutable rows
        /// @param snapshot matching initial state
        /// @return immediate fake model
        private static FakeCatalogModel immediate(
                @Unmodifiable List<GameVersionCatalogItem> items,
                GameVersionCatalogSnapshot snapshot) {
            return new FakeCatalogModel(items, snapshot, true);
        }

        /// Creates a source whose viewport requests require explicit completion.
        ///
        /// @param items initial immutable rows
        /// @param snapshot matching initial state
        /// @return controlled fake model
        private static FakeCatalogModel controlled(
                @Unmodifiable List<GameVersionCatalogItem> items,
                GameVersionCatalogSnapshot snapshot) {
            return new FakeCatalogModel(items, snapshot, false);
        }

        /// Returns the latest fake state.
        @Override
        public GameVersionCatalogSnapshot snapshot() {
            return current.get();
        }

        /// Registers a fake state listener.
        @Override
        public Subscription subscribe(ValueChangeListener<GameVersionCatalogSnapshot> listener) {
            Subscription subscription = changes.subscribe(listener);
            return Subscription.create(() -> {
                try {
                    subscription.unsubscribe();
                } finally {
                    unsubscribed.countDown();
                }
            });
        }

        /// Returns the exact immutable visible count.
        @Override
        public OptionalInt exactItemCount() {
            return OptionalInt.of(items.size());
        }

        /// Captures and optionally completes a viewport request.
        @Override
        public synchronized CompletionStage<ChoicePage<GameVersionCatalogItem>> load(
                IndexRange desiredRange,
                LoadCancellation cancellation) {
            requestedRanges.add(desiredRange);
            @Unmodifiable List<GameVersionCatalogItem> itemSnapshot = items;
            if (immediateLoads) {
                return CompletableFuture.completedFuture(page(desiredRange, itemSnapshot));
            }
            CompletableFuture<ChoicePage<GameVersionCatalogItem>> future = new CompletableFuture<>();
            pendingLoads.add(new PendingLoad(desiredRange, itemSnapshot, future));
            return future;
        }

        /// Records one lazy-load request.
        @Override
        public void loadIfNeeded() {
            lazyLoads.incrementAndGet();
        }

        /// Records one refresh request.
        @Override
        public void refresh() {
            refreshes.incrementAndGet();
        }

        /// Records one query replacement.
        @Override
        public synchronized void setQuery(String query) {
            queryValues.add(query);
        }

        /// Records one filter replacement.
        @Override
        public synchronized void setFilter(GameVersionFilter filter) {
            filterValues.add(filter);
        }

        /// Records one stable version selection.
        @Override
        public synchronized void selectVersion(String versionId) {
            selectedIds.add(versionId);
        }

        /// Returns captured viewport ranges.
        ///
        /// @return immutable ranges in invocation order
        private synchronized @Unmodifiable List<IndexRange> requestedRanges() {
            return List.copyOf(requestedRanges);
        }

        /// Returns selected stable version IDs.
        ///
        /// @return immutable IDs in command order
        private synchronized @Unmodifiable List<String> selectedIds() {
            return List.copyOf(selectedIds);
        }

        /// Returns delegated query values.
        ///
        /// @return immutable queries in command order
        private synchronized @Unmodifiable List<String> queries() {
            return List.copyOf(queryValues);
        }

        /// Returns delegated filter values.
        ///
        /// @return immutable filters in command order
        private synchronized @Unmodifiable List<GameVersionFilter> filters() {
            return List.copyOf(filterValues);
        }

        /// Completes all currently pending viewport loads from captured source content.
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

        /// Replaces indexed content before publishing its exactly matching state.
        ///
        /// @param replacement replacement immutable rows
        /// @param snapshot matching page state
        private void replaceItemsAndPublish(
                @Unmodifiable List<GameVersionCatalogItem> replacement,
                GameVersionCatalogSnapshot snapshot) {
            items = List.copyOf(replacement);
            GameVersionCatalogSnapshot previous = current.getAndSet(snapshot);
            changes.fireChange(previous, snapshot);
        }

        /// Returns whether a page listener remains registered.
        ///
        /// @return whether this fake has subscribers
        private boolean hasSubscribers() {
            return changes.hasSubscribers();
        }

        /// Waits until the panel removes its model subscription.
        ///
        /// @return whether removal occurred before the test timeout
        private boolean awaitUnsubscribed() {
            try {
                return unsubscribed.await(5L, TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while waiting for subscription removal", exception);
            }
        }

        /// Creates one source-aligned exact page.
        ///
        /// @param desiredRange requested range
        /// @param itemSnapshot immutable source rows captured for the request
        /// @return exact choice page
        private static ChoicePage<GameVersionCatalogItem> page(
                IndexRange desiredRange,
                @Unmodifiable List<GameVersionCatalogItem> itemSnapshot) {
            IndexRange actualRange = desiredRange.clampToItemCount(itemSnapshot.size());
            List<GameVersionCatalogItem> values = itemSnapshot.subList(
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
