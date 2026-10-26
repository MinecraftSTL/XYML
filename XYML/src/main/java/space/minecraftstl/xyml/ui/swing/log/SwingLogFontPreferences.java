// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.log;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import space.minecraftstl.xyml.setting.LauncherSettings;
import space.minecraftstl.xyml.setting.SettingsManager;

import java.awt.Font;

/// Resolves persisted game-log font settings to an AWT font without external resources.
@NotNullByDefault
public final class SwingLogFontPreferences {
    /// Legacy game-log font-size fallback.
    private static final float DEFAULT_FONT_SIZE = 12.0F;

    /// Prevents construction of the stateless resolver.
    private SwingLogFontPreferences() {
    }

    /// Reads the currently loaded launcher settings and resolves the game-log font.
    ///
    /// @return configured local game-log font
    static Font current() {
        LauncherSettings settings = SettingsManager.settings();
        return resolve(
                settings.logFontFamilyProperty().get(),
                settings.logFontSizeProperty().get());
    }

    /// Reads loaded settings or returns the legacy default during pre-configuration test construction.
    ///
    /// @return configured font when settings are loaded, otherwise the default monospaced font
    public static Font currentOrDefault() {
        try {
            return current();
        } catch (IllegalStateException settingsNotLoaded) {
            return resolve(null, DEFAULT_FONT_SIZE);
        }
    }

    /// Resolves nullable family and numeric size settings to a usable AWT font.
    ///
    /// @param family selected local family, or `null` for the platform monospaced family
    /// @param size configured logical-pixel size
    /// @return resolved plain log font
    static Font resolve(@Nullable String family, double size) {
        String resolvedFamily = family == null || family.isBlank() ? Font.MONOSPACED : family.trim();
        float resolvedSize = (float) size;
        if (!Float.isFinite(resolvedSize) || resolvedSize <= 0) {
            resolvedSize = DEFAULT_FONT_SIZE;
        }
        return new Font(resolvedFamily, Font.PLAIN, 1).deriveFont(resolvedSize);
    }
}
