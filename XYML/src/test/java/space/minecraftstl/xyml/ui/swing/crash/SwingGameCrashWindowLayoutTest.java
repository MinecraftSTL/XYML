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
import space.minecraftstl.xyml.game.CrashReportAnalyzer;
import space.minecraftstl.xyml.game.Log;
import space.minecraftstl.xyml.game.analyzer.LogAnalyzable;
import space.minecraftstl.xyml.launch.ProcessListener;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;
import space.minecraftstl.xyml.util.platform.Bits;
import space.minecraftstl.xyml.util.platform.OperatingSystem;

import javax.swing.JEditorPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.ScrollPaneConstants;
import javax.swing.Scrollable;
import java.awt.Component;
import java.awt.Container;
import java.lang.reflect.Field;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies that the reason viewport constrains diagnosis rows and keeps their evidence readable.
@NotNullByDefault
class SwingGameCrashWindowLayoutTest {
    /// Keeps a long Fabric warning inside the diagnosis viewport and left-aligns its wrapping row.
    @Test
    void constrainsDiagnosisRowsToTheReasonViewport() throws Exception {
        CompletableFuture<GameCrashAnalysis> analysis = new CompletableFuture<>();
        ExecutorService worker = Executors.newSingleThreadExecutor();
        SwingGameCrashWindow window = new SwingGameCrashWindow(
                model(),
                (logAnalyzable, latestLog) -> analysis,
                new GameCrashReasonFormatter(),
                new NoOpActions(),
                worker,
                false);

        try {
            window.show();
            EdtDispatcher.executeAndWait(() -> { });
            analysis.complete(fabricWarningAnalysis());
            EdtDispatcher.executeAndWait(() -> { });

            JPanel content = content(window);
            EdtDispatcher.executeAndWait(() -> {
                content.setSize(800, 480);
                for (int i = 0; i < 4; i++) {
                    layout(content);
                }

                JScrollPane informationScroll = Objects.requireNonNull(
                        findScrollPane(content, "gameCrashInformationScroll"),
                        "information scroll pane");
                JScrollPane reasonScroll = Objects.requireNonNull(
                        findScrollPane(content, "gameCrashReasonScroll"),
                        "reason scroll pane");
                assertEquals(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER,
                        informationScroll.getHorizontalScrollBarPolicy());
                assertEquals(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER,
                        reasonScroll.getHorizontalScrollBarPolicy());
                assertTrue(informationScroll.getViewport().getView() instanceof JTextArea information
                        && information.getLineWrap()
                        && information.getWrapStyleWord());
                assertTrue(reasonScroll.getViewport().getView().getWidth()
                        <= reasonScroll.getViewport().getExtentSize().width + 1);

                JEditorPane evidence = Objects.requireNonNull(
                        findEvidenceView(content),
                        "evidence view");
                assertTrue(evidence.getText().contains("captured"));
                assertEquals(Component.LEFT_ALIGNMENT, evidence.getAlignmentX());
            });
        } finally {
            window.close();
            EdtDispatcher.executeAndWait(() -> { });
        }
    }

    /// Creates the immutable model used by the layout test.
    ///
    /// @return crash-window model with deterministic launch context
    private static GameCrashWindowModel model() {
        return new GameCrashWindowModel(
                ProcessListener.ExitType.APPLICATION_ERROR,
                List.of(new GameCrashWindowModel.Detail("Instance", "26.2-Fabric-apple-df")),
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
    }

    /// Creates one long Fabric warning with source evidence.
    ///
    /// @return merged diagnosis containing the Fabric warning rule
    private static GameCrashAnalysis fabricWarningAnalysis() {
        String sample = "Warnings were found!\n"
                + " - Mod 'Forge Config API Port' (forgeconfigapiport) 26.2.1 suggests installing modmenu\n"
                + "[main/INFO]: [FabricLoader]";
        CrashReportAnalyzer.Result result = CrashReportAnalyzer.analyze(sample).stream()
                .filter(candidate -> candidate.rule() == CrashReportAnalyzer.Rule.FABRIC_WARNINGS)
                .findFirst()
                .orElseThrow();
        return new GameCrashAnalysis(
                List.of(result),
                List.of(),
                Set.of(),
                List.of(),
                Map.of(CrashReportAnalyzer.Rule.FABRIC_WARNINGS, List.of("captured", "latest.log")));
    }

    /// Returns the private content root from the native-frame-disabled window.
    ///
    /// @param window window under test
    /// @return composed content root
    /// @throws ReflectiveOperationException if the layout fixture no longer exposes the expected root
    private static JPanel content(SwingGameCrashWindow window) throws ReflectiveOperationException {
        Field field = SwingGameCrashWindow.class.getDeclaredField("content");
        field.setAccessible(true);
        return (JPanel) Objects.requireNonNull(field.get(window), "content");
    }

    /// Finds one named scroll pane below a component tree.
    ///
    /// @param component component tree root
    /// @param name deterministic component name
    /// @return matching scroll pane, or null
    private static @Nullable JScrollPane findScrollPane(Component component, String name) {
        if (component instanceof JScrollPane scrollPane && name.equals(scrollPane.getName())) {
            return scrollPane;
        }
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                @Nullable JScrollPane result = findScrollPane(child, name);
                if (result != null) {
                    return result;
                }
            }
        }
        return null;
    }

    /// Finds the width-tracking diagnosis viewport.
    ///
    /// @param component component tree root
    /// @return matching scroll pane, or null when no viewport tracks width
    private static @Nullable JScrollPane findTrackingScrollPane(Component component) {
        if (component instanceof JScrollPane scrollPane
                && scrollPane.getViewport().getView() instanceof Scrollable scrollable
                && scrollable.getScrollableTracksViewportWidth()) {
            return scrollPane;
        }
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                @Nullable JScrollPane result = findTrackingScrollPane(child);
                if (result != null) {
                    return result;
                }
            }
        }
        return null;
    }

    /// Finds the wrapping evidence view in the rendered diagnosis row.
    ///
    /// @param component component tree root
    /// @return matching evidence view, or null when no rendered row contains it
    private static @Nullable JEditorPane findEvidenceView(Component component) {
        if (component instanceof JEditorPane editorPane
                && editorPane.getText().contains("captured")) {
            return editorPane;
        }
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                @Nullable JEditorPane result = findEvidenceView(child);
                if (result != null) {
                    return result;
                }
            }
        }
        return null;
    }

    /// Recursively lays out a peerless Swing hierarchy for deterministic headless sizing.
    ///
    /// @param container container to lay out
    private static void layout(Container container) {
        container.doLayout();
        for (Component component : container.getComponents()) {
            if (component instanceof Container child) {
                layout(child);
            }
        }
    }

    /// Keeps all window side effects inert for layout verification.
    @NotNullByDefault
    private static final class NoOpActions implements GameCrashWindowActions {
        /// Returns an inert export stage.
        ///
        /// @return completed unused path
        @Override
        public CompletionStage<Path> exportCrashLogs() {
            return CompletableFuture.completedFuture(Path.of("unused.zip"));
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

        /// Performs no resource-release side effect.
        @Override
        public void close() {
        }
    }
}
