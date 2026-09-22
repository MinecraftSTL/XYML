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
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import space.minecraftstl.xyml.game.Log;
import space.minecraftstl.xyml.game.analyzer.LogAnalyzable;
import space.minecraftstl.xyml.launch.ProcessListener;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;
import space.minecraftstl.xyml.util.platform.Bits;
import space.minecraftstl.xyml.util.platform.OperatingSystem;

import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.Component;
import java.awt.Container;
import java.io.IOException;
import java.lang.reflect.Field;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static space.minecraftstl.xyml.util.i18n.I18n.i18n;

/// Verifies explicit crash-report export and native file-manager reveal behavior.
@NotNullByDefault
class SwingGameCrashWindowExportTest {
    /// Shows the reveal command after export without opening the file manager automatically.
    @Test
    void exportSuccessShowsRevealButtonWithoutOpeningFileManager() throws Exception {
        ExecutorService worker = Executors.newSingleThreadExecutor();
        RecordingActions actions = new RecordingActions();
        SwingGameCrashWindow window = window(actions, worker);
        Path report = Path.of("reports", "exported-crash.zip").toAbsolutePath();
        actions.prepareExport(CompletableFuture.completedFuture(report));

        try {
            window.show();
            EdtDispatcher.executeAndWait(() -> {
            });
            JPanel content = content(window);
            JButton export = findComponent(content, "gameCrashExport", JButton.class);
            JButton reveal = findComponent(content, "gameCrashReveal", JButton.class);
            JLabel status = findComponent(content, "gameCrashOperationStatus", JLabel.class);

            EdtDispatcher.executeAndWait(() -> {
                assertFalse(reveal.isVisible());
                export.doClick();
            });
            EdtDispatcher.executeAndWait(() -> {
                assertTrue(reveal.isVisible());
                assertEquals(i18n("button.reveal_dir"), reveal.getText());
                assertEquals(i18n("reveal.in_file_manager"), reveal.getToolTipText());
                assertEquals(i18n("message.success"), status.getText());
            });
            assertTrue(actions.revealCalls().isEmpty());
        } finally {
            window.close();
            EdtDispatcher.executeAndWait(() -> {
            });
        }
    }

    /// Opens the latest successful report only after the user activates the reveal command.
    @Test
    void revealButtonOpensLatestSuccessfulReport() throws Exception {
        ExecutorService worker = Executors.newSingleThreadExecutor();
        RecordingActions actions = new RecordingActions();
        SwingGameCrashWindow window = window(actions, worker);
        Path report = Path.of("reports", "latest-crash.zip").toAbsolutePath();
        actions.prepareExport(CompletableFuture.completedFuture(report));

        try {
            window.show();
            EdtDispatcher.executeAndWait(() -> {
            });
            JPanel content = content(window);
            JButton export = findComponent(content, "gameCrashExport", JButton.class);
            JButton reveal = findComponent(content, "gameCrashReveal", JButton.class);
            EdtDispatcher.executeAndWait(export::doClick);
            EdtDispatcher.executeAndWait(() -> assertTrue(reveal.isVisible()));

            EdtDispatcher.executeAndWait(reveal::doClick);
            actions.awaitReveal();

            assertEquals(List.of(report), actions.revealCalls());
        } finally {
            window.close();
            EdtDispatcher.executeAndWait(() -> {
            });
        }
    }

