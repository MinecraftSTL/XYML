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
package space.minecraftstl.xyml.ui.swing.page.settings;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import space.minecraftstl.xyml.ui.swing.FontAntialiasingMode;

import java.util.Objects;

/// Immutable global font preferences displayed by [FontSettingsPanel].
///
/// @param launcherFontFamily launcher UI family, or `null` for the active look-and-feel default
/// @param logFontFamily game-log family, or `null` for the platform monospaced family
/// @param logFontSize positive finite game-log font size
/// @param antialiasingMode persisted text antialiasing preference
/// @param launcherSettingsWritable whether launcher and log font values can be persisted
/// @param userSettingsWritable whether the per-user antialiasing value can be persisted
@NotNullByDefault
record FontSettingsSnapshot(
        @Nullable String launcherFontFamily,
        @Nullable String logFontFamily,
        double logFontSize,
        FontAntialiasingMode antialiasingMode,
        boolean launcherSettingsWritable,
        boolean userSettingsWritable) {
    /// Validates non-null and numeric invariants.
    FontSettingsSnapshot {
        Objects.requireNonNull(antialiasingMode, "antialiasingMode");
        if (!Double.isFinite(logFontSize) || logFontSize <= 0) {
            throw new IllegalArgumentException("logFontSize must be positive and finite");
        }
    }
}
