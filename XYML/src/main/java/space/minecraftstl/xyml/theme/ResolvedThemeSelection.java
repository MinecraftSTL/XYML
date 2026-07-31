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

/// Effective theme selection including whether a missing persisted reference required fallback.
///
/// @param requestedReference persisted requested reference
/// @param effectiveReference reference whose declaration was actually resolved
/// @param themePackage validated package owning the effective declaration and its assets
/// @param appearance fully merged theme appearance before user overrides
/// @param theme concrete appearance after theme conditions and user overrides
/// @param fallbackUsed whether the requested reference was unavailable
@NotNullByDefault
public record ResolvedThemeSelection(
        ThemeReference requestedReference,
        ThemeReference effectiveReference,
        ThemePackPackage themePackage,
        ThemeAppearance appearance,
        ResolvedTheme theme,
        boolean fallbackUsed) {
    /// Validates every resolved selection value.
    public ResolvedThemeSelection {
        Objects.requireNonNull(requestedReference, "requestedReference");
        Objects.requireNonNull(effectiveReference, "effectiveReference");
        Objects.requireNonNull(themePackage, "themePackage");
        Objects.requireNonNull(appearance, "appearance");
        Objects.requireNonNull(theme, "theme");
        if (!themePackage.manifest().id().equals(effectiveReference.packId())) {
            throw new IllegalArgumentException("Effective theme package does not own the effective reference");
        }
        if (fallbackUsed == requestedReference.equals(effectiveReference)) {
            throw new IllegalArgumentException("Fallback flag must match the effective theme reference");
        }
    }
}
