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
package space.minecraftstl.xyml.ui.swing.page.instances.management.addonupdates;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import space.minecraftstl.xyml.addon.RemoteAddon;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;

import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JTable;
import java.awt.Component;
import java.awt.Container;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies that add-on scanning remains opt-in and completed rows expose only real navigation actions.
@NotNullByDefault
final class AddonUpdatesPanelTest {
    /// Does not discover local files or contact a source until the user presses Check updates.
    @Test
    void defersScanUntilExplicitCheckAndExposesSourceAndLocalActions() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        RecordingScanAccess access = new RecordingScanAccess(result());
        RecordingInteractions interactions = new RecordingInteractions();
        AtomicReference<@Nullable AddonUpdatesPanel> panelReference = new AtomicReference<>();
        try {
            EdtDispatcher.executeAndWait(() -> panelReference.set(new AddonUpdatesPanel(
                    access,
                    executor,
                    AddonUpdatesStrings.english(),
                    interactions)));
            AddonUpdatesPanel panel = Objects.requireNonNull(panelReference.get());
            assertEquals(0, access.scanCount.get());

            EdtDispatcher.executeAndWait(() -> {
                JTable table = panel.resultsTable();
                JButton check = findNamed(panel, "addonUpdatesCheck", JButton.class);
                JButton source = findNamed(panel, "addonUpdatesOpenSource", JButton.class);
                JButton local = findNamed(panel, "addonUpdatesRevealLocal", JButton.class);
                assertNotNull(check);
                assertNotNull(source);
                assertNotNull(local);
                assertEquals(0, table.getRowCount());
                assertFalse(source.isEnabled());
                assertFalse(local.isEnabled());
                check.doClick();
            });
            awaitBackgroundWork(executor);
            assertEquals(1, access.scanCount.get());

            EdtDispatcher.executeAndWait(() -> {
                JTable table = panel.resultsTable();
                JButton source = Objects.requireNonNull(
                        findNamed(panel, "addonUpdatesOpenSource", JButton.class));
                JButton local = Objects.requireNonNull(
                        findNamed(panel, "addonUpdatesRevealLocal", JButton.class));
                assertEquals(2, table.getRowCount());
                assertEquals("example.jar", table.getValueAt(0, 0));
                assertEquals("1.0.0", table.getValueAt(0, 1));
                assertEquals("1.1.0", table.getValueAt(0, 2));
                assertEquals("Modrinth", table.getValueAt(0, 3));
                table.setRowSelectionInterval(0, 0);
                assertTrue(source.isEnabled());
                assertTrue(local.isEnabled());
                source.doClick();
                local.doClick();
                table.setRowSelectionInterval(1, 1);
                assertFalse(source.isEnabled());
                assertTrue(local.isEnabled());
            });
            EdtDispatcher.executeAndWait(() -> { });
            assertEquals(URI.create("https://modrinth.com/mod/example"), interactions.openedSource.get());
            assertEquals(Path.of("test-addons", "example.jar").toAbsolutePath().normalize(),
                    interactions.revealedLocalFile.get());
            assertNull(interactions.failureDetail.get());
        } finally {
            @Nullable AddonUpdatesPanel panel = panelReference.get();
            if (panel != null) {
                panel.close();
            }
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    /// Creates a result containing both an actual update and an all-source failure row.
    ///
    /// @return immutable deterministic scan result
    private static AddonUpdateScanResult result() {
        return new AddonUpdateScanResult(
                2,
                List.of(new AddonUpdateItem(
                        "example.jar",
                        Path.of("test-addons", "example.jar"),
                        "1.0.0",
                        "1.1.0",
                        RemoteAddon.Source.MODRINTH,
                        URI.create("https://modrinth.com/mod/example"))),
                List.of(new AddonUpdateCheckFailure(
                        "offline.zip",
                        Path.of("test-addons", "offline.zip"),
                        "MODRINTH: unavailable")));
    }

    /// Waits for one FIFO executor barrier and any EDT callbacks queued before that barrier.
    ///
    /// @param executor panel background executor
    /// @throws Exception when the barrier cannot complete
    private static void awaitBackgroundWork(ExecutorService executor) throws Exception {
        executor.submit(() -> { }).get(5, TimeUnit.SECONDS);
        EdtDispatcher.executeAndWait(() -> { });
    }

    /// Finds a descendant with one deterministic component name and type.
    ///
    /// @param root component tree root
    /// @param name expected component name
    /// @param type required component type
    /// @param <T> component type
    /// @return matching component, or `null` when absent
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

    /// Test-only scan boundary counting explicit check invocations.
    @NotNullByDefault
    private static final class RecordingScanAccess implements AddonUpdateScanAccess {
        /// Immutable result returned by each explicit scan.
        private final AddonUpdateScanResult result;

        /// Number of actual scan calls observed.
        private final AtomicInteger scanCount = new AtomicInteger();

        /// Creates a deterministic scan boundary.
        ///
        /// @param result immutable result returned by the boundary
        private RecordingScanAccess(AddonUpdateScanResult result) {
            this.result = Objects.requireNonNull(result, "result");
        }

        /// Records and returns one explicit scan.
        ///
        /// @return configured immutable scan result
        @Override
        public AddonUpdateScanResult scan() {
            scanCount.incrementAndGet();
            return result;
        }
    }

    /// Native-interaction substitute recording page commands without opening desktop applications.
    @NotNullByDefault
    private static final class RecordingInteractions implements AddonUpdatesInteractions {
        /// Last requested remote source page, or `null` before command use.
        private final AtomicReference<@Nullable URI> openedSource = new AtomicReference<>();

        /// Last requested local add-on path, or `null` before command use.
        private final AtomicReference<@Nullable Path> revealedLocalFile = new AtomicReference<>();

        /// Last dialog failure detail, or `null` after successful commands.
        private final AtomicReference<@Nullable String> failureDetail = new AtomicReference<>();

        /// Records one requested project page.
        ///
        /// @param sourcePage remote page to record
        /// @return already-completed successful desktop stage
        @Override
        public CompletionStage<@Nullable Void> openSourcePage(URI sourcePage) {
            openedSource.set(sourcePage);
            return CompletableFuture.completedFuture(null);
        }

        /// Records one requested local installed path.
        ///
        /// @param localFile local add-on path to record
        /// @return already-completed successful desktop stage
        @Override
        public CompletionStage<@Nullable Void> revealLocalFile(Path localFile) {
            revealedLocalFile.set(localFile);
            return CompletableFuture.completedFuture(null);
        }

        /// Records a failure that production code would show in a native dialog.
        ///
        /// @param owner unused dialog owner
        /// @param title unused dialog title
        /// @param detail recorded failure detail
        @Override
        public void showFailure(Component owner, String title, String detail) {
            failureDetail.set(detail);
        }
    }
}
