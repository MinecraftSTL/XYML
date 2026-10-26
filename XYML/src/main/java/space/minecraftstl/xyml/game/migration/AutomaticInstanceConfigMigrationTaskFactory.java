// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.game.migration;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import space.minecraftstl.xyml.game.GameInstanceID;
import space.minecraftstl.xyml.game.XYMLGameRepository;
import space.minecraftstl.xyml.setting.GameSettingsPresetID;
import space.minecraftstl.xyml.task.Task;

import java.nio.file.Path;
import java.util.Map;

/// Creates automatic configuration-migration tasks at the two supported isolation transitions.
@NotNullByDefault
public interface AutomaticInstanceConfigMigrationTaskFactory {
    /// Creates a non-replacing migration after a normal installation has refreshed its repository.
    ///
    /// @param repository target repository
    /// @param instanceId newly installed instance
    /// @return unstarted migration task, or a completed no-op when migration is disabled or the instance is not isolated
    Task<InstanceConfigMigrationResult> createAfterInstall(
            XYMLGameRepository repository,
            GameInstanceID instanceId);

    /// Creates a non-replacing migration before an existing instance persists its first isolation override.
    ///
    /// @param repository target repository
    /// @param instanceId existing instance being isolated
    /// @param targetDirectory candidate isolated running directory
    /// @return unstarted migration task, or a completed no-op when migration is disabled
    Task<InstanceConfigMigrationResult> createBeforeIsolation(
            XYMLGameRepository repository,
            GameInstanceID instanceId,
            Path targetDirectory);

    /// Creates migration with the unsaved parent-preset selection without persisting that selection first.
    ///
    /// @param repository target repository
    /// @param instanceId existing instance being isolated
    /// @param targetDirectory candidate isolated running directory
    /// @param parentPresetId selected preset ID, or null for the launcher default
    /// @return unstarted migration task
    default Task<InstanceConfigMigrationResult> createBeforeIsolation(
            XYMLGameRepository repository,
            GameInstanceID instanceId,
            Path targetDirectory,
            @Nullable GameSettingsPresetID parentPresetId) {
        return createBeforeIsolation(repository, instanceId, targetDirectory);
    }

    /// Returns a factory that always performs a successful no-op.
    ///
    /// @return disabled automatic migration factory
    static AutomaticInstanceConfigMigrationTaskFactory disabled() {
        return new AutomaticInstanceConfigMigrationTaskFactory() {
            /// Returns an empty completed result.
            @Override
            public Task<InstanceConfigMigrationResult> createAfterInstall(
                    XYMLGameRepository repository,
                    GameInstanceID instanceId) {
                return Task.completed(new InstanceConfigMigrationResult(Map.of()));
            }

            /// Returns an empty completed result.
            @Override
            public Task<InstanceConfigMigrationResult> createBeforeIsolation(
                    XYMLGameRepository repository,
                    GameInstanceID instanceId,
                    Path targetDirectory) {
                return Task.completed(new InstanceConfigMigrationResult(Map.of()));
            }
        };
    }
}
