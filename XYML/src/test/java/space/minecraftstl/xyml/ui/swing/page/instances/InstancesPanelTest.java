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

import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLightLaf;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import space.minecraftstl.xyml.image.InstanceIconData;
import space.minecraftstl.xyml.observable.Subscription;
import space.minecraftstl.xyml.observable.ValueChangeListener;
import space.minecraftstl.xyml.observable.ValueChangeSupport;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;
import space.minecraftstl.xyml.ui.swing.choice.ChoiceListEntry;
import space.minecraftstl.xyml.ui.swing.choice.ChoicePage;
import space.minecraftstl.xyml.ui.swing.choice.IndexRange;
import space.minecraftstl.xyml.ui.swing.choice.LoadCancellation;
import space.minecraftstl.xyml.ui.swing.page.instances.management.InstanceManagementCoordinator;
import space.minecraftstl.xyml.ui.swing.page.instances.management.InstanceManagementHost;
import space.minecraftstl.xyml.ui.swing.page.instances.management.InstanceManagementView;
import space.minecraftstl.xyml.ui.swing.page.instances.management.InstanceManagementViewFactory;

import javax.swing.AbstractButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JRadioButton;
import javax.swing.JTextField;
import javax.swing.ListCellRenderer;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.event.ActionEvent;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests installed-instance commands, placeholder selection, dynamic reload, and viewport-sized demand.
@NotNullByDefault
public final class InstancesPanelTest {
    /// Exact opaque accent painted by the test instance icon.
    private static final int TEST_ICON_ARGB = 0xFFFF3366;

    /// Localized strings used by the focused page tests.
    private static final InstancesStrings STRINGS = new InstancesStrings(
            "Instances",
            "Search",
            "Refresh",
            "Refreshing",
            "Add",
            "Manage",
            "No installed instances",
            "No matching instances");

    /// A selected-directory context keeps version management visible until the user opens one instance.
    @Test
    public void keepsVersionManagementVisibleByDefault() {
        FakeInstancesModel model = FakeInstancesModel.immediate(items(2), snapshot(0, 2, 0L));
        model.selectionContextRevision = 1L;
        RecordingManagementFactory factory = new RecordingManagementFactory(null);
        InstanceManagementCoordinator coordinator = new InstanceManagementCoordinator(factory);
        InstancesPanel panel = onEventDispatchThread(() -> new InstancesPanel(model, STRINGS, coordinator));

        onEventDispatchThread(() -> {
            assertAll(
                    () -> assertEquals(0, model.managementRequests.get()),
                    () -> assertTrue(model.managedIds().isEmpty()),
                    () -> assertTrue(findComponent(panel, "instancesListWorkspace").isVisible()),
                    () -> assertFalse(findComponent(panel, "instancesManagementHost").isVisible()));
            panel.close();
            coordinator.close();
        });
    }

    /// Instance list containers and unselected rows leave the window background visible.
    @Test
    public void keepsInstanceContentBackgroundTransparent() {
        FakeInstancesModel model = FakeInstancesModel.immediate(items(2), snapshot(0, 2, 0L));
        RecordingManagementFactory factory = new RecordingManagementFactory(null);
        InstanceManagementCoordinator coordinator = new InstanceManagementCoordinator(factory);
        InstancesPanel panel = onEventDispatchThread(() -> new InstancesPanel(model, STRINGS, coordinator));

        onEventDispatchThread(() -> {
            JList<ChoiceListEntry<InstanceListItem>> list = panel.choiceList().getList();
            ListCellRenderer<? super ChoiceListEntry<InstanceListItem>> renderer = list.getCellRenderer();
            JComponent unselectedRow = (JComponent) renderer.getListCellRendererComponent(
                    list,
                    ChoiceListEntry.loading(0),
                    0,
                    false,
                    false);
            boolean unselectedOpaque = unselectedRow.isOpaque();
            JComponent selectedRow = (JComponent) renderer.getListCellRendererComponent(
                    list,
                    ChoiceListEntry.loading(0),
                    0,
                    true,
                    false);

            assertAll(
                    () -> assertFalse(panel.isOpaque()),
                    () -> assertFalse(((JComponent) findComponent(
                            panel, "instancesListWorkspace")).isOpaque()),
                    () -> assertFalse(((JComponent) findComponent(
                            panel, "instancesManagementHost")).isOpaque()),
                    () -> assertFalse(((JComponent) findComponent(
                            panel, "instancesListCards")).isOpaque()),
                    () -> assertFalse(panel.choiceList().isOpaque()),
                    () -> assertFalse(panel.choiceList().getViewport().isOpaque()),
                    () -> assertFalse(list.isOpaque()),
                    () -> assertFalse(unselectedOpaque),
                    () -> assertTrue(selectedRow.isOpaque()),
                    () -> assertFalse(containsComponentType(
                            (Container) selectedRow,
                            JRadioButton.class)));
            panel.close();
            coordinator.close();
        });
    }

