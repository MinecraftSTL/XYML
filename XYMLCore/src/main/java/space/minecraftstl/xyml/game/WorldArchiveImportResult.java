// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

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
