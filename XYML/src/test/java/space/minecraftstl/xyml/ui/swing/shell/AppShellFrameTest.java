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
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/// Verifies deterministic shell disposal and native-frame constraints.
@NotNullByDefault
public final class AppShellFrameTest {
    /// Shell cleanup precedes native disposal when neither action fails.
    @Test
    public void disposesInOrder() {
        List<String> actions = new ArrayList<>();

        AppShellFrame.disposeInOrder(
                () -> actions.add("shell"),
                () -> actions.add("native"));

        assertEquals(List.of("shell", "native"), actions);
    }

    /// Native disposal still runs when shell cleanup fails, and the original failure is preserved.
    @Test
    public void disposesNativeWindowAfterShellCleanupFailure() {
        List<String> actions = new ArrayList<>();
        IllegalStateException cleanupFailure = new IllegalStateException("shell cleanup failed");

        IllegalStateException thrown = assertThrows(IllegalStateException.class, () ->
                AppShellFrame.disposeInOrder(
                        () -> {
                            actions.add("shell");
                            throw cleanupFailure;
                        },
                        () -> actions.add("native")));

        assertAll(
                () -> assertSame(cleanupFailure, thrown),
                () -> assertEquals(List.of("shell", "native"), actions));
    }

    /// A native-disposal failure is suppressed by the earlier shell-cleanup failure.
    @Test
    public void suppressesNativeFailureAfterShellCleanupFailure() {
        List<String> actions = new ArrayList<>();
        IllegalStateException cleanupFailure = new IllegalStateException("shell cleanup failed");
        IllegalArgumentException nativeFailure = new IllegalArgumentException("native disposal failed");

        IllegalStateException thrown = assertThrows(IllegalStateException.class, () ->
                AppShellFrame.disposeInOrder(
                        () -> {
                            actions.add("shell");
                            throw cleanupFailure;
                        },
                        () -> {
                            actions.add("native");
                            throw nativeFailure;
                        }));

        assertAll(
                () -> assertSame(cleanupFailure, thrown),
                () -> assertEquals(List.of("shell", "native"), actions),
                () -> assertEquals(1, thrown.getSuppressed().length),
                () -> assertSame(nativeFailure, thrown.getSuppressed()[0]));
    }

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
            frame.open();
            assertAll(
                    () -> assertFalse(frame.isUndecorated()),
                    () -> assertTrue(frame.isResizable()),
                    () -> assertEquals(AppShellPanel.MINIMUM_WIDTH, frame.getMinimumSize().width),
                    () -> assertEquals(AppShellPanel.MINIMUM_HEIGHT, frame.getMinimumSize().height),
                    () -> assertTrue(frame.getWidth() >= AppShellPanel.PREFERRED_WIDTH),
                    () -> assertTrue(frame.getHeight() >= AppShellPanel.PREFERRED_HEIGHT),
                    () -> assertTrue(frame.isVisible()));
            frame.hideWindow();
            assertFalse(frame.isVisible());
        } finally {
            EdtDispatcher.executeAndWait(frame::dispose);
        }
    }
}
