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
package space.minecraftstl.xyml.game.migration;

import org.jetbrains.annotations.NotNullByDefault;
import space.minecraftstl.xyml.game.GameInstanceID;
import space.minecraftstl.xyml.game.XYMLGameRepository;
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
