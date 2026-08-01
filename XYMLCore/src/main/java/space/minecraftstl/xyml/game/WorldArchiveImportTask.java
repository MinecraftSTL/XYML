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
import space.minecraftstl.xyml.task.Task;

import java.nio.file.Path;
import java.util.Objects;

/// Offline task wrapper that imports one world archive into a managed instance's effective run directory.
@NotNullByDefault
public final class WorldArchiveImportTask extends Task<WorldArchiveImportResult> {
    /// Repository used to resolve the instance run directory at execution time.
    private final GameRepository repository;

    /// Stable managed instance identifier.
    private final GameInstanceID instanceId;

    /// Local ZIP archive to validate and extract.
    private final Path archive;

    /// Requested final world directory and stored level name.
    private final String targetName;

    /// Strict importer used by this task.
    private final WorldArchiveImporter importer;

    /// Creates a task using the launcher's default archive resource ceilings.
    ///
    /// @param repository managed game repository
    /// @param instanceId target instance identifier
    /// @param archive local world ZIP archive
    /// @param targetName final world directory and stored level name
    public WorldArchiveImportTask(
            GameRepository repository,
            GameInstanceID instanceId,
            Path archive,
            String targetName) {
        this(repository, instanceId, archive, targetName, new WorldArchiveImporter());
    }

    /// Creates a task with an explicit importer policy.
    ///
    /// @param repository managed game repository
    /// @param instanceId target instance identifier
    /// @param archive local world ZIP archive
    /// @param targetName final world directory and stored level name
    /// @param importer strict offline importer
    public WorldArchiveImportTask(
            GameRepository repository,
            GameInstanceID instanceId,
            Path archive,
            String targetName,
            WorldArchiveImporter importer) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.instanceId = Objects.requireNonNull(instanceId, "instanceId");
        this.archive = Objects.requireNonNull(archive, "archive").toAbsolutePath().normalize();
        this.targetName = requireNonBlank(targetName, "targetName");
        this.importer = Objects.requireNonNull(importer, "importer");
    }

    /// Resolves `saves` from the effective instance run directory and performs the offline import.
    ///
    /// @throws Exception when validation, extraction, NBT parsing, or atomic publication fails
    @Override
    public void execute() throws Exception {
        Path runDirectory = Objects.requireNonNull(
                repository.getRunDirectory(instanceId),
                "repository run directory");
        setResult(importer.importArchive(archive, runDirectory.resolve("saves"), targetName));
    }

    /// Rejects blank task identifiers and names.
    ///
    /// @param value candidate value
    /// @param parameterName parameter name used in diagnostics
    /// @return validated value
    private static String requireNonBlank(String value, String parameterName) {
        String checkedValue = Objects.requireNonNull(value, parameterName);
        if (checkedValue.isBlank()) {
            throw new IllegalArgumentException(parameterName + " must not be blank");
        }
        return checkedValue;
    }
}
