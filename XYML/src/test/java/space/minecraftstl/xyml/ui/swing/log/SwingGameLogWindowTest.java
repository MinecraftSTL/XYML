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
import space.minecraftstl.xyml.game.Log;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;
import space.minecraftstl.xyml.util.CircularArrayList;
import space.minecraftstl.xyml.util.Log4jLevel;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies cross-thread input copying, bounded post-close retention, and resource shutdown without opening a frame.
@NotNullByDefault
class SwingGameLogWindowTest {
    /// Copies a mutable producer batch before the producer reuses it and enforces the configured capacity on the EDT.
    @Test
    void logLinesCopiesProducerBatchBeforeDispatch() {
        RecordingActions actions = new RecordingActions();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        SwingGameLogWindow window = new SwingGameLogWindow(
                new BoundedGameLogBuffer(new CircularArrayList<>(), 2),
                actions,
                executor,
                ignored -> {
                });
        List<Log> mutableBatch = new ArrayList<>(List.of(
                log("one", Log4jLevel.INFO),
                log("two", Log4jLevel.WARN),
                log("three", Log4jLevel.ERROR)));

        window.logLines(mutableBatch);
        mutableBatch.clear();
        EdtDispatcher.executeAndWait(() -> assertEquals(2, window.retainedLogCount()));

        window.close();
        EdtDispatcher.executeAndWait(() -> {
        });
    }

    /// Releases the export executor on close while retaining later process logs for crash diagnostics.
    @Test
    void closeReleasesResourcesButContinuesBoundedHistory() {
        RecordingActions actions = new RecordingActions();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        SwingGameLogWindow window = new SwingGameLogWindow(
                new BoundedGameLogBuffer(new CircularArrayList<>(), 2),
                actions,
                executor,
                ignored -> {
                });

        window.close();
        window.logLine(log("after-close", Log4jLevel.INFO));
        EdtDispatcher.executeAndWait(() -> assertEquals(1, window.retainedLogCount()));

        assertTrue(window.isClosed());
        assertTrue(executor.isShutdown());
    }

    /// Registers one weak-window process-exit callback through the injected action boundary.
    @Test
    void processExitRegistrationDoesNotRequireAVisibleFrame() {
        RecordingActions actions = new RecordingActions();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        SwingGameLogWindow window = new SwingGameLogWindow(
                new BoundedGameLogBuffer(new CircularArrayList<>(), 2),
                actions,
                executor,
                ignored -> {
                });

        assertNotNull(actions.exitListener);
        Objects.requireNonNull(actions.exitListener, "registered exit listener").run();
        EdtDispatcher.executeAndWait(() -> {
        });

        window.close();
        EdtDispatcher.executeAndWait(() -> {
        });
    }

    /// Creates one explicitly leveled test entry.
    ///
    /// @param text entry text
    /// @param level entry severity
    /// @return test log entry
    private static Log log(String text, Log4jLevel level) {
        return new Log(text, level);
    }

    /// Records the process-exit callback while keeping all other side effects inert.
    @NotNullByDefault
    private static final class RecordingActions implements GameLogWindowActions {
        /// Registered process-exit callback.
        private @Nullable Runnable exitListener;

        /// Records the process-exit callback.
        ///
        /// @param listener callback invoked after raw process termination
        @Override
        public void registerProcessExit(Runnable listener) {
            exitListener = listener;
        }

        /// Reports a running fake process.
        ///
        /// @return true for deterministic initialization
        @Override
        public boolean isProcessRunning() {
            return true;
        }

        /// Reports no stack-dump support.
        ///
        /// @return false
        @Override
        public boolean canExportDump() {
            return false;
        }

        /// Performs no termination side effect.
        @Override
        public void terminateGame() {
        }

        /// Returns an inert export destination.
        ///
        /// @param lines immutable text snapshot
        /// @return inert export destination
        @Override
        public Path exportLogs(@Unmodifiable List<String> lines) {
            return Path.of("logs.txt");
        }

        /// Returns an inert dump destination.
        ///
        /// @return inert dump destination
        @Override
        public Path exportDump() {
            return Path.of("dump.txt");
        }

        /// Performs no reveal side effect.
        ///
        /// @param file exported file
        @Override
        public void revealFile(Path file) {
        }

        /// Performs no clipboard side effect.
        ///
        /// @param text selected log text
        @Override
        public void copyText(String text) {
        }
    }
}