    /// Hides a previous reveal target while a replacement export is still pending.
    @Test
    void secondExportReplacesRevealTarget() throws Exception {
        ExecutorService worker = Executors.newSingleThreadExecutor();
        RecordingActions actions = new RecordingActions();
        SwingGameCrashWindow window = window(actions, worker);
        Path firstReport = Path.of("reports", "first-crash.zip").toAbsolutePath();
        Path secondReport = Path.of("reports", "second-crash.zip").toAbsolutePath();
        CompletableFuture<Path> secondExport = new CompletableFuture<>();

        try {
            window.show();
            EdtDispatcher.executeAndWait(() -> {
            });
            JPanel content = content(window);
            JButton export = findComponent(content, "gameCrashExport", JButton.class);
            JButton reveal = findComponent(content, "gameCrashReveal", JButton.class);

            actions.prepareExport(CompletableFuture.completedFuture(firstReport));
            EdtDispatcher.executeAndWait(export::doClick);
            EdtDispatcher.executeAndWait(() -> assertTrue(reveal.isVisible()));

            actions.prepareExport(secondExport);
            EdtDispatcher.executeAndWait(export::doClick);
            EdtDispatcher.executeAndWait(() -> assertFalse(reveal.isVisible()));

            secondExport.complete(secondReport);
            EdtDispatcher.executeAndWait(() -> assertTrue(reveal.isVisible()));
            EdtDispatcher.executeAndWait(reveal::doClick);
            actions.awaitReveal();

            assertEquals(List.of(secondReport), actions.revealCalls());
        } finally {
            window.close();
            EdtDispatcher.executeAndWait(() -> {
            });
        }
    }

    /// Restores the reveal command and marks the operation failed when desktop integration fails.
    @Test
    void revealFailureRestoresAction() throws Exception {
        ExecutorService worker = Executors.newSingleThreadExecutor();
        RecordingActions actions = new RecordingActions();
        SwingGameCrashWindow window = window(actions, worker);
        Path report = Path.of("reports", "failed-reveal.zip").toAbsolutePath();
        actions.prepareExport(CompletableFuture.completedFuture(report));
        actions.prepareRevealFailure(new IOException("File manager unavailable"));

        try {
            window.show();
            EdtDispatcher.executeAndWait(() -> {
            });
            JPanel content = content(window);
            JButton export = findComponent(content, "gameCrashExport", JButton.class);
            JButton reveal = findComponent(content, "gameCrashReveal", JButton.class);
            JLabel status = findComponent(content, "gameCrashOperationStatus", JLabel.class);
            EdtDispatcher.executeAndWait(export::doClick);
            EdtDispatcher.executeAndWait(reveal::doClick);
            assertThrows(ExecutionException.class, actions::awaitReveal);
            EdtDispatcher.executeAndWait(() -> {
                assertTrue(reveal.isEnabled());
                assertEquals(i18n("message.failed"), status.getText());
            });
        } finally {
            window.close();
            EdtDispatcher.executeAndWait(() -> {
            });
        }
    }

    /// Suppresses a late export completion after the window has been closed.
    @Test
    void closeSuppressesLateExportCompletion() throws Exception {
        ExecutorService worker = Executors.newSingleThreadExecutor();
        RecordingActions actions = new RecordingActions();
        SwingGameCrashWindow window = window(actions, worker);
        CompletableFuture<Path> pendingExport = new CompletableFuture<>();
        actions.prepareExport(pendingExport);

        window.show();
        EdtDispatcher.executeAndWait(() -> {
        });
        JPanel content = content(window);
        JButton export = findComponent(content, "gameCrashExport", JButton.class);
        JButton reveal = findComponent(content, "gameCrashReveal", JButton.class);
        EdtDispatcher.executeAndWait(export::doClick);

        window.close();
        EdtDispatcher.executeAndWait(() -> {
        });
        pendingExport.complete(Path.of("reports", "late-crash.zip").toAbsolutePath());
        EdtDispatcher.executeAndWait(() -> {
        });

        assertTrue(window.isClosed());
        assertFalse(reveal.isVisible());
        assertTrue(actions.revealCalls().isEmpty());
    }

    /// Creates one native-frame-disabled window with deterministic analysis.
    ///
    /// @param actions controlled export and desktop actions
    /// @param worker window-owned executor
    /// @return test window
    private static SwingGameCrashWindow window(RecordingActions actions, ExecutorService worker) {
        GameCrashWindowModel model = new GameCrashWindowModel(
                ProcessListener.ExitType.APPLICATION_ERROR,
                List.of(new GameCrashWindowModel.Detail("Instance", "Test")),
                new LogAnalyzable(
                        "1.20.4",
                        "net.minecraft.client.main.Main",
                        ProcessListener.ExitType.APPLICATION_ERROR,
                        OperatingSystem.WINDOWS,
                        936,
                        Path.of("C:/Games/Minecraft/.minecraft"),
                        Path.of("C:/Java/bin/javaw.exe"),
                        17,
                        17,
                        Bits.BIT_64,
                        4096,
                        List.of(new Log("captured").getLog())),
                Path.of("missing-latest.log"));
        return new SwingGameCrashWindow(
                model,
                (logAnalyzable, latestLog) -> CompletableFuture.completedFuture(
                        new GameCrashAnalysis(List.of(), Set.of())),
                new GameCrashReasonFormatter(),
                actions,
                worker,
                false);
    }

