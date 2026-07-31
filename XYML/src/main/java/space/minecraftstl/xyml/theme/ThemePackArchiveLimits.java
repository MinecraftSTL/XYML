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

/// Resource ceilings applied to untrusted theme-pack archives and exports.
///
/// @param maximumEntryCount maximum central-directory entries
/// @param maximumSingleAssetBytes maximum expanded bytes in one asset
/// @param maximumExpandedBytes maximum aggregate expanded bytes
/// @param maximumManifestBytes maximum UTF-8 manifest bytes
/// @param maximumImageEdge maximum decoded image width or height
/// @param maximumImagePixels maximum decoded image pixel area
@NotNullByDefault
public record ThemePackArchiveLimits(
        int maximumEntryCount,
        long maximumSingleAssetBytes,
        long maximumExpandedBytes,
        int maximumManifestBytes,
        int maximumImageEdge,
        long maximumImagePixels) {
    /// Validates internally consistent positive limits.
    public ThemePackArchiveLimits {
        if (maximumEntryCount <= 0
                || maximumSingleAssetBytes <= 0L
                || maximumExpandedBytes < maximumSingleAssetBytes
                || maximumManifestBytes <= 0
                || maximumManifestBytes > maximumSingleAssetBytes
                || maximumImageEdge <= 0
                || maximumImagePixels <= 0L) {
            throw new IllegalArgumentException("Invalid theme-pack archive limits");
        }
    }

    /// Returns conservative launcher defaults for small appearance packages.
    ///
    /// @return default limits
    public static ThemePackArchiveLimits launcherDefaults() {
        return new ThemePackArchiveLimits(
                512,
                16L * 1024L * 1024L,
                64L * 1024L * 1024L,
                256 * 1024,
                8_192,
                16L * 1024L * 1024L);
    }
}
