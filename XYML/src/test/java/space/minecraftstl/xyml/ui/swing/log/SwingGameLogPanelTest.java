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

import javax.swing.Action;
import java.awt.Component;
import java.awt.event.ActionEvent;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static space.minecraftstl.xyml.util.i18n.I18n.i18n;

/// Verifies native Swing log controls through deterministic process, clipboard, export, and notifier boundaries.
@NotNullByDefault
class SwingGameLogPanelTest {
    /// Filters rows, adjusts the capacity, pauses tail following, and clears retained history.
    @Test
    void controlsMutateBoundedModelWithoutOpeningAFrame() {
        EdtDispatcher.executeAndWait(() -> {
            BoundedGameLogBuffer buffer = new BoundedGameLogBuffer(new CircularArrayList<>(), 3);
            RecordingActions actions = new RecordingActions();
            AtomicInteger persistedLimit = new AtomicInteger();
            SwingGameLogPanel panel = panel(buffer, actions, persistedLimit);
            panel.appendAll(List.of(
                    log("info", Log4jLevel.INFO),
                    log("error", Log4jLevel.ERROR),
                    log("debug", Log4jLevel.DEBUG)));

            panel.levelButton(Log4jLevel.ERROR).doClick();

            assertEquals(2, panel.visibleRowCount());
            assertEquals("info", panel.visibleText(0));
            assertEquals("debug", panel.visibleText(1));

            panel.lineLimitControl().setSelectedItem(500);
            assertEquals(500, persistedLimit.get());
            assertEquals(2, panel.visibleRowCount());
            assertEquals("debug", panel.visibleText(1));

            panel.autoScrollControl().doClick();
            assertFalse(panel.autoScrollControl().isSelected());

            panel.clearLogsButton().doClick();
            assertEquals(0, panel.visibleRowCount());
            assertEquals(0, buffer.size());
        });
    }

    /// Copies selected rows in visual order and preserves the established trailing newline.
    @Test
    void controlCUsesClipboardBoundaryAndNonModalStatus() {
        EdtDispatcher.executeAndWait(() -> {
            RecordingActions actions = new RecordingActions();
            SwingGameLogPanel panel = panel(
                    new BoundedGameLogBuffer(new CircularArrayList<>(), 10),
                    actions,
                    new AtomicInteger());
            panel.appendAll(List.of(log("one", Log4jLevel.INFO), log("two", Log4jLevel.WARN)));
            panel.logList().setSelectionInterval(0, 1);
            Action copyAction = panel.logList().getActionMap().get("copy-selected-logs");

            assertNotNull(copyAction);
            copyAction.actionPerformed(new ActionEvent(panel.logList(), ActionEvent.ACTION_PERFORMED, "copy"));

            assertEquals("one\ntwo\n", actions.copiedText);
            assertEquals(i18n("message.copied"), panel.statusText());
        });
    }

    /// Exports the immutable retained snapshot, reveals the result, and reports success on the EDT.
    @Test
    void exportUsesBackgroundBoundaryAndReenablesButton() {
        EdtDispatcher.executeAndWait(() -> {
            RecordingActions actions = new RecordingActions();
            RecordingNotifier notifier = new RecordingNotifier();
            BoundedGameLogBuffer buffer = new BoundedGameLogBuffer(new CircularArrayList<>(), 10);
            SwingGameLogPanel panel = new SwingGameLogPanel(
                    buffer,
                    actions,
                    Runnable::run,
                    ignored -> {
                    },
                    ignored -> {
                    },
                    notifier);
            panel.appendAll(List.of(log("first", Log4jLevel.INFO), log("second", Log4jLevel.ERROR)));

            panel.exportLogsButton().doClick();

            assertEquals(List.of("first", "second"), actions.exportedLines);
            assertSame(actions.exportPath, actions.revealedPath);
            assertSame(actions.exportPath, notifier.succeededPath);
            assertTrue(panel.exportLogsButton().isEnabled());
        });
    }

