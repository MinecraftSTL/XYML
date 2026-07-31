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
import space.minecraftstl.xyml.task.Task;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;
import space.minecraftstl.xyml.ui.swing.choice.ChoiceListEntry;
import space.minecraftstl.xyml.ui.swing.choice.ViewportChoiceList;
import space.minecraftstl.xyml.ui.swing.task.TaskProgressStrings;

import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JList;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies explicit direct-install remote catalog search, viewport sizing, cache reuse, and task handoff.
@NotNullByDefault
final class RemoteAddonCatalogPanelTest {
    /// Keeps the remote-content result surface transparent except for the selected row highlight.
    @Test
    void leavesBackgroundVisibleThroughResultListAndRows() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        AtomicReference<@Nullable RemoteAddonCatalogPanel> panelReference = new AtomicReference<>();
        try {
            EdtDispatcher.executeAndWait(() -> panelReference.set(new RemoteAddonCatalogPanel(
                    RemoteAddonCatalogKind.MOD,
                    new RecordingBackend(fixtureAddon(), fixtureVersion()),
                    request -> Task.completed(null),
                    kind -> Optional.of(fixtureTarget()),
                    executor,
                    RemoteAddonCatalogStrings.english(RemoteAddonCatalogKind.MOD),
                    TaskProgressStrings.english(),
                    null,
                    Duration.ZERO)));
            RemoteAddonCatalogPanel panel = Objects.requireNonNull(panelReference.get());

            EdtDispatcher.executeAndWait(() -> {
                JList<ChoiceListEntry<RemoteAddonCatalogItem>> list = panel.choiceList().getList();
                ChoiceListEntry<RemoteAddonCatalogItem> entry = ChoiceListEntry.loaded(
                        0,
                        new RemoteAddonCatalogItem(
                                fixtureAddon(),
                                RemoteAddonCatalogKind.MOD,
                                RemoteAddonCatalogSource.CURSEFORGE));
                boolean unselectedOpaque = ((JComponent) list.getCellRenderer()
                        .getListCellRendererComponent(list, entry, 0, false, false)).isOpaque();
                boolean selectedOpaque = ((JComponent) list.getCellRenderer()
                        .getListCellRendererComponent(list, entry, 0, true, false)).isOpaque();

                assertFalse(panel.isOpaque());
                assertFalse(panel.choiceList().isOpaque());
                assertFalse(panel.choiceList().getViewport().isOpaque());
                assertFalse(list.isOpaque());
                assertFalse(unselectedOpaque);
                assertTrue(selectedOpaque);
            });
        } finally {
            @Nullable RemoteAddonCatalogPanel panel = panelReference.get();
            if (panel != null) {
                panel.close();
            }
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    /// Searches only after an explicit command, resolves the selected project's versions, and captures its install target.
    @Test
    void waitsForExplicitSearchAndHandsSelectedVersionToSelectedInstanceTask() throws Exception {
        RemoteAddon addon = fixtureAddon();
        RemoteAddon.Version version = fixtureVersion();
        RecordingBackend backend = new RecordingBackend(addon, version);
        RecordingInstallLauncher installLauncher = new RecordingInstallLauncher();
        RemoteAddonInstallTarget target = fixtureTarget();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        AtomicReference<@Nullable RemoteAddonCatalogPanel> panelReference = new AtomicReference<>();
        try {
            EdtDispatcher.executeAndWait(() -> panelReference.set(new RemoteAddonCatalogPanel(
                    RemoteAddonCatalogKind.MOD,
                    backend,
                    installLauncher,
                    kind -> Optional.of(target),
                    executor,
                    RemoteAddonCatalogStrings.english(RemoteAddonCatalogKind.MOD),
                    TaskProgressStrings.english(),
                    null,
                    Duration.ZERO)));
            RemoteAddonCatalogPanel panel = Objects.requireNonNull(panelReference.get());

            EdtDispatcher.executeAndWait(() -> {
                prepareViewport(panel.choiceList(), 160);
                assertEquals(0, backend.searchRequests.get());
                assertEquals(0, backend.versionRequests.get());
                assertAll(
                        () -> assertEquals(Boolean.TRUE, findNamed(
                                panel,
                                "remoteAddonSearch",
                                JComponent.class).getClientProperty("JTextField.showClearButton")),
                        () -> assertEquals(Boolean.TRUE, findNamed(
                                panel,
                                "remoteAddonGameVersion",
                                JComponent.class).getClientProperty("JTextField.showClearButton")));
                JButton search = findNamed(panel, "remoteAddonSearchAction", JButton.class);
                assertNotNull(search);
                search.doClick();
            });
            awaitBackgroundWork(executor);

            EdtDispatcher.executeAndWait(() -> prepareViewport(panel.choiceList(), 160));
            drainEdt();
            EdtDispatcher.executeAndWait(() -> {
                assertEquals(1, backend.searchRequests.get());
                RemoteAddonCatalogQuery query = backend.lastQuery.get();
                assertNotNull(query);
                int rowHeight = panel.choiceList().getList().getFixedCellHeight();
                int expectedPageSize = Math.max(1, Math.floorDiv(160 + rowHeight - 1, rowHeight));
                assertEquals(expectedPageSize, query.pageSize());
                assertEquals(RemoteAddonCatalogKind.MOD, query.kind());
                assertEquals(0, query.pageOffset());
                panel.choiceList().getList().setSelectedIndex(0);
            });
            awaitBackgroundWork(executor);

            EdtDispatcher.executeAndWait(() -> {
                assertEquals(1, backend.versionRequests.get());
                JComboBox<?> versionBox = findNamed(panel, "remoteAddonVersion", JComboBox.class);
                JButton install = findNamed(panel, "remoteAddonInstall", JButton.class);
                assertNotNull(versionBox);
                assertNotNull(install);
                assertEquals(1, versionBox.getItemCount());
                assertTrue(install.isEnabled());
                install.doClick();
            });
            drainEdt();

            RemoteAddonInstallRequest request = installLauncher.request.get();
            assertNotNull(request);
            assertEquals(addon, request.item().addon());
            assertEquals(version, request.version());
            assertEquals(target, request.target());
        } finally {
            @Nullable RemoteAddonCatalogPanel panel = panelReference.get();
            if (panel != null) {
                panel.close();
            }
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    /// Defers an interactive target resolver until the user presses the enabled acquisition command.
    @Test
    void resolvesInteractiveTargetOnlyAfterAcquisitionCommand() throws Exception {
        RecordingBackend backend = new RecordingBackend(fixtureAddon(), fixtureVersion());
        RecordingInstallLauncher installLauncher = new RecordingInstallLauncher();
        RecordingInteractiveTargetResolver targetResolver = new RecordingInteractiveTargetResolver();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        AtomicReference<@Nullable RemoteAddonCatalogPanel> panelReference = new AtomicReference<>();
        try {
            EdtDispatcher.executeAndWait(() -> panelReference.set(new RemoteAddonCatalogPanel(
                    RemoteAddonCatalogKind.MOD,
                    backend,
                    installLauncher,
                    targetResolver,
                    executor,
                    RemoteAddonCatalogStrings.english(RemoteAddonCatalogKind.MOD),
                    TaskProgressStrings.english(),
                    null,
                    Duration.ZERO)));
            RemoteAddonCatalogPanel panel = Objects.requireNonNull(panelReference.get());

            EdtDispatcher.executeAndWait(() -> {
                prepareViewport(panel.choiceList(), 160);
                JButton search = findNamed(panel, "remoteAddonSearchAction", JButton.class);
                assertNotNull(search);
                search.doClick();
            });
            awaitBackgroundWork(executor);
            EdtDispatcher.executeAndWait(() -> {
                prepareViewport(panel.choiceList(), 160);
                panel.choiceList().getList().setSelectedIndex(0);
            });
            awaitBackgroundWork(executor);

            EdtDispatcher.executeAndWait(() -> {
                JButton install = findNamed(panel, "remoteAddonInstall", JButton.class);
                assertNotNull(install);
                assertTrue(install.isEnabled());
                assertEquals(0, targetResolver.selectionRequests.get());
                install.doClick();
            });
            drainEdt();

            assertEquals(1, targetResolver.selectionRequests.get());
            assertNotNull(targetResolver.owner.get());
            assertNotNull(installLauncher.request.get());
        } finally {
            @Nullable RemoteAddonCatalogPanel panel = panelReference.get();
            if (panel != null) {
                panel.close();
            }
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    /// Reuses only pages that the user already visited with the identical viewport-derived page-size key.
    @Test
    void reusesVisitedPagesWithoutPrefetchButMissesAfterViewportPageSizeChanges() throws Exception {
        RecordingBackend backend = new RecordingBackend(fixtureAddon(), fixtureVersion());
        ExecutorService executor = Executors.newSingleThreadExecutor();
        AtomicReference<@Nullable RemoteAddonCatalogPanel> panelReference = new AtomicReference<>();
        try {
            EdtDispatcher.executeAndWait(() -> panelReference.set(new RemoteAddonCatalogPanel(
                    RemoteAddonCatalogKind.MOD,
                    backend,
                    request -> Task.completed(null),
                    kind -> Optional.of(fixtureTarget()),
                    executor,
                    RemoteAddonCatalogStrings.english(RemoteAddonCatalogKind.MOD),
                    TaskProgressStrings.english(),
                    null,
                    Duration.ZERO)));
            RemoteAddonCatalogPanel panel = Objects.requireNonNull(panelReference.get());

            EdtDispatcher.executeAndWait(() -> {
                prepareViewport(panel.choiceList(), 160);
                JButton search = findNamed(panel, "remoteAddonSearchAction", JButton.class);
                assertNotNull(search);
                search.doClick();
            });
            awaitBackgroundWork(executor);
            EdtDispatcher.executeAndWait(() -> {
                prepareViewport(panel.choiceList(), 160);
                JButton next = findNamed(panel, "remoteAddonNextPage", JButton.class);
                assertNotNull(next);
                assertTrue(next.isEnabled());
                next.doClick();
            });
            awaitBackgroundWork(executor);
            assertEquals(2, backend.searchRequests.get());

            EdtDispatcher.executeAndWait(() -> {
                JButton previous = findNamed(panel, "remoteAddonPreviousPage", JButton.class);
                assertNotNull(previous);
                assertTrue(previous.isEnabled());
                previous.doClick();
            });
            drainEdt();
            assertEquals(2, backend.searchRequests.get());

            EdtDispatcher.executeAndWait(() -> {
                prepareViewport(panel.choiceList(), 240);
                JButton next = findNamed(panel, "remoteAddonNextPage", JButton.class);
                assertNotNull(next);
                next.doClick();
            });
            awaitBackgroundWork(executor);
            assertEquals(3, backend.searchRequests.get());
            RemoteAddonCatalogQuery resizedQuery = backend.lastQuery.get();
            assertNotNull(resizedQuery);
            int rowHeight = Objects.requireNonNull(panelReference.get()).choiceList().getList().getFixedCellHeight();
            assertEquals(Math.max(1, Math.floorDiv(240 + rowHeight - 1, rowHeight)), resizedQuery.pageSize());
        } finally {
            @Nullable RemoteAddonCatalogPanel panel = panelReference.get();
            if (panel != null) {
                panel.close();
            }
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    /// Refuses direct installation when a selected-instance target cannot be resolved.
    @Test
    void disablesDirectInstallationWithoutSelectedInstanceTarget() throws Exception {
        RecordingBackend backend = new RecordingBackend(fixtureAddon(), fixtureVersion());
        ExecutorService executor = Executors.newSingleThreadExecutor();
        AtomicReference<@Nullable RemoteAddonCatalogPanel> panelReference = new AtomicReference<>();
        try {
            EdtDispatcher.executeAndWait(() -> panelReference.set(new RemoteAddonCatalogPanel(
                    RemoteAddonCatalogKind.MOD,
                    backend,
                    request -> Task.completed(null),
                    kind -> Optional.empty(),
                    executor,
                    RemoteAddonCatalogStrings.english(RemoteAddonCatalogKind.MOD),
                    TaskProgressStrings.english(),
                    null,
                    Duration.ZERO)));
            RemoteAddonCatalogPanel panel = Objects.requireNonNull(panelReference.get());
            EdtDispatcher.executeAndWait(() -> {
                prepareViewport(panel.choiceList(), 160);
                JButton search = findNamed(panel, "remoteAddonSearchAction", JButton.class);
                assertNotNull(search);
                search.doClick();
            });
            awaitBackgroundWork(executor);
            EdtDispatcher.executeAndWait(() -> {
                prepareViewport(panel.choiceList(), 160);
                panel.choiceList().getList().setSelectedIndex(0);
            });
            awaitBackgroundWork(executor);
            EdtDispatcher.executeAndWait(() -> {
                JButton install = findNamed(panel, "remoteAddonInstall", JButton.class);
                assertNotNull(install);
                assertFalse(install.isEnabled());
            });
        } finally {
            @Nullable RemoteAddonCatalogPanel panel = panelReference.get();
            if (panel != null) {
                panel.close();
            }
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    /// Creates every direct-install category without issuing a source or version request.
    @Test
    void constructionOfEveryCatalogKindIsOffline() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            for (RemoteAddonCatalogKind kind : RemoteAddonCatalogKind.values()) {
                RecordingBackend backend = new RecordingBackend(fixtureAddon(), fixtureVersion());
                AtomicReference<@Nullable RemoteAddonCatalogPanel> panelReference = new AtomicReference<>();
                try {
                    EdtDispatcher.executeAndWait(() -> panelReference.set(new RemoteAddonCatalogPanel(
                            kind,
                            backend,
                            request -> Task.completed(null),
                            ignoredKind -> Optional.empty(),
                            executor,
                            RemoteAddonCatalogStrings.english(kind),
                            TaskProgressStrings.english(),
                            null,
                            Duration.ZERO)));

                    assertEquals(0, backend.searchRequests.get());
                    assertEquals(0, backend.versionRequests.get());
                    if (kind == RemoteAddonCatalogKind.WORLD) {
                        EdtDispatcher.executeAndWait(() -> {
                            JComboBox<?> sourceBox = findNamed(
                                    Objects.requireNonNull(panelReference.get()),
                                    "remoteAddonSource",
                                    JComboBox.class);
                            assertNotNull(sourceBox);
                            assertEquals(1, sourceBox.getItemCount());
                            assertEquals(RemoteAddonCatalogSource.CURSEFORGE, sourceBox.getSelectedItem());
                        });
                    }
                } finally {
                    @Nullable RemoteAddonCatalogPanel panel = panelReference.get();
                    if (panel != null) {
                        panel.close();
                    }
                }
            }
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    /// Loads provider categories after display and forwards the selected category and sort to Core search.
    @Test
    void loadsAndAppliesProviderCategoryAndSortFilters() throws Exception {
        RecordingBackend backend = new RecordingBackend(fixtureAddon(), fixtureVersion());
        ExecutorService executor = Executors.newSingleThreadExecutor();
        AtomicReference<@Nullable RemoteAddonCatalogPanel> panelReference = new AtomicReference<>();
        try {
            EdtDispatcher.executeAndWait(() -> {
                RemoteAddonCatalogPanel panel = new RemoteAddonCatalogPanel(
                        RemoteAddonCatalogKind.MOD,
                        backend,
                        request -> Task.completed(null),
                        kind -> Optional.of(fixtureTarget()),
                        executor,
                        RemoteAddonCatalogStrings.english(RemoteAddonCatalogKind.MOD),
                        TaskProgressStrings.english(),
                        null,
                        Duration.ZERO);
                panelReference.set(panel);
                prepareViewport(panel.choiceList(), 160);
                panel.addNotify();
            });
            awaitBackgroundWork(executor);

            EdtDispatcher.executeAndWait(() -> {
                RemoteAddonCatalogPanel panel = Objects.requireNonNull(panelReference.get());
                JComboBox<?> categoryBox = findNamed(panel, "remoteAddonCategory", JComboBox.class);
                JComboBox<?> sortBox = findNamed(panel, "remoteAddonSort", JComboBox.class);
                JButton search = findNamed(panel, "remoteAddonSearchAction", JButton.class);
                assertNotNull(categoryBox);
                assertNotNull(sortBox);
                assertNotNull(search);
                assertEquals(3, categoryBox.getItemCount());
                assertEquals(4, sortBox.getItemCount());
                categoryBox.setSelectedIndex(2);
                sortBox.setSelectedItem(RemoteAddonRepository.SortType.LAST_UPDATED);
                search.doClick();
            });
            awaitBackgroundWork(executor);

            assertEquals(1, backend.categoryRequests.get());
            RemoteAddonCatalogQuery query = backend.lastQuery.get();
            assertNotNull(query);
            assertNotNull(query.category());
            assertEquals("technology-child", query.category().id());
            assertEquals(RemoteAddonRepository.SortType.LAST_UPDATED, query.sortType());
        } finally {
            @Nullable RemoteAddonCatalogPanel panel = panelReference.get();
            if (panel != null) {
                panel.close();
            }
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    /// Gives a detached sparse list measurable result geometry without invoking a source request.
    ///
    /// @param choiceList detached result list
    /// @param extentHeight actual visible list viewport height
    private static void prepareViewport(ViewportChoiceList<?> choiceList, int extentHeight) {
        choiceList.setSize(480, extentHeight + 20);
        choiceList.getViewport().setExtentSize(new Dimension(480, extentHeight));
        choiceList.getList().setSize(480, extentHeight);
        choiceList.refreshLoadPlan();
    }

    /// Waits for queued worker work and all currently queued EDT callbacks.
    ///
    /// @param executor panel worker executor
    /// @throws Exception when worker work does not finish promptly
    private static void awaitBackgroundWork(ExecutorService executor) throws Exception {
        executor.submit(() -> { }).get(5, TimeUnit.SECONDS);
        drainEdt();
    }

    /// Flushes callbacks already queued onto the Swing event dispatch thread.
    private static void drainEdt() {
        EdtDispatcher.executeAndWait(() -> { });
    }

    /// Finds one named child component of a requested type.
    ///
    /// @param root component subtree root
    /// @param name stable component name
    /// @param type requested Swing component type
    /// @param <T> requested component subtype
    /// @return matching descendant, or null when not present
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

    /// Creates one deterministic remote Mod project for selected-version handoff tests.
    ///
    /// @return non-null fixture remote Mod
    private static RemoteAddon fixtureAddon() {
        return new RemoteAddon(
                "fixture-mod",
                "fixture-author",
                "Fixture Mod",
                "Fixture description",
                List.of(),
                "https://example.invalid/fixture-mod",
                "https://example.invalid/fixture-mod.png",
                new FixtureAddonData(),
                RemoteAddonRepository.Type.MOD);
    }

    /// Creates one installable remote Mod version for selected-version handoff tests.
    ///
    /// @return non-null fixture remote version
    private static RemoteAddon.Version fixtureVersion() {
        return new RemoteAddon.Version(
                () -> RemoteAddon.Source.MODRINTH,
                "fixture-mod",
                "Fixture Mod 1.0",
                "1.0.0",
                "",
                Instant.EPOCH,
                RemoteAddon.VersionType.Release,
                new RemoteAddon.File(
                        Map.of("sha256", "0123456789012345678901234567890123456789012345678901234567890123"),
                        "https://example.invalid/fixture-mod.jar",
                        "fixture-mod.jar"),
                List.of(),
                List.of("1.20.1"),
                List.<ModLoaderType>of());
    }

    /// Creates a stable selected-instance install target without touching the local filesystem.
    ///
    /// @return immutable fixture target
    private static RemoteAddonInstallTarget fixtureTarget() {
        return new RemoteAddonInstallTarget(
                RemoteAddonCatalogKind.MOD,
                "fixture-instance",
                Path.of("build", "fixture-mods"));
    }

    /// Recording explicit source gateway returning a deterministic item for each user-requested page.
    @NotNullByDefault
    private static final class RecordingBackend implements RemoteAddonCatalogBackend {
        /// Immutable item returned by every user-requested provider page.
        private final RemoteAddonCatalogItem item;

        /// Immutable selected-project version returned after selection.
        private final RemoteAddon.Version version;

        /// Count of source searches started by explicit UI commands.
        private final AtomicInteger searchRequests = new AtomicInteger();

        /// Count of selected-project version requests.
        private final AtomicInteger versionRequests = new AtomicInteger();

        /// Count of display-triggered provider category requests.
        private final AtomicInteger categoryRequests = new AtomicInteger();

        /// Last exact source query, or null before an explicit search.
        private final AtomicReference<@Nullable RemoteAddonCatalogQuery> lastQuery = new AtomicReference<>();

        /// Creates the deterministic source gateway.
        ///
        /// @param addon fixture project returned by searches
        /// @param version fixture version returned for project selection
        private RecordingBackend(RemoteAddon addon, RemoteAddon.Version version) {
            item = new RemoteAddonCatalogItem(
                    addon,
                    RemoteAddonCatalogKind.MOD,
                    RemoteAddonCatalogSource.MODRINTH);
            this.version = Objects.requireNonNull(version, "version");
        }

        /// Records and returns a nested provider category tree for selector tests.
        ///
        /// @param kind requested add-on kind
        /// @param source selected provider
        /// @return immutable nested fixture categories
        @Override
        public @Unmodifiable List<RemoteAddonRepository.Category> loadCategories(
                RemoteAddonCatalogKind kind,
                RemoteAddonCatalogSource source) {
            assertEquals(RemoteAddonCatalogKind.MOD, kind);
            assertEquals(RemoteAddonCatalogSource.MODRINTH, source);
            categoryRequests.incrementAndGet();
            RemoteAddonRepository.Category child = new RemoteAddonRepository.Category(
                    new Object(),
                    "technology-child",
                    List.of());
            return List.of(new RemoteAddonRepository.Category(
                    new Object(),
                    "technology",
                    List.of(child)));
        }

        /// Records and returns a two-page provider result so next and previous commands are testable.
        ///
        /// @param query explicit user-requested source query
        /// @return deterministic page containing the fixture item
        @Override
        public RemoteAddonCatalogPage search(RemoteAddonCatalogQuery query) {
            lastQuery.set(Objects.requireNonNull(query, "query"));
            searchRequests.incrementAndGet();
            return new RemoteAddonCatalogPage(List.of(item), query.pageOffset(), 2);
        }

        /// Records and returns the fixture project's selected version.
        ///
        /// @param item selected fixture result
        /// @return immutable singleton fixture version list
        @Override
        public List<RemoteAddon.Version> loadVersions(RemoteAddonCatalogItem item) {
            assertEquals(this.item, item);
            versionRequests.incrementAndGet();
            return List.of(version);
        }
    }

    /// Task-launcher substitute recording direct selected-instance installation handoff without I/O.
    @NotNullByDefault
    private static final class RecordingInstallLauncher implements RemoteAddonInstallLauncher {
        /// Last install request, or null before user action.
        private final AtomicReference<@Nullable RemoteAddonInstallRequest> request = new AtomicReference<>();

        /// Records one selected artifact request and supplies a successful no-op task.
        ///
        /// @param request selected artifact and selected-instance target
        /// @return completed no-op task
        @Override
        public Task<?> createInstallTask(RemoteAddonInstallRequest request) {
            this.request.set(Objects.requireNonNull(request, "request"));
            return Task.completed(null);
        }
    }

    /// Interactive target substitute proving control refreshes never trigger destination selection.
    @NotNullByDefault
    private static final class RecordingInteractiveTargetResolver implements RemoteAddonInstallTargetResolver {
        /// Count of exact selected-artifact target requests.
        private final AtomicInteger selectionRequests = new AtomicInteger();

        /// Last component owning the explicit selection request, or null before acquisition.
        private final AtomicReference<@Nullable Component> owner = new AtomicReference<>();

        /// Returns no noninteractive target so the richer selection method must be used.
        ///
        /// @param kind requested category
        /// @return empty target
        @Override
        public Optional<RemoteAddonInstallTarget> resolve(RemoteAddonCatalogKind kind) {
            Objects.requireNonNull(kind, "kind");
            return Optional.empty();
        }

        /// Reports that the explicit acquisition command can request a target.
        ///
        /// @param kind requested category
        /// @return true for the fixture Mod category
        @Override
        public boolean isSelectionAvailable(RemoteAddonCatalogKind kind) {
            return Objects.requireNonNull(kind, "kind") == RemoteAddonCatalogKind.MOD;
        }

        /// Records the explicit selection context and returns a stable target.
        ///
        /// @param kind selected category
        /// @param item selected project
        /// @param version selected version
        /// @param ownerComponent owning panel
        /// @return stable selected-instance target
        @Override
        public Optional<RemoteAddonInstallTarget> resolveSelection(
                RemoteAddonCatalogKind kind,
                RemoteAddonCatalogItem item,
                RemoteAddon.Version version,
                Component ownerComponent) {
            assertEquals(RemoteAddonCatalogKind.MOD, kind);
            assertEquals(RemoteAddonCatalogKind.MOD, item.kind());
            assertEquals("fixture-mod.jar", version.file().filename());
            owner.set(Objects.requireNonNull(ownerComponent, "ownerComponent"));
            selectionRequests.incrementAndGet();
            return Optional.of(fixtureTarget());
        }
    }

    /// Provides unused Core data contracts required to create a realistic fixture remote project.
    @NotNullByDefault
    private static final class FixtureAddonData implements RemoteAddon.IMod {
        /// Rejects dependency resolution because the focused panel test supplies selected versions through its backend.
        ///
        /// @param modRepository unused source repository
        /// @param downloadProvider unused Core download provider
        /// @return never returns normally
        /// @throws IOException always because dependencies are outside the fixture scope
        @Override
        public List<RemoteAddon> loadDependencies(
                RemoteAddonRepository modRepository,
                DownloadProvider downloadProvider) throws IOException {
            throw new IOException("Fixture dependencies are outside the catalog-panel test");
        }

        /// Rejects direct Core version resolution because the test backend owns fixture version responses.
        ///
        /// @param modRepository unused source repository
        /// @param downloadProvider unused Core download provider
        /// @return never returns normally
        /// @throws IOException always because versions are supplied by the recording backend
        @Override
        public Stream<RemoteAddon.Version> loadVersions(
                RemoteAddonRepository modRepository,
                DownloadProvider downloadProvider) throws IOException {
            throw new IOException("Fixture versions are supplied by the recording backend");
        }
    }
}
