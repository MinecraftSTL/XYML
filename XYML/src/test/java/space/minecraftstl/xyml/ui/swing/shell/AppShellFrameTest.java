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
import org.junit.jupiter.api.Test;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;
import space.minecraftstl.xyml.ui.swing.MotionPolicy;
import space.minecraftstl.xyml.ui.swing.SwingAnimator;
import space.minecraftstl.xyml.ui.swing.SwingDesignTokens;
import space.minecraftstl.xyml.ui.swing.SwingThemeManager;
import space.minecraftstl.xyml.ui.swing.SystemThemeDetector;
import space.minecraftstl.xyml.ui.swing.ThemeMode;

import javax.swing.JComponent;
import javax.swing.JPanel;
import java.awt.GraphicsEnvironment;
import java.time.Duration;
import java.util.EnumMap;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/// Verifies native-frame constraints when the test environment provides a display server.
@NotNullByDefault
public final class AppShellFrameTest {
    /// The frame remains operating-system decorated, resizable, and packed to the shell's preferred bounds.
    @Test
    public void createsSystemDecoratedFrame() {
        assumeFalse(GraphicsEnvironment.isHeadless());
        EnumMap<ShellPageId, ShellPageFactory<? extends JComponent>> factories =
                new EnumMap<>(ShellPageId.class);
        for (ShellPageId page : ShellPageId.values()) {
            factories.put(page, JPanel::new);
        }

        SwingThemeManager themeManager = new SwingThemeManager(
                ThemeMode.LIGHT,
                new SwingDesignTokens(8),
                SystemThemeDetector.lightFallback());
        AppShellFrame frame = AppShellFrame.create(
                "XYML",
                themeManager,
                factories,
                ShellPageId.HOME,
                ShellPagePresentations.englishFallback(),
                new SwingAnimator(MotionPolicy.OFF, 16),
                Duration.ZERO);
        try {
            assertAll(
                    () -> assertFalse(frame.isUndecorated()),
                    () -> assertTrue(frame.isResizable()),
                    () -> assertEquals(AppShellPanel.MINIMUM_WIDTH, frame.getMinimumSize().width),
                    () -> assertEquals(AppShellPanel.MINIMUM_HEIGHT, frame.getMinimumSize().height),
                    () -> assertTrue(frame.getWidth() >= AppShellPanel.PREFERRED_WIDTH),
                    () -> assertTrue(frame.getHeight() >= AppShellPanel.PREFERRED_HEIGHT));
        } finally {
            EdtDispatcher.executeAndWait(frame::dispose);
        }
    }
}
