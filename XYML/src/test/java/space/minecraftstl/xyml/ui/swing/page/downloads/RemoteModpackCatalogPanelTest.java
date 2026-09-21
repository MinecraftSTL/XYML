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
import space.minecraftstl.xyml.addon.RemoteAddon;
import space.minecraftstl.xyml.addon.RemoteAddonRepository;
import space.minecraftstl.xyml.addon.mod.ModLoaderType;
import space.minecraftstl.xyml.download.DownloadProvider;
import space.minecraftstl.xyml.game.GameInstanceID;
import space.minecraftstl.xyml.task.Task;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;
import space.minecraftstl.xyml.ui.swing.choice.ChoiceListEntry;
import space.minecraftstl.xyml.ui.swing.choice.ViewportChoiceList;
import space.minecraftstl.xyml.ui.swing.task.TaskProgressStrings;

import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JTextField;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies explicit remote discovery, viewport-derived pagination, selected-version loading, and task handoff.
@NotNullByDefault
final class RemoteModpackCatalogPanelTest {
    /// Keeps the remote-modpack result surface transparent except for the selected row highlight.
    @Test
    void leavesBackgroundVisibleThroughResultListAndRows() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        AtomicReference<@Nullable RemoteModpackCatalogPanel> panelReference = new AtomicReference<>();
        try {
            EdtDispatcher.executeAndWait(() -> panelReference.set(new RemoteModpackCatalogPanel(
                    new RecordingBackend(fixtureAddon(), fixtureVersion()),
                    request -> Task.completed(null),
                    executor,
                    RemoteModpackCatalogStrings.english(),
                    TaskProgressStrings.english(),
                    null,
                    Duration.ZERO)));
            RemoteModpackCatalogPanel panel = Objects.requireNonNull(panelReference.get());

            EdtDispatcher.executeAndWait(() -> {
                JList<ChoiceListEntry<RemoteModpackCatalogItem>> list = panel.choiceList().getList();
                ChoiceListEntry<RemoteModpackCatalogItem> entry = ChoiceListEntry.loaded(
                        0,
                        new RemoteModpackCatalogItem(
                                fixtureAddon(),
                                RemoteModpackCatalogSource.CURSEFORGE));
                boolean unselectedOpaque = ((JComponent) list.getCellRenderer()
                        .getListCellRendererComponent(list, entry, 0, false, false)).isOpaque();
                boolean selectedOpaque = ((JComponent) list.getCellRenderer()
                        .getListCellRendererComponent(list, entry, 0, true, false)).isOpaque();

                assertFalse(panel.isOpaque());
                assertFalse(panel.choiceList().isOpaque());
                assertFalse(panel.choiceList().getViewport().isOpaque());
                assertFalse(list.isOpaque());
                assertFalse(unselectedOpaque);
                assertFalse(selectedOpaque);
                JLabel status = findNamed(panel, "remoteModpackStatus", JLabel.class);
                assertNotNull(status);
                assertEquals(RemoteModpackCatalogStrings.english().initialStatus(), status.getText());
            });
        } finally {
            @Nullable RemoteModpackCatalogPanel panel = panelReference.get();
            if (panel != null) {
                panel.close();
            }
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    /// Does not query sources at first paint, then searches, resolves a selected version, and creates a task request.
    @Test
    void waitsForExplicitSearchAndHandsSelectedVersionToTaskLauncher() throws Exception {
        RemoteAddon addon = fixtureAddon();
        RemoteAddon.Version version = fixtureVersion();
        RecordingBackend backend = new RecordingBackend(addon, version);
        RecordingInstallLauncher installLauncher = new RecordingInstallLauncher();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        AtomicReference<@Nullable RemoteModpackCatalogPanel> panelReference = new AtomicReference<>();
        try {
            EdtDispatcher.executeAndWait(() -> panelReference.set(new RemoteModpackCatalogPanel(
                    backend,
                    installLauncher,
                    executor,
                    RemoteModpackCatalogStrings.english(),
                    TaskProgressStrings.english(),
                    null,
                    Duration.ZERO)));
            RemoteModpackCatalogPanel panel = Objects.requireNonNull(panelReference.get());

            EdtDispatcher.executeAndWait(() -> {
                prepareViewport(panel.choiceList());
                assertEquals(0, backend.searchRequests.get());
                assertEquals(0, backend.versionRequests.get());
                assertAll(
                        () -> assertEquals(Boolean.TRUE, findNamed(
                                panel,
                                "remoteModpackSearch",
                                JComponent.class).getClientProperty("JTextField.showClearButton")),
                        () -> assertEquals(Boolean.TRUE, findNamed(
                                panel,
                                "remoteModpackGameVersion",
                                JComponent.class).getClientProperty("JTextField.showClearButton")),
                        () -> assertEquals(Boolean.TRUE, findNamed(
                                panel,
                                "remoteModpackInstanceName",
                                JComponent.class).getClientProperty("JTextField.showClearButton")));
                JButton search = findNamed(panel, "remoteModpackSearchAction", JButton.class);
                assertNotNull(search);
                search.doClick();
            });
            awaitBackgroundWork(executor);

            EdtDispatcher.executeAndWait(() -> prepareViewport(panel.choiceList()));
            drainEdt();
            EdtDispatcher.executeAndWait(() -> {
                assertEquals(1, backend.searchRequests.get());
                RemoteModpackCatalogQuery query = backend.lastQuery.get();
                assertNotNull(query);
                int rowHeight = panel.choiceList().getList().getFixedCellHeight();
                int expectedPageSize = Math.max(1, Math.floorDiv(160 + rowHeight - 1, rowHeight));
                assertEquals(expectedPageSize, query.pageSize());
                assertEquals(0, query.pageOffset());
                panel.choiceList().getList().setSelectedIndex(0);
            });
            awaitBackgroundWork(executor);

            EdtDispatcher.executeAndWait(() -> {
                assertEquals(1, backend.versionRequests.get());
                JComboBox<?> versionBox = findNamed(panel, "remoteModpackVersion", JComboBox.class);
                JButton install = findNamed(panel, "remoteModpackInstall", JButton.class);
                assertNotNull(versionBox);
                assertNotNull(install);
                assertEquals(1, versionBox.getItemCount());
                assertTrue(install.isEnabled());
                install.doClick();
            });
            drainEdt();

            awaitInstallCompletion(panel);
            EdtDispatcher.executeAndWait(() -> {
                JComponent progressHost = findNamed(panel, "remoteModpackInstallProgress", JComponent.class);
                JTextField searchField = findNamed(panel, "remoteModpackSearch", JTextField.class);
                JButton search = findNamed(panel, "remoteModpackSearchAction", JButton.class);
                assertNull(progressHost);
                assertNotNull(searchField);
                assertNotNull(search);
                searchField.setText("fixture-pack-again");
                prepareViewport(panel.choiceList());
                assertTrue(search.isEnabled());
                search.doClick();
            });
            awaitBackgroundWork(executor);
            EdtDispatcher.executeAndWait(() -> assertEquals(
                    1,
                    panel.choiceList().getList().getModel().getSize()));

            RemoteModpackInstallRequest request = installLauncher.request.get();
            assertNotNull(request);
            assertEquals(addon, request.item().addon());
            assertEquals(version, request.version());
            assertEquals(new GameInstanceID("fixture-pack"), request.instanceId());
        } finally {
            @Nullable RemoteModpackCatalogPanel panel = panelReference.get();
            if (panel != null) {
                panel.close();
            }
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    /// Retries failed catalog and selected-version requests when their status text is clicked.
    @Test
    void retriesFailedCatalogAndVersionLoadsFromStatusLabel() throws Exception {
        RecordingBackend backend = new RecordingBackend(fixtureAddon(), fixtureVersion());
        backend.failNextSearchRequest();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        AtomicReference<@Nullable RemoteModpackCatalogPanel> panelReference = new AtomicReference<>();
        try {
            EdtDispatcher.executeAndWait(() -> panelReference.set(new RemoteModpackCatalogPanel(
                    backend,
                    request -> Task.completed(null),
                    executor,
                    RemoteModpackCatalogStrings.english(),
                    TaskProgressStrings.english(),
                    null,
                    Duration.ZERO)));
            RemoteModpackCatalogPanel panel = Objects.requireNonNull(panelReference.get());

            EdtDispatcher.executeAndWait(() -> {
                prepareViewport(panel.choiceList());
                JButton search = findNamed(panel, "remoteModpackSearchAction", JButton.class);
                assertNotNull(search);
                search.doClick();
            });
            awaitBackgroundWork(executor);

            EdtDispatcher.executeAndWait(() -> {
                JLabel status = findNamed(panel, "remoteModpackStatus", JLabel.class);
                assertNotNull(status);
                assertEquals(RemoteModpackCatalogStrings.english().searchFailedStatus(), status.getText());
                status.dispatchEvent(primaryClick(status));
            });
            awaitBackgroundWork(executor);

            backend.failNextVersionRequest();
            EdtDispatcher.executeAndWait(() -> {
                prepareViewport(panel.choiceList());
                panel.choiceList().getList().setSelectedIndex(0);
            });
            awaitBackgroundWork(executor);

            EdtDispatcher.executeAndWait(() -> {
                JLabel status = findNamed(panel, "remoteModpackStatus", JLabel.class);
                assertNotNull(status);
                assertEquals(RemoteModpackCatalogStrings.english().versionLoadFailedStatus(), status.getText());
                status.dispatchEvent(primaryClick(status));
            });
            awaitBackgroundWork(executor);

            EdtDispatcher.executeAndWait(() -> {
                JComboBox<?> versionBox = findNamed(panel, "remoteModpackVersion", JComboBox.class);
                assertNotNull(versionBox);
                assertEquals(2, backend.searchRequests.get());
                assertEquals(2, backend.versionRequests.get());
                assertEquals(1, versionBox.getItemCount());
            });
        } finally {
            @Nullable RemoteModpackCatalogPanel panel = panelReference.get();
            if (panel != null) {
                panel.close();
            }
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    /// Keeps an existing update target read-only and hands that exact identifier to the selected-version task.
    @Test
    void fixedInstanceModeDoesNotSuggestOrCreateAnotherDestination() throws Exception {
        GameInstanceID fixedInstanceId = new GameInstanceID("installed-pack");
        RecordingBackend backend = new RecordingBackend(fixtureAddon(), fixtureVersion());
        RecordingInstallLauncher installLauncher = new RecordingInstallLauncher();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        AtomicReference<@Nullable RemoteModpackCatalogPanel> panelReference = new AtomicReference<>();
        try {
            EdtDispatcher.executeAndWait(() -> panelReference.set(new RemoteModpackCatalogPanel(
                    backend,
                    installLauncher,
                    executor,
                    RemoteModpackCatalogStrings.english(),
                    TaskProgressStrings.english(),
                    null,
                    Duration.ZERO,
                    fixedInstanceId)));
            RemoteModpackCatalogPanel panel = Objects.requireNonNull(panelReference.get());

            EdtDispatcher.executeAndWait(() -> {
                prepareViewport(panel.choiceList());
                JTextField instanceName = findNamed(
                        panel,
                        "remoteModpackInstanceName",
                        JTextField.class);
                assertEquals(fixedInstanceId.id(), instanceName.getText());
                assertFalse(instanceName.isEditable());
                assertFalse(Boolean.TRUE.equals(instanceName.getClientProperty("JTextField.showClearButton")));
                findNamed(panel, "remoteModpackSearchAction", JButton.class).doClick();
            });
            awaitBackgroundWork(executor);

            EdtDispatcher.executeAndWait(() -> panel.choiceList().getList().setSelectedIndex(0));
            awaitBackgroundWork(executor);
            EdtDispatcher.executeAndWait(() -> findNamed(
                    panel,
                    "remoteModpackInstall",
                    JButton.class).doClick());
            drainEdt();

            assertEquals(fixedInstanceId, Objects.requireNonNull(installLauncher.request.get()).instanceId());
        } finally {
            @Nullable RemoteModpackCatalogPanel panel = panelReference.get();
            if (panel != null) {
                panel.close();
            }
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    /// Keeps the search form in explicit rows and forwards loaded category and sort selections.
    @Test
    void laysOutSearchRowsAndAppliesProviderFilters() throws Exception {
        RecordingBackend backend = new RecordingBackend(fixtureAddon(), fixtureVersion());
        ExecutorService executor = Executors.newSingleThreadExecutor();
        AtomicReference<@Nullable RemoteModpackCatalogPanel> panelReference = new AtomicReference<>();
        try {
            EdtDispatcher.executeAndWait(() -> {
                RemoteModpackCatalogPanel panel = new RemoteModpackCatalogPanel(
                        backend,
                        request -> Task.completed(null),
                        executor,
                        RemoteModpackCatalogStrings.english(),
                        TaskProgressStrings.english(),
                        null,
                        Duration.ZERO);
                panelReference.set(panel);
                prepareViewport(panel.choiceList());
                panel.addNotify();
            });
            awaitBackgroundWork(executor);

            EdtDispatcher.executeAndWait(() -> {
                RemoteModpackCatalogPanel panel = Objects.requireNonNull(panelReference.get());
                JPanel searchBand = findNamed(panel, "remoteModpackSearchBand", JPanel.class);
                JPanel criteriaBand = findNamed(panel, "remoteModpackCriteriaBand", JPanel.class);
                JPanel pageBand = findNamed(panel, "remoteModpackPageBand", JPanel.class);
                JComboBox<?> categoryBox = findNamed(panel, "remoteModpackCategory", JComboBox.class);
                JComboBox<?> sortBox = findNamed(panel, "remoteModpackSort", JComboBox.class);
                JComboBox<?> versionSortBox = findNamed(panel, "remoteModpackVersionSort", JComboBox.class);
                JComboBox<?> gameVersionBox = findNamed(panel, "remoteModpackGameVersion", JComboBox.class);
                JButton search = findNamed(panel, "remoteModpackSearchAction", JButton.class);
                JButton previous = findNamed(panel, "remoteModpackPreviousPage", JButton.class);
                assertNotNull(searchBand);
                assertNotNull(criteriaBand);
                assertNotNull(pageBand);
                assertNotSame(searchBand, criteriaBand);
                assertNotSame(criteriaBand, pageBand);
                assertNotNull(categoryBox);
                assertNotNull(sortBox);
                assertNotNull(versionSortBox);
                assertNotNull(gameVersionBox);
                assertNotNull(search);
                assertNotNull(previous);
                assertEquals(searchBand, search.getParent());
                assertEquals(criteriaBand, categoryBox.getParent());
                assertEquals(criteriaBand, sortBox.getParent());
                assertEquals(pageBand, previous.getParent());
                assertEquals(3, categoryBox.getItemCount());
                assertEquals(6, sortBox.getItemCount());
                assertEquals(RemoteAddonRepository.SortType.POPULARITY, sortBox.getSelectedItem());
                assertEquals(3, versionSortBox.getItemCount());
                assertTrue(gameVersionBox.isEditable());
                assertTrue(gameVersionBox.getItemCount() > 1);
                categoryBox.setSelectedIndex(2);
                sortBox.setSelectedItem(RemoteAddonRepository.SortType.TOTAL_DOWNLOADS);
                search.doClick();
            });
            awaitBackgroundWork(executor);

            assertEquals(1, backend.categoryRequests.get());
            RemoteModpackCatalogQuery query = backend.lastQuery.get();
            assertNotNull(query);
            assertNotNull(query.category());
            assertEquals("adventure-child", query.category().id());
            assertEquals(RemoteAddonRepository.SortType.TOTAL_DOWNLOADS, query.sortType());
        } finally {
            @Nullable RemoteModpackCatalogPanel panel = panelReference.get();
            if (panel != null) {
                panel.close();
            }
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    /// Retries a failed category request through the visible status action.
    @Test
    void retriesFailedCategoryLoadingFromStatus() throws Exception {
        RecordingBackend backend = new RecordingBackend(fixtureAddon(), fixtureVersion());
        backend.failNextCategoryRequest();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        AtomicReference<@Nullable RemoteModpackCatalogPanel> panelReference = new AtomicReference<>();
        try {
            EdtDispatcher.executeAndWait(() -> {
                RemoteModpackCatalogPanel panel = new RemoteModpackCatalogPanel(
                        backend,
                        request -> Task.completed(null),
                        executor,
                        RemoteModpackCatalogStrings.english(),
                        TaskProgressStrings.english(),
                        null,
                        Duration.ZERO);
                panelReference.set(panel);
                panel.addNotify();
            });
            awaitBackgroundWork(executor);

            EdtDispatcher.executeAndWait(() -> {
                RemoteModpackCatalogPanel panel = Objects.requireNonNull(panelReference.get());
                JComboBox<?> categoryBox = findNamed(panel, "remoteModpackCategory", JComboBox.class);
                JLabel status = findNamed(panel, "remoteModpackStatus", JLabel.class);
                JTextField search = findNamed(panel, "remoteModpackSearch", JTextField.class);
                assertNotNull(categoryBox);
                assertNotNull(status);
                assertNotNull(search);
                assertEquals(1, categoryBox.getItemCount());
                assertEquals(RemoteModpackCatalogStrings.english().categoryLoadFailedStatus(), status.getText());
                search.setText("keep retry visible");
                assertEquals(RemoteModpackCatalogStrings.english().categoryLoadFailedStatus(), status.getText());
                status.dispatchEvent(primaryClick(status));
            });
            awaitBackgroundWork(executor);

            EdtDispatcher.executeAndWait(() -> {
                RemoteModpackCatalogPanel panel = Objects.requireNonNull(panelReference.get());
                JComboBox<?> categoryBox = findNamed(panel, "remoteModpackCategory", JComboBox.class);
                JLabel status = findNamed(panel, "remoteModpackStatus", JLabel.class);
                assertNotNull(categoryBox);
                assertNotNull(status);
                assertEquals(3, categoryBox.getItemCount());
                assertEquals(RemoteModpackCatalogStrings.english().initialStatus(), status.getText());
            });
            assertEquals(2, backend.categoryRequests.get());
        } finally {
            @Nullable RemoteModpackCatalogPanel panel = panelReference.get();
            if (panel != null) {
                panel.close();
            }
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    /// Keeps an earlier category completion from replacing a later search failure and its retry action.
    @Test
    void categoryCompletionDoesNotReplaceSearchFailure() {
        RecordingBackend backend = new RecordingBackend(fixtureAddon(), fixtureVersion());
        backend.failNextSearchRequest();
        ArrayDeque<Runnable> queuedTasks = new ArrayDeque<>();
        Executor executor = queuedTasks::addLast;
        AtomicReference<@Nullable RemoteModpackCatalogPanel> panelReference = new AtomicReference<>();
        try {
            EdtDispatcher.executeAndWait(() -> {
                RemoteModpackCatalogPanel panel = new RemoteModpackCatalogPanel(
                        backend,
                        request -> Task.completed(null),
                        executor,
                        RemoteModpackCatalogStrings.english(),
                        TaskProgressStrings.english(),
                        null,
                        Duration.ZERO);
                panelReference.set(panel);
                prepareViewport(panel.choiceList());
                panel.addNotify();
                JButton search = findNamed(panel, "remoteModpackSearchAction", JButton.class);
                assertNotNull(search);
                search.doClick();
            });
            assertEquals(2, queuedTasks.size());

            queuedTasks.removeLast().run();
            drainEdt();
            EdtDispatcher.executeAndWait(() -> assertEquals(
                    RemoteModpackCatalogStrings.english().searchFailedStatus(),
                    Objects.requireNonNull(findNamed(
                            Objects.requireNonNull(panelReference.get()),
                            "remoteModpackStatus",
                            JLabel.class)).getText()));

            queuedTasks.removeFirst().run();
            drainEdt();
            EdtDispatcher.executeAndWait(() -> assertEquals(
                    RemoteModpackCatalogStrings.english().searchFailedStatus(),
                    Objects.requireNonNull(findNamed(
                            Objects.requireNonNull(panelReference.get()),
                            "remoteModpackStatus",
                            JLabel.class)).getText()));
        } finally {
            @Nullable RemoteModpackCatalogPanel panel = panelReference.get();
            if (panel != null) {
                panel.close();
            }
        }
    }

    /// Keeps every modpack filter row inside its allocated catalog band at common compact heights.
    @Test
    void laysOutAllFilterRowsWithoutClipping() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        AtomicReference<@Nullable RemoteModpackCatalogPanel> panelReference = new AtomicReference<>();
        try {
            EdtDispatcher.executeAndWait(() -> {
                RemoteModpackCatalogPanel panel = new RemoteModpackCatalogPanel(
                        new RecordingBackend(fixtureAddon(), fixtureVersion()),
                        request -> Task.completed(null),
                        executor,
                        RemoteModpackCatalogStrings.english(),
                        TaskProgressStrings.english(),
                        null,
                        Duration.ZERO);
                panelReference.set(panel);
                JComponent filterBand = findNamed(panel, "remoteModpackFilterBand", JComponent.class);
                JComponent searchBand = findNamed(panel, "remoteModpackSearchBand", JComponent.class);
                JComponent criteriaBand = findNamed(panel, "remoteModpackCriteriaBand", JComponent.class);
                JComponent pageBand = findNamed(panel, "remoteModpackPageBand", JComponent.class);
                JComponent results = findNamed(panel, "remoteModpackResults", JComponent.class);
                assertNotNull(filterBand);
                assertNotNull(searchBand);
                assertNotNull(criteriaBand);
                assertNotNull(pageBand);
                assertNotNull(results);

                for (Dimension panelSize : List.of(
                        new Dimension(640, 600),
                        new Dimension(960, 600),
                        new Dimension(960, 720),
                        new Dimension(960, 900))) {
                    panel.setSize(panelSize);
                    layoutTree(panel);

                    assertTrue(searchBand.getWidth() > 0 && searchBand.getHeight() > 40);
                    assertTrue(criteriaBand.getWidth() > 0 && criteriaBand.getHeight() > 40);
                    assertTrue(pageBand.getWidth() > 0 && pageBand.getHeight() >= 40);
                    assertComponentInside(filterBand, searchBand);
                    assertComponentInside(filterBand, criteriaBand);
                    assertComponentInside(filterBand, pageBand);
                    assertTrue(filterBand.getY() + filterBand.getHeight() <= results.getY(),
                            () -> "size=" + panelSize + ", filter=" + filterBand.getBounds()
                                    + ", results=" + results.getBounds());
                    assertChildrenInside(searchBand);
                    assertChildrenInside(criteriaBand);
                    assertChildrenInside(pageBand);
                }
            });
        } finally {
            @Nullable RemoteModpackCatalogPanel panel = panelReference.get();
            if (panel != null) {
                panel.close();
            }
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    /// Jumps directly between the remote modpack result boundaries and synchronizes navigation state.
    @Test
    void jumpsDirectlyBetweenFirstAndLastProviderPages() throws Exception {
        RecordingBackend backend = new RecordingBackend(fixtureAddon(), fixtureVersion());
        ExecutorService executor = Executors.newSingleThreadExecutor();
        AtomicReference<@Nullable RemoteModpackCatalogPanel> panelReference = new AtomicReference<>();
        try {
            EdtDispatcher.executeAndWait(() -> panelReference.set(new RemoteModpackCatalogPanel(
                    backend,
                    request -> Task.completed(null),
                    executor,
                    RemoteModpackCatalogStrings.english(),
                    TaskProgressStrings.english(),
                    null,
                    Duration.ZERO)));
            RemoteModpackCatalogPanel panel = Objects.requireNonNull(panelReference.get());

            EdtDispatcher.executeAndWait(() -> {
                prepareViewport(panel.choiceList());
                JButton search = findNamed(panel, "remoteModpackSearchAction", JButton.class);
                assertNotNull(search);
                search.doClick();
            });
            awaitBackgroundWork(executor);

            EdtDispatcher.executeAndWait(() -> {
                JButton first = findNamed(panel, "remoteModpackFirstPage", JButton.class);
                JButton previous = findNamed(panel, "remoteModpackPreviousPage", JButton.class);
                JButton next = findNamed(panel, "remoteModpackNextPage", JButton.class);
                JButton last = findNamed(panel, "remoteModpackLastPage", JButton.class);
                assertNotNull(first);
                assertNotNull(previous);
                assertNotNull(next);
                assertNotNull(last);
                assertAll(
                        () -> assertFalse(first.isEnabled()),
                        () -> assertFalse(previous.isEnabled()),
                        () -> assertTrue(next.isEnabled()),
                        () -> assertTrue(last.isEnabled()));
                last.doClick();
            });
            awaitBackgroundWork(executor);
            RemoteModpackCatalogQuery lastPageQuery = backend.lastQuery.get();
            assertNotNull(lastPageQuery);
            assertEquals(4, lastPageQuery.pageOffset());

            EdtDispatcher.executeAndWait(() -> {
                JButton first = findNamed(panel, "remoteModpackFirstPage", JButton.class);
                JButton previous = findNamed(panel, "remoteModpackPreviousPage", JButton.class);
                JButton next = findNamed(panel, "remoteModpackNextPage", JButton.class);
                JButton last = findNamed(panel, "remoteModpackLastPage", JButton.class);
                assertNotNull(first);
                assertNotNull(previous);
                assertNotNull(next);
                assertNotNull(last);
                assertAll(
                        () -> assertTrue(first.isEnabled()),
                        () -> assertTrue(previous.isEnabled()),
                        () -> assertFalse(next.isEnabled()),
                        () -> assertFalse(last.isEnabled()));
                prepareViewport(panel.choiceList(), 240);
                first.doClick();
            });
            awaitBackgroundWork(executor);
            RemoteModpackCatalogQuery firstPageQuery = backend.lastQuery.get();
            assertNotNull(firstPageQuery);
            assertEquals(0, firstPageQuery.pageOffset());
            assertEquals(3, backend.searchRequests.get());
        } finally {
            @Nullable RemoteModpackCatalogPanel panel = panelReference.get();
            if (panel != null) {
                panel.close();
            }
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    /// Gives a detached sparse list actual result geometry without triggering a network operation.
    ///
    /// @param choiceList detached result list to measure and request
    private static void prepareViewport(ViewportChoiceList<?> choiceList) {
        prepareViewport(choiceList, 160);
    }

    /// Gives a detached sparse list explicit result geometry without triggering a network operation.
    ///
    /// @param choiceList detached result list to measure and request
    /// @param visibleHeight result viewport height in pixels
    private static void prepareViewport(ViewportChoiceList<?> choiceList, int visibleHeight) {
        choiceList.setSize(480, visibleHeight + 20);
        choiceList.getViewport().setExtentSize(new Dimension(480, visibleHeight));
        choiceList.getList().setSize(480, visibleHeight);
        choiceList.refreshLoadPlan();
    }

    /// Recursively lays out a detached component tree just as an allocated window hierarchy would.
    ///
    /// @param container current layout root
    private static void layoutTree(Container container) {
        container.doLayout();
        for (Component component : container.getComponents()) {
            if (component instanceof Container child) {
                layoutTree(child);
            }
        }
    }

    /// Verifies that every direct control remains visible inside its responsive parent band.
    ///
    /// @param parent layout band expected to contain all direct controls
    private static void assertChildrenInside(JComponent parent) {
        for (Component child : parent.getComponents()) {
            assertTrue(child.getWidth() > 0 && child.getHeight() > 0);
            assertComponentInside(parent, child);
        }
    }

    /// Verifies that a nested control remains fully contained by its responsive parent band.
    ///
    /// @param parent layout band expected to contain the control
    /// @param child control whose bounds must remain visible
    private static void assertComponentInside(JComponent parent, @Nullable Component child) {
        Component resolvedChild = Objects.requireNonNull(child, "child");
        assertTrue(resolvedChild.getX() >= 0);
        assertTrue(resolvedChild.getY() >= 0);
        assertTrue(resolvedChild.getX() + resolvedChild.getWidth() <= parent.getWidth());
        assertTrue(resolvedChild.getY() + resolvedChild.getHeight() <= parent.getHeight());
    }

    /// Waits for queued worker work and the EDT callbacks it schedules.
    ///
    /// @param executor panel worker executor
    /// @throws Exception when queued work does not finish promptly
    private static void awaitBackgroundWork(ExecutorService executor) throws Exception {
        executor.submit(() -> { }).get(5, TimeUnit.SECONDS);
        drainEdt();
    }

    /// Waits until the completed installation callback has re-enabled catalog controls.
    ///
    /// @param panel catalog panel whose installation must reach a terminal callback
    /// @throws InterruptedException when the test thread is interrupted while polling
    private static void awaitInstallCompletion(RemoteModpackCatalogPanel panel) throws InterruptedException {
        for (int attempt = 0; attempt < 500; attempt++) {
            AtomicBoolean enabled = new AtomicBoolean();
            EdtDispatcher.executeAndWait(() -> {
                @Nullable JButton install = findNamed(panel, "remoteModpackInstall", JButton.class);
                enabled.set(install != null && install.isEnabled());
            });
            if (enabled.get()) {
                return;
            }
            Thread.sleep(10L);
        }
        throw new AssertionError("Timed out waiting for remote modpack installation completion");
    }

    /// Flushes callbacks already queued on the Swing event dispatch thread.
    private static void drainEdt() {
        EdtDispatcher.executeAndWait(() -> { });
    }

    /// Creates a primary-button mouse event for status-label retry tests.
    ///
    /// @param source status label receiving the event
    /// @return deterministic single-click event
    private static MouseEvent primaryClick(Component source) {
        return new MouseEvent(
                source,
                MouseEvent.MOUSE_CLICKED,
                System.currentTimeMillis(),
                0,
                1,
                1,
                1,
                1,
                1,
                false,
                MouseEvent.BUTTON1);
    }

    /// Finds one named descendant of the requested Swing component type.
    ///
    /// @param root component tree root
    /// @param name stable component name
    /// @param type expected component type
    /// @param <T> expected Swing component type
    /// @return matching descendant, or null when the component is absent
    private static <T extends JComponent> @Nullable T findNamed(
            Container root,
            String name,
            Class<T> type) {
        for (Component component : root.getComponents()) {
            if (type.isInstance(component) && name.equals(component.getName())) {
                return type.cast(component);
            }
            if (component instanceof Container child) {
                @Nullable T nested = findNamed(child, name, type);
                if (nested != null) {
                    return nested;
                }
            }
        }
        return null;
    }

    /// Creates a deterministic remote project whose actual version data is supplied by the injected backend.
    ///
    /// @return non-null fixture remote modpack project
    private static RemoteAddon fixtureAddon() {
        return new RemoteAddon(
                "fixture-pack",
                "fixture-author",
                "Fixture Pack",
                "Fixture description",
                List.of(),
                "https://example.invalid/fixture-pack",
                "https://example.invalid/fixture-pack.png",
                new FixtureAddonData(),
                RemoteAddon.Type.MODPACK);
    }

    /// Creates an installable source version for selected-version handoff verification.
    ///
    /// @return non-null fixture remote modpack version
    private static RemoteAddon.Version fixtureVersion() {
        return new RemoteAddon.Version(
                () -> RemoteAddon.Source.MODRINTH,
                "1.0.0",
                "fixture-pack",
                "Fixture Pack 1.0",
                "1.0.0",
                Instant.EPOCH,
                RemoteAddon.VersionType.Release,
                new RemoteAddon.File(Map.of(), "https://example.invalid/fixture-pack.mrpack", "fixture-pack.mrpack"),
                List.of(),
                List.of("1.20.1"),
                List.<ModLoaderType>of());
    }

    /// Deterministic backend recording actual user-triggered Core boundary requests.
    @NotNullByDefault
    private static final class RecordingBackend implements RemoteModpackCatalogBackend {
        /// Immutable item returned for the first explicit source search.
        private final RemoteModpackCatalogItem item;

        /// Immutable version returned after the user selects the fixture item.
        private final RemoteAddon.Version version;

        /// Count of explicit search calls observed by the backend.
        private final AtomicInteger searchRequests = new AtomicInteger();

        /// Causes exactly one subsequent source search to fail.
        private final AtomicBoolean failNextSearch = new AtomicBoolean();

        /// Count of selected-item version calls observed by the backend.
        private final AtomicInteger versionRequests = new AtomicInteger();

        /// Causes exactly one subsequent selected-item version request to fail.
        private final AtomicBoolean failNextVersion = new AtomicBoolean();

        /// Count of display-triggered provider category requests.
        private final AtomicInteger categoryRequests = new AtomicInteger();

        /// Causes exactly one subsequent provider category request to fail.
        private final AtomicBoolean failNextCategory = new AtomicBoolean();

        /// Last explicit search query, or null before the user invokes Search.
        private final AtomicReference<@Nullable RemoteModpackCatalogQuery> lastQuery = new AtomicReference<>();

        /// Creates one deterministic catalog backend.
        ///
        /// @param addon fixture add-on returned by searches
        /// @param version fixture selected-project version
        private RecordingBackend(RemoteAddon addon, RemoteAddon.Version version) {
            item = new RemoteModpackCatalogItem(addon, RemoteModpackCatalogSource.MODRINTH);
            this.version = Objects.requireNonNull(version, "version");
        }

        /// Marks the next source search as a deterministic failure for retry tests.
        private void failNextSearchRequest() {
            failNextSearch.set(true);
        }

        /// Marks the next provider category request as a deterministic failure for retry tests.
        private void failNextCategoryRequest() {
            failNextCategory.set(true);
        }

        /// Marks the next selected-version request as a deterministic failure for retry tests.
        private void failNextVersionRequest() {
            failNextVersion.set(true);
        }

        /// Records and returns a nested provider category tree for selector tests.
        ///
        /// @param source selected remote provider
        /// @return immutable nested fixture categories
        @Override
        public @Unmodifiable List<RemoteAddonRepository.Category> loadCategories(
                RemoteModpackCatalogSource source) {
            assertEquals(RemoteModpackCatalogSource.MODRINTH, source);
            categoryRequests.incrementAndGet();
            if (failNextCategory.compareAndSet(true, false)) {
                throw new IllegalStateException("recorded category failure");
            }
            RemoteAddonRepository.Category child = new RemoteAddonRepository.Category(
                    new Object(),
                    "adventure-child",
                    List.of());
            return List.of(new RemoteAddonRepository.Category(
                    new Object(),
                    "adventure",
                    List.of(child)));
        }

        /// Records and returns a five-page source result so every pagination command is testable.
        ///
        /// @param query user-triggered search query
        /// @return one-page fixture result
        @Override
        public RemoteModpackCatalogPage search(RemoteModpackCatalogQuery query) {
            lastQuery.set(Objects.requireNonNull(query, "query"));
            searchRequests.incrementAndGet();
            if (failNextSearch.compareAndSet(true, false)) {
                throw new IllegalStateException("recorded catalog failure");
            }
            return new RemoteModpackCatalogPage(List.of(item), query.pageOffset(), 5);
        }

        /// Records and returns the selected fixture project's one installable version.
        ///
        /// @param item selected fixture result
        /// @return immutable one-version list
        @Override
        public List<RemoteAddon.Version> loadVersions(RemoteModpackCatalogItem item) {
            assertEquals(this.item, item);
            versionRequests.incrementAndGet();
            if (failNextVersion.compareAndSet(true, false)) {
                throw new IllegalStateException("recorded version failure");
            }
            return List.of(version);
        }
    }

    /// Task-launcher substitute recording selected-version installation handoff without network or filesystem work.
    @NotNullByDefault
    private static final class RecordingInstallLauncher implements RemoteModpackInstallLauncher {
        /// Last selected-version install request, or null before the user presses Install.
        private final AtomicReference<@Nullable RemoteModpackInstallRequest> request = new AtomicReference<>();

        /// Records the request and returns a successful no-op task for normal panel lifecycle execution.
        ///
        /// @param request selected project, version, and destination request
        /// @return completed no-op task
        @Override
        public Task<?> createInstallTask(RemoteModpackInstallRequest request) {
            this.request.set(Objects.requireNonNull(request, "request"));
            return Task.completed(null);
        }
    }

    /// Provides the unused Core add-on data contract required to build a realistic fixture result.
    @NotNullByDefault
    private static final class FixtureAddonData implements RemoteAddon.IAddon {
        /// Rejects dependency resolution because the focused catalog test supplies versions
        /// through its backend boundary.
        ///
        /// @param repo unused source repository
        /// @param downloadProvider unused Core download provider
        /// @return never returns normally
        /// @throws IOException always because dependencies are outside this fixture's scope
        @Override
        public List<RemoteAddon> loadDependencies(
                RemoteAddonRepository repo,
                DownloadProvider downloadProvider) throws IOException {
            throw new IOException("Fixture dependencies are outside the catalog-panel test");
        }

        /// Rejects direct Core version resolution because the injected backend owns fixture versions.
        ///
        /// @param repo unused source repository
        /// @param downloadProvider unused Core download provider
        /// @return never returns normally
        /// @throws IOException always because versions are supplied by RecordingBackend
        @Override
        public Stream<RemoteAddon.Version> loadVersions(
                RemoteAddonRepository repo,
                DownloadProvider downloadProvider) throws IOException {
            throw new IOException("Fixture versions are supplied by RecordingBackend");
        }
    }
}