    /// Returns the private content root from a native-frame-disabled window.
    ///
    /// @param window window under test
    /// @return composed content root
    /// @throws ReflectiveOperationException when the content field is unavailable
    private static JPanel content(SwingGameCrashWindow window) throws ReflectiveOperationException {
        Field field = SwingGameCrashWindow.class.getDeclaredField("content");
        field.setAccessible(true);
        return (JPanel) Objects.requireNonNull(field.get(window), "content");
    }

    /// Finds one named component below a component tree.
    ///
    /// @param root component tree root
    /// @param name deterministic component name
    /// @param type expected component type
    /// @param <T> component type
    /// @return matching component
    private static <T extends Component> T findComponent(Component root, String name, Class<T> type) {
        if (type.isInstance(root) && name.equals(root.getName())) {
            return type.cast(root);
        }
        if (root instanceof Container container) {
            for (Component child : container.getComponents()) {
                try {
                    return findComponent(child, name, type);
                } catch (AssertionError ignored) {
                }
            }
        }
        throw new AssertionError("Component not found: " + name);
    }

    /// Records export results and native reveal requests for deterministic tests.
    @NotNullByDefault
    private static final class RecordingActions implements GameCrashWindowActions {
        /// Export result supplied by the current test.
        private @Nullable CompletableFuture<Path> exportResult;

        /// Failure supplied to the next reveal request.
        private @Nullable Exception revealFailure;

        /// Native reveal requests in invocation order.
        private final List<Path> revealCalls = new CopyOnWriteArrayList<>();

        /// Completion signal for the current reveal request.
        private final CompletableFuture<Void> revealCompletion = new CompletableFuture<>();

        /// Supplies the next asynchronous export result.
        ///
        /// @param result export completion to return
        void prepareExport(CompletionStage<Path> result) {
            exportResult = Objects.requireNonNull(result, "result").toCompletableFuture();
        }

        /// Supplies the exception thrown by the next reveal request.
        ///
        /// @param failure failure to throw
        void prepareRevealFailure(Exception failure) {
            revealFailure = Objects.requireNonNull(failure, "failure");
        }

        /// Returns an immutable snapshot of reveal requests.
        ///
        /// @return reveal requests in invocation order
        List<Path> revealCalls() {
            return List.copyOf(revealCalls);
        }

        /// Waits for the current reveal request to reach its terminal state.
        ///
        /// @throws InterruptedException when the test thread is interrupted
        /// @throws ExecutionException when reveal failed
        /// @throws TimeoutException when reveal does not finish within the test timeout
        void awaitReveal() throws InterruptedException, ExecutionException, TimeoutException {
            revealCompletion.get(5, TimeUnit.SECONDS);
        }

        /// Returns one prepared asynchronous export result.
        ///
        /// @return export stage
        @Override
        public CompletionStage<Path> exportCrashLogs() {
            @Nullable CompletableFuture<Path> result = exportResult;
            exportResult = null;
            return Objects.requireNonNull(result, "export result");
        }

        /// Records one reveal request and applies its configured result.
        ///
        /// @param file exported file
        /// @throws Exception when reveal failure was requested
        @Override
        public void revealFile(Path file) throws Exception {
            revealCalls.add(Objects.requireNonNull(file, "file"));
            @Nullable Exception failure = revealFailure;
            revealFailure = null;
            if (failure != null) {
                revealCompletion.completeExceptionally(failure);
                throw failure;
            }
            revealCompletion.complete(null);
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

        /// Performs no resource-release side effect.
        @Override
        public void close() {
        }
    }
}
