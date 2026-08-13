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
import com.formdev.flatlaf.icons.FlatCheckBoxIcon;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import space.minecraftstl.xyml.theme.ResolvedTheme;
import space.minecraftstl.xyml.theme.ThemeBrightness;
import space.minecraftstl.xyml.theme.ThemeBrightnessPreference;
import space.minecraftstl.xyml.theme.ThemeColor;
import space.minecraftstl.xyml.theme.ThemeColorStyle;
import space.minecraftstl.xyml.theme.ThemeContrast;
import space.minecraftstl.xyml.ui.swing.shell.RoundedComboBoxUI;

import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.UIManager;
import java.awt.Color;
import java.awt.Font;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests FlatLaf initialization and hot updates without creating a displayable window.
@NotNullByDefault
public final class SwingThemeManagerTest {
    /// The manager exposes a brightness-matched bundled XYML background before the first frame is created.
    @Test
    public void preparesBrightnessSpecificInitialWindowAppearance() {
        SwingThemeManager light = new SwingThemeManager(
                ThemeBrightnessPreference.LIGHT,
                new SwingDesignTokens(4),
                () -> true);
        SwingThemeManager dark = new SwingThemeManager(
                ThemeBrightnessPreference.SYSTEM,
                new SwingDesignTokens(4),
                () -> true);

        SwingBackgroundSource.ThemePackImage lightSource = assertInstanceOf(
                SwingBackgroundSource.ThemePackImage.class,
                light.windowAppearance().source());
        SwingBackgroundSource.ThemePackImage darkSource = assertInstanceOf(
                SwingBackgroundSource.ThemePackImage.class,
                dark.windowAppearance().source());
        assertAll(
                () -> assertEquals("assets/background-light.png", lightSource.resource().name()),
                () -> assertEquals("assets/background-dark.png", darkSource.resource().name()));
    }

    /// Initialization and explicit updates replace the palette and radius defaults on the EDT.
    @Test
    public void initializesAndUpdatesFlatLaf() {
        SwingThemeManager manager = new SwingThemeManager(
                ThemeBrightnessPreference.LIGHT,
                new SwingDesignTokens(4),
                SystemThemeDetector.lightFallback());

        assertNull(manager.effectiveVariant());
        manager.initialize();
        FlatCheckBoxIcon initialCheckBoxIcon = assertInstanceOf(
                FlatCheckBoxIcon.class,
                UIManager.getIcon("CheckBox.icon"));

        assertAll(
                () -> assertTrue(manager.isInitialized()),
                () -> assertEquals(ThemeVariant.LIGHT, manager.effectiveVariant()),
                () -> assertInstanceOf(FlatLightLaf.class, UIManager.getLookAndFeel()),
                () -> assertEquals(8, UIManager.getInt("Component.arc")),
                () -> assertEquals(6, UIManager.getInt("CheckBox.arc")),
                () -> assertEquals(4, UIManager.getInt("PopupMenu.borderCornerRadius")),
                () -> assertEquals(8, UIManager.getInt("List.selectionArc")),
                () -> assertEquals(RoundedComboBoxUI.class.getName(), UIManager.getString("ComboBoxUI")),
                () -> assertEquals(6, initialCheckBoxIcon.getStyleableValue("arc")));

        assertTrue(new JComboBox<>().getUI() instanceof RoundedComboBoxUI);

        manager.update(ThemeBrightnessPreference.LIGHT, new SwingDesignTokens(13));
        FlatCheckBoxIcon updatedCheckBoxIcon = assertInstanceOf(
                FlatCheckBoxIcon.class,
                UIManager.getIcon("CheckBox.icon"));

        assertAll(
                () -> assertEquals(ThemeVariant.LIGHT, manager.effectiveVariant()),
                () -> assertInstanceOf(FlatLightLaf.class, UIManager.getLookAndFeel()),
                () -> assertEquals(26, UIManager.getInt("Component.arc")),
                () -> assertEquals(6, UIManager.getInt("CheckBox.arc")),
                () -> assertEquals(13, UIManager.getInt("PopupMenu.borderCornerRadius")),
                () -> assertEquals(26, UIManager.getInt("List.selectionArc")),
                () -> assertNotSame(initialCheckBoxIcon, updatedCheckBoxIcon),
                () -> assertEquals(6, updatedCheckBoxIcon.getStyleableValue("arc")));

        manager.update(ThemeBrightnessPreference.DARK, new SwingDesignTokens(13));

        assertAll(
                () -> assertEquals(ThemeVariant.DARK, manager.effectiveVariant()),
                () -> assertInstanceOf(FlatDarkLaf.class, UIManager.getLookAndFeel()),
                () -> assertEquals(26, UIManager.getInt("Component.arc")),
                () -> assertEquals(6, UIManager.getInt("CheckBox.arc")));
    }

