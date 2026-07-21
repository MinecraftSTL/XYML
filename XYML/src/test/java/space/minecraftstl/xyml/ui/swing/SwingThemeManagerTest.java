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
package space.minecraftstl.xyml.ui.swing;

import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLightLaf;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import javax.swing.UIManager;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests FlatLaf initialization and hot updates without creating a displayable window.
@NotNullByDefault
public final class SwingThemeManagerTest {
    /// Initialization and explicit updates replace the palette and radius defaults on the EDT.
    @Test
    public void initializesAndUpdatesFlatLaf() {
        SwingThemeManager manager = new SwingThemeManager(
                ThemeMode.LIGHT,
                new SwingDesignTokens(4),
                SystemThemeDetector.lightFallback());

        assertNull(manager.effectiveVariant());
        manager.initialize();

        assertAll(
                () -> assertTrue(manager.isInitialized()),
                () -> assertEquals(ThemeVariant.LIGHT, manager.effectiveVariant()),
                () -> assertInstanceOf(FlatLightLaf.class, UIManager.getLookAndFeel()),
                () -> assertEquals(4, UIManager.getInt("Component.arc")));

        manager.update(ThemeMode.DARK, new SwingDesignTokens(13));

        assertAll(
                () -> assertEquals(ThemeVariant.DARK, manager.effectiveVariant()),
                () -> assertInstanceOf(FlatDarkLaf.class, UIManager.getLookAndFeel()),
                () -> assertEquals(13, UIManager.getInt("Component.arc")));
    }

    /// Refreshing system mode responds to a changed platform appearance signal.
    @Test
    public void refreshesSystemAppearance() {
        AtomicBoolean dark = new AtomicBoolean();
        SwingThemeManager manager = new SwingThemeManager(
                ThemeMode.SYSTEM,
                new SwingDesignTokens(6),
                dark::get);
        manager.initialize();

        dark.set(true);
        manager.refreshSystemTheme();

        assertAll(
                () -> assertEquals(ThemeVariant.DARK, manager.effectiveVariant()),
                () -> assertInstanceOf(FlatDarkLaf.class, UIManager.getLookAndFeel()));
    }
}
