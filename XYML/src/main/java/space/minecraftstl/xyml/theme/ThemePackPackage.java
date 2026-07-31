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

/// Common resource boundary for bundled and safely installed theme packs.
@NotNullByDefault
public sealed interface ThemePackPackage permits BuiltinThemePack, InstalledThemePack {
    /// Returns the validated manifest.
    ///
    /// @return package manifest
    ThemePackManifest manifest();

    /// Resolves one normalized theme-pack asset without opening it on the calling thread.
    ///
    /// @param entryName normalized referenced asset
    /// @return reopenable resource
    ThemePackResource asset(String entryName);

    /// Returns a persisted reference for one declared theme.
    ///
    /// @param theme selected theme
    /// @return stable selection reference
    /// @throws IllegalArgumentException when the theme is not declared by this package
    default ThemeReference referenceFor(Theme theme) {
        Objects.requireNonNull(theme, "theme");
        if (!manifest().themes().contains(theme)) {
            throw new IllegalArgumentException("Theme does not belong to pack " + manifest().id());
        }
        return new ThemeReference(manifest().id(), theme.id());
    }
}
