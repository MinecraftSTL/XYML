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

import java.nio.file.Path;
import java.util.Objects;

/// Immutable descriptor for one validated theme pack installed as a local directory.
///
/// @param directory normalized installation directory
/// @param manifest parsed manifest
@NotNullByDefault
public record InstalledThemePack(Path directory, ThemePackManifest manifest) implements ThemePackPackage {
    /// Normalizes the directory and validates the manifest.
    public InstalledThemePack {
        directory = Objects.requireNonNull(directory, "directory").toAbsolutePath().normalize();
        Objects.requireNonNull(manifest, "manifest");
    }

    /// Creates a contained resource for one normalized referenced asset.
    ///
    /// Opening the returned resource repeats no-follow checks and must happen on background work.
    ///
    /// @param entryName asset entry name
    /// @return contained resource
    public ThemePackResource asset(String entryName) {
        return new ThemePackResource.ContainedFile(directory, entryName);
    }

}
