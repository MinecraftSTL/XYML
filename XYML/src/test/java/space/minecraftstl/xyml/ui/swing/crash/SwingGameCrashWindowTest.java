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
package space.minecraftstl.xyml.ui.swing.crash;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import space.minecraftstl.xyml.game.Log;
import space.minecraftstl.xyml.launch.ProcessListener;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;

import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static space.minecraftstl.xyml.util.i18n.I18n.i18n;

/// Verifies headless composition, EDT result application, exit titles, and late-result cancellation.
@NotNullByDefault
class SwingGameCrashWindowTest {
    /// Builds testable content without a native frame and applies a completed diagnosis on the EDT.
    @Test
    void composesAndAppliesAnalysisWithoutNativeFrame() {
        ControlledAnalysisService service = new ControlledAnalysisService();
        ExecutorService worker = Executors.newSingleThreadExecutor();
        SwingGameCrashWindow window = window(service, worker);

        window.show();
        EdtDispatcher.executeAndWait(() -> assertTrue(window.hasContentOnEdt()));
        service.result.complete(new GameCrashAnalysis(List.of(), Set.of()));
        EdtDispatcher.executeAndWait(() -> assertEquals(
                i18n("game.crash.reason.unknown"),
                window.displayedReasonOnEdt()));

        window.close();
        EdtDispatcher.executeAndWait(() -> {
        });
    }

    /// Ignores an analysis that completes after close and shuts down the window-owned executor.
    @Test
    void closeSuppressesLateAnalysisUpdate() {
        ControlledAnalysisService service = new ControlledAnalysisService();
        ExecutorService worker = Executors.newSingleThreadExecutor();
        SwingGameCrashWindow window = window(service, worker);

        window.show();
        EdtDispatcher.executeAndWait(() -> assertEquals(
                i18n("game.crash.reason.analyzing"),
                window.displayedReasonOnEdt()));
        window.close();
        service.result.complete(new GameCrashAnalysis(List.of(), Set.of("late.keyword")));
        EdtDispatcher.executeAndWait(() -> assertEquals(
                i18n("game.crash.reason.analyzing"),
                window.displayedReasonOnEdt()));

        assertTrue(window.isClosed());
        assertTrue(worker.isShutdown());
    }

    /// Maps every process-exit classification to a deliberate localized headline.
    @Test
    void mapsExitTypesToLocalizedTitles() {
        assertEquals(i18n("launch.failed.cannot_create_jvm"),
                SwingGameCrashWindow.titleFor(ProcessListener.ExitType.JVM_ERROR));
        assertEquals(i18n("launch.failed.exited_abnormally"),
                SwingGameCrashWindow.titleFor(ProcessListener.ExitType.APPLICATION_ERROR));
        assertEquals(i18n("launch.failed.sigkill"),
                SwingGameCrashWindow.titleFor(ProcessListener.ExitType.SIGKILL));
        assertEquals(i18n("game.crash.title"),
                SwingGameCrashWindow.titleFor(ProcessListener.ExitType.NORMAL));
        assertEquals(i18n("game.crash.title"),
                SwingGameCrashWindow.titleFor(ProcessListener.ExitType.INTERRUPTED));
    }

    /// Creates one native-frame-disabled window around controlled boundaries.
    ///
    /// @param service controlled analysis service
    /// @param worker window-owned executor
    /// @return test window
    private static SwingGameCrashWindow window(
            ControlledAnalysisService service,
            ExecutorService worker) {
        GameCrashWindowModel model = new GameCrashWindowModel(
                ProcessListener.ExitType.APPLICATION_ERROR,
                List.of(new GameCrashWindowModel.Detail("Instance", "Test")),
                List.of(new Log("captured")),
                Path.of("missing-latest.log"));
        return new SwingGameCrashWindow(
                model,
                service,
                new GameCrashReasonFormatter(),
                new RecordingActions(),
                worker,
                false);
    }

    /// Exposes a manually completed analysis future.
    @NotNullByDefault
    private static final class ControlledAnalysisService implements GameCrashAnalysisService {
        /// Future completed by each test after the window starts analysis.
        private final CompletableFuture<GameCrashAnalysis> result = new CompletableFuture<>();

        /// Returns the controlled future without touching its inputs.
        ///
        /// @param capturedLogs immutable process-output snapshot
        /// @param latestLog on-disk latest-log path
        /// @return manually completed diagnosis
        @Override
        public CompletionStage<GameCrashAnalysis> analyze(
                List<Log> capturedLogs,
                Path latestLog) {
            return result;
        }
    }

    /// Keeps export, log-window, and desktop effects inert for lifecycle tests.
    @NotNullByDefault
    private static final class RecordingActions implements GameCrashWindowActions {
        /// Returns an already completed inert export path.
        ///
        /// @return inert export stage
        @Override
        public CompletionStage<Path> exportCrashLogs() {
            return CompletableFuture.completedFuture(Path.of("crash.zip"));
        }

        /// Performs no file-manager side effect.
        ///
        /// @param file exported file
        @Override
        public void revealFile(Path file) {
        }

        /// Performs no game-log-window side effect.
        @Override
        public void showGameLogs() {
        }

        /// Performs no desktop browsing side effect.
        ///
        /// @param destination link destination
        @Override
        public void openLink(URI destination) {
        }
    }
}
