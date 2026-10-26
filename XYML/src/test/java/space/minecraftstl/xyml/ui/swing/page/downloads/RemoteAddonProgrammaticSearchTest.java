// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.downloads;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import space.minecraftstl.xyml.addon.RemoteAddon;
import space.minecraftstl.xyml.addon.RemoteAddonRepository;
import space.minecraftstl.xyml.task.Task;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;
import space.minecraftstl.xyml.ui.swing.task.TaskProgressStrings;

import javax.swing.JTextField;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies retained project queries against an offline backend through Swing layout and shutdown.
@NotNullByDefault
final class RemoteAddonProgrammaticSearchTest {
    /// Prefills immediately, submits the latest query once after layout, and survives repeated visibility changes.
    @Test
    void submitsLatestQueryOnlyAfterViewportIsReady() throws Exception {
        try (Fixture fixture = new Fixture()) {
            EdtDispatcher.executeAndWait(() -> {
                fixture.panel.openSearch("old-mod");
                fixture.panel.openSearch(" Litematica ");
                assertEquals("Litematica", searchField(fixture.panel).getText());
            });
            fixture.awaitWork();
            assertTrue(fixture.backend.queries.isEmpty());
            fixture.showViewport();
            fixture.awaitWork();
            assertEquals(1, fixture.backend.queries.size());
            assertEquals("Litematica", fixture.backend.queries.get(0).searchText());
            assertEquals(0, fixture.backend.queries.get(0).pageOffset());
            EdtDispatcher.executeAndWait(() -> {
                fixture.panel.setVisible(false);
                fixture.panel.setVisible(true);
                fixture.panel.choiceList().refreshLoadPlan();
            });
            fixture.awaitWork();
            assertEquals(1, fixture.backend.queries.size());
            EdtDispatcher.executeAndWait(() -> fixture.panel.openSearch("other-mod"));
            fixture.awaitWork();
            assertEquals(2, fixture.backend.queries.size());
            assertEquals("other-mod", fixture.backend.queries.get(1).searchText());
            EdtDispatcher.executeAndWait(() -> fixture.panel.openSearch("Litematica"));
            fixture.awaitWork();
            EdtDispatcher.executeAndWait(() -> assertEquals("Litematica", searchField(fixture.panel).getText()));
            assertEquals(3, fixture.backend.queries.size(), "Changed search text invalidates the previous criteria cache");
            assertEquals("Litematica", fixture.backend.queries.get(2).searchText());
            EdtDispatcher.executeAndWait(() -> {
                fixture.panel.setVisible(false);
                fixture.panel.setVisible(true);
                fixture.panel.choiceList().refreshLoadPlan();
            });
            fixture.awaitWork();
            assertEquals(3, fixture.backend.queries.size());
        }
    }

    /// Closing before layout discards pending submission and ignores later project queries.
    @Test
    void ignoresPendingAndNewQueriesAfterClose() throws Exception {
        try (Fixture fixture = new Fixture()) {
            EdtDispatcher.executeAndWait(() -> {
                fixture.panel.openSearch("Litematica");
                fixture.panel.close();
                fixture.panel.close();
                fixture.panel.openSearch("ignored-after-close");
                assertEquals("Litematica", searchField(fixture.panel).getText());
            });
            fixture.showViewport();
            fixture.awaitWork();
            assertTrue(fixture.backend.queries.isEmpty());
        }
    }

