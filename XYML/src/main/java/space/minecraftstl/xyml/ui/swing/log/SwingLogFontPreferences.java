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
import space.minecraftstl.xyml.setting.LauncherSettings;
import space.minecraftstl.xyml.setting.SettingsManager;

import java.awt.Font;

/// Resolves persisted game-log font settings to an AWT font without external resources.
@NotNullByDefault
final class SwingLogFontPreferences {
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
    static Font currentOrDefault() {
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
