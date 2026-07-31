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

import java.nio.file.Path;
import java.util.Objects;

/// Immutable description of one safely published world archive import.
///
/// @param worldDirectory final direct child of the instance `saves` directory
/// @param extractedFileCount number of regular files extracted from the archive
/// @param extractedBytes total expanded bytes written before publication
@NotNullByDefault
public record WorldArchiveImportResult(
        Path worldDirectory,
        int extractedFileCount,
        long extractedBytes) {
    /// Normalizes the published path and validates non-negative extraction metrics.
    public WorldArchiveImportResult {
        worldDirectory = Objects.requireNonNull(worldDirectory, "worldDirectory")
                .toAbsolutePath()
                .normalize();
        if (extractedFileCount < 0) {
            throw new IllegalArgumentException("extractedFileCount must not be negative");
        }
        if (extractedBytes < 0L) {
            throw new IllegalArgumentException("extractedBytes must not be negative");
        }
    }
}
