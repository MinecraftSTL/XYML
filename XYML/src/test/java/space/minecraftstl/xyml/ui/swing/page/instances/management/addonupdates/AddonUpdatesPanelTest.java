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
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import space.minecraftstl.xyml.addon.LocalAddonFile;
import space.minecraftstl.xyml.addon.LocalAddonManager;
import space.minecraftstl.xyml.addon.RemoteAddon;
import space.minecraftstl.xyml.download.DownloadProvider;
import space.minecraftstl.xyml.task.Task;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;
import space.minecraftstl.xyml.ui.swing.task.TaskProgressStrings;

import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JTable;
import java.awt.Component;
import java.awt.Container;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies opt-in scanning, result export, exact checked updates, and real navigation actions.
@NotNullByDefault
final class AddonUpdatesPanelTest {
    /// Does not discover local files or contact a source until the user presses Check updates.
    @Test
    void defersScanUntilExplicitCheckAndExposesSourceAndLocalActions() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        RecordingScanAccess access = new RecordingScanAccess(result());
        RecordingApplicationService applicationService = new RecordingApplicationService(Runnable::run);
        RecordingInteractions interactions = new RecordingInteractions();
        AtomicReference<@Nullable AddonUpdatesPanel> panelReference = new AtomicReference<>();
        try {
            EdtDispatcher.executeAndWait(() -> panelReference.set(new AddonUpdatesPanel(
                    access,
                    applicationService,
                    executor,
                    AddonUpdatesStrings.english(),
                    interactions,
                    TaskProgressStrings.english(),
                    null,
                    Duration.ZERO)));
            AddonUpdatesPanel panel = Objects.requireNonNull(panelReference.get());
            assertEquals(0, access.scanCount.get());

            EdtDispatcher.executeAndWait(() -> {
                JTable table = panel.resultsTable();
                JButton check = findNamed(panel, "addonUpdatesCheck", JButton.class);
                JButton export = findNamed(panel, "addonUpdatesExport", JButton.class);
                JButton source = findNamed(panel, "addonUpdatesOpenSource", JButton.class);
                JButton local = findNamed(panel, "addonUpdatesRevealLocal", JButton.class);
                assertNotNull(check);
                assertNotNull(export);
                assertNotNull(source);
                assertNotNull(local);
                assertEquals(0, table.getRowCount());
                assertEquals("Choose", table.getColumnName(0));
                assertEquals("View source page", source.getAccessibleContext().getAccessibleName());
                assertEquals("Show local file", local.getAccessibleContext().getAccessibleName());
                assertFalse(source.isEnabled());
                assertFalse(local.isEnabled());
                assertFalse(export.isEnabled());
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
                JButton export = Objects.requireNonNull(
                        findNamed(panel, "addonUpdatesExport", JButton.class));
                assertEquals(2, table.getRowCount());
                assertEquals(Boolean.TRUE, table.getValueAt(0, 0));
                assertEquals("example.jar", table.getValueAt(0, 1));
                assertEquals("1.0.0", table.getValueAt(0, 2));
                assertEquals("1.1.0", table.getValueAt(0, 3));
                assertEquals("Modrinth", table.getValueAt(0, 4));
                assertTrue(export.isEnabled());
                export.doClick();
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
            assertTrue(Objects.requireNonNull(interactions.suggestedExportName.get())
                    .matches("xyml-addon-update-list-\\d{4}-\\d{2}-\\d{2}T\\d{2}-\\d{2}-\\d{2}\\.csv"));
            assertEquals(interactions.exportDestination, interactions.exportedDestination.get());
            assertEquals(List.of(new AddonUpdateExportRow(
                    "example.jar",
                    "1.0.0",
                    "1.1.0",
                    "Modrinth")), interactions.exportedRows.get());
            assertNull(interactions.failureDetail.get());
            assertEquals(0, applicationService.applyCalls.get());
        } finally {
            @Nullable AddonUpdatesPanel panel = panelReference.get();
            if (panel != null) {
                panel.close();
            }
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    /// Applies only checked exact updates, prevents a second task, and rescans after aggregate completion.
    @Test
    void appliesCheckedUpdatesAsOneSingleFlightTaskAndRescans() throws Exception {
        ExecutorService scanExecutor = Executors.newSingleThreadExecutor();
        QueuedExecutor updateExecutor = new QueuedExecutor();
        AddonUpdateScanResult scanResult = selectableResult();
        RecordingScanAccess access = new RecordingScanAccess(scanResult);
        RecordingApplicationService applicationService = new RecordingApplicationService(updateExecutor);
        RecordingInteractions interactions = new RecordingInteractions();
        AtomicReference<@Nullable AddonUpdatesPanel> panelReference = new AtomicReference<>();
        try {
            EdtDispatcher.executeAndWait(() -> panelReference.set(new AddonUpdatesPanel(
                    access,
                    applicationService,
                    scanExecutor,
                    AddonUpdatesStrings.english(),
                    interactions,
                    TaskProgressStrings.english(),
                    null,
                    Duration.ZERO)));
            AddonUpdatesPanel panel = Objects.requireNonNull(panelReference.get(), "panel");

            EdtDispatcher.executeAndWait(() -> findRequiredButton(panel, "addonUpdatesCheck").doClick());
            awaitBackgroundWork(scanExecutor);
            EdtDispatcher.executeAndWait(() -> {
                JTable table = panel.resultsTable();
                javax.swing.JCheckBox selectAll = findNamed(
                        panel,
                        "addonUpdatesSelectAll",
                        javax.swing.JCheckBox.class);
                JButton apply = findRequiredButton(panel, "addonUpdatesApply");
                assertNotNull(selectAll);
                assertEquals(Boolean.TRUE, table.getValueAt(0, 0));
                assertEquals(Boolean.FALSE, table.getValueAt(1, 0));
                assertFalse(selectAll.isSelected());

                selectAll.doClick();
                assertEquals(Boolean.TRUE, table.getValueAt(1, 0));
                table.setValueAt(Boolean.FALSE, 1, 0);
                assertFalse(selectAll.isSelected());
                assertTrue(apply.isEnabled());
                apply.doClick();

                assertEquals(1, applicationService.applyCalls.get());
                assertEquals(1, applicationService.appliedUpdates.size());
                assertSame(scanResult.updates().get(0).update(),
                        applicationService.appliedUpdates.get(0).update());
                assertFalse(apply.isEnabled());
                assertFalse(table.isEnabled());
                apply.doClick();
                assertEquals(1, applicationService.applyCalls.get());
            });

            waitFor(updateExecutor::hasPendingWork);
            updateExecutor.runAll();
            waitFor(() -> access.scanCount.get() == 2);
            awaitBackgroundWork(scanExecutor);

            assertEquals(2, access.scanCount.get());
            assertEquals(1, applicationService.applyCalls.get());
            assertNull(interactions.failureDetail.get());
        } finally {
            @Nullable AddonUpdatesPanel panel = panelReference.get();
            if (panel != null) {
                panel.close();
            }
            scanExecutor.shutdownNow();
            assertTrue(scanExecutor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    /// Removes applied rows when the authoritative automatic rescan fails.
    @Test
    void clearsAppliedRowsWhenAutomaticRescanFails() throws Exception {
        ExecutorService scanExecutor = Executors.newSingleThreadExecutor();
        QueuedExecutor updateExecutor = new QueuedExecutor();
        FailingRescanAccess access = new FailingRescanAccess(selectableResult());
        RecordingApplicationService applicationService = new RecordingApplicationService(updateExecutor);
        AddonUpdatesPanel panel = createPanel(access, applicationService, scanExecutor, new RecordingInteractions());
        try {
            EdtDispatcher.executeAndWait(() -> findRequiredButton(panel, "addonUpdatesCheck").doClick());
            awaitBackgroundWork(scanExecutor);
            EdtDispatcher.executeAndWait(() -> {
                assertEquals(2, panel.resultsTable().getRowCount());
                findRequiredButton(panel, "addonUpdatesApply").doClick();
            });

            waitFor(updateExecutor::hasPendingWork);
            updateExecutor.runAll();
            waitFor(() -> access.scanCount.get() == 2);
            awaitBackgroundWork(scanExecutor);

            EdtDispatcher.executeAndWait(() -> {
                JButton apply = findRequiredButton(panel, "addonUpdatesApply");
                assertEquals(0, panel.resultsTable().getRowCount());
                assertFalse(apply.isEnabled());
                apply.doClick();
            });
            assertEquals(1, applicationService.applyCalls.get());
        } finally {
            panel.close();
            scanExecutor.shutdownNow();
            assertTrue(scanExecutor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    /// Invalidates the checked snapshot after a terminal update-task failure.
    @Test
    void clearsRowsAfterTerminalApplicationFailure() throws Exception {
        ExecutorService scanExecutor = Executors.newSingleThreadExecutor();
        QueuedExecutor updateExecutor = new QueuedExecutor();
        RecordingScanAccess access = new RecordingScanAccess(selectableResult());
        FailingApplicationService applicationService = new FailingApplicationService(updateExecutor);
        RecordingInteractions interactions = new RecordingInteractions();
        AddonUpdatesPanel panel = createPanel(access, applicationService, scanExecutor, interactions);
        try {
            EdtDispatcher.executeAndWait(() -> findRequiredButton(panel, "addonUpdatesCheck").doClick());
            awaitBackgroundWork(scanExecutor);
            EdtDispatcher.executeAndWait(() -> findRequiredButton(panel, "addonUpdatesApply").doClick());

            waitFor(updateExecutor::hasPendingWork);
            updateExecutor.runAll();
            waitFor(() -> interactions.failureDetail.get() != null);

            EdtDispatcher.executeAndWait(() -> {
                assertEquals(0, panel.resultsTable().getRowCount());
                assertFalse(findRequiredButton(panel, "addonUpdatesApply").isEnabled());
            });
            assertEquals(1, applicationService.applyCalls.get());
            assertEquals(1, access.scanCount.get());
        } finally {
            panel.close();
            scanExecutor.shutdownNow();
            assertTrue(scanExecutor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    /// Closing a panel cancels the active task and suppresses its eventual automatic rescan callback.
    @Test
    void closeSuppressesLateUpdateCompletionAndRescan() throws Exception {
        ExecutorService scanExecutor = Executors.newSingleThreadExecutor();
        QueuedExecutor updateExecutor = new QueuedExecutor();
        RecordingScanAccess access = new RecordingScanAccess(selectableResult());
        RecordingApplicationService applicationService = new RecordingApplicationService(updateExecutor);
        AddonUpdatesPanel panel = createPanel(access, applicationService, scanExecutor, new RecordingInteractions());
        try {
            EdtDispatcher.executeAndWait(() -> findRequiredButton(panel, "addonUpdatesCheck").doClick());
            awaitBackgroundWork(scanExecutor);
            EdtDispatcher.executeAndWait(() -> findRequiredButton(panel, "addonUpdatesApply").doClick());
            waitFor(updateExecutor::hasPendingWork);

            panel.close();
            updateExecutor.runAll();
            EdtDispatcher.executeAndWait(() -> { });

            assertEquals(1, access.scanCount.get());
            assertEquals(1, applicationService.applyCalls.get());
        } finally {
            panel.close();
            scanExecutor.shutdownNow();
            assertTrue(scanExecutor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    /// Creates a result containing both an actual update and an all-source failure row.
    ///
    /// @return immutable deterministic scan result
    private static AddonUpdateScanResult result() {
        return new AddonUpdateScanResult(
                2,
                List.of(updateItem(
                        "test-addons/example.jar",
                        false,
                        URI.create("https://modrinth.com/mod/example"))),
                List.of(new AddonUpdateCheckFailure(
                        "offline.zip",
                        Path.of("test-addons", "offline.zip"),
                        "MODRINTH: unavailable")));
    }

    /// Creates enabled and disabled update rows for checkbox-default and exact-selection tests.
    ///
    /// @return immutable result with two actionable updates
    private static AddonUpdateScanResult selectableResult() {
        return new AddonUpdateScanResult(
                2,
                List.of(
                        updateItem("test-addons/enabled.jar", false, null),
                        updateItem("test-addons/disabled.zip", true, null)),
                List.of());
    }

    /// Creates one exact Core update item without file or network I/O.
    ///
    /// @param path local add-on path before an optional disabled suffix
    /// @param disabled whether the local file starts disabled
    /// @param sourcePage optional remote project page
    /// @return immutable update item retaining its exact Core object
    private static AddonUpdateItem updateItem(
            String path,
            boolean disabled,
            @Nullable URI sourcePage) {
        Path localPath = Path.of(path).toAbsolutePath().normalize();
        if (disabled && !localPath.toString().endsWith(LocalAddonManager.DISABLED_EXTENSION)) {
            localPath = localPath.resolveSibling(
                    localPath.getFileName() + LocalAddonManager.DISABLED_EXTENSION);
        }
        TestLocalAddonFile localAddonFile = new TestLocalAddonFile(localPath);
        RemoteAddon.IVersion source = () -> RemoteAddon.Source.MODRINTH;
        LocalAddonFile.AddonUpdate update = new LocalAddonFile.AddonUpdate(
                RemoteAddon.Source.MODRINTH,
                RemoteAddon.Type.MOD,
                localAddonFile,
                version(source, "Current", "1.0.0", "current.jar"),
                version(source, "Target", "1.1.0", "target.jar"),
                true);
        return AddonUpdateItem.from(update, sourcePage);
    }

    /// Creates one minimal remote version for panel-only tests.
    ///
    /// @param source exact remote source descriptor
    /// @param name progress display name
    /// @param version visible version text
    /// @param fileName remote artifact file name
    /// @return exact remote version
    private static RemoteAddon.Version version(
            RemoteAddon.IVersion source,
            String name,
            String version,
            String fileName) {
        return new RemoteAddon.Version(
                source,
                version,
                "example-project",
                name,
                version,
                Instant.EPOCH,
                RemoteAddon.VersionType.Release,
                new RemoteAddon.File(
                        Map.of(),
                        "https://example.invalid/" + fileName,
                        fileName),
                List.of(),
                List.of("1.21.1"),
                List.of());
    }

    /// Creates one panel with deterministic task presentation and no native desktop side effects.
    ///
    /// @param access scan boundary
    /// @param applicationService update application boundary
    /// @param scanExecutor background scan executor
    /// @param interactions native interaction boundary
    /// @return constructed inactive panel
    private static AddonUpdatesPanel createPanel(
            AddonUpdateScanAccess access,
            AddonUpdateApplicationService applicationService,
            Executor scanExecutor,
            AddonUpdatesInteractions interactions) {
        AtomicReference<@Nullable AddonUpdatesPanel> panelReference = new AtomicReference<>();
        EdtDispatcher.executeAndWait(() -> panelReference.set(new AddonUpdatesPanel(
                access,
                applicationService,
                scanExecutor,
                AddonUpdatesStrings.english(),
                interactions,
                TaskProgressStrings.english(),
                null,
                Duration.ZERO)));
        return Objects.requireNonNull(panelReference.get(), "panel");
    }

    /// Waits for one FIFO executor barrier and any EDT callbacks queued before that barrier.
    ///
    /// @param executor panel background executor
    /// @throws Exception when the barrier cannot complete
    private static void awaitBackgroundWork(ExecutorService executor) throws Exception {
        executor.submit(() -> { }).get(5, TimeUnit.SECONDS);
        EdtDispatcher.executeAndWait(() -> { });
    }

    /// Waits for one bounded asynchronous condition used by deferred update tasks.
    ///
    /// @param condition expected eventual state
    private static void waitFor(java.util.function.BooleanSupplier condition) {
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertTrue(condition.getAsBoolean(), "Timed out waiting for asynchronous add-on update state");
    }

    /// Finds one required deterministic button and fails immediately when the component is absent.
    ///
    /// @param root component tree root
    /// @param name deterministic component name
    /// @return required button
    private static JButton findRequiredButton(Container root, String name) {
        return Objects.requireNonNull(findNamed(root, name, JButton.class), name);
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

    /// Returns one initial result and fails every later authoritative rescan.
    @NotNullByDefault
    private static final class FailingRescanAccess implements AddonUpdateScanAccess {
        /// Immutable initial result returned before the simulated rescan failure.
        private final AddonUpdateScanResult initialResult;

        /// Number of actual scan calls observed.
        private final AtomicInteger scanCount = new AtomicInteger();

        /// Creates a boundary with one successful initial scan.
        ///
        /// @param initialResult immutable first scan result
        private FailingRescanAccess(AddonUpdateScanResult initialResult) {
            this.initialResult = Objects.requireNonNull(initialResult, "initialResult");
        }

        /// Returns the initial result once, then simulates an unavailable authoritative rescan.
        ///
        /// @return initial result for the first call
        /// @throws IOException after the first call
        @Override
        public AddonUpdateScanResult scan() throws IOException {
            if (scanCount.incrementAndGet() == 1) {
                return initialResult;
            }
            throw new IOException("simulated automatic rescan failure");
        }
    }

    /// Records exact checked update objects and returns a deferred aggregate success task.
    @NotNullByDefault
    private static final class RecordingApplicationService implements AddonUpdateApplicationService {
        /// Executor used by the stopped fake task after the panel starts it.
        private final Executor taskExecutor;

        /// Number of exact application task requests.
        private final AtomicInteger applyCalls = new AtomicInteger();

        /// Last immutable exact update selection supplied by the panel.
        private @Unmodifiable List<AddonUpdateItem> appliedUpdates = List.of();

        /// Creates one deterministic application boundary.
        ///
        /// @param taskExecutor deferred task-body executor
        private RecordingApplicationService(Executor taskExecutor) {
            this.taskExecutor = Objects.requireNonNull(taskExecutor, "taskExecutor");
        }

        /// Captures exact update items and returns a stopped aggregate success task.
        ///
        /// @param updates exact checked update selection
        /// @return stopped deterministic task
        @Override
        public Task<AddonUpdateApplicationResult> applyUpdates(
                Collection<AddonUpdateItem> updates) {
            @Unmodifiable List<AddonUpdateItem> captured = List.copyOf(
                    Objects.requireNonNull(updates, "updates"));
            applyCalls.incrementAndGet();
            appliedUpdates = captured;
            return Task.supplyAsync(
                    taskExecutor,
                    () -> new AddonUpdateApplicationResult(captured, List.of()));
        }
    }

    /// Returns one deferred aggregate task that always fails after the panel starts it.
    @NotNullByDefault
    private static final class FailingApplicationService implements AddonUpdateApplicationService {
        /// Executor used by the stopped fake task.
        private final Executor taskExecutor;

        /// Number of exact application task requests.
        private final AtomicInteger applyCalls = new AtomicInteger();

        /// Creates a deterministic failing boundary.
        ///
        /// @param taskExecutor deferred task-body executor
        private FailingApplicationService(Executor taskExecutor) {
            this.taskExecutor = Objects.requireNonNull(taskExecutor, "taskExecutor");
        }

        /// Returns a stopped task that fails without producing a result.
        ///
        /// @param updates exact checked update selection
        /// @return stopped deterministic failure task
        @Override
        public Task<AddonUpdateApplicationResult> applyUpdates(
                Collection<AddonUpdateItem> updates) {
            Objects.requireNonNull(updates, "updates");
            applyCalls.incrementAndGet();
            return Task.supplyAsync(taskExecutor, () -> {
                throw new IOException("simulated aggregate update failure");
            });
        }
    }

    /// Queues task bodies until the test explicitly allows their execution.
    @NotNullByDefault
    private static final class QueuedExecutor implements Executor {
        /// Pending task bodies in submission order.
        private final ArrayDeque<Runnable> pending = new ArrayDeque<>();

        /// Captures one task body without running it.
        ///
        /// @param command submitted task body
        @Override
        public synchronized void execute(Runnable command) {
            pending.addLast(Objects.requireNonNull(command, "command"));
        }

        /// Returns whether at least one task body is pending.
        ///
        /// @return whether work is pending
        private synchronized boolean hasPendingWork() {
            return !pending.isEmpty();
        }

        /// Runs every queued task body in original submission order.
        private void runAll() {
            while (true) {
                final Runnable command;
                synchronized (this) {
                    command = pending.pollFirst();
                }
                if (command == null) {
                    return;
                }
                command.run();
            }
        }
    }

    /// Minimal local add-on used only to construct exact scan results without filesystem I/O.
    @NotNullByDefault
    private static final class TestLocalAddonFile extends LocalAddonFile {
        /// Stable exact local path.
        private final Path file;

        /// Creates one immutable test local add-on.
        ///
        /// @param file exact local path
        private TestLocalAddonFile(Path file) {
            this.file = Objects.requireNonNull(file, "file");
        }

        /// Returns the stable local path.
        ///
        /// @return local path
        @Override
        public Path getFile() {
            return file;
        }

        /// Returns the stable display name without state suffixes.
        ///
        /// @return local display name
        @Override
        public String getFileName() {
            return LocalAddonManager.getLocalAddonName(file);
        }

        /// Performs no state mutation in panel-only tests.
        @Override
        public void markDisabled() {
        }

        /// Performs no state mutation in panel-only tests.
        ///
        /// @param old ignored old-file state
        @Override
        public void setOld(boolean old) {
        }

        /// Retains no simulated old file.
        ///
        /// @return always false
        @Override
        public boolean keepOldFiles() {
            return false;
        }

        /// Performs no deletion in panel-only tests.
        @Override
        public void delete() {
        }

        /// Returns no update because the test constructs exact update records directly.
        ///
        /// @param downloadProvider unused provider
        /// @param gameVersion unused game version
        /// @param source unused source
        /// @return always `null`
        @Override
        public @Nullable AddonUpdate checkUpdates(
                DownloadProvider downloadProvider,
                String gameVersion,
                RemoteAddon.Source source) {
            return null;
        }
    }

    /// Native-interaction substitute recording page commands without opening desktop applications.
    @NotNullByDefault
    private static final class RecordingInteractions implements AddonUpdatesInteractions {
        /// Deterministic destination returned by the fake save chooser.
        private final Path exportDestination = Path.of("test-exports", "updates.csv")
                .toAbsolutePath()
                .normalize();

        /// Last suggested export file name, or `null` before chooser use.
        private final AtomicReference<@Nullable String> suggestedExportName = new AtomicReference<>();

        /// Last requested export destination, or `null` before export use.
        private final AtomicReference<@Nullable Path> exportedDestination = new AtomicReference<>();

        /// Last immutable exported row snapshot.
        private final AtomicReference<@Unmodifiable List<AddonUpdateExportRow>> exportedRows =
                new AtomicReference<>(List.of());

        /// Last requested remote source page, or `null` before command use.
        private final AtomicReference<@Nullable URI> openedSource = new AtomicReference<>();

        /// Last requested local add-on path, or `null` before command use.
        private final AtomicReference<@Nullable Path> revealedLocalFile = new AtomicReference<>();

        /// Last dialog failure detail, or `null` after successful commands.
        private final AtomicReference<@Nullable String> failureDetail = new AtomicReference<>();

        /// Records the suggested name and chooses one deterministic destination.
        ///
        /// @param owner unused dialog owner
        /// @param suggestedName suggested collision-resistant file name
        /// @return deterministic export destination
        @Override
        public Path chooseExportFile(Component owner, String suggestedName) {
            suggestedExportName.set(suggestedName);
            return exportDestination;
        }

        /// Records one immutable update export without file-system work.
        ///
        /// @param destination exact export destination
        /// @param rows immutable actionable rows
        /// @return already-completed successful export stage
        @Override
        public CompletionStage<@Nullable Void> exportUpdateList(
                Path destination,
                @Unmodifiable List<AddonUpdateExportRow> rows) {
            exportedDestination.set(destination);
            exportedRows.set(List.copyOf(rows));
            return CompletableFuture.completedFuture(null);
        }

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
