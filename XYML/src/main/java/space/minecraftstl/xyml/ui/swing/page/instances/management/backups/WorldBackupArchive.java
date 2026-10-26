// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.instances.management.backups;

import org.jetbrains.annotations.NotNullByDefault;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;

/// Lightweight metadata for one local world backup ZIP archive.
///
/// Archive contents are not opened during indexing. Optional thumbnails are decoded separately on workers;
/// full world validation and extraction remain explicit restore work.
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
