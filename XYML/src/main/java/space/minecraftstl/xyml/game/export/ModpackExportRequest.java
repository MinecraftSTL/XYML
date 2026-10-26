// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.game.export;

import org.jetbrains.annotations.NotNullByDefault;
import space.minecraftstl.xyml.game.GameInstanceID;

import java.nio.file.Path;
import java.util.Objects;

/// Immutable request to export one installed instance as a modpack archive.
///
/// @param format destination archive format
/// @param instanceId repository instance identifier
/// @param metadata immutable manifest metadata
/// @param fileSelection immutable non-empty run-directory selection
/// @param outputFile requested final archive path
@NotNullByDefault
public record ModpackExportRequest(
        ModpackExportFormat format,
        GameInstanceID instanceId,
        ModpackExportMetadata metadata,
        ModpackExportFileSelection fileSelection,
        Path outputFile) {

    /// Validates object components and normalizes the destination to an absolute path.
    public ModpackExportRequest {
        format = Objects.requireNonNull(format, "format");
        instanceId = Objects.requireNonNull(instanceId, "instanceId");
        metadata = Objects.requireNonNull(metadata, "metadata");
        fileSelection = Objects.requireNonNull(fileSelection, "fileSelection");
        outputFile = Objects.requireNonNull(outputFile, "outputFile").toAbsolutePath().normalize();
        if (outputFile.getFileName() == null) {
            throw new IllegalArgumentException("outputFile must name an archive file");
        }
    }

}
