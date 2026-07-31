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

import java.util.Objects;

/// Complete toolkit-neutral input for resolving one selected launcher theme.
///
/// @param selectedTheme persisted requested theme reference
/// @param context current platform, locale, and operating-system brightness
/// @param userOverrides user appearance values applied after theme conditions
@NotNullByDefault
public record ThemeResolutionRequest(
        ThemeReference selectedTheme,
        ThemeResolveContext context,
        ThemeUserAppearanceOverrides userOverrides) {
    /// Validates every resolution input.
    public ThemeResolutionRequest {
        Objects.requireNonNull(selectedTheme, "selectedTheme");
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(userOverrides, "userOverrides");
    }
}
