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
import space.minecraftstl.xyml.download.RemoteVersion;
import space.minecraftstl.xyml.game.install.DefaultGameInstallService;
import space.minecraftstl.xyml.game.install.GameInstallRequest;
import space.minecraftstl.xyml.game.install.GameInstallRequestRejectedException;
import space.minecraftstl.xyml.game.install.GameInstallService;
import space.minecraftstl.xyml.game.install.GameInstallSession;
import space.minecraftstl.xyml.game.install.GameInstallStatus;
import space.minecraftstl.xyml.observable.Subscription;
import space.minecraftstl.xyml.observable.ValueChangeListener;
import space.minecraftstl.xyml.observable.ValueChangeSupport;
import space.minecraftstl.xyml.observable.property.ReadOnlyProperty;
import space.minecraftstl.xyml.task.Task;
import space.minecraftstl.xyml.task.presentation.TaskSnapshot;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;
import space.minecraftstl.xyml.ui.swing.choice.ChoiceListEntry;
import space.minecraftstl.xyml.ui.swing.choice.ChoicePage;
import space.minecraftstl.xyml.ui.swing.choice.IndexRange;
import space.minecraftstl.xyml.ui.swing.choice.LoadCancellation;
import space.minecraftstl.xyml.ui.swing.page.downloads.loaders.DefaultGameLoaderCatalogModel;
import space.minecraftstl.xyml.ui.swing.page.downloads.loaders.GameLoaderCatalogItem;
import space.minecraftstl.xyml.ui.swing.page.downloads.loaders.GameLoaderCatalogRequest;
import space.minecraftstl.xyml.ui.swing.page.downloads.loaders.GameLoaderCatalogSource;
import space.minecraftstl.xyml.ui.swing.page.downloads.loaders.GameLoaderKind;
import space.minecraftstl.xyml.ui.swing.page.downloads.loaders.LoaderSelectionWizardPanel;
import space.minecraftstl.xyml.ui.swing.page.downloads.loaders.LoaderSelectionWizardStrings;
import space.minecraftstl.xyml.ui.swing.task.TaskProgressStrings;

