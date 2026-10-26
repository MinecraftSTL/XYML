// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

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
