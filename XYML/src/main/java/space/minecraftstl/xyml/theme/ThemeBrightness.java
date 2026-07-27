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
package space.minecraftstl.xyml.theme;

import org.jetbrains.annotations.NotNullByDefault;

import java.util.Locale;

/// Describes the effective light or dark appearance without depending on a UI toolkit.
@NotNullByDefault
public enum ThemeBrightness {
    /// Light surfaces and dark foreground content.
    LIGHT,

    /// Dark surfaces and light foreground content.
    DARK;

    /// Parses the historical lowercase manifest representation.
    ///
    /// @param value serialized brightness
    /// @return parsed brightness
    /// @throws IllegalArgumentException when the value is unsupported
    public static ThemeBrightness parse(String value) {
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "light" -> LIGHT;
            case "dark" -> DARK;
            default -> throw new IllegalArgumentException("Unsupported theme brightness: " + value);
        };
    }

    /// Returns the canonical manifest representation.
    ///
    /// @return lowercase serialized value
    public String serializedName() {
        return name().toLowerCase(Locale.ROOT);
    }
}