import javax.swing.AbstractButton;
import javax.swing.Action;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JTabbedPane;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Rectangle;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.time.Duration;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

    /// Explicit localized vanilla-installation text used by the page fixture.
    private static final GameInstallStrings INSTALL_STRINGS = new GameInstallStrings(
            "Instance name",
            "Install",
            "Back to versions",
            "Install game",
            "Preparing installation",
            "Invalid name",
            "Instance already exists",
            "Another installation is running",
            "Installation failed");

    /// Explicit localized task-progress text used by the page fixture.
    private static final TaskProgressStrings TASK_STRINGS = new TaskProgressStrings(
            "Waiting",
            "Running",
            "Completed",
            "Failed",
            "Cancelled",
            "Task progress",
            "Cancel",
            "Show details",
            "Hide details");

    /// Construction performs no source load and the first display delegates lazy loading.
    @Test
    public void startsLoadingOnlyAfterDisplayNotification() {
        FakeCatalogModel model = FakeCatalogModel.immediate(List.of(), snapshot(
                -1, 0, 0L, GameVersionCatalogStatus.IDLE, "Waiting", false, true));
        GameVersionCatalogPanel panel = onEventDispatchThread(
                () -> createPanel(model));

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

    /// Keeps the download-center background visible through every catalog canvas and unselected row.
    @Test
    public void leavesBackgroundVisibleThroughCatalogHierarchyAndRows() {
        GameVersionCatalogItem item = new GameVersionCatalogItem(
                "1.21.4",
                GameVersionKind.RELEASE,
                Optional.of(Instant.parse("2024-12-03T10:00:00Z")));
        FakeCatalogModel model = FakeCatalogModel.immediate(
                List.of(item),
                snapshot(0, 1, 1L, GameVersionCatalogStatus.READY, "Ready", true, true));
        GameVersionCatalogPanel panel = onEventDispatchThread(() -> createPanel(model));

        onEventDispatchThread(() -> {
            JTabbedPane tabs = findComponent(panel, "downloadCenterTabs", JTabbedPane.class);
            JList<ChoiceListEntry<GameVersionCatalogItem>> list = panel.choiceList().getList();
            ChoiceListEntry<GameVersionCatalogItem> entry = ChoiceListEntry.loaded(0, item);
            boolean unselectedOpaque = ((JComponent) list.getCellRenderer()
                    .getListCellRendererComponent(list, entry, 0, false, false)).isOpaque();
            boolean selectedOpaque = ((JComponent) list.getCellRenderer()
                    .getListCellRendererComponent(list, entry, 0, true, false)).isOpaque();

            assertAll(
                    () -> assertFalse(panel.isOpaque()),
                    () -> assertFalse(tabs.isOpaque()),
                    () -> assertFalse(findComponent(
                            panel,
                            "gameVersionsContentCards",
                            JComponent.class).isOpaque()),
                    () -> assertFalse(panel.choiceList().isOpaque()),
                    () -> assertFalse(panel.choiceList().getViewport().isOpaque()),
                    () -> assertFalse(list.isOpaque()),
                    () -> assertFalse(unselectedOpaque),
                    () -> assertTrue(selectedOpaque));
            for (int index = 0; index < tabs.getTabCount(); index++) {
                assertFalse(((JComponent) tabs.getComponentAt(index)).isOpaque());
            }
            panel.close();
        });
    }

    /// Controls delegate stable values and first demand warms one measured viewport ahead.
    @Test
    public void delegatesControlsAndUsesMeasuredVisibleRange() {
        FakeCatalogModel model = FakeCatalogModel.immediate(
                items(1_000),
                snapshot(-1, 1_000, 1L, GameVersionCatalogStatus.READY, "Ready", true, true));
        GameVersionCatalogPanel panel = onEventDispatchThread(
                () -> createPanel(model));

        onEventDispatchThread(() -> {
            panel.setSize(new Dimension(820, 520));
            layoutRecursively(panel);
            panel.choiceList().refreshLoadPlan();

            JList<ChoiceListEntry<GameVersionCatalogItem>> list = panel.choiceList().getList();
            IndexRange requested = model.requestedRanges().get(0);
            int viewportHeight = panel.choiceList().getViewport().getExtentSize().height;
            int rowHeight = list.getFixedCellHeight();
            int visibleRows = (viewportHeight + rowHeight - 1) / rowHeight;

            JTextField search = findTextField(panel, "gameVersionsSearch");
            JTextField instanceName = findTextField(panel, "gameVersionsInstanceName");
            assertAll(
                    () -> assertEquals(Boolean.TRUE,
                            search.getClientProperty("JTextField.showClearButton")),
                    () -> assertEquals(Boolean.TRUE,
                            instanceName.getClientProperty("JTextField.showClearButton")));
            search.setText("version-9");
            findFilterButton(panel, GameVersionFilter.SNAPSHOT).doClick();
            findButton(panel, "gameVersionsRefresh").doClick();
            list.setSelectedIndex(1);

            assertAll(
                    () -> assertEquals(javax.swing.ListSelectionModel.SINGLE_SELECTION,
                            list.getSelectionMode()),
                    () -> assertEquals(visibleRows * 2, requested.length()),
                    () -> assertTrue(requested.length() < model.exactItemCount().orElseThrow()),
                    () -> assertEquals(List.of("version-9"), model.queries()),
                    () -> assertEquals(List.of(GameVersionFilter.SNAPSHOT), model.filters()),
                    () -> assertEquals(1, model.refreshes.get()),
                    () -> assertEquals(List.of("version-1"), model.selectedIds()));
            panel.close();
        });
    }

    /// Shows all five kind filters at once and renders loaded rows with kind and publication metadata.
    @Test
    public void exposesVisibleKindFiltersAndVersionMetadata() {
        GameVersionCatalogItem release = new GameVersionCatalogItem(
                "1.21.4",
                GameVersionKind.RELEASE,
                Optional.of(Instant.parse("2024-12-03T10:00:00Z")));
        FakeCatalogModel model = FakeCatalogModel.immediate(
                List.of(release),
                snapshot(0, 1, 1L, GameVersionCatalogStatus.READY, "Ready", true, true));
        GameVersionCatalogPanel panel = onEventDispatchThread(() -> createPanel(model));

        onEventDispatchThread(() -> {
            panel.setSize(new Dimension(820, 520));
            layoutRecursively(panel);
            panel.choiceList().refreshLoadPlan();

            for (GameVersionFilter filter : GameVersionFilter.values()) {
                JToggleButton button = findFilterButton(panel, filter);
                assertAll(
                        () -> assertTrue(button.isVisible()),
                        () -> assertEquals(STRINGS.filterText(filter), button.getText()));
            }
            assertTrue(findFilterButton(panel, GameVersionFilter.ALL).isSelected());

            JList<ChoiceListEntry<GameVersionCatalogItem>> list = panel.choiceList().getList();
            Component rendered = list.getCellRenderer().getListCellRendererComponent(
                    list,
                    ChoiceListEntry.loaded(0, release),
                    0,
                    false,
                    false);
            Container row = (Container) rendered;
            JLabel title = findComponent(row, "gameVersionRowTitle", JLabel.class);
            JLabel metadata = findComponent(row, "gameVersionRowMetadata", JLabel.class);
            assertAll(
                    () -> assertEquals("1.21.4", title.getText()),
                    () -> assertTrue(metadata.getText().contains("Release")),
                    () -> assertTrue(metadata.getText().contains("2024")));
            panel.close();
        });
    }

    /// Keeps installation controls inside common viewport bounds and activates them by Enter or double click.
    @Test
    public void activatesReachableInstallConfigurationFromKeyboardAndMouse() {
        FakeCatalogModel model = FakeCatalogModel.immediate(
                items(40),
                snapshot(-1, 40, 1L, GameVersionCatalogStatus.READY, "Ready", true, true));
        GameVersionCatalogPanel panel = onEventDispatchThread(() -> createPanel(model));

        onEventDispatchThread(() -> {
            for (Dimension size : List.of(new Dimension(820, 420), new Dimension(1024, 720))) {
                panel.setSize(size);
                layoutRecursively(panel);
                JPanel configuration = findComponent(
                        panel,
                        "gameVersionsInstallConfiguration",
                        JPanel.class);
                AbstractButton install = findButton(panel, "gameVersionsInstall");
                Rectangle panelBounds = new Rectangle(0, 0, panel.getWidth(), panel.getHeight());
                Rectangle configurationBounds = boundsRelativeTo(panel, configuration);
                Rectangle installBounds = boundsRelativeTo(panel, install);
                assertAll(
                        () -> assertTrue(configurationBounds.width > 0),
                        () -> assertTrue(configurationBounds.height > 0),
                        () -> assertTrue(
                                panelBounds.contains(configurationBounds),
                                () -> "configuration " + configurationBounds + " outside " + panelBounds),
                        () -> assertTrue(
                                panelBounds.contains(installBounds),
                                () -> "install " + installBounds + " outside " + panelBounds));
            }

            panel.choiceList().refreshLoadPlan();
            JList<ChoiceListEntry<GameVersionCatalogItem>> list = panel.choiceList().getList();
            list.setSelectedIndex(0);
            JTextField instanceName = findTextField(panel, "gameVersionsInstanceName");
            assertEquals("version-0", instanceName.getText());

            instanceName.select(0, 0);
            KeyStroke enter = KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0);
            @Nullable Object actionKey = list.getInputMap(JComponent.WHEN_FOCUSED).get(enter);
            assertNotNull(actionKey);
            @Nullable Action activation = list.getActionMap().get(actionKey);
            assertNotNull(activation);
            Objects.requireNonNull(activation, "Enter activation action").actionPerformed(
                    new ActionEvent(list, ActionEvent.ACTION_PERFORMED, "enter"));
            assertEquals(instanceName.getText().length(), instanceName.getSelectionEnd());

            instanceName.select(0, 0);
            @Nullable Rectangle selectedCell = list.getCellBounds(0, 0);
            assertNotNull(selectedCell);
            Rectangle cell = Objects.requireNonNull(selectedCell, "selected cell bounds");
            list.dispatchEvent(new MouseEvent(
                    list,
                    MouseEvent.MOUSE_CLICKED,
                    System.currentTimeMillis(),
                    0,
                    cell.x + 1,
                    cell.y + 1,
                    2,
                    false,
                    MouseEvent.BUTTON1));
            assertEquals(instanceName.getText().length(), instanceName.getSelectionEnd());
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
                () -> createPanel(model));

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

    /// Cancels delayed configuration focus when selection moves away from the activated sparse row.
    @Test
    public void doesNotTransferPendingActivationToAnotherVersion() {
        FakeCatalogModel model = FakeCatalogModel.controlled(
                items(40),
                snapshot(-1, 40, 1L, GameVersionCatalogStatus.READY, "Ready", true, true));
        GameVersionCatalogPanel panel = onEventDispatchThread(() -> createPanel(model));

        onEventDispatchThread(() -> {
            panel.setSize(new Dimension(820, 420));
            layoutRecursively(panel);
            panel.choiceList().refreshLoadPlan();

            JList<ChoiceListEntry<GameVersionCatalogItem>> list = panel.choiceList().getList();
            list.setSelectedIndex(0);
            KeyStroke enter = KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0);
            @Nullable Object actionKey = list.getInputMap(JComponent.WHEN_FOCUSED).get(enter);
            @Nullable Action activation = actionKey == null ? null : list.getActionMap().get(actionKey);
            Objects.requireNonNull(activation, "Enter activation action").actionPerformed(
                    new ActionEvent(list, ActionEvent.ACTION_PERFORMED, "enter"));

            list.setSelectedIndex(1);
            JTextField instanceName = findTextField(panel, "gameVersionsInstanceName");
            instanceName.setText("custom-name");
            instanceName.select(0, 0);
        });

        model.completePendingLoads();
        EdtDispatcher.executeAndWait(() -> { });

        onEventDispatchThread(() -> {
            JTextField instanceName = findTextField(panel, "gameVersionsInstanceName");
            assertAll(
                    () -> assertEquals("custom-name", instanceName.getText()),
                    () -> assertEquals(0, instanceName.getSelectionStart()),
                    () -> assertEquals(0, instanceName.getSelectionEnd()),
                    () -> assertEquals(List.of("version-1"), model.selectedIds()));
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
                () -> createPanel(model));
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
            findFilterButton(panel, GameVersionFilter.RELEASE).doClick();
            assertAll(
                    () -> assertEquals(failed, panel.displayedSnapshot()),
                    () -> assertEquals(0, model.refreshes.get()),
                    () -> assertEquals(List.of(), model.queries()),
                    () -> assertEquals(List.of(), model.filters()));
        });
    }

    /// Installs the exact loaded choice, derives only from its version ID, and preserves a user name.
    @Test
    public void installsExactLoadedChoiceAndPreservesUserAuthoredName() throws Exception {
        FakeCatalogModel model = FakeCatalogModel.immediate(
                items(1_000),
                snapshot(-1, 1_000, 1L, GameVersionCatalogStatus.READY, "Ready", true, true));
        RecordingGameInstallService service = RecordingGameInstallService.completed();
        GameVersionCatalogPanel panel = onEventDispatchThread(() -> createPanel(model, service));

        onEventDispatchThread(() -> {
            panel.setSize(new Dimension(820, 520));
            layoutRecursively(panel);
            panel.choiceList().refreshLoadPlan();
            JList<ChoiceListEntry<GameVersionCatalogItem>> list = panel.choiceList().getList();
            list.setSelectedIndex(1);

            JTextField instanceName = findTextField(panel, "gameVersionsInstanceName");
            assertEquals("version-1", instanceName.getText());
            instanceName.setText("my-instance");
            list.setSelectedIndex(2);
            assertEquals("my-instance", instanceName.getText());
            findButton(panel, "gameVersionsResetInstanceName").doClick();
            assertEquals("version-2", instanceName.getText());
            instanceName.setText("my-instance");

            findButton(panel, "gameVersionsInstall").doClick();
            assertAll(
                    () -> assertEquals(
                            List.of(new GameInstallRequest("my-instance", "version-2")),
                            service.requests()),
                    () -> assertTrue(findComponent(panel, "gameVersionsTaskWorkspace").isVisible()),
                    () -> assertFalse(findComponent(panel, "gameVersionsCatalogWorkspace").isVisible()));
        });

        awaitTerminal(service.latestSession());
        EdtDispatcher.executeAndWait(() -> { });

        onEventDispatchThread(() -> {
            assertTrue(findButton(panel, "gameVersionsBackToCatalog").isEnabled());
            findButton(panel, "gameVersionsBackToCatalog").doClick();
            assertAll(
                    () -> assertTrue(findComponent(panel, "gameVersionsCatalogWorkspace").isVisible()),
                    () -> assertFalse(findComponent(panel, "gameVersionsTaskWorkspace").isVisible()),
                    () -> assertTrue(findButton(panel, "gameVersionsInstall").isEnabled()));

            IndexRange requested = model.requestedRanges().get(0);
            int viewportHeight = panel.choiceList().getViewport().getExtentSize().height;
            int rowHeight = panel.choiceList().getList().getFixedCellHeight();
            int visibleRows = (viewportHeight + rowHeight - 1) / rowHeight;
            assertAll(
                    () -> assertEquals(visibleRows * 2, requested.length()),
                    () -> assertTrue(requested.length() < model.exactItemCount().orElseThrow()));
            panel.close();
        });

        assertEquals(0, service.closeCount());
        service.close();
        assertEquals(1, service.closeCount());
    }

    /// Carries the exact embedded loader selection into installation and clears it for another base version.
    @Test
    public void installsEmbeddedLoaderSelectionAndClearsItWhenBaseVersionChanges() throws Exception {
        RemoteVersion fabric = new RemoteVersion(
                "fabric",
                "1.20.1",
                "0.16.0",
                Instant.EPOCH,
                List.of("https://example.invalid/fabric.jar"));
        FixtureLoaderCatalogSource loaderSource = new FixtureLoaderCatalogSource(fabric);
        LoaderSelectionWizardPanel loaderPanel = onEventDispatchThread(() ->
                new LoaderSelectionWizardPanel(
                        new DefaultGameLoaderCatalogModel(loaderSource),
                        Runnable::run,
                        LoaderSelectionWizardStrings.english()));
        @Unmodifiable List<GameVersionCatalogItem> gameVersions = List.of(
                new GameVersionCatalogItem(
                        "1.20.1",
                        GameVersionKind.RELEASE,
                        Optional.of(Instant.EPOCH)),
                new GameVersionCatalogItem(
                        "1.21.1",
                        GameVersionKind.RELEASE,
                        Optional.of(Instant.EPOCH.plusSeconds(1L))));
        FakeCatalogModel model = FakeCatalogModel.immediate(
                gameVersions,
                snapshot(-1, 2, 1L, GameVersionCatalogStatus.READY, "Ready", true, true));
        RecordingGameInstallService service = RecordingGameInstallService.completed();
        GameVersionCatalogPanel panel = onEventDispatchThread(() ->
                createPanel(model, service, loaderPanel));

        onEventDispatchThread(() -> {
            panel.setSize(new Dimension(900, 720));
            layoutRecursively(panel);
            panel.choiceList().refreshLoadPlan();
            panel.choiceList().getList().setSelectedIndex(0);

            assertEquals(0, loaderSource.requestCount());
            findButton(panel, "gameVersionsLoaders").doClick();
            assertTrue(findComponent(panel, "gameVersionsLoaderWorkspace").isVisible());
            assertEquals("1.20.1", loaderPanel.selectionSnapshot().gameVersion().orElseThrow());
            findButton(panel, "loaderKind_FABRIC").doClick();
            assertEquals(0, loaderSource.requestCount());
            findButton(panel, "loaderLoadVersions").doClick();
            assertEquals(1, loaderSource.requestCount());

            layoutRecursively(panel);
            loaderPanel.versionChoiceList().getViewport().setExtentSize(new Dimension(560, 180));
            loaderPanel.versionChoiceList().getList().setSize(560, 180);
            loaderPanel.versionChoiceList().refreshLoadPlan();
        });
        EdtDispatcher.executeAndWait(() -> { });

        onEventDispatchThread(() -> {
            loaderPanel.versionChoiceList().getList().setSelectedIndex(0);
            findButton(panel, "loaderAddSelection").doClick();
            assertSame(fabric, loaderPanel.selectedRemoteVersions().get(0));
            assertTrue(findComponent(
                    panel,
                    "gameVersionsLoaderSummary",
                    javax.swing.JLabel.class).getText().contains("Fabric 0.16.0"));

            findButton(panel, "gameVersionsBackFromLoaders").doClick();
            assertTrue(findComponent(panel, "gameVersionsCatalogWorkspace").isVisible());
        });

        model.replaceItemsAndPublish(
                gameVersions,
                snapshot(0, 2, 1L, GameVersionCatalogStatus.LOADING, "Refreshing", false, false));
        EdtDispatcher.executeAndWait(() -> { });
        onEventDispatchThread(() -> assertEquals(List.of(fabric), loaderPanel.selectedRemoteVersions()));
        model.replaceItemsAndPublish(
                gameVersions,
                snapshot(0, 2, 1L, GameVersionCatalogStatus.READY, "Ready", true, true));
        EdtDispatcher.executeAndWait(() -> { });

        onEventDispatchThread(() -> {
            assertEquals(List.of(fabric), loaderPanel.selectedRemoteVersions());
            findButton(panel, "gameVersionsInstall").doClick();

            assertEquals(1, service.requests().size());
            GameInstallRequest request = service.requests().get(0);
            assertEquals("1.20.1", request.versionId());
            assertEquals(List.of(fabric), request.selectedRemoteVersions());
            assertSame(fabric, request.selectedRemoteVersions().get(0));
        });

        awaitTerminal(service.latestSession());
        EdtDispatcher.executeAndWait(() -> { });

        onEventDispatchThread(() -> {
            findButton(panel, "gameVersionsBackToCatalog").doClick();
            panel.choiceList().getList().setSelectedIndex(1);
            assertTrue(loaderPanel.selectedRemoteVersions().isEmpty());
            assertEquals("1.21.1", loaderPanel.selectionSnapshot().gameVersion().orElseThrow());
            assertTrue(findComponent(
                    panel,
                    "gameVersionsLoaderSummary",
                    javax.swing.JLabel.class).getText().contains("No loaders selected"));
            panel.close();
        });
        service.close();
    }

    /// Maps repository validation by typed reason while retaining the failed task until dismissal.
    @Test
    public void localizesTypedInstallationRejection() throws Exception {
        FakeCatalogModel model = FakeCatalogModel.immediate(
                items(4),
                snapshot(-1, 4, 1L, GameVersionCatalogStatus.READY, "Ready", true, true));
        RecordingGameInstallService service = RecordingGameInstallService.failingWith(
                GameInstallRequestRejectedException.Reason.INSTANCE_ALREADY_EXISTS);
        GameVersionCatalogPanel panel = onEventDispatchThread(() -> createPanel(model, service));

        onEventDispatchThread(() -> {
            panel.setSize(new Dimension(820, 420));
            layoutRecursively(panel);
            panel.choiceList().refreshLoadPlan();
            panel.choiceList().getList().setSelectedIndex(0);
            findButton(panel, "gameVersionsInstall").doClick();
        });

        awaitTerminal(service.latestSession());
        EdtDispatcher.executeAndWait(() -> { });

        onEventDispatchThread(() -> {
            assertAll(
                    () -> assertEquals(
                            INSTALL_STRINGS.instanceAlreadyExistsStatus(),
                            findComponent(
                                    panel,
                                    "gameVersionsInstallTaskStatus",
                                    javax.swing.JLabel.class).getText()),
                    () -> assertTrue(findButton(panel, "gameVersionsBackToCatalog").isEnabled()));
            panel.close();
        });
        service.close();
    }

    /// Closing the page detaches late task transitions without taking service ownership.
    @Test
    public void closeIgnoresLateInstallationStatusWithoutClosingService() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        FakeCatalogModel model = FakeCatalogModel.immediate(
                items(4),
                snapshot(-1, 4, 1L, GameVersionCatalogStatus.READY, "Ready", true, true));
        RecordingGameInstallService service = RecordingGameInstallService.blocking(entered, release);
        GameVersionCatalogPanel panel = onEventDispatchThread(() -> createPanel(model, service));

        onEventDispatchThread(() -> {
            panel.setSize(new Dimension(820, 420));
            layoutRecursively(panel);
            panel.choiceList().refreshLoadPlan();
            panel.choiceList().getList().setSelectedIndex(0);
            findButton(panel, "gameVersionsInstall").doClick();
        });
        assertTrue(entered.await(5L, TimeUnit.SECONDS));

        panel.close();
        assertEquals(0, service.closeCount());
        release.countDown();
        awaitTerminal(service.latestSession());
        EdtDispatcher.executeAndWait(() -> { });

        onEventDispatchThread(() -> assertAll(
                () -> assertFalse(findButton(panel, "gameVersionsBackToCatalog").isEnabled()),
                () -> assertFalse(findTextField(panel, "gameVersionsInstanceName").isEnabled())));
        service.close();
        assertEquals(1, service.closeCount());
    }

    /// A task-surface binding failure cancels the started session and restores visible catalog feedback.
    @Test
    public void presentationBindingFailureRollsBackStartedInstallation() throws Exception {
        IllegalStateException bindingFailure = new IllegalStateException("status subscription failed");
        FakeCatalogModel model = FakeCatalogModel.immediate(
                items(4),
                snapshot(-1, 4, 1L, GameVersionCatalogStatus.READY, "Ready", true, true));
        RecordingGameInstallService service = RecordingGameInstallService.statusSubscribeFailure(bindingFailure);
        GameVersionCatalogPanel panel = onEventDispatchThread(() -> createPanel(model, service));

        IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> onEventDispatchThread(() -> {
            panel.setSize(new Dimension(820, 420));
            layoutRecursively(panel);
            panel.choiceList().refreshLoadPlan();
            panel.choiceList().getList().setSelectedIndex(0);
            findButton(panel, "gameVersionsInstall").doClick();
        }));

        assertSame(bindingFailure, thrown);
        StatusPropertyFailureSession failedPresentation = service.latestDecoratedSession();
        assertEquals(1, failedPresentation.cancelCalls());
        awaitTerminal(failedPresentation);
        EdtDispatcher.executeAndWait(() -> { });

        onEventDispatchThread(() -> {
            assertAll(
                    () -> assertTrue(findComponent(panel, "gameVersionsCatalogWorkspace").isVisible()),
                    () -> assertFalse(findComponent(panel, "gameVersionsTaskWorkspace").isVisible()),
                    () -> assertEquals(
                            INSTALL_STRINGS.installationFailedStatus(),
                            findComponent(
                                    panel,
                                    "gameVersionsInstallStatus",
                                    javax.swing.JLabel.class).getText()));
            panel.close();
        });
        service.close();
    }

    /// A status-unsubscribe failure is propagated only after the catalog card has been restored.
    @Test
    public void returnCleanupFailureStillRestoresCatalogCard() throws Exception {
        IllegalStateException unsubscribeFailure = new IllegalStateException("status unsubscribe failed");
        FakeCatalogModel model = FakeCatalogModel.immediate(
                items(4),
                snapshot(-1, 4, 1L, GameVersionCatalogStatus.READY, "Ready", true, true));
        RecordingGameInstallService service = RecordingGameInstallService.statusUnsubscribeFailure(
                unsubscribeFailure);
        GameVersionCatalogPanel panel = onEventDispatchThread(() -> createPanel(model, service));

        onEventDispatchThread(() -> {
            panel.setSize(new Dimension(820, 420));
            layoutRecursively(panel);
            panel.choiceList().refreshLoadPlan();
            panel.choiceList().getList().setSelectedIndex(0);
            findButton(panel, "gameVersionsInstall").doClick();
        });
        awaitTerminal(service.latestSession());
        EdtDispatcher.executeAndWait(() -> { });

        IllegalStateException thrown = assertThrows(
                IllegalStateException.class,
                () -> onEventDispatchThread(
                        () -> findButton(panel, "gameVersionsBackToCatalog").doClick()));
        assertSame(unsubscribeFailure, thrown);

        onEventDispatchThread(() -> {
            assertAll(
                    () -> assertTrue(findComponent(panel, "gameVersionsCatalogWorkspace").isVisible()),
                    () -> assertFalse(findComponent(panel, "gameVersionsTaskWorkspace").isVisible()),
                    () -> assertTrue(findButton(panel, "gameVersionsInstall").isEnabled()));
            panel.close();
        });
        service.close();
    }

    /// Builds restored instance names from primary loader kinds while ignoring API companion entries.
    @Test
    public void derivesDefaultInstanceNameFromSelectedLoaders() {
        assertEquals(
                "1.21.1-Forge-Fabric",
                GameVersionCatalogPanel.defaultInstanceName(
                        "1.21.1",
                        List.of(
                                remoteVersion("forge", "47.3.0"),
                                remoteVersion("fabric-api", "0.100.0"),
                                remoteVersion("fabric", "0.16.0"))));
    }

    /// Creates one loader version with a stable patch identifier for name suggestion tests.
    ///
    /// @param libraryId loader patch identifier
    /// @param version loader self version
    /// @return remote loader version
    private static RemoteVersion remoteVersion(String libraryId, String version) {
        return new RemoteVersion(
                libraryId,
                "1.21.1",
                version,
                Instant.EPOCH,
                List.of());
    }

    /// Waits for either normal or exceptional installation completion.
    ///
    /// @param session session whose terminal state is required
    private static void awaitTerminal(GameInstallSession session) throws Exception {
        session.completion()
                .handle((ignored, failure) -> null)
                .toCompletableFuture()
                .get(5L, TimeUnit.SECONDS);
    }

    /// Creates a catalog-only fixture whose installer rejects unexpected use or ownership transfer.
    ///
    /// @param model catalog model under test
    /// @return panel with explicit zero-duration task presentation
    private static GameVersionCatalogPanel createPanel(GameVersionCatalogModel model) {
        return createPanel(model, new RejectingGameInstallService());
    }

    /// Creates a page fixture with one explicit installation service.
    ///
    /// @param model catalog model under test
    /// @param installService installation service under test
    /// @return page fixture
    private static GameVersionCatalogPanel createPanel(
            GameVersionCatalogModel model,
            GameInstallService installService) {
        return new GameVersionCatalogPanel(
                model,
                installService,
                STRINGS,
                INSTALL_STRINGS,
                TASK_STRINGS,
                null,
                Duration.ZERO);
    }

    /// Creates a page fixture with an injected loader-selection workflow.
    ///
    /// @param model catalog model under test
    /// @param installService installation service under test
    /// @param loaderSelectionPanel loader workflow under test
    /// @return page fixture
    private static GameVersionCatalogPanel createPanel(
            GameVersionCatalogModel model,
            GameInstallService installService,
            LoaderSelectionWizardPanel loaderSelectionPanel) {
        return new GameVersionCatalogPanel(
                model,
                installService,
                STRINGS,
                INSTALL_STRINGS,
                TASK_STRINGS,
                null,
                Duration.ZERO,
                loaderSelectionPanel);
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

    /// Finds one visible game-version filter control.
    ///
    /// @param root hierarchy root
    /// @param filter exact filter represented by the control
    /// @return matching toggle button
    private static JToggleButton findFilterButton(Container root, GameVersionFilter filter) {
        return findComponent(
                root,
                "gameVersionsFilter_" + Objects.requireNonNull(filter, "filter").name(),
                JToggleButton.class);
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

    /// Converts one descendant's bounds into the panel coordinate system used for reachability assertions.
    ///
    /// @param root panel whose viewport bounds are authoritative
    /// @param component descendant whose laid-out bounds are required
    /// @return descendant bounds expressed relative to the panel
    private static Rectangle boundsRelativeTo(Container root, Component component) {
        Container parent = Objects.requireNonNull(component.getParent(), "component parent");
        return SwingUtilities.convertRectangle(parent, component.getBounds(), root);
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

    /// Returns one exact loader version only after an explicit matching catalog request.
    @NotNullByDefault
    private static final class FixtureLoaderCatalogSource implements GameLoaderCatalogSource {
        /// Original Core remote version whose identity must reach the install request.
        private final RemoteVersion remoteVersion;

        /// Number of explicit catalog refreshes.
        private final AtomicInteger requests = new AtomicInteger();

        /// Creates a deterministic source for one Fabric catalog row.
        ///
        /// @param remoteVersion exact remote version returned by the source
        private FixtureLoaderCatalogSource(RemoteVersion remoteVersion) {
            this.remoteVersion = Objects.requireNonNull(remoteVersion, "remoteVersion");
        }

        /// Records one explicit request and returns the configured matching row.
        ///
        /// @param request exact selected catalog request
        /// @return completed immutable catalog rows
        @Override
        public CompletionStage<@Unmodifiable List<GameLoaderCatalogItem>> refreshAsync(
                GameLoaderCatalogRequest request) {
            GameLoaderCatalogRequest exactRequest = Objects.requireNonNull(request, "request");
            requests.incrementAndGet();
            @Unmodifiable List<GameLoaderCatalogItem> items = exactRequest.kind() == GameLoaderKind.FABRIC
                    ? List.of(new GameLoaderCatalogItem(GameLoaderKind.FABRIC, remoteVersion))
                    : List.of();
            return CompletableFuture.completedFuture(items);
        }

        /// Returns the number of explicit source requests.
        ///
        /// @return source request count
        private int requestCount() {
            return requests.get();
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

    /// Records exact requests while delegating real session behavior to the production service.
    @NotNullByDefault
    private static final class RecordingGameInstallService implements GameInstallService {
        /// Real single-flight service under the page integration.
        private final DefaultGameInstallService delegate;

        /// Optional test decorator applied to each real returned session.
        private final java.util.function.Function<GameInstallSession, GameInstallSession> sessionDecorator;

        /// Exact installation requests in invocation order.
        private final List<GameInstallRequest> requests = new ArrayList<>();

        /// Latest returned session, or null before the installation command.
        private final AtomicReference<@Nullable GameInstallSession> latestSession = new AtomicReference<>();

        /// Whether this test-owned service has been closed.
        private final AtomicBoolean closed = new AtomicBoolean();

        /// Number of first close transitions.
        private final AtomicInteger closeCount = new AtomicInteger();

        /// Creates a recording wrapper around one explicit production service.
        ///
        /// @param delegate real service
        /// @param sessionDecorator optional behavior decorator for returned sessions
        private RecordingGameInstallService(
                DefaultGameInstallService delegate,
                java.util.function.Function<GameInstallSession, GameInstallSession> sessionDecorator) {
            this.delegate = Objects.requireNonNull(delegate, "delegate");
            this.sessionDecorator = Objects.requireNonNull(sessionDecorator, "sessionDecorator");
        }

        /// Creates a service whose task completes normally.
        ///
        /// @return successful recording service
        private static RecordingGameInstallService completed() {
            return wrapping(request -> new CompletedInstallTask());
        }

        /// Creates a service whose task rejects the exact request for one typed reason.
        ///
        /// @param reason typed repository validation reason
        /// @return failing recording service
        private static RecordingGameInstallService failingWith(
                GameInstallRequestRejectedException.Reason reason) {
            Objects.requireNonNull(reason, "reason");
            return wrapping(request -> new FailingInstallTask(
                    new GameInstallRequestRejectedException(request, reason)));
        }

        /// Creates a service whose task remains active until released.
        ///
        /// @param entered task-start signal
        /// @param release task-completion gate
        /// @return blocking recording service
        private static RecordingGameInstallService blocking(
                CountDownLatch entered,
                CountDownLatch release) {
            Objects.requireNonNull(entered, "entered");
            Objects.requireNonNull(release, "release");
            return wrapping(request -> new BlockingInstallTask(entered, release));
        }

        /// Creates a service whose status-property subscription fails during presentation binding.
        ///
        /// @param failure exact subscription failure
        /// @return recording service with a failing status property
        private static RecordingGameInstallService statusSubscribeFailure(RuntimeException failure) {
            Objects.requireNonNull(failure, "failure");
            return wrapping(
                    request -> new CompletedInstallTask(),
                    session -> new StatusPropertyFailureSession(session, failure, true));
        }

        /// Creates a service whose installed status subscription fails when the task is dismissed.
        ///
        /// @param failure exact unsubscription failure
        /// @return recording service with a failing status cleanup
        private static RecordingGameInstallService statusUnsubscribeFailure(RuntimeException failure) {
            Objects.requireNonNull(failure, "failure");
            return wrapping(
                    request -> new CompletedInstallTask(),
                    session -> new StatusPropertyFailureSession(session, failure, false));
        }

        /// Wraps one request-to-task function in the production single-flight service.
        ///
        /// @param taskFactory request-specific task function
        /// @return recording service
        private static RecordingGameInstallService wrapping(
                java.util.function.Function<GameInstallRequest, Task<?>> taskFactory) {
            return wrapping(taskFactory, java.util.function.Function.identity());
        }

        /// Wraps one task function and one returned-session decorator in the production service.
        ///
        /// @param taskFactory request-specific task function
        /// @param sessionDecorator decorator applied to each returned session
        /// @return recording service
        private static RecordingGameInstallService wrapping(
                java.util.function.Function<GameInstallRequest, Task<?>> taskFactory,
                java.util.function.Function<GameInstallSession, GameInstallSession> sessionDecorator) {
            Objects.requireNonNull(taskFactory, "taskFactory");
            Objects.requireNonNull(sessionDecorator, "sessionDecorator");
            return new RecordingGameInstallService(
                    new DefaultGameInstallService(
                            taskFactory::apply,
                            Runnable::run,
                            INSTALL_STRINGS.taskTitle(),
                            INSTALL_STRINGS.preparingPhase()),
                    sessionDecorator);
        }

        /// Records and delegates one exact request.
        ///
        /// @param request exact page request
        /// @return returned production session
        @Override
        public synchronized GameInstallSession install(GameInstallRequest request) {
            requests.add(Objects.requireNonNull(request, "request"));
            GameInstallSession session = Objects.requireNonNull(
                    sessionDecorator.apply(delegate.install(request)),
                    "sessionDecorator returned null");
            latestSession.set(session);
            return session;
        }

        /// Returns the real active installation state.
        ///
        /// @return current active session
        @Override
        public Optional<GameInstallSession> activeInstallation() {
            return delegate.activeInstallation();
        }

        /// Closes the test-owned production service once.
        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                closeCount.incrementAndGet();
                delegate.close();
            }
        }

        /// Returns immutable recorded requests.
        ///
        /// @return request snapshot
        private synchronized @Unmodifiable List<GameInstallRequest> requests() {
            return List.copyOf(requests);
        }

        /// Returns the latest page-returned session.
        ///
        /// @return latest session
        private GameInstallSession latestSession() {
            return Objects.requireNonNull(latestSession.get(), "installation session was not created");
        }

        /// Returns the latest session as the configured status-property failure decorator.
        ///
        /// @return latest decorated session
        private StatusPropertyFailureSession latestDecoratedSession() {
            return (StatusPropertyFailureSession) latestSession();
        }

        /// Returns the number of first service close transitions.
        ///
        /// @return close count
        private int closeCount() {
            return closeCount.get();
        }
    }

    /// Delegates a real session while injecting one deterministic status-property failure.
    @NotNullByDefault
    private static final class StatusPropertyFailureSession implements GameInstallSession {
        /// Real installation session supplying every authoritative state.
        private final GameInstallSession delegate;

        /// Wrapped status property used by the page binding.
        private final ReadOnlyProperty<GameInstallStatus> statusProperty;

        /// Number of direct session cancellation requests.
        private final AtomicInteger cancelCalls = new AtomicInteger();

        /// Creates one failure-decorated session.
        ///
        /// @param delegate real session
        /// @param failure exact injected failure
        /// @param failOnSubscribe whether to fail acquisition rather than removal
        private StatusPropertyFailureSession(
                GameInstallSession delegate,
                RuntimeException failure,
                boolean failOnSubscribe) {
            this.delegate = Objects.requireNonNull(delegate, "delegate");
            statusProperty = new StatusPropertyFailure(
                    delegate.statusProperty(),
                    failure,
                    failOnSubscribe);
        }

        /// Returns the exact real request.
        ///
        /// @return real request
        @Override
        public GameInstallRequest request() {
            return delegate.request();
        }

        /// Returns the authoritative real lifecycle.
        ///
        /// @return real lifecycle
        @Override
        public GameInstallStatus status() {
            return delegate.status();
        }

        /// Returns the failure-decorated status property.
        ///
        /// @return decorated status property
        @Override
        public ReadOnlyProperty<GameInstallStatus> statusProperty() {
            return statusProperty;
        }

        /// Returns the real minimal completion stage.
        ///
        /// @return real completion
        @Override
        public CompletionStage<Void> completion() {
            return delegate.completion();
        }

        /// Returns the exact real terminal failure.
        ///
        /// @return real failure state
        @Override
        public Optional<Throwable> failure() {
            return delegate.failure();
        }

        /// Records and forwards direct cancellation.
        ///
        /// @return real cancellation acceptance
        @Override
        public boolean cancel() {
            cancelCalls.incrementAndGet();
            return delegate.cancel();
        }

        /// Returns the real task presentation snapshot.
        ///
        /// @return real task snapshot
        @Override
        public TaskSnapshot snapshot() {
            return delegate.snapshot();
        }

        /// Registers a real task-presentation listener.
        ///
        /// @param listener presentation listener
        /// @return real registration
        @Override
        public Subscription subscribe(ValueChangeListener<TaskSnapshot> listener) {
            return delegate.subscribe(listener);
        }

        /// Forwards presentation cancellation through the counted direct path.
        @Override
        public void requestCancellation() {
            cancel();
        }

        /// Returns the number of direct cancellation calls.
        ///
        /// @return cancellation count
        private int cancelCalls() {
            return cancelCalls.get();
        }
    }

    /// Wraps one status property and fails either subscription acquisition or removal.
    @NotNullByDefault
    private static final class StatusPropertyFailure implements ReadOnlyProperty<GameInstallStatus> {
        /// Real status property.
        private final ReadOnlyProperty<GameInstallStatus> delegate;

        /// Exact injected unchecked failure.
        private final RuntimeException failure;

        /// Whether listener acquisition rather than removal fails.
        private final boolean failOnSubscribe;

        /// Creates one deterministic failure wrapper.
        ///
        /// @param delegate real status property
        /// @param failure exact injected failure
        /// @param failOnSubscribe whether acquisition fails
        private StatusPropertyFailure(
                ReadOnlyProperty<GameInstallStatus> delegate,
                RuntimeException failure,
                boolean failOnSubscribe) {
            this.delegate = Objects.requireNonNull(delegate, "delegate");
            this.failure = Objects.requireNonNull(failure, "failure");
            this.failOnSubscribe = failOnSubscribe;
        }

        /// Returns the current real status.
        ///
        /// @return real property value
        @Override
        public GameInstallStatus getValue() {
            return delegate.getValue();
        }

        /// Registers a real listener or throws the configured acquisition failure.
        ///
        /// @param listener status listener
        /// @return removal-failing registration when acquisition succeeds
        @Override
        public Subscription subscribe(ValueChangeListener<GameInstallStatus> listener) {
            if (failOnSubscribe) {
                throw failure;
            }
            Subscription subscription = delegate.subscribe(listener);
            return Subscription.create(() -> {
                try {
                    subscription.unsubscribe();
                } catch (RuntimeException | Error cleanupFailure) {
                    if (cleanupFailure != failure) {
                        failure.addSuppressed(cleanupFailure);
                    }
                }
                throw failure;
            });
        }

        /// Returns the real property bean.
        ///
        /// @return real property bean, or null
        @Override
        public @Nullable Object getBean() {
            return delegate.getBean();
        }

        /// Returns the real property name.
        ///
        /// @return real property name
        @Override
        public String getName() {
            return delegate.getName();
        }
    }

    /// Completes one installation task normally.
    @NotNullByDefault
    private static final class CompletedInstallTask extends Task<@Nullable Void> {
        /// Creates a successful task.
        private CompletedInstallTask() {
        }

        /// Completes without producing a value.
        @Override
        public void execute() {
        }
    }

    /// Throws one exact request failure from task execution.
    @NotNullByDefault
    private static final class FailingInstallTask extends Task<@Nullable Void> {
        /// Exact failure emitted by execution.
        private final RuntimeException failure;

        /// Creates a failing task.
        ///
        /// @param failure exact failure
        private FailingInstallTask(RuntimeException failure) {
            this.failure = Objects.requireNonNull(failure, "failure");
        }

        /// Throws the configured failure unchanged.
        @Override
        public void execute() {
            throw failure;
        }
    }

    /// Waits for an explicit completion gate while allowing cooperative cancellation.
    @NotNullByDefault
    private static final class BlockingInstallTask extends Task<@Nullable Void> {
        /// Signals that task execution began.
        private final CountDownLatch entered;

        /// Controls completion of the simulated installation.
        private final CountDownLatch release;

        /// Creates a controlled task.
        ///
        /// @param entered task-start signal
        /// @param release task-completion gate
        private BlockingInstallTask(CountDownLatch entered, CountDownLatch release) {
            this.entered = Objects.requireNonNull(entered, "entered");
            this.release = Objects.requireNonNull(release, "release");
        }

        /// Waits in bounded intervals until completion or cancellation.
        @Override
        public void execute() throws Exception {
            entered.countDown();
            while (!release.await(10L, TimeUnit.MILLISECONDS)) {
                if (isCancelled()) {
                    throw new java.util.concurrent.CancellationException("installation cancelled");
                }
            }
        }
    }

    /// Installer fixture that fails if catalog-only tests accidentally start or own installation.
    @NotNullByDefault
    private static final class RejectingGameInstallService implements GameInstallService {
        /// Rejects an unexpected installation command.
        ///
        /// @param request unexpected request
        /// @return never returns
        @Override
        public GameInstallSession install(GameInstallRequest request) {
            throw new AssertionError("Unexpected install request: "
                    + Objects.requireNonNull(request, "request"));
        }

        /// Returns the stable empty active-session state.
        ///
        /// @return empty active installation
        @Override
        public Optional<GameInstallSession> activeInstallation() {
            return Optional.empty();
        }

        /// Rejects accidental service ownership by the panel.
        @Override
        public void close() {
            throw new AssertionError("Catalog panel must not close its installation service");
        }
    }
}
