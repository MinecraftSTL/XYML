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

import java.util.Locale;

/// Supported launcher text antialiasing preferences and their persisted legacy identifiers.
@NotNullByDefault
public enum FontAntialiasingMode {
    /// Lets AWT choose the platform text-rendering strategy.
    AUTO(null),

    /// Requests sub-pixel LCD text rendering.
    LCD("lcd"),

    /// Requests grayscale text rendering.
    GRAY("gray");

    /// Legacy-compatible persisted identifier, or `null` for automatic selection.
    private final @Nullable String persistedValue;

    /// Creates one mode from its persisted identifier.
    ///
    /// @param persistedValue legacy-compatible identifier, or `null` for automatic selection
    FontAntialiasingMode(@Nullable String persistedValue) {
        this.persistedValue = persistedValue;
    }

    /// Returns the value written to `user-settings.json`.
    ///
    /// @return persisted identifier, or `null` for automatic selection
    public @Nullable String persistedValue() {
        return persistedValue;
    }

    /// Maps a persisted value without rewriting unknown future values during a read.
    ///
    /// @param persistedValue stored identifier, or `null`
    /// @return corresponding supported mode, defaulting to automatic selection
    public static FontAntialiasingMode fromPersistedValue(@Nullable String persistedValue) {
        if (persistedValue == null) {
            return AUTO;
        }
        return switch (persistedValue.trim().toLowerCase(Locale.ROOT)) {
            case "lcd" -> LCD;
            case "gray" -> GRAY;
            default -> AUTO;
        };
    }
}
