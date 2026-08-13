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
package space.minecraftstl.xyml.ui.swing.page.downloads.loaders;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import space.minecraftstl.xyml.download.RemoteVersion;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;
import space.minecraftstl.xyml.ui.swing.choice.ChoiceListEntry;
import space.minecraftstl.xyml.ui.swing.choice.ViewportChoiceList;

import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.ListSelectionModel;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies the lazy Swing loader selection control without creating a native window or a network request.
@NotNullByDefault
final class LoaderSelectionWizardPanelTest {
    /// Keeps the loader page and lazy list transparent except for its selected-row highlight.
    @Test
    void leavesBackgroundVisibleThroughVersionListAndRows() throws Exception {
        RecordingSource source = new RecordingSource();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        AtomicReference<@Nullable LoaderSelectionWizardPanel> panelReference = new AtomicReference<>();
        try {
            EdtDispatcher.executeAndWait(() -> panelReference.set(new LoaderSelectionWizardPanel(
                    new DefaultGameLoaderCatalogModel(source),
                    executor,
                    LoaderSelectionWizardStrings.english())));
            LoaderSelectionWizardPanel panel = Objects.requireNonNull(panelReference.get());

            EdtDispatcher.executeAndWait(() -> {
                JList<ChoiceListEntry<GameLoaderCatalogItem>> list = panel.versionChoiceList().getList();
                ChoiceListEntry<GameLoaderCatalogItem> entry = ChoiceListEntry.loading(0);
                boolean unselectedOpaque = ((JComponent) list.getCellRenderer()
                        .getListCellRendererComponent(list, entry, 0, false, false)).isOpaque();
                boolean selectedOpaque = ((JComponent) list.getCellRenderer()
                        .getListCellRendererComponent(list, entry, 0, true, false)).isOpaque();

                assertFalse(panel.isOpaque());
                assertFalse(panel.versionChoiceList().isOpaque());
                assertFalse(panel.versionChoiceList().getViewport().isOpaque());
                assertFalse(list.isOpaque());
                assertFalse(unselectedOpaque);
                assertFalse(selectedOpaque);
            });
        } finally {
            closePanel(panelReference.get());
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    /// Confirms construction, base-version selection, and loader-card selection never refresh a source.
    @Test
    void constructionAndLocalChoicesDoNotRefreshLoaderCatalogs() throws Exception {
        RecordingSource source = new RecordingSource();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        AtomicReference<@Nullable LoaderSelectionWizardPanel> panelReference = new AtomicReference<>();
        try {
            EdtDispatcher.executeAndWait(() -> panelReference.set(new LoaderSelectionWizardPanel(
                    new DefaultGameLoaderCatalogModel(source),
                    executor,
                    LoaderSelectionWizardStrings.english())));
            LoaderSelectionWizardPanel panel = Objects.requireNonNull(panelReference.get());

            EdtDispatcher.executeAndWait(() -> {
                assertEquals(0, source.requestCount.get());
                panel.selectGameVersion("1.20.1");
                JButton fabricButton = findNamed(panel, "loaderKind_FABRIC", JButton.class);
                JButton fabricApiButton = findNamed(panel, "loaderKind_FABRIC_API", JButton.class);
                assertNotNull(fabricButton);
                assertNotNull(fabricApiButton);
                assertTrue(fabricButton.isVisible());
                fabricButton.doClick();
                assertEquals(0, source.requestCount.get());
                panel.selectGameVersion("1.20.1");
                assertFalse(fabricApiButton.isEnabled());
                fabricApiButton.setEnabled(true);
                fabricApiButton.doClick();
                JLabel status = findNamed(panel, "loaderSelectionStatus", JLabel.class);
                assertNotNull(status);
                assertEquals(LoaderSelectionWizardStrings.english().parentRequiredStatus(), status.getText());
                assertFalse(fabricApiButton.isEnabled());
                assertEquals(0, source.requestCount.get());
                assertEquals(ListSelectionModel.SINGLE_SELECTION,
                        panel.versionChoiceList().getList().getSelectionMode());
                assertTrue(panel.selectedRemoteVersions().isEmpty());
            });
        } finally {
            closePanel(panelReference.get());
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    /// Verifies explicit loading, viewport-list selection, parent requirements, conflict blocking,
    /// safe ordering, and clearing.
    @Test
    void selectsExactVersionsOnlyAfterExplicitRefreshAndEnforcesInstallRules() throws Exception {
        RemoteVersion fabric = remoteVersion("fabric", "1.20.1", "0.16.0");
        RemoteVersion fabricApi = remoteVersion("fabric-api", "1.20.1", "0.100.0");
        RemoteVersion forge = remoteVersion("forge", "1.20.1", "47.2.0");
        RecordingSource source = new RecordingSource();
        source.put(GameLoaderKind.FABRIC, fabric);
        source.put(GameLoaderKind.FABRIC_API, fabricApi);
        source.put(GameLoaderKind.FORGE, forge);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        AtomicReference<@Nullable LoaderSelectionWizardPanel> panelReference = new AtomicReference<>();
        AtomicReference<@Nullable LoaderSelectionSnapshot> listenerSnapshot = new AtomicReference<>();
        try {
            EdtDispatcher.executeAndWait(() -> panelReference.set(new LoaderSelectionWizardPanel(
                    new DefaultGameLoaderCatalogModel(source),
                    executor,
                    LoaderSelectionWizardStrings.english())));
            LoaderSelectionWizardPanel panel = Objects.requireNonNull(panelReference.get());
            EdtDispatcher.executeAndWait(() -> panel.addSelectionListener(listenerSnapshot::set));

            EdtDispatcher.executeAndWait(() -> {
                panel.selectGameVersion("1.20.1");
                JButton fabricApiButton = findNamed(panel, "loaderKind_FABRIC_API", JButton.class);
                assertNotNull(fabricApiButton);
                assertTrue(fabricApiButton.isVisible());
                assertFalse(fabricApiButton.isEnabled());
                assertEquals(0, source.requestCount.get());
            });

            selectAndLoad(panel, executor, GameLoaderKind.FABRIC);
            EdtDispatcher.executeAndWait(() -> {
                selectFirstLoadedCatalogRow(panel);
                JButton addButton = findNamed(panel, "loaderAddSelection", JButton.class);
                assertNotNull(addButton);
                assertTrue(addButton.isEnabled());
                addButton.doClick();
                assertEquals(1, panel.selectedRemoteVersions().size());
                assertSame(fabric, panel.selectedRemoteVersions().get(0));
                assertThrows(UnsupportedOperationException.class,
                        () -> panel.selectedRemoteVersions().add(forge));
                JButton fabricButton = findNamed(panel, "loaderKind_FABRIC", JButton.class);
                JButton fabricApiButton = findNamed(panel, "loaderKind_FABRIC_API", JButton.class);
                JButton forgeButton = findNamed(panel, "loaderKind_FORGE", JButton.class);
                assertNotNull(fabricButton);
                assertNotNull(fabricApiButton);
                assertNotNull(forgeButton);
                assertFalse(fabricButton.isEnabled());
                assertTrue(fabricApiButton.isEnabled());
                assertFalse(forgeButton.isEnabled());
            });

            selectAndLoad(panel, executor, GameLoaderKind.FABRIC_API);
            EdtDispatcher.executeAndWait(() -> {
                selectFirstLoadedCatalogRow(panel);
                JButton addButton = findNamed(panel, "loaderAddSelection", JButton.class);
                assertNotNull(addButton);
                assertTrue(addButton.isEnabled());
                addButton.doClick();
                assertEquals(List.of(fabric, fabricApi), panel.selectedRemoteVersions());
                LoaderSelectionSnapshot snapshot = listenerSnapshot.get();
                assertNotNull(snapshot);
                assertEquals(List.of(fabric, fabricApi), snapshot.selectedRemoteVersions());
                assertTrue(snapshot.summary().contains("Fabric 0.16.0"));
                assertTrue(snapshot.summary().contains("Fabric API 0.100.0"));
            });

            EdtDispatcher.executeAndWait(() -> {
                JList<?> selectedList = findNamed(panel, "loaderSelectedList", JList.class);
                JButton removeButton = findNamed(panel, "loaderRemoveSelection", JButton.class);
                assertNotNull(selectedList);
                assertNotNull(removeButton);
                selectedList.setSelectedIndex(0);
                assertFalse(removeButton.isEnabled());
                selectedList.setSelectedIndex(1);
                assertTrue(removeButton.isEnabled());
                removeButton.doClick();
                assertEquals(List.of(fabric), panel.selectedRemoteVersions());

                panel.selectGameVersion("1.21.1");
                assertTrue(panel.selectedRemoteVersions().isEmpty());
                assertEquals("1.21.1", panel.selectionSnapshot().gameVersion().orElseThrow());
            });
            assertEquals(2, source.requestCount.get());
        } finally {
            closePanel(panelReference.get());
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    /// Verifies existing instance loaders participate in compatibility checks without becoming task rows.
    @Test
    void retainedInstanceLoadersBlockConflictsAndSatisfyApiParents() throws Exception {
        RemoteVersion fabric = remoteVersion("fabric", "1.20.1", "0.16.0");
        RemoteVersion fabricApi = remoteVersion("fabric-api", "1.20.1", "0.100.0");
        RecordingSource source = new RecordingSource();
        source.put(GameLoaderKind.FABRIC, fabric);
        source.put(GameLoaderKind.FABRIC_API, fabricApi);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        AtomicReference<@Nullable LoaderSelectionWizardPanel> panelReference = new AtomicReference<>();
        try {
            EdtDispatcher.executeAndWait(() -> panelReference.set(new LoaderSelectionWizardPanel(
                    new DefaultGameLoaderCatalogModel(source),
                    executor,
                    LoaderSelectionWizardStrings.english())));
            LoaderSelectionWizardPanel panel = Objects.requireNonNull(panelReference.get());

            EdtDispatcher.executeAndWait(() -> {
                panel.selectGameVersion("1.20.1");
                panel.setRetainedLoaderKinds(List.of(GameLoaderKind.FABRIC));
                JButton fabricButton = findNamed(panel, "loaderKind_FABRIC", JButton.class);
                JButton fabricApiButton = findNamed(panel, "loaderKind_FABRIC_API", JButton.class);
                JButton forgeButton = findNamed(panel, "loaderKind_FORGE", JButton.class);
                assertNotNull(fabricButton);
                assertNotNull(fabricApiButton);
                assertNotNull(forgeButton);
                assertTrue(fabricButton.isEnabled());
                assertTrue(fabricApiButton.isEnabled());
                assertFalse(forgeButton.isEnabled());
            });

            selectAndLoad(panel, executor, GameLoaderKind.FABRIC_API);
            EdtDispatcher.executeAndWait(() -> {
                selectFirstLoadedCatalogRow(panel);
                JButton addButton = findNamed(panel, "loaderAddSelection", JButton.class);
                assertNotNull(addButton);
                assertTrue(addButton.isEnabled());
                addButton.doClick();
                assertEquals(List.of(fabricApi), panel.selectedRemoteVersions());
            });

            selectAndLoad(panel, executor, GameLoaderKind.FABRIC);
            EdtDispatcher.executeAndWait(() -> {
                selectFirstLoadedCatalogRow(panel);
                JButton addButton = findNamed(panel, "loaderAddSelection", JButton.class);
                assertNotNull(addButton);
                addButton.doClick();
                assertEquals(List.of(fabric, fabricApi), panel.selectedRemoteVersions());
                JList<?> selectedList = findNamed(panel, "loaderSelectedList", JList.class);
                JButton removeButton = findNamed(panel, "loaderRemoveSelection", JButton.class);
                assertNotNull(selectedList);
                assertNotNull(removeButton);
                selectedList.setSelectedIndex(0);
                assertTrue(removeButton.isEnabled());
                removeButton.doClick();
                assertEquals(List.of(fabricApi), panel.selectedRemoteVersions());
            });
        } finally {
            closePanel(panelReference.get());
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    /// Selects a compatible loader card and explicitly refreshes its versions through the worker boundary.
    ///
    /// @param panel embedded Swing loader wizard
    /// @param executor worker executor used by the panel
    /// @param kind loader catalog to select and refresh
    /// @throws Exception when worker or EDT work does not complete promptly
    private static void selectAndLoad(
            LoaderSelectionWizardPanel panel,
            ExecutorService executor,
            GameLoaderKind kind) throws Exception {
        EdtDispatcher.executeAndWait(() -> {
            if (panel.selectionSnapshot().gameVersion().isEmpty()) {
                panel.selectGameVersion("1.20.1");
            }
            JButton kindButton = findNamed(panel, "loaderKind_" + kind.name(), JButton.class);
            assertNotNull(kindButton);
            kindButton.doClick();
            JButton loadButton = findNamed(panel, "loaderLoadVersions", JButton.class);
            assertNotNull(loadButton);
            loadButton.doClick();
        });
        awaitBackgroundWork(executor);
        EdtDispatcher.executeAndWait(() -> prepareViewport(panel.versionChoiceList(), 160));
        drainEdt();
    }

    /// Selects the first currently materialized version-list row.
    ///
    /// @param panel embedded Swing loader wizard
    private static void selectFirstLoadedCatalogRow(LoaderSelectionWizardPanel panel) {
        JList<?> versionList = panel.versionChoiceList().getList();
        assertTrue(versionList.getModel().getSize() > 0);
        versionList.setSelectedIndex(0);
    }

    /// Gives a detached viewport list actual measured geometry without triggering a catalog source request.
    ///
    /// @param choiceList lazy local version-list control
    /// @param extentHeight actual visible list viewport height
    private static void prepareViewport(ViewportChoiceList<?> choiceList, int extentHeight) {
        choiceList.setSize(480, extentHeight + 20);
        choiceList.getViewport().setExtentSize(new Dimension(480, extentHeight));
        choiceList.getList().setSize(480, extentHeight);
        choiceList.refreshLoadPlan();
    }

    /// Waits for queued worker work and all currently queued event-dispatch work.
    ///
    /// @param executor panel worker executor
    /// @throws Exception when worker work does not terminate promptly
    private static void awaitBackgroundWork(ExecutorService executor) throws Exception {
        executor.submit(() -> { }).get(5, TimeUnit.SECONDS);
        drainEdt();
    }

    /// Flushes work already queued on the Swing event dispatch thread.
    private static void drainEdt() {
        EdtDispatcher.executeAndWait(() -> { });
    }

    /// Closes a constructed panel on the EDT when a fixture owns one.
    ///
    /// @param panel panel to close, or null after a failed construction
    private static void closePanel(@Nullable LoaderSelectionWizardPanel panel) {
        if (panel != null) {
            EdtDispatcher.executeAndWait(panel::close);
            drainEdt();
        }
    }

    /// Finds one named child component of the requested type.
    ///
    /// @param root component subtree root
    /// @param name stable component name
    /// @param type requested component subtype
    /// @param <T> requested component subtype
    /// @return matching descendant, or null when none exists
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

    /// Creates one deterministic exact Core remote version fixture.
    ///
    /// @param libraryId source version-list identifier
    /// @param gameVersion matching Minecraft version
    /// @param selfVersion selected loader self version
    /// @return non-null exact Core remote version
    private static RemoteVersion remoteVersion(
            String libraryId,
            String gameVersion,
            String selfVersion) {
        return new RemoteVersion(
                libraryId,
                gameVersion,
                selfVersion,
                Instant.EPOCH,
                List.of("https://example.invalid/" + libraryId + ".jar"));
    }

    /// Records explicit source calls and returns exact configured Core remote versions synchronously.
    @NotNullByDefault
    private static final class RecordingSource implements GameLoaderCatalogSource {
        /// Exact source items indexed by their selected loader kind.
        private final Map<GameLoaderKind, @Unmodifiable List<GameLoaderCatalogItem>> itemsByKind =
                new EnumMap<>(GameLoaderKind.class);

        /// Number of source refreshes initiated by an explicit version-list command.
        private final AtomicInteger requestCount = new AtomicInteger();

        /// Configures one source result retaining the exact supplied remote object.
        ///
        /// @param kind selected loader catalog kind
        /// @param remoteVersion exact Core remote version to return
        private void put(GameLoaderKind kind, RemoteVersion remoteVersion) {
            GameLoaderKind nonNullKind = Objects.requireNonNull(kind, "kind");
            itemsByKind.put(nonNullKind, List.of(new GameLoaderCatalogItem(
                    nonNullKind,
                    Objects.requireNonNull(remoteVersion, "remoteVersion"))));
        }

        /// Records one explicit selection and returns the configured local immutable catalog result.
        ///
        /// @param request explicit loader catalog request
        /// @return completed exact loader item list
        @Override
        public CompletionStage<@Unmodifiable List<GameLoaderCatalogItem>> refreshAsync(
                GameLoaderCatalogRequest request) {
            GameLoaderCatalogRequest nonNullRequest = Objects.requireNonNull(request, "request");
            requestCount.incrementAndGet();
            @Unmodifiable List<GameLoaderCatalogItem> items = itemsByKind.getOrDefault(
                    nonNullRequest.kind(),
                    List.of());
            return CompletableFuture.completedFuture(items);
        }
    }
}
