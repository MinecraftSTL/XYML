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
