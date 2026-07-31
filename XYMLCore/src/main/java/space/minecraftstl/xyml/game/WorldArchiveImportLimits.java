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
package space.minecraftstl.xyml.game;

import org.jetbrains.annotations.NotNullByDefault;

/// Resource ceilings applied before and during a local world archive import.
///
/// The launcher defaults accommodate large long-lived worlds while bounding archive bombs. Callers
/// that intentionally support larger worlds can supply an explicit policy without weakening path
/// validation.
///
/// @param maximumEntryCount maximum archive entries, including directories
/// @param maximumExpandedBytes maximum total expanded regular-file bytes
/// @param maximumSingleFileBytes maximum expanded bytes for one regular file
@NotNullByDefault
public record WorldArchiveImportLimits(
        int maximumEntryCount,
        long maximumExpandedBytes,
        long maximumSingleFileBytes) {
    /// Validates positive, internally consistent import ceilings.
    public WorldArchiveImportLimits {
        if (maximumEntryCount <= 0) {
            throw new IllegalArgumentException("maximumEntryCount must be positive");
        }
        if (maximumExpandedBytes <= 0L) {
            throw new IllegalArgumentException("maximumExpandedBytes must be positive");
        }
        if (maximumSingleFileBytes <= 0L) {
            throw new IllegalArgumentException("maximumSingleFileBytes must be positive");
        }
        if (maximumSingleFileBytes > maximumExpandedBytes) {
            throw new IllegalArgumentException("maximumSingleFileBytes must not exceed maximumExpandedBytes");
        }
    }

    /// Returns the launcher policy for ordinary local and downloaded Minecraft worlds.
    ///
    /// @return immutable default import ceilings
    public static WorldArchiveImportLimits launcherDefaults() {
        return new WorldArchiveImportLimits(250_000, 64L * 1024L * 1024L * 1024L, 8L * 1024L * 1024L * 1024L);
    }
}