    /// A requested launcher family is applied before first paint and survives later look-and-feel replacement.
    @Test
    public void appliesAndRetainsLauncherFontFamily() {
        SwingThemeManager manager = new SwingThemeManager(
                ThemeBrightnessPreference.LIGHT,
                new SwingDesignTokens(4),
                SystemThemeDetector.lightFallback());
        manager.updateDefaultFontFamily(Font.MONOSPACED);
        manager.initialize();

        assertAll(
                () -> assertEquals(Font.MONOSPACED, manager.defaultFontFamily()),
                () -> assertEquals(Font.MONOSPACED, UIManager.getFont("defaultFont").getFamily()));

        manager.update(ThemeBrightnessPreference.DARK, new SwingDesignTokens(4));
        assertAll(
                () -> assertEquals(Font.MONOSPACED, manager.defaultFontFamily()),
                () -> assertEquals(Font.MONOSPACED, UIManager.getFont("defaultFont").getFamily()));

        manager.updateDefaultFontFamily(null);
        assertNull(manager.defaultFontFamily());
    }

    /// Existing derived launcher fonts change family while fixed domain fonts retain their own family and size.
    @Test
    public void replacesDerivedFontsButPreservesFixedSubtrees() {
        EdtDispatcher.executeAndWait(() -> {
            JPanel root = new JPanel();
            JLabel launcherHeading = new JLabel();
            launcherHeading.setFont(new Font(Font.DIALOG, Font.BOLD, 19));
            JLabel fixedLog = new JLabel();
            fixedLog.setFont(new Font(Font.DIALOG, Font.PLAIN, 15));
            SwingThemeManager.preserveExplicitFontFamily(fixedLog);
            root.add(launcherHeading);
            root.add(fixedLog);

            SwingThemeManager.replaceFontFamily(
                    root,
                    new Font(Font.DIALOG, Font.PLAIN, 12),
                    new Font(Font.MONOSPACED, Font.PLAIN, 12));

            assertAll(
                    () -> assertEquals(Font.MONOSPACED, launcherHeading.getFont().getFamily()),
                    () -> assertEquals(Font.BOLD, launcherHeading.getFont().getStyle()),
                    () -> assertEquals(19.0F, launcherHeading.getFont().getSize2D()),
                    () -> assertEquals(Font.DIALOG, fixedLog.getFont().getFamily()),
                    () -> assertEquals(15.0F, fixedLog.getFont().getSize2D()));
        });
    }

    /// Refreshing system mode responds to a changed platform appearance signal.
    @Test
    public void refreshesSystemAppearance() {
        AtomicBoolean dark = new AtomicBoolean();
        SwingThemeManager manager = new SwingThemeManager(
                ThemeBrightnessPreference.SYSTEM,
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
                ThemeBrightnessPreference.SYSTEM,
                new SwingDesignTokens(6),
                SystemThemeDetector.lightFallback());
        manager.setSystemThemeRefreshHandler(refreshRequests::incrementAndGet);
        manager.initialize();
        manager.update(
                resolved(accent, ThemeBrightness.LIGHT),
                new SwingDesignTokens(6),
                ThemeBrightnessPreference.SYSTEM);

        manager.refreshSystemTheme();

        assertAll(
                () -> assertEquals(
                        ThemeBrightnessPreference.SYSTEM,
                        manager.brightnessPreference()),
                () -> assertEquals(1, refreshRequests.get()),
                () -> assertEquals(accent, manager.effectiveAccentColor()));
    }

    /// Resolved brightness and accent values install through FlatLaf and update through current APIs.
    @Test
    public void appliesResolvedThemeBrightnessAndAccent() {
        @Nullable Map<String, String> previousExtraDefaults = FlatLaf.getGlobalExtraDefaults();
        try {
            ThemeColor firstAccent = new ThemeColor("first", "#147D64");
            SwingThemeManager manager = new SwingThemeManager(
                    ThemeBrightnessPreference.DARK,
                    new SwingDesignTokens(7),
                    SystemThemeDetector.lightFallback());

            manager.initialize();
            manager.update(
                    resolved(firstAccent, ThemeBrightness.DARK),
                    new SwingDesignTokens(7),
                    ThemeBrightnessPreference.DARK);

            assertAll(
                    () -> assertEquals(ThemeVariant.DARK, manager.effectiveVariant()),
                    () -> assertEquals(firstAccent, manager.effectiveAccentColor()),
                    () -> assertInstanceOf(FlatDarkLaf.class, UIManager.getLookAndFeel()),
                    () -> assertEquals(Color.decode(firstAccent.color()), UIManager.getColor("Component.accentColor")));

            ThemeColor secondAccent = new ThemeColor("second", "#E67E22");
            manager.update(
                    resolved(secondAccent, ThemeBrightness.DARK),
                    new SwingDesignTokens(7),
                    ThemeBrightnessPreference.DARK);

            assertAll(
                    () -> assertEquals(secondAccent, manager.effectiveAccentColor()),
                    () -> assertEquals(Color.decode(secondAccent.color()), UIManager.getColor("Component.accentColor")),
                    () -> assertEquals(secondAccent.color(),
                            FlatLaf.getGlobalExtraDefaults().get("@accentColor")));

            manager.update(ThemeBrightnessPreference.LIGHT, new SwingDesignTokens(7));

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
        return new ResolvedTheme(color, brightness, ThemeColorStyle.FIDELITY, ThemeContrast.STANDARD);
    }
}
