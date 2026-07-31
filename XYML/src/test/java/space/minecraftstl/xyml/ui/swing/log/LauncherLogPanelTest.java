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
package space.minecraftstl.xyml.ui.swing.log;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;

import java.awt.Component;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests the settings action row's explicit export, reveal, and lifecycle behavior without native dialogs.
@NotNullByDefault
public final class LauncherLogPanelTest {
    /// Isolated log and output tree for every panel fixture.
    @TempDir
    private Path temporaryDirectory;

    /// Visible actions delegate only after clicks and deliver the successful result through the interaction boundary.
    @Test
    public void exportsAndRevealsLauncherLogsOnExplicitUserActions() throws Exception {
        Path logDirectory = Files.createDirectories(temporaryDirectory.resolve("logs"));
        Path outputDirectory = Files.createDirectories(temporaryDirectory.resolve("exports"));
        Path current = Files.writeString(logDirectory.resolve("current.log"), "current-file");
        RecordingAccess access = new RecordingAccess(current, outputDirectory);
        LauncherLogExportService service = new LauncherLogExportService(
                access,
                Runnable::run,
                () -> LocalDateTime.of(2026, 7, 25, 12, 0, 0));
        RecordingInteractions interactions = new RecordingInteractions();
        LauncherLogPanel panel = onEventDispatchThread(() -> new LauncherLogPanel(service, interactions));

        onEventDispatchThread(() -> {
            assertAll(
                    () -> assertTrue(panel.revealButton().isEnabled()),
                    () -> assertTrue(panel.exportButton().isEnabled()),
                    () -> assertFalse(panel.isExporting()));
            panel.revealButton().doClick();
            panel.exportButton().doClick();
            assertAll(
                    () -> assertFalse(panel.isExporting()),
                    () -> assertEquals(1, interactions.revealDirectoryCalls.get()),
                    () -> assertEquals(1, interactions.successCalls.get()),
                    () -> assertEquals(1, interactions.revealExportCalls.get()),
                    () -> assertEquals(0, interactions.failureCalls.get()));
            panel.close();
        });

        Path export = Objects.requireNonNull(interactions.successfulExport.get(), "successful export");
        assertAll(
                () -> assertTrue(Files.isRegularFile(export)),
                () -> assertEquals("current-stream", Files.readString(export)));
    }

    /// Closing the panel disables both commands and cancels a queued service task before its worker runs.
    @Test
    public void closeCancelsQueuedExportAndIgnoresItsCompletion() throws Exception {
        Path logDirectory = Files.createDirectories(temporaryDirectory.resolve("logs"));
        Path outputDirectory = Files.createDirectories(temporaryDirectory.resolve("exports"));
        Path current = Files.writeString(logDirectory.resolve("current.log"), "current-file");
        AtomicReference<@Nullable Runnable> queuedWorker = new AtomicReference<>();
        RecordingAccess access = new RecordingAccess(current, outputDirectory);
        LauncherLogExportService service = new LauncherLogExportService(
                access,
                queuedWorker::set,
                () -> LocalDateTime.of(2026, 7, 25, 12, 1, 0));
        RecordingInteractions interactions = new RecordingInteractions();
        LauncherLogPanel panel = onEventDispatchThread(() -> new LauncherLogPanel(service, interactions));

        onEventDispatchThread(() -> {
            panel.exportButton().doClick();
            assertTrue(panel.isExporting());
            panel.close();
            assertAll(
                    () -> assertFalse(panel.exportButton().isEnabled()),
                    () -> assertFalse(panel.revealButton().isEnabled()));
        });
        Runnable worker = Objects.requireNonNull(queuedWorker.get(), "queued export worker");
        worker.run();
        EdtDispatcher.executeAndWait(() -> { });

        assertAll(
                () -> assertEquals(0, interactions.successCalls.get()),
                () -> assertEquals(0, interactions.failureCalls.get()),
                () -> assertFalse(Files.exists(outputDirectory.resolve("xyml-exported-logs-2026-07-25T12-01-00.log"))));
    }