    /// Delegates termination, disables live-process controls after exit, and releases listeners on disposal.
    @Test
    void processExitAndDisposalReleaseInteractiveControls() {
        EdtDispatcher.executeAndWait(() -> {
            RecordingActions actions = new RecordingActions();
            actions.dumpSupported = true;
            SwingGameLogPanel panel = panel(
                    new BoundedGameLogBuffer(new CircularArrayList<>(), 10),
                    actions,
                    new AtomicInteger());
            panel.append(log("entry", Log4jLevel.INFO));

            panel.terminateGameButton().doClick();
            assertEquals(1, actions.terminateCalls);
            assertTrue(panel.exportDumpButton().isEnabled());

            panel.processExited();
            assertFalse(panel.terminateGameButton().isEnabled());
            assertFalse(panel.exportDumpButton().isEnabled());

            panel.disposePanel();
            panel.clearLogsButton().doClick();
            panel.terminateGameButton().doClick();

            assertEquals(0, panel.visibleRowCount());
            assertEquals(1, actions.terminateCalls);
        });
    }

    /// Creates a panel with direct background execution for deterministic tests.
    ///
    /// @param buffer bounded shared history
    /// @param actions recording side-effect boundary
    /// @param persistedLimit selected line-limit recorder
    /// @return configured panel
    private static SwingGameLogPanel panel(
            BoundedGameLogBuffer buffer,
            RecordingActions actions,
            AtomicInteger persistedLimit) {
        return new SwingGameLogPanel(
                buffer,
                actions,
                Runnable::run,
                persistedLimit::set,
                ignored -> {
                },
                new RecordingNotifier());
    }

    /// Creates one explicitly leveled test entry.
    ///
    /// @param text entry text
    /// @param level entry severity
    /// @return test log entry
    private static Log log(String text, Log4jLevel level) {
        return new Log(text, level);
    }

    /// Records all non-Swing side effects without touching the real process, clipboard, or filesystem.
    @NotNullByDefault
    private static final class RecordingActions implements GameLogWindowActions {
        /// Stable fake export destination.
        private final Path exportPath = Path.of("game-log-export.log").toAbsolutePath();

        /// Last immutable exported line snapshot.
        private @Nullable @Unmodifiable List<String> exportedLines;

        /// Last path passed to native reveal.
        private @Nullable Path revealedPath;

        /// Last text copied to the clipboard boundary.
        private @Nullable String copiedText;

        /// Number of process termination requests.
        private int terminateCalls;

        /// Whether the fake process supports dump export.
        private boolean dumpSupported;

        /// Ignores process-exit registration because the panel receives exit directly in this test.
        ///
        /// @param listener unused process-exit callback
        @Override
        public void registerProcessExit(Runnable listener) {
        }

        /// Reports a running fake process.
        ///
        /// @return true for the initial panel state
        @Override
        public boolean isProcessRunning() {
            return true;
        }

        /// Reports configured fake stack-dump support.
        ///
        /// @return configured support flag
        @Override
        public boolean canExportDump() {
            return dumpSupported;
        }

        /// Records one termination request.
        @Override
        public void terminateGame() {
            terminateCalls++;
        }

        /// Records and returns one successful log export.
        ///
        /// @param lines immutable text snapshot in source order
        /// @return stable fake export destination
        @Override
        public Path exportLogs(@Unmodifiable List<String> lines) {
            exportedLines = List.copyOf(lines);
            return exportPath;
        }

        /// Returns one successful fake dump export.
        ///
        /// @return stable fake export destination
        @Override
        public Path exportDump() {
            return exportPath;
        }

        /// Records the path passed to native reveal.
        ///
        /// @param file exported file to reveal
        @Override
        public void revealFile(Path file) {
            revealedPath = file;
        }

        /// Records copied log text.
        ///
        /// @param text selected log text
        @Override
        public void copyText(String text) {
            copiedText = text;
        }
    }

    /// Records export notifications without opening native dialogs.
    @NotNullByDefault
    private static final class RecordingNotifier implements GameLogWindowNotifier {
        /// Last path reported as a successful export.
        private @Nullable Path succeededPath;

        /// Last failure reported by the panel.
        private @Nullable Throwable failure;

        /// Records one successful export.
        ///
        /// @param owner panel owning the notification
        /// @param file exported file
        @Override
        public void exportSucceeded(Component owner, Path file) {
            succeededPath = file;
        }

        /// Records one failed export.
        ///
        /// @param owner panel owning the notification
        /// @param operation localized operation name
        /// @param failure export failure
        @Override
        public void exportFailed(Component owner, String operation, Throwable failure) {
            this.failure = failure;
        }
    }
}
