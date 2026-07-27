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
import com.formdev.flatlaf.FlatLaf;
import com.formdev.flatlaf.FlatLightLaf;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import space.minecraftstl.xyml.theme.ResolvedTheme;
import space.minecraftstl.xyml.theme.ThemeBrightness;
import space.minecraftstl.xyml.theme.ThemeColor;
import space.minecraftstl.xyml.theme.ThemeColorStyle;
import space.minecraftstl.xyml.theme.ThemeContrast;

import javax.swing.UIManager;
import java.awt.Color;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    /// A resolved theme delegates system changes back to the application resolver without dropping its accent.
    @Test
    public void delegatesResolvedSystemAppearanceRefresh() {
        AtomicInteger refreshRequests = new AtomicInteger();
        ThemeColor accent = new ThemeColor("resolved", "#147D64");
        SwingThemeManager manager = new SwingThemeManager(
                resolved(accent, ThemeBrightness.LIGHT),
                new SwingDesignTokens(6),
                SystemThemeDetector.lightFallback());
        manager.setSystemThemeRefreshHandler(refreshRequests::incrementAndGet);
        manager.initialize();
        manager.update(resolved(accent, ThemeBrightness.LIGHT), new SwingDesignTokens(6), true);

        manager.refreshSystemTheme();

        assertAll(
                () -> assertEquals(ThemeMode.SYSTEM, manager.mode()),
                () -> assertEquals(1, refreshRequests.get()),
                () -> assertEquals(accent, manager.effectiveAccentColor()));
    }

    /// Resolved brightness and accent values install through FlatLaf and update without changing legacy APIs.
    @Test
    public void appliesResolvedThemeBrightnessAndAccent() {
        @Nullable Map<String, String> previousExtraDefaults = FlatLaf.getGlobalExtraDefaults();
        try {
            ThemeColor firstAccent = new ThemeColor("first", "#147D64");
            SwingThemeManager manager = new SwingThemeManager(
                    resolved(firstAccent, ThemeBrightness.DARK),
                    new SwingDesignTokens(7),
                    SystemThemeDetector.lightFallback());

            manager.initialize();

            assertAll(
                    () -> assertEquals(ThemeVariant.DARK, manager.effectiveVariant()),
                    () -> assertEquals(firstAccent, manager.effectiveAccentColor()),
                    () -> assertInstanceOf(FlatDarkLaf.class, UIManager.getLookAndFeel()),
                    () -> assertEquals(Color.decode(firstAccent.color()), UIManager.getColor("Component.accentColor")));

            ThemeColor secondAccent = new ThemeColor("second", "#E67E22");
            manager.update(resolved(secondAccent, ThemeBrightness.DARK), new SwingDesignTokens(7));

            assertAll(
                    () -> assertEquals(secondAccent, manager.effectiveAccentColor()),
                    () -> assertEquals(Color.decode(secondAccent.color()), UIManager.getColor("Component.accentColor")),
                    () -> assertEquals(secondAccent.color(),
                            FlatLaf.getGlobalExtraDefaults().get("@accentColor")));

            manager.update(ThemeMode.LIGHT, new SwingDesignTokens(7));

            assertAll(
                    () -> assertEquals(ThemeVariant.LIGHT, manager.effectiveVariant()),
                    () -> assertNull(manager.resolvedTheme()),
                    () -> assertNull(manager.effectiveAccentColor()),
                    () -> assertFalse(FlatLaf.getGlobalExtraDefaults().containsKey("@accentColor")));
        } finally {
            FlatLaf.setGlobalExtraDefaults(previousExtraDefaults != null ? previousExtraDefaults : Map.of());
            FlatLaf.setup(new FlatLightLaf());
        }
    }

    /// Creates one concrete theme with stable non-color values.
    ///
    /// @param color accent seed
    /// @param brightness concrete brightness
    /// @return resolved test theme
    private static ResolvedTheme resolved(ThemeColor color, ThemeBrightness brightness) {
        return new ResolvedTheme(color, brightness, ThemeColorStyle.FIDELITY, ThemeContrast.DEFAULT);
    }
}
