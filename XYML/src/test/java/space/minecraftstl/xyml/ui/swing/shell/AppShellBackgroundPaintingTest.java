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
package space.minecraftstl.xyml.ui.swing.shell;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import space.minecraftstl.xyml.theme.ThemeBrightnessPreference;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;
import space.minecraftstl.xyml.ui.swing.MotionPolicy;
import space.minecraftstl.xyml.ui.swing.SwingAnimator;
import space.minecraftstl.xyml.ui.swing.SwingDesignTokens;
import space.minecraftstl.xyml.ui.swing.SwingThemeManager;
import space.minecraftstl.xyml.ui.swing.SystemThemeDetector;
import space.minecraftstl.xyml.ui.swing.task.TaskProgressStrings;

import javax.swing.JComponent;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies the shell paints decoded backgrounds with opacity and native alpha semantics.
@NotNullByDefault
public final class AppShellBackgroundPaintingTest {
    /// Opaque mode paints the selected fill over the theme surface.
    @Test
    public void paintsOpaqueFill() {
        AppShellPanel panel = createPanel();
        try {
            Color rendered = renderFill(panel, new Color(20, 40, 60), 1.0, false);
            assertEquals(new Color(20, 40, 60), rendered);
        } finally {
            EdtDispatcher.executeAndWait(panel::close);
        }
    }

    /// Transparent mode clears stale pixels before compositing the selected background opacity.
    @Test
    public void paintsPerPixelAlphaAfterClearingSurface() {
        AppShellPanel panel = createPanel();
        try {
            Color rendered = renderFill(panel, new Color(20, 40, 60), 0.5, true);
            assertAll(
                    () -> assertEquals(20, rendered.getRed()),
                    () -> assertEquals(40, rendered.getGreen()),
                    () -> assertEquals(60, rendered.getBlue()),
                    () -> assertEquals(128, rendered.getAlpha()),
                    () -> assertFalse(panel.isOpaque()));
        } finally {
            EdtDispatcher.executeAndWait(panel::close);
        }
    }

    /// Bounds-aware linear paint reaches both shell edges instead of collapsing to a single sampled color.
    @Test
    public void paintsBoundsAwareLinearGradient() {
        AppShellPanel panel = createPanel();
        try {
            WindowBackgroundPaint paint = new WindowBackgroundPaint.Linear(
                    0.0,
                    0.0,
                    1.0,
                    0.0,
                    true,
                    WindowBackgroundPaint.Cycle.PAD,
                    List.of(
                            new WindowBackgroundPaint.GradientStop(0.0, Color.RED),
                            new WindowBackgroundPaint.GradientStop(1.0, Color.BLUE)));
            BufferedImage rendered = renderBackground(panel, paint, 1.0, false);
            Color left = new Color(rendered.getRGB(1, 8), true);
            Color right = new Color(rendered.getRGB(22, 8), true);

            assertAll(
                    () -> assertEquals(255, left.getAlpha()),
                    () -> assertEquals(255, right.getAlpha()),
                    () -> assertFalse(left.equals(right)),
                    () -> assertTrue(left.getRed() > left.getBlue()),
                    () -> assertTrue(right.getBlue() > right.getRed()));
        } finally {
            EdtDispatcher.executeAndWait(panel::close);
        }
    }

    /// Creates one deterministic shell with no external page or account I/O.
    ///
    /// @return initialized shell panel
    private static AppShellPanel createPanel() {
        AtomicReference<@Nullable AppShellPanel> result = new AtomicReference<>();
        EdtDispatcher.executeAndWait(() -> {
            SwingThemeManager themeManager = new SwingThemeManager(
                    ThemeBrightnessPreference.LIGHT,
                    new SwingDesignTokens(8),
                    SystemThemeDetector.lightFallback());
            themeManager.initialize();
            EnumMap<ShellPageId, ShellPageFactory<? extends JComponent>> factories =
                    AppShellPanelTest.pageFactories(AppShellPanelTest.creationCounts());
            result.set(new AppShellPanel(
                    "XYML",
                    factories,
                    ShellPagePresentations.englishFallback(),
                    new ShellToolbarModels(
                            AppShellPanelTest.testHomeModel(),
                            AppShellPanelTest.testInstancesModel(),
                            AppShellPanelTest.testAccountsModel(),
                            AppShellPanelTest.testGameDirectories(),
                            ShellRecentSelections.transientSelections()),
                    AppShellPanelTest.testHomeStrings(),
                    TaskProgressStrings.english(),
                    new SwingAnimator(MotionPolicy.OFF, 16),
                    Duration.ZERO,
                    Duration.ZERO));
        });
        return Objects.requireNonNull(result.get(), "shell panel");
    }

    /// Paints only the shell background layer to an isolated ARGB raster.
    ///
    /// @param panel initialized shell
    /// @param fill requested fill
    /// @param opacity requested layer opacity
    /// @param transparent native transparency paint mode
    /// @return center pixel after painting
    private static Color renderFill(
            AppShellPanel panel,
            Color fill,
            double opacity,
            boolean transparent) {
        BufferedImage target = renderBackground(
                panel,
                WindowBackgroundPaint.solid(fill),
                opacity,
                transparent);
        return new Color(target.getRGB(12, 8), true);
    }

    /// Paints one shell paint layer to an isolated ARGB raster.
    ///
    /// @param panel initialized shell
    /// @param paint requested bounds-aware paint
    /// @param opacity requested layer opacity
    /// @param transparent native transparency paint mode
    /// @return rendered raster
    private static BufferedImage renderBackground(
            AppShellPanel panel,
            WindowBackgroundPaint paint,
            double opacity,
            boolean transparent) {
        AtomicReference<@Nullable BufferedImage> result = new AtomicReference<>();
        EdtDispatcher.executeAndWait(() -> {
            panel.setSize(24, 16);
            panel.setWindowBackground(new WindowBackgroundVisual(null, paint, opacity, transparent));
            BufferedImage target = new BufferedImage(24, 16, BufferedImage.TYPE_INT_ARGB);
            Graphics2D graphics = target.createGraphics();
            try {
                graphics.setColor(Color.MAGENTA);
                graphics.fillRect(0, 0, target.getWidth(), target.getHeight());
                panel.paintComponent(graphics);
            } finally {
                graphics.dispose();
            }
            result.set(target);
        });
        return Objects.requireNonNull(result.get(), "rendered background");
    }
}