    /// The restored search matches a visible name distinct from its ID and loads only that stable row.
    @Test
    public void filtersInstancesWithoutEagerlyLoadingHiddenRows() {
        FakeInstancesModel model = FakeInstancesModel.immediate(items(80), snapshot(37, 80, 0L));
        RecordingManagementFactory factory = new RecordingManagementFactory(null);
        InstanceManagementCoordinator coordinator = new InstanceManagementCoordinator(factory);
        InstancesPanel panel = onEventDispatchThread(() -> new InstancesPanel(model, STRINGS, coordinator));

        onEventDispatchThread(() -> {
            panel.setSize(new Dimension(820, 520));
            layoutRecursively(panel);
            JTextField search = (JTextField) findComponent(panel, "instancesSearch");
            assertAll(
                    () -> assertEquals("Search", search.getAccessibleContext().getAccessibleName()),
                    () -> assertEquals("Search", search.getClientProperty("JTextField.placeholderText")),
                    () -> assertEquals(Boolean.TRUE,
                            search.getClientProperty("JTextField.showClearButton")),
                    () -> assertTrue(search.isVisible()),
                    () -> assertTrue(search.getWidth() >= 160));
            search.setText("instance 37");
        });
        flushEventDispatchThread();
        onEventDispatchThread(panel.choiceList()::refreshLoadPlan);
        flushEventDispatchThread();

        onEventDispatchThread(() -> {
            @Nullable InstanceListItem visible = panel.choiceList().getChoiceModel().loadedValueAt(0);
            assertAll(
                    () -> assertEquals(1, panel.choiceList().getChoiceModel().getSize()),
                    () -> assertEquals("instance-37", Objects.requireNonNull(visible).id()),
                    () -> assertEquals(0, panel.choiceList().getList().getSelectedIndex()),
                    () -> assertEquals(Set.of("instance-37"), Set.copyOf(model.requestedStableIds())),
                    () -> assertTrue(findButton(panel, "instancesManage").isEnabled()));

            JTextField search = (JTextField) findComponent(panel, "instancesSearch");
            search.setText("does-not-exist");
        });
        flushEventDispatchThread();
        onEventDispatchThread(() -> assertAll(
                () -> assertEquals(0, panel.choiceList().getChoiceModel().getSize()),
                () -> assertTrue(findComponent(panel, "instancesNoSearchResults").isVisible()),
                () -> assertFalse(findButton(panel, "instancesManage").isEnabled())));

        onEventDispatchThread(() -> ((JTextField) findComponent(panel, "instancesSearch")).setText(""));
        flushEventDispatchThread();
        onEventDispatchThread(panel.choiceList()::refreshLoadPlan);
        flushEventDispatchThread();
        onEventDispatchThread(() -> {
            assertAll(
                    () -> assertEquals(80, panel.choiceList().getChoiceModel().getSize()),
                    () -> assertEquals(37, panel.choiceList().getList().getSelectedIndex()),
                    () -> assertFalse(findComponent(panel, "instancesNoSearchResults").isVisible()));
            panel.close();
            coordinator.close();
        });
    }

