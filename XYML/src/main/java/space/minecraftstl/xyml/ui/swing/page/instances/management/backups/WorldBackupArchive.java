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
package space.minecraftstl.xyml.ui.swing.page.instances.management.backups;

import org.jetbrains.annotations.NotNullByDefault;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;

/// Lightweight metadata for one local world backup ZIP archive.
///
/// Archive contents are deliberately not opened during indexing. Validation and decompression only
/// happen for an explicit restore request, preserving responsive initial page activation.
///
/// @param archive normalized ZIP archive location
/// @param fileName visible archive filename
/// @param sizeBytes indexed archive size in bytes
/// @param lastModified indexed archive modification instant
@NotNullByDefault
public record WorldBackupArchive(Path archive, String fileName, long sizeBytes, Instant lastModified) {
    /// Normalizes stable archive metadata and rejects impossible size values.
    ///
    /// @param archive local ZIP archive location
    /// @param fileName visible archive filename
    /// @param sizeBytes non-negative archive size
    /// @param lastModified indexed modification instant
    public WorldBackupArchive {
        archive = Objects.requireNonNull(archive, "archive").toAbsolutePath().normalize();
        fileName = Objects.requireNonNull(fileName, "fileName");
        lastModified = Objects.requireNonNull(lastModified, "lastModified");
        if (fileName.isBlank()) {
            throw new IllegalArgumentException("fileName must not be blank");
        }
        if (sizeBytes < 0L) {
            throw new IllegalArgumentException("sizeBytes must not be negative");
        }
    }

    /// Returns the concise archive label used by standard Swing list renderers.
    ///
    /// @return visible archive filename
    @Override
    public String toString() {
        return fileName;
    }
}
