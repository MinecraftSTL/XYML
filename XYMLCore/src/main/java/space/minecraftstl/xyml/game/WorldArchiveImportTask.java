// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.game;

import org.jetbrains.annotations.NotNullByDefault;
import space.minecraftstl.xyml.task.Task;
import space.minecraftstl.xyml.task.TaskResource;

import java.nio.file.Path;
import java.util.Objects;

/// Offline task wrapper that imports one world archive into a managed instance's effective run directory.
@NotNullByDefault
public final class WorldArchiveImportTask extends Task<WorldArchiveImportResult> {
    /// Local ZIP archive to validate and extract.
    private final Path archive;

    /// Stable target instance saves directory.
    private final Path savesDirectory;

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
        GameRepository checkedRepository = Objects.requireNonNull(repository, "repository");
        GameInstanceID checkedInstanceId = Objects.requireNonNull(instanceId, "instanceId");
        this.archive = Objects.requireNonNull(archive, "archive").toAbsolutePath().normalize();
        Path runDirectory = Objects.requireNonNull(
                        checkedRepository.getRunDirectory(checkedInstanceId),
                        "repository run directory")
                .toAbsolutePath()
                .normalize();
        this.savesDirectory = runDirectory.resolve("saves");
        this.targetName = requireNonBlank(targetName, "targetName");
        this.importer = Objects.requireNonNull(importer, "importer");
        setResources(TaskResource.gameInstance(runDirectory), TaskResource.archive(this.archive));
    }

    /// Imports into the effective instance `saves` directory captured at task creation.
    ///
    /// @throws Exception when validation, extraction, NBT parsing, or atomic publication fails
    @Override
    public void execute() throws Exception {
        setResult(importer.importArchive(archive, savesDirectory, targetName));
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