    /// Loaded rows delegate commands once and warm one measured viewport beyond first display.
    @Test
    public void delegatesCommandsAndUsesMeasuredVisibleRange() {
        FakeInstancesModel model = FakeInstancesModel.immediate(items(1_000), snapshot(0, 1_000, 0L));
        RecordingManagementFactory factory = new RecordingManagementFactory(null);
        InstanceManagementCoordinator coordinator = new InstanceManagementCoordinator(factory);
        InstancesPanel panel = onEventDispatchThread(() -> new InstancesPanel(model, STRINGS, coordinator));

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
                    () -> assertEquals(expectedVisibleRows * 2, requested.length()),
                    () -> assertTrue(requested.length() < model.exactItemCount().orElseThrow()),
                    () -> assertEquals(List.of("instance-1"), model.selectedIds()),
                    () -> assertEquals(1, model.refreshes.get()),
                    () -> assertEquals(1, model.additions.get()),
                    () -> assertEquals(1, model.managementRequests.get()));
            panel.close();
            coordinator.close();
        });
    }

    /// A user-selected placeholder is committed exactly once after its sparse row finishes loading.
    @Test
    public void commitsPlaceholderSelectionAfterLoad() {
        FakeInstancesModel model = FakeInstancesModel.controlled(items(40), snapshot(-1, 40, 0L));
        RecordingManagementFactory factory = new RecordingManagementFactory(null);
        InstanceManagementCoordinator coordinator = new InstanceManagementCoordinator(factory);
        InstancesPanel panel = onEventDispatchThread(() -> new InstancesPanel(model, STRINGS, coordinator));

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
            coordinator.close();
        });
    }

    /// Management remains disabled until a loaded user choice is confirmed by a model snapshot.
    @Test
    public void gatesManagementUntilPlaceholderSelectionIsConfirmed() {
        FakeInstancesModel model = FakeInstancesModel.controlled(items(12), snapshot(0, 12, 0L));
        RecordingManagementFactory factory = new RecordingManagementFactory(null);
        InstanceManagementCoordinator coordinator = new InstanceManagementCoordinator(factory);
        InstancesPanel panel = onEventDispatchThread(
                () -> new InstancesPanel(model, STRINGS, coordinator));

        onEventDispatchThread(() -> {
            panel.setSize(new Dimension(820, 360));
            layoutRecursively(panel);
            panel.choiceList().refreshLoadPlan();
            panel.choiceList().getList().setSelectedIndex(2);
            assertFalse(findButton(panel, "instancesManage").isEnabled());
            findButton(panel, "instancesManage").doClick();
        });
        assertEquals(0, model.managementRequests.get());

        model.completePendingLoads();
        EdtDispatcher.executeAndWait(() -> { });

        onEventDispatchThread(() -> {
            assertAll(
                    () -> assertEquals(List.of("instance-2"), model.selectedIds()),
                    () -> assertEquals(OptionalInt.of(2), panel.displayedSnapshot().selectedIndex()),
                    () -> assertTrue(findButton(panel, "instancesManage").isEnabled()));
            findButton(panel, "instancesManage").doClick();
            assertEquals(List.of("instance-2"), model.managedIds());
            panel.close();
            coordinator.close();
        });
    }

    /// A worker-published content revision re-reads the exact count and restores its matching selection.
    @Test
    public void reloadsChangedContentAndAppliesWorkerState() throws InterruptedException {
        FakeInstancesModel model = FakeInstancesModel.immediate(items(3), snapshot(1, 3, 0L));
        RecordingManagementFactory factory = new RecordingManagementFactory(null);
        InstanceManagementCoordinator coordinator = new InstanceManagementCoordinator(factory);
        InstancesPanel panel = onEventDispatchThread(() -> new InstancesPanel(model, STRINGS, coordinator));
        onEventDispatchThread(() -> {
            panel.setSize(new Dimension(820, 420));
            layoutRecursively(panel);
            panel.choiceList().refreshLoadPlan();
        });
        int requestsBeforeRevision = model.requestedRanges().size();

        InstancesSnapshot refreshing = new InstancesSnapshot(
                OptionalInt.of(4), 5, 1L, "Scanning instances", true,
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
            coordinator.close();
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
                        "Minecraft 1.21.1 with a long loader description",
                        solidIcon(0))),
                snapshot(0, 1, 0L));
        RecordingManagementFactory factory = new RecordingManagementFactory(null);
        InstanceManagementCoordinator coordinator = new InstanceManagementCoordinator(factory);
        InstancesPanel panel = onEventDispatchThread(() -> new InstancesPanel(populated, STRINGS, coordinator));

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
            coordinator.close();
            return rendered;
        });
        assertTrue(distinctColors(image).size() > 4);

        FakeInstancesModel empty = FakeInstancesModel.immediate(List.of(), snapshot(-1, 0, 0L));
        RecordingManagementFactory emptyFactory = new RecordingManagementFactory(null);
        InstanceManagementCoordinator emptyCoordinator = new InstanceManagementCoordinator(emptyFactory);
        InstancesPanel emptyPanel = onEventDispatchThread(
                () -> new InstancesPanel(empty, STRINGS, emptyCoordinator));
        onEventDispatchThread(() -> {
            Component emptyLabel = findComponent(emptyPanel, "instancesEmpty");
            assertTrue(emptyLabel.isVisible());
            emptyPanel.close();
            emptyCoordinator.close();
        });
    }

    /// The dedicated renderer paints fixed-size instance pixels under both FlatLaf palettes.
    @Test
    public void paintsStableInstanceIconInLightAndDarkThemes() {
        InstanceIconData icon = solidIcon(TEST_ICON_ARGB);
        for (boolean dark : List.of(false, true)) {
            assertTrue(dark ? FlatDarkLaf.setup() : FlatLightLaf.setup());
            BufferedImage image = renderPanelWithIcon(icon);
            int iconPixels = countPixels(image, TEST_ICON_ARGB);
            assertTrue(
                    iconPixels >= InstanceIconData.PIXEL_COUNT / 2,
                    "Expected at least half of the instance icon pixels but painted " + iconPixels);
        }
    }

    /// Loaded, loading, and failed sparse rows reuse identical measured geometry and selection colors.
    @Test
    public void keepsRendererGeometryStableAcrossSparseStates() {
        onEventDispatchThread(() -> {
            InstanceListCellRenderer renderer = new InstanceListCellRenderer();
            JList<ChoiceListEntry<InstanceListItem>> list = new JList<>();
            Component loading = renderer.getListCellRendererComponent(
                    list,
                    ChoiceListEntry.loading(0),
                    0,
                    false,
                    false);
            int loadingHeight = loading.getPreferredSize().height;
            Component failed = renderer.getListCellRendererComponent(
                    list,
                    ChoiceListEntry.failed(0, new IllegalStateException("load failed")),
                    0,
                    false,
                    false);
            int failedHeight = failed.getPreferredSize().height;
            Component loaded = renderer.getListCellRendererComponent(
                    list,
                    ChoiceListEntry.loaded(0, new InstanceListItem(
                            "id",
                            "A very long modded instance name",
                            "Minecraft 1.21.1 / Fabric",
                            solidIcon(0))),
                    0,
                    true,
                    true);
            loaded.setSize(new Dimension(470, InstanceListCellRenderer.ROW_HEIGHT));
            layoutRecursively((Container) loaded);
            JLabel nameLabel = (JLabel) findComponent((Container) loaded, "instanceListName");
            JLabel detailLabel = (JLabel) findComponent((Container) loaded, "instanceListDetail");

            assertAll(
                    () -> assertSame(loading, failed),
                    () -> assertSame(failed, loaded),
                    () -> assertEquals(InstanceListCellRenderer.ROW_HEIGHT, loadingHeight),
                    () -> assertEquals(InstanceListCellRenderer.ROW_HEIGHT, failedHeight),
                    () -> assertEquals(InstanceListCellRenderer.ROW_HEIGHT,
                            loaded.getPreferredSize().height),
                    () -> assertEquals(list.getSelectionBackground(), loaded.getBackground()),
                    () -> assertEquals("A very long modded instance name", nameLabel.getText()),
                    () -> assertEquals("Minecraft 1.21.1 / Fabric", detailLabel.getText()),
                    () -> assertTrue(nameLabel.getWidth() >= 300),
                    () -> assertEquals(nameLabel.getWidth(), detailLabel.getWidth()));
        });
    }

    /// The coordinator mount replaces the list card and the public list command restores it.
    @Test
    public void mountsAndReturnsCoordinatorOwnedManagementView() {
        FakeInstancesModel model = FakeInstancesModel.immediate(items(1), snapshot(0, 1, 0L));
        RecordingManagementFactory factory = new RecordingManagementFactory(null);
        InstanceManagementCoordinator coordinator = new InstanceManagementCoordinator(factory);
        InstancesPanel panel = onEventDispatchThread(
                () -> new InstancesPanel(model, STRINGS, coordinator));

        onEventDispatchThread(() -> {
            assertTrue(findComponent(panel, "instancesListWorkspace").isVisible());
            assertFalse(findComponent(panel, "instancesManagementHost").isVisible());
            findButton(panel, "instancesManage").doClick();
        });
        assertAll(
                () -> assertEquals(1, model.managementRequests.get()),
                () -> assertEquals(0, factory.creationCount()));

        coordinator.open("instance-0").toCompletableFuture().join();
        FakeManagementView view = factory.latestView();
        onEventDispatchThread(() -> assertAll(
                () -> assertFalse(findComponent(panel, "instancesListWorkspace").isVisible()),
                () -> assertTrue(findComponent(panel, "instancesManagementHost").isVisible()),
                () -> assertSame(
                        findComponent(panel, "instancesManagementHost"),
                        view.component().getParent()),
                () -> assertEquals("instance-0", coordinator.currentInstanceId())));

        panel.showInstanceList().toCompletableFuture().join();
        onEventDispatchThread(() -> assertAll(
                () -> assertTrue(findComponent(panel, "instancesListWorkspace").isVisible()),
                () -> assertFalse(findComponent(panel, "instancesManagementHost").isVisible()),
                () -> assertNull(view.component().getParent()),
                () -> assertNull(coordinator.currentInstanceId()),
                () -> assertEquals(1, view.closeCount()),
                () -> assertTrue(view.closedOnEventDispatchThread())));

        panel.close();
        coordinator.close();
    }

    /// The shell can expose the list temporarily without discarding the selected instance's main management view.
    @Test
    public void preservesDefaultManagementBehindTheInstanceListSidePage() {
        FakeInstancesModel model = FakeInstancesModel.immediate(items(2), snapshot(0, 2, 0L));
        RecordingManagementFactory factory = new RecordingManagementFactory(null);
        InstanceManagementCoordinator coordinator = new InstanceManagementCoordinator(factory);
        InstancesPanel panel = onEventDispatchThread(
                () -> new InstancesPanel(model, STRINGS, coordinator));
        AtomicInteger defaultPageReveals = new AtomicInteger();
        onEventDispatchThread(() -> panel.setRevealDefaultPageCommand(defaultPageReveals::incrementAndGet));

        onEventDispatchThread(() -> {
            panel.showSelectedInstanceManagement().toCompletableFuture().join();
        });
        FakeManagementView firstView = factory.latestView();
        onEventDispatchThread(() -> {
            assertAll(
                    () -> assertEquals("instance-0", coordinator.currentInstanceId()),
                    () -> assertTrue(findComponent(panel, "instancesManagementHost").isVisible()),
                    () -> assertEquals(1, factory.creationCount()));

            panel.showInstanceListPage();
            assertAll(
                    () -> assertTrue(findComponent(panel, "instancesListWorkspace").isVisible()),
                    () -> assertSame(
                            findComponent(panel, "instancesManagementHost"),
                            firstView.component().getParent()),
                    () -> assertEquals(0, firstView.closeCount()));

            panel.showSelectedInstanceManagement().toCompletableFuture().join();
            assertAll(
                    () -> assertTrue(findComponent(panel, "instancesManagementHost").isVisible()),
                    () -> assertEquals(1, factory.creationCount()),
                    () -> assertEquals(0, firstView.closeCount()));

            panel.showInstanceListPage();
            coordinator.open("instance-0").toCompletableFuture().join();
            assertAll(
                    () -> assertTrue(findComponent(panel, "instancesManagementHost").isVisible()),
                    () -> assertEquals(2, factory.creationCount()),
                    () -> assertEquals(1, firstView.closeCount()),
                    () -> assertEquals(1, defaultPageReveals.get()));

            model.selectInstance("instance-1");
            assertAll(
                    () -> assertEquals("instance-1", coordinator.currentInstanceId()),
                    () -> assertEquals(3, factory.creationCount()),
                    () -> assertEquals(1, firstView.closeCount()));

            FakeManagementView secondView = factory.latestView();
            model.selectionContextRevision++;
            model.replaceItemsAndPublish(items(2), snapshot(1, 2, 1L));
            assertAll(
                    () -> assertEquals("instance-1", coordinator.currentInstanceId()),
                    () -> assertEquals(4, factory.creationCount()),
                    () -> assertEquals(1, secondView.closeCount()));
            panel.close();
            coordinator.close();
        });
    }

    /// A worker close synchronously detaches the host, closes its active view once, and is idempotent.
    @Test
    public void closesActiveManagementViewSynchronouslyFromWorker() throws InterruptedException {
        FakeInstancesModel model = FakeInstancesModel.immediate(items(1), snapshot(0, 1, 0L));
        RecordingManagementFactory factory = new RecordingManagementFactory(null);
        InstanceManagementCoordinator coordinator = new InstanceManagementCoordinator(factory);
        InstancesPanel panel = onEventDispatchThread(
                () -> new InstancesPanel(model, STRINGS, coordinator));
        coordinator.open("instance-0").toCompletableFuture().join();
        FakeManagementView view = factory.latestView();

        AtomicReference<@Nullable Throwable> closeFailure = new AtomicReference<>();
        Thread closer = new Thread(() -> {
            try {
                panel.close();
            } catch (RuntimeException | Error failure) {
                closeFailure.set(failure);
            }
        }, "instances-panel-worker-closer");
        closer.start();
        closer.join();
        panel.close();

        CompletionException noHost = assertThrows(
                CompletionException.class,
                () -> coordinator.open("instance-after-close").toCompletableFuture().join());
        onEventDispatchThread(() -> {
            invokeRegisteredActions(findButton(panel, "instancesRefresh"));
            invokeRegisteredActions(findButton(panel, "instancesAdd"));
            invokeRegisteredActions(findButton(panel, "instancesManage"));
            assertAll(
                    () -> assertNull(closeFailure.get()),
                    () -> assertNull(coordinator.currentInstanceId()),
                    () -> assertNull(view.component().getParent()),
                    () -> assertEquals(1, view.closeCount()),
                    () -> assertTrue(view.closedOnEventDispatchThread()),
                    () -> assertFalse(model.hasSubscribers()),
                    () -> assertEquals(0, panel.choiceList().getChoiceModel().getSize()),
                    () -> assertFalse(findButton(panel, "instancesRefresh").isEnabled()),
                    () -> assertFalse(findButton(panel, "instancesAdd").isEnabled()),
                    () -> assertFalse(findButton(panel, "instancesManage").isEnabled()),
                    () -> assertEquals(0, model.refreshes.get()),
                    () -> assertEquals(0, model.additions.get()),
                    () -> assertEquals(0, model.managementRequests.get()),
                    () -> assertTrue(noHost.getCause() instanceof IllegalStateException));
        });
        coordinator.close();
    }

    /// Close preserves the first detach failure while attempting every later cleanup exactly once.
    @Test
    public void preservesSuppressedFailuresWhileCompletingCleanup() {
        RuntimeException viewFailure = new RuntimeException("view close failed");
        RuntimeException modelFailure = new RuntimeException("model unsubscribe failed");
        FakeInstancesModel model = FakeInstancesModel.immediate(items(1), snapshot(0, 1, 0L));
        RecordingManagementFactory factory = new RecordingManagementFactory(viewFailure);
        InstanceManagementCoordinator coordinator = new InstanceManagementCoordinator(factory);
        InstancesPanel panel = onEventDispatchThread(
                () -> new InstancesPanel(model, STRINGS, coordinator));
        coordinator.open("instance-0").toCompletableFuture().join();
        FakeManagementView view = factory.latestView();
        model.failUnsubscribeWith(modelFailure);

        RuntimeException actualFailure = assertThrows(RuntimeException.class, panel::close);
        panel.close();

        onEventDispatchThread(() -> assertAll(
                () -> assertSame(viewFailure, actualFailure),
                () -> assertEquals(1, actualFailure.getSuppressed().length),
                () -> assertSame(modelFailure, actualFailure.getSuppressed()[0]),
                () -> assertEquals(1, view.closeCount()),
                () -> assertTrue(view.closedOnEventDispatchThread()),
                () -> assertNull(view.component().getParent()),
                () -> assertFalse(model.hasSubscribers()),
                () -> assertEquals(0, panel.choiceList().getChoiceModel().getSize())));
        coordinator.close();
    }

    /// A null model subscription fails construction before host attachment without leaking state.
    @Test
    public void cleansUpWhenModelReturnsNullSubscription() {
        FakeInstancesModel model = FakeInstancesModel.immediate(items(1), snapshot(0, 1, 0L));
        model.returnNullSubscription();
        RecordingManagementFactory factory = new RecordingManagementFactory(null);
        InstanceManagementCoordinator coordinator = new InstanceManagementCoordinator(factory);

        NullPointerException failure = assertThrows(
                NullPointerException.class,
                () -> onEventDispatchThread(() -> new InstancesPanel(model, STRINGS, coordinator)));

        assertAll(
                () -> assertEquals("model returned null subscription", failure.getMessage()),
                () -> assertFalse(model.hasSubscribers()),
                () -> assertEquals(0, factory.creationCount()));
        coordinator.close();
    }

    /// A rejected host attachment releases the model subscription acquired earlier in construction.
    @Test
    public void cleansUpWhenCoordinatorRejectsHostAttachment() {
        FakeInstancesModel model = FakeInstancesModel.immediate(items(1), snapshot(0, 1, 0L));
        RecordingManagementFactory factory = new RecordingManagementFactory(null);
        InstanceManagementCoordinator coordinator = new InstanceManagementCoordinator(factory);
        Subscription occupiedLease = coordinator.attachHost(new TestManagementHost());

        assertThrows(
                IllegalStateException.class,
                () -> onEventDispatchThread(() -> new InstancesPanel(model, STRINGS, coordinator)));

        assertAll(
                () -> assertFalse(model.hasSubscribers()),
                () -> assertTrue(occupiedLease.isSubscribed()),
                () -> assertEquals(0, factory.creationCount()));
        occupiedLease.unsubscribe();
        coordinator.close();
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
                    "Minecraft " + (index % 2 == 0 ? "1.21.1" : "1.20.1"),
                    solidIcon(0)));
        }
        return List.copyOf(result);
    }

    /// Creates one opaque fixed-size icon for deterministic renderer verification.
    ///
    /// @param argb packed ARGB color used for every pixel
    /// @return immutable normalized icon data
    private static InstanceIconData solidIcon(int argb) {
        int[] pixels = new int[InstanceIconData.PIXEL_COUNT];
        java.util.Arrays.fill(pixels, argb);
        return new InstanceIconData(pixels);
    }

    /// Creates and off-screen paints an instance panel containing one explicit icon.
    ///
    /// @param icon normalized icon to paint
    /// @return rendered page image
    private static BufferedImage renderPanelWithIcon(InstanceIconData icon) {
        FakeInstancesModel model = FakeInstancesModel.immediate(
                List.of(new InstanceListItem(
                        "icon-instance",
                        "Icon instance",
                        "Minecraft 1.21.1",
                        icon)),
                snapshot(0, 1, 0L));
        RecordingManagementFactory factory = new RecordingManagementFactory(null);
        InstanceManagementCoordinator coordinator = new InstanceManagementCoordinator(factory);
        InstancesPanel panel = onEventDispatchThread(() -> new InstancesPanel(model, STRINGS, coordinator));
        return onEventDispatchThread(() -> {
            panel.setSize(new Dimension(720, 420));
            layoutRecursively(panel);
            panel.choiceList().refreshLoadPlan();
            JList<ChoiceListEntry<InstanceListItem>> list = panel.choiceList().getList();
            assertTrue(list.getCellRenderer() instanceof InstanceListCellRenderer);
            assertEquals(InstanceListCellRenderer.ROW_HEIGHT,
                    list.getFixedCellHeight());

            ChoiceListEntry<InstanceListItem> entry = list.getModel().getElementAt(0);
            assertSame(icon, Objects.requireNonNull(entry.value(), "instance row was not loaded").icon());
            ListCellRenderer<? super ChoiceListEntry<InstanceListItem>> renderer = list.getCellRenderer();
            Component row = renderer.getListCellRendererComponent(list, entry, 0, true, true);
            Dimension size = new Dimension(640, list.getFixedCellHeight());
            row.setSize(size);
            if (row instanceof Container rowContainer) {
                layoutRecursively(rowContainer);
            }

            BufferedImage rendered = new BufferedImage(
                    size.width,
                    size.height,
                    BufferedImage.TYPE_INT_ARGB);
            Graphics2D graphics = rendered.createGraphics();
            try {
                row.printAll(graphics);
            } finally {
                graphics.dispose();
                panel.close();
                coordinator.close();
            }
            return rendered;
        });
    }

    /// Creates a normal command-enabled snapshot.
    ///
    /// @param selectedIndex selected source index, or -1 for no selection
    /// @param itemCount exact item count
    /// @param revision content revision
    /// @return enabled snapshot
    private static InstancesSnapshot snapshot(int selectedIndex, int itemCount, long revision) {
        OptionalInt selected = selectedIndex < 0
                ? OptionalInt.empty()
                : OptionalInt.of(selectedIndex);
        return new InstancesSnapshot(selected, itemCount, revision, "Ready", false, true, true, true, true);
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

    /// Invokes every registered action directly to verify handler gates independent of button state.
    ///
    /// @param button command button whose handlers should run
    private static void invokeRegisteredActions(AbstractButton button) {
        ActionEvent event = new ActionEvent(button, ActionEvent.ACTION_PERFORMED, "test");
        for (java.awt.event.ActionListener listener : button.getActionListeners()) {
            listener.actionPerformed(event);
        }
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

    /// Drains coalesced document updates and viewport completions queued on the EDT.
    private static void flushEventDispatchThread() {
        EdtDispatcher.executeAndWait(() -> { });
        EdtDispatcher.executeAndWait(() -> { });
    }

    /// Returns whether a component hierarchy contains one Swing component type.
    ///
    /// @param root hierarchy root
    /// @param type component type to locate
    /// @return whether a matching descendant exists
    private static boolean containsComponentType(Container root, Class<? extends Component> type) {
        for (Component child : root.getComponents()) {
            if (type.isInstance(child)) {
                return true;
            }
            if (child instanceof Container nested && containsComponentType(nested, type)) {
                return true;
            }
        }
        return false;
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

    /// Counts exact occurrences of one packed ARGB color in an off-screen rendering.
    ///
    /// @param image rendered instance page
    /// @param argb packed ARGB color to count
    /// @return matching pixel count
    private static int countPixels(BufferedImage image, int argb) {
        int matches = 0;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                if (image.getRGB(x, y) == argb) {
                    matches++;
                }
            }
        }
        return matches;
    }

    /// Records coordinator-created management views for panel lifecycle assertions.
    @NotNullByDefault
    private static final class RecordingManagementFactory implements InstanceManagementViewFactory {
        /// Failure raised whenever a created view closes, or null for successful close.
        private final @Nullable RuntimeException closeFailure;

        /// Most recently created view, or null before the first factory call.
        private final AtomicReference<@Nullable FakeManagementView> latestView = new AtomicReference<>();

        /// Number of factory invocations.
        private final AtomicInteger creations = new AtomicInteger();

        /// Creates a recording factory.
        ///
        /// @param closeFailure failure raised by created views, or null for successful close
        private RecordingManagementFactory(@Nullable RuntimeException closeFailure) {
            this.closeFailure = closeFailure;
        }

        /// Creates and records one management view on the EDT.
        ///
        /// @param instanceId stable repository instance identifier
        /// @param returnCommand command returning to the instances list
        /// @return newly recorded management view
        @Override
        public InstanceManagementView create(String instanceId, Runnable returnCommand) {
            EdtDispatcher.requireEventDispatchThread();
            FakeManagementView view = new FakeManagementView(
                    instanceId,
                    returnCommand,
                    closeFailure);
            latestView.set(view);
            creations.incrementAndGet();
            return view;
        }

        /// Returns the latest created view.
        ///
        /// @return latest management view
        private FakeManagementView latestView() {
            return Objects.requireNonNull(latestView.get(), "management view was not created");
        }

        /// Returns the factory invocation count.
        ///
        /// @return number of created views
        private int creationCount() {
            return creations.get();
        }
    }

    /// Coordinator-owned test view recording return and close behavior.
    @NotNullByDefault
    private static final class FakeManagementView implements InstanceManagementView {
        /// Stable instance identifier represented by this view.
        private final String instanceId;

        /// Component mounted into the panel's management host.
        private final JPanel component = new JPanel();

        /// Coordinator callback returning to the list card.
        private final Runnable returnCommand;

        /// Configured close failure, or null for successful close.
        private final @Nullable RuntimeException closeFailure;

        /// Number of close invocations.
        private final AtomicInteger closes = new AtomicInteger();

        /// Whether the latest close invocation ran on the EDT.
        private volatile boolean closeOnEventDispatchThread;

        /// Creates a recording management view.
        ///
        /// @param instanceId stable instance identifier
        /// @param returnCommand coordinator return command
        /// @param closeFailure close failure, or null for successful close
        private FakeManagementView(
                String instanceId,
                Runnable returnCommand,
                @Nullable RuntimeException closeFailure) {
            this.instanceId = instanceId;
            this.returnCommand = returnCommand;
            this.closeFailure = closeFailure;
            component.setName("instanceManagementTestView");
        }

        /// Returns the represented stable identifier.
        ///
        /// @return stable instance identifier
        @Override
        public String instanceId() {
            return instanceId;
        }

        /// Returns the component mounted into the panel.
        ///
        /// @return management root component
        @Override
        public JComponent component() {
            return component;
        }

        /// Invokes the coordinator-provided return command from the current thread.
        private void requestReturn() {
            returnCommand.run();
        }

        /// Records one EDT close before raising any configured failure.
        @Override
        public void close() {
            closeOnEventDispatchThread = SwingUtilities.isEventDispatchThread();
            closes.incrementAndGet();
            if (closeFailure != null) {
                throw closeFailure;
            }
        }

        /// Returns the close invocation count.
        ///
        /// @return number of close calls
        private int closeCount() {
            return closes.get();
        }

        /// Returns whether close ran on the EDT.
        ///
        /// @return whether close observed the EDT
        private boolean closedOnEventDispatchThread() {
            return closeOnEventDispatchThread;
        }
    }

    /// Minimal occupied coordinator host used to force a second-attachment failure.
    @NotNullByDefault
    private static final class TestManagementHost implements InstanceManagementHost {
        /// Accepts a component because this host exists only to occupy the exclusive lease.
        ///
        /// @param component ignored coordinator-owned component
        @Override
        public void showManagementView(JComponent component) {
            Objects.requireNonNull(component, "component");
            EdtDispatcher.requireEventDispatchThread();
        }

        /// Accepts list restoration while the occupied lease is released.
        @Override
        public void showInstanceList() {
            EdtDispatcher.requireEventDispatchThread();
        }
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

        /// Stable identifiers requested by a filtered projection.
        private final List<String> requestedStableIds = new ArrayList<>();

        /// Selected stable instance identifiers.
        private final List<String> selectedIds = new ArrayList<>();

        /// Stable instance identifiers observed by management commands.
        private final List<String> managedIds = new ArrayList<>();

        /// Refresh command count.
        private final AtomicInteger refreshes = new AtomicInteger();

        /// Add command count.
        private final AtomicInteger additions = new AtomicInteger();

        /// Manage command count.
        private final AtomicInteger managementRequests = new AtomicInteger();

        /// Selected-directory context revision, or zero for ordinary static model tests.
        private long selectionContextRevision;

        /// Failure raised after the backing model listener is removed, or null for normal cleanup.
        private volatile @Nullable RuntimeException unsubscribeFailure;

        /// Whether subscribe intentionally violates its non-null contract for construction testing.
        private boolean returnNullSubscription;

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
            if (returnNullSubscription) {
                @Nullable Subscription missingSubscription = null;
                return missingSubscription;
            }
            Subscription delegate = changes.subscribe(listener);
            return Subscription.create(() -> {
                delegate.unsubscribe();
                @Nullable RuntimeException failure = unsubscribeFailure;
                if (failure != null) {
                    throw failure;
                }
            });
        }

        /// Returns the exact immutable source count.
        @Override
        public OptionalInt exactItemCount() {
            return OptionalInt.of(items.size());
        }

        /// Returns exact stable instance identifiers without loading row details.
        @Override
        public synchronized @Unmodifiable List<String> stableItemIds() {
            List<String> identifiers = new ArrayList<>(items.size());
            for (InstanceListItem item : items) {
                identifiers.add(item.id());
            }
            return List.copyOf(identifiers);
        }

        /// Returns stable IDs paired with their independently searchable visible names.
        @Override
        public synchronized @Unmodifiable List<InstanceSearchEntry> searchEntries() {
            List<InstanceSearchEntry> entries = new ArrayList<>(items.size());
            for (InstanceListItem item : items) {
                entries.add(new InstanceSearchEntry(item.id(), item.name()));
            }
            return List.copyOf(entries);
        }

        /// Loads one exact fake row by stable instance identifier.
        @Override
        public synchronized CompletionStage<InstanceListItem> loadItem(
                String stableId,
                LoadCancellation cancellation) {
            Objects.requireNonNull(stableId, "stableId");
            Objects.requireNonNull(cancellation, "cancellation").throwIfCancelled();
            requestedStableIds.add(stableId);
            for (InstanceListItem item : items) {
                if (item.id().equals(stableId)) {
                    return CompletableFuture.completedFuture(item);
                }
            }
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("Unknown fake instance: " + stableId));
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
        public void selectInstance(String instanceId) {
            InstancesSnapshot previous;
            InstancesSnapshot replacement;
            synchronized (this) {
                selectedIds.add(instanceId);
                int selectedIndex = -1;
                for (int index = 0; index < items.size(); index++) {
                    if (items.get(index).id().equals(instanceId)) {
                        selectedIndex = index;
                        break;
                    }
                }
                if (selectedIndex < 0) {
                    throw new IllegalArgumentException("Unknown fake instance: " + instanceId);
                }
                previous = current.get();
                replacement = new InstancesSnapshot(
                        OptionalInt.of(selectedIndex),
                        previous.itemCount(),
                        previous.contentRevision(),
                        previous.statusText(),
                        previous.refreshing(),
                        previous.listEnabled(),
                        previous.refreshEnabled(),
                        previous.addEnabled(),
                        true);
                current.set(replacement);
            }
            changes.fireChange(previous, replacement);
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
            InstancesSnapshot snapshot = current.get();
            if (snapshot.selectedIndex().isPresent()) {
                int selectedIndex = snapshot.selectedIndex().getAsInt();
                synchronized (this) {
                    managedIds.add(items.get(selectedIndex).id());
                }
            }
        }

        /// Returns the selected-directory context revision configured by the focused test.
        @Override
        public long selectionContextRevision() {
            return selectionContextRevision;
        }

        /// Returns a snapshot of captured request ranges.
        ///
        /// @return immutable ranges in invocation order
        private synchronized @Unmodifiable List<IndexRange> requestedRanges() {
            return List.copyOf(requestedRanges);
        }

        /// Returns stable identifiers requested through filtered single-row loading.
        ///
        /// @return immutable requested identifiers in invocation order
        private synchronized @Unmodifiable List<String> requestedStableIds() {
            return List.copyOf(requestedStableIds);
        }

        /// Returns a snapshot of selected instance identifiers.
        ///
        /// @return immutable selected identifiers in command order
        private synchronized @Unmodifiable List<String> selectedIds() {
            return List.copyOf(selectedIds);
        }

        /// Returns stable identifiers observed by management commands.
        ///
        /// @return immutable managed identifiers in command order
        private synchronized @Unmodifiable List<String> managedIds() {
            return List.copyOf(managedIds);
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

        /// Configures this fake to violate the subscription result contract.
        private void returnNullSubscription() {
            returnNullSubscription = true;
        }

        /// Configures a failure raised after listener removal during unsubscription.
        ///
        /// @param failure exact cleanup failure to raise
        private void failUnsubscribeWith(RuntimeException failure) {
            unsubscribeFailure = Objects.requireNonNull(failure, "failure");
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
