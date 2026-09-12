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
import space.minecraftstl.xyml.setting.GameInstanceIconType;
import space.minecraftstl.xyml.task.Task;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.Executor;

/// Provides toolkit-neutral instance icon state and repository mutations to the Swing overview.
///
/// Loading and mutation methods run on the overview's caller-owned background executor. Change
/// publication runs on the EDT after a mutation has completed successfully.
@NotNullByDefault
interface InstanceIconStore {
    /// Loads the effective built-in type and optional custom-image path.
    ///
    /// @return complete icon state for preview rendering
    Snapshot load();

    /// Removes any custom image and persists one bundled icon type.
    ///
    /// @param iconType one of the fourteen independently selectable bundled icon types
    /// @throws IOException when the repository cannot complete the file mutation
    void selectBuiltIn(GameInstanceIconType iconType) throws IOException;

    /// Creates a resource-aware task for selecting a bundled icon.
    ///
    /// The default keeps test and non-repository stores source-compatible; repository-backed implementations should
    /// override it with precise instance and configuration resources.
    ///
    /// @param iconType one of the bundled icon types
    /// @param executor executor used for the mutation body
    /// @return deferred icon mutation task
    default Task<?> selectBuiltInTask(GameInstanceIconType iconType, Executor executor) {
        Objects.requireNonNull(iconType, "iconType");
        return Task.runAsync(Objects.requireNonNull(executor, "executor"), () -> selectBuiltIn(iconType));
    }

    /// Copies one custom image and restores the default built-in fallback type.
    ///
    /// @param sourceImage local image selected by the user
    /// @throws IOException when the repository cannot copy the custom image
    void selectCustom(Path sourceImage) throws IOException;

    /// Creates a resource-aware task for selecting a custom icon.
    ///
    /// @param sourceImage local image selected by the user
    /// @param executor executor used for the mutation body
    /// @return deferred icon mutation task
    default Task<?> selectCustomTask(Path sourceImage, Executor executor) {
        Objects.requireNonNull(sourceImage, "sourceImage");
        return Task.runAsync(
                Objects.requireNonNull(executor, "executor"),
                () -> selectCustom(sourceImage));
    }

    /// Removes every persisted custom-image variant while retaining the built-in fallback type.
    ///
    /// @throws IOException when the repository cannot remove a custom image
    void deleteCustom() throws IOException;

    /// Creates a resource-aware task for deleting custom icon files.
    ///
    /// @param executor executor used for the mutation body
    /// @return deferred icon mutation task
    default Task<?> deleteCustomTask(Executor executor) {
        return Task.runAsync(Objects.requireNonNull(executor, "executor"), this::deleteCustom);
    }

    /// Publishes one successful icon transition to repository listeners.
    ///
    /// @param source object responsible for the transition
    void publishChanged(Object source);

    /// Immutable icon state loaded outside the EDT.
    ///
    /// @param builtInType effective bundled type after automatic `DEFAULT` resolution
    /// @param customImage existing custom-image path, or `null` when the bundled image is active
    @NotNullByDefault
    record Snapshot(GameInstanceIconType builtInType, @Nullable Path customImage) {
    }
}
