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
import org.jetbrains.annotations.Nullable;
import space.minecraftstl.xyml.util.platform.OperatingSystem;

import java.util.Locale;
import java.util.Objects;

/// Environment values used to resolve conditional theme overrides.
///
/// @param brightness effective light or dark mode
/// @param os normalized operating-system token
/// @param language normalized UI language token
@NotNullByDefault
public record ThemeResolveContext(ThemeBrightness brightness, String os, String language) {
    /// Normalizes all context values.
    public ThemeResolveContext {
        Objects.requireNonNull(brightness, "brightness");
        os = normalizeToken(os, "os");
        language = normalizeToken(language, "language");
    }

    /// Creates a context for the current process platform and default locale.
    ///
    /// @param brightness effective brightness
    /// @return current context
    public static ThemeResolveContext current(ThemeBrightness brightness) {
        return new ThemeResolveContext(
                brightness,
                normalizeOperatingSystem(OperatingSystem.CURRENT_OS),
                Locale.getDefault().getLanguage());
    }

    /// Returns the context value for one supported condition key.
    ///
    /// @param key condition key
    /// @return normalized value, or `null` for an unknown key
    @Nullable String conditionValue(String key) {
        return switch (key) {
            case ThemeCondition.KEY_BRIGHTNESS -> brightness.serializedName();
            case ThemeCondition.KEY_OS -> os;
            case ThemeCondition.KEY_LANGUAGE -> language;
            default -> null;
        };
    }

    /// Converts one launcher operating-system value to the manifest token.
    private static String normalizeOperatingSystem(OperatingSystem operatingSystem) {
        return operatingSystem == OperatingSystem.UNKNOWN ? "unknown" : operatingSystem.getCheckedName();
    }

    /// Normalizes one non-empty condition token.
    private static String normalizeToken(String value, String name) {
        String normalized = Objects.requireNonNull(value, name).trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("Empty theme condition context value: " + name);
        }
        return normalized;
    }
}