    /// Runs a value-producing operation synchronously on the event dispatch thread.
    ///
    /// @param operation EDT operation
    /// @param <T> non-null result type
    /// @return EDT result
    private static <T extends Object> T onEventDispatchThread(Supplier<T> operation) {
        AtomicReference<@Nullable T> result = new AtomicReference<>();
        EdtDispatcher.executeAndWait(() -> result.set(operation.get()));
        return Objects.requireNonNull(result.get(), "EDT operation did not return a result");
    }

    /// Runs one operation synchronously on the event dispatch thread.
    ///
    /// @param operation EDT operation
    private static void onEventDispatchThread(Runnable operation) {
        EdtDispatcher.executeAndWait(operation);
    }

    /// Minimal logger adapter that records writes but performs no historical file I/O.
    @NotNullByDefault
    private static final class RecordingAccess implements LauncherLogExportAccess {
        /// Active log file used to enable the reveal command.
        private final Path currentLogFile;

        /// Directory receiving test export artifacts.
        private final Path outputDirectory;

        /// Creates one logger access fixture.
        ///
        /// @param currentLogFile active logger path
        /// @param outputDirectory output directory
        private RecordingAccess(Path currentLogFile, Path outputDirectory) {
            this.currentLogFile = currentLogFile;
            this.outputDirectory = outputDirectory;
        }

        /// Returns the configured active logger path.
        ///
        /// @return active logger file
        @Override
        public @Nullable Path currentLogFile() {
            return currentLogFile;
        }

        /// Returns no historical files so the panel test produces a plain text export.
        ///
        /// @param maximumCount requested history count
        /// @return empty immutable history
        @Override
        public @Unmodifiable List<Path> findRecentLogFiles(int maximumCount) {
            return List.of();
        }

        /// Writes deterministic current launcher log text.
        ///
        /// @param output output destination
        /// @throws IOException when writing fails
        @Override
        public void writeCurrentLogs(OutputStream output) throws IOException {
            output.write("current-stream".getBytes(StandardCharsets.UTF_8));
        }

        /// Returns the configured test output directory.
        ///
        /// @return output directory
        @Override
        public Path outputDirectory() {
            return outputDirectory;
        }
    }

    /// Recording native interaction adapter that never invokes desktop integrations or dialogs.
    @NotNullByDefault
    private static final class RecordingInteractions implements LauncherLogPanelInteractions {
        /// Number of live-log directory reveal requests.
        private final AtomicInteger revealDirectoryCalls = new AtomicInteger();

        /// Number of completed export reveal requests.
        private final AtomicInteger revealExportCalls = new AtomicInteger();

        /// Number of success dialog requests.
        private final AtomicInteger successCalls = new AtomicInteger();

        /// Number of failure dialog requests.
        private final AtomicInteger failureCalls = new AtomicInteger();

        /// Last successful export delivered through the interaction boundary.
        private final AtomicReference<@Nullable Path> successfulExport = new AtomicReference<>();

        /// Records a live-log directory reveal request.
        ///
        /// @param owner native owner component
        /// @param directory active log directory
        @Override
        public void revealLogDirectory(Component owner, Path directory) {
            revealDirectoryCalls.incrementAndGet();
        }

        /// Records an export file reveal request.
        ///
        /// @param owner native owner component
        /// @param exportFile completed export file
        @Override
        public void revealExport(Component owner, Path exportFile) {
            revealExportCalls.incrementAndGet();
        }

        /// Records the export result presented to the user.
        ///
        /// @param owner native owner component
        /// @param exportFile completed export file
        @Override
        public void showExportSuccess(Component owner, Path exportFile) {
            successCalls.incrementAndGet();
            successfulExport.set(exportFile);
        }

        /// Records an unexpected export failure.
        ///
        /// @param owner native owner component
        /// @param failure failure presented to the user
        @Override
        public void showExportFailure(Component owner, Throwable failure) {
            failureCalls.incrementAndGet();
        }
    }
}
