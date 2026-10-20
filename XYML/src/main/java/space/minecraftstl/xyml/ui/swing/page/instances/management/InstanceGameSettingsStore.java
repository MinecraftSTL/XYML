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
package space.minecraftstl.xyml.ui.swing.page.instances.management;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import space.minecraftstl.xyml.task.Task;

import java.util.Objects;
import java.util.concurrent.Executor;

/// Reads and persists the complete editable launch-settings surface for one game instance.
///
/// Implementations own the mapping between instance inheritance flags and their durable configuration format. The
/// Swing page deliberately receives only snapshots so it does not need direct access to observable model properties.
@NotNullByDefault
public interface InstanceGameSettingsStore {
    /// Returns the forced instance directory for content whose format requires isolation.
    ///
    /// @return forced working-directory text, or `null` when users may configure isolation
    default @Nullable String forcedRunningDirectory() {
        return null;
    }

    /// Returns the latest effective values and local-override state.
    ///
    /// @return current editable settings snapshot
    InstanceGameSettingsSnapshot snapshot();

    /// Resolves a candidate snapshot against its selected parent preset without persisting it.
    ///
    /// Implementations that do not support parent presets may return the candidate unchanged.
    ///
    /// @param candidate complete unsaved editor state
    /// @return effective preview retaining the candidate's local overrides
    default InstanceGameSettingsSnapshot preview(InstanceGameSettingsSnapshot candidate) {
        return candidate;
    }

    /// Persists one complete edited snapshot for the represented instance.
    ///
    /// @param snapshot validated values and inheritance choices to persist
    void save(InstanceGameSettingsSnapshot snapshot);

    /// Creates a deferred task that persists one complete edited snapshot.
    ///
    /// The default implementation keeps non-repository stores source-compatible. Repository-backed stores should
    /// override this method to declare every instance and configuration resource occupied by the write.
    ///
    /// @param snapshot validated values and inheritance choices to persist
    /// @param executor executor used for the blocking persistence action
    /// @return an unstarted task that performs the same operation as [#save(InstanceGameSettingsSnapshot)]
    default Task<@Nullable Void> saveTask(InstanceGameSettingsSnapshot snapshot, Executor executor) {
        InstanceGameSettingsSnapshot checkedSnapshot = Objects.requireNonNull(snapshot, "snapshot");
        return Task.runAsync(
                "Save instance game settings",
                Objects.requireNonNull(executor, "executor"),
                () -> save(checkedSnapshot));
    }

    /// Returns whether a newer read-only settings file can be backed up and overwritten.
    ///
    /// @return whether recovery is available
    default boolean canForceOverwrite() {
        return false;
    }

    /// Backs up and overwrites a read-only settings file with the currently loaded representation.
    ///
    /// @throws IllegalStateException when this store cannot recover a read-only file
    default void forceOverwrite() {
        throw new IllegalStateException("Instance game settings cannot be overwritten");
    }

    /// Creates a deferred task that backs up and overwrites a read-only settings file.
    ///
    /// The default implementation keeps non-repository stores source-compatible. Repository-backed stores should
    /// override this method to declare every instance and configuration resource occupied by the recovery write.
    ///
    /// @param executor executor used for the blocking recovery action
    /// @return an unstarted task that performs the same operation as [#forceOverwrite()]
    default Task<@Nullable Void> forceOverwriteTask(Executor executor) {
        return Task.runAsync(
                "Force overwrite instance game settings",
                Objects.requireNonNull(executor, "executor"),
                this::forceOverwrite);
    }
}