    /// A failed provider search leaves the programmatic shortcut usable for the next attempt.
    @Test
    void retriesAfterProviderFailure() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.backend.failNextSearch.set(true);
            EdtDispatcher.executeAndWait(() -> fixture.panel.openSearch("Litematica"));
            fixture.showViewport();
            fixture.awaitWork();
            assertEquals(1, fixture.backend.queries.size());
            EdtDispatcher.executeAndWait(() -> fixture.panel.openSearch("Litematica"));
            fixture.awaitWork();
            assertEquals(2, fixture.backend.queries.size());
            assertEquals("Litematica", fixture.backend.queries.get(1).searchText());
        }
    }

    /// Finds the user-visible search input in the real catalog hierarchy.
    ///
    /// @param root current Swing container
    /// @return named query field
    private static JTextField searchField(Container root) {
        return Objects.requireNonNull(findSearchField(root), "remoteAddonSearch");
    }

    /// Traverses one subtree without assuming the catalog's intermediate layout structure.
    ///
    /// @param root current Swing container
    /// @return query field, or null when this subtree does not contain it
    private static @Nullable JTextField findSearchField(Container root) {
        for (Component component : root.getComponents()) {
            if (component instanceof JTextField field && "remoteAddonSearch".equals(field.getName())) {
                return field;
            }
            if (component instanceof Container child) {
                @Nullable JTextField field = findSearchField(child);
                if (field != null) {
                    return field;
                }
            }
        }
        return null;
    }

    /// Owns one detached catalog, its deterministic source, and its bounded worker lifetime.
    @NotNullByDefault
    private static final class Fixture implements AutoCloseable {
        /// Offline provider recording requests in worker submission order.
        private final Backend backend = new Backend();

        /// Single owner of provider work; shut down after catalog listeners are detached.
        private final ExecutorService executor = Executors.newSingleThreadExecutor();

        /// Real EDT-owned catalog used by each isolated scenario.
        private final RemoteAddonCatalogPanel panel;

        /// Constructs the catalog on the EDT without starting provider work.
        private Fixture() {
            AtomicReference<@Nullable RemoteAddonCatalogPanel> reference = new AtomicReference<>();
            try {
                EdtDispatcher.executeAndWait(() -> reference.set(new RemoteAddonCatalogPanel(
                        RemoteAddonCatalogKind.MOD,
                        backend,
                        request -> Task.completed(null),
                        kind -> Optional.empty(),
                        executor,
                        RemoteAddonCatalogStrings.english(RemoteAddonCatalogKind.MOD),
                        TaskProgressStrings.english(),
                        null,
                        Duration.ZERO)));
                panel = Objects.requireNonNull(reference.get());
            } catch (RuntimeException | Error failure) {
                executor.shutdownNow();
                throw failure;
            }
        }

        /// Establishes deterministic geometry and dispatches the existing pending-search callback.
        private void showViewport() {
            EdtDispatcher.executeAndWait(() -> {
                panel.choiceList().setSize(480, 180);
                panel.choiceList().getViewport().setExtentSize(new Dimension(480, 160));
                panel.choiceList().getList().setSize(480, 160);
                panel.choiceList().refreshLoadPlan();
                if (!panel.isDisplayable()) {
                    panel.addNotify();
                }
            });
        }

        /// Flushes bounded worker and EDT rounds, including categories followed by a retained search.
        ///
        /// @throws Exception if queued work fails to finish within five seconds per round
        private void awaitWork() throws Exception {
            for (int round = 0; round < 3; round++) {
                EdtDispatcher.executeAndWait(() -> { });
                executor.submit(() -> { }).get(5, TimeUnit.SECONDS);
                EdtDispatcher.executeAndWait(() -> { });
            }
        }

        /// Detaches the peer and listeners before stopping owned background work.
        ///
        /// @throws InterruptedException if interrupted while waiting for worker termination
        @Override
        public void close() throws InterruptedException {
            try {
                EdtDispatcher.executeAndWait(() -> {
                    panel.close();
                    if (panel.isDisplayable()) {
                        panel.removeNotify();
                    }
                });
            } finally {
                executor.shutdownNow();
                assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
            }
        }
    }

    /// Returns empty deterministic provider pages without any network or installation effects.
    @NotNullByDefault
    private static final class Backend implements RemoteAddonCatalogBackend {
        /// Thread-safe worker-ordered requests observed by the test thread after a barrier.
        private final List<RemoteAddonCatalogQuery> queries = new CopyOnWriteArrayList<>();

        /// One-shot provider failure used to exercise subsequent shortcut recovery.
        private final AtomicBoolean failNextSearch = new AtomicBoolean();

        /// Returns an immutable empty category tree.
        @Override
        public @Unmodifiable List<RemoteAddonRepository.Category> loadCategories(
                RemoteAddonCatalogKind kind, RemoteAddonCatalogSource source) {
            return List.of();
        }

        /// Records the exact query and returns an immutable empty result page.
        @Override
        public RemoteAddonCatalogPage search(RemoteAddonCatalogQuery query) throws IOException {
            queries.add(query);
            if (failNextSearch.compareAndSet(true, false)) {
                throw new IOException("Fixture provider failure");
            }
            return new RemoteAddonCatalogPage(List.of(), query.pageOffset(), 0);
        }

        /// Returns no installable versions because these scenarios never select a project.
        @Override
        public @Unmodifiable List<RemoteAddon.Version> loadVersions(RemoteAddonCatalogItem item) {
            return List.of();
        }
    }
}
