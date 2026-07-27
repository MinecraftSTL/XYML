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
import space.minecraftstl.xyml.setting.VersionIconType;

import java.io.IOException;
import java.nio.file.Path;

/// Provides toolkit-neutral instance icon state and repository mutations to the Swing overview.
///
/// Loading and mutation methods run on the overview's caller-owned background executor. Change
/// publication runs on the EDT after a mutation has completed successfully.
@NotNullByDefault
interface InstanceIconStore {
    /// Loads the currently persisted built-in type and optional custom-image path.
    ///
    /// @return complete icon state for preview rendering
    Snapshot load();

    /// Removes any custom image and persists one bundled icon type.
    ///
    /// @param iconType one of the fourteen independently selectable bundled icon types
    /// @throws IOException when the repository cannot complete the file mutation
    void selectBuiltIn(VersionIconType iconType) throws IOException;

    /// Copies one custom image and restores the default built-in fallback type.
    ///
    /// @param sourceImage local image selected by the user
    /// @throws IOException when the repository cannot copy the custom image
    void selectCustom(Path sourceImage) throws IOException;

    /// Removes every persisted custom-image variant while retaining the built-in fallback type.
    ///
    /// @throws IOException when the repository cannot remove a custom image
    void deleteCustom() throws IOException;

    /// Publishes one successful icon transition to repository listeners.
    ///
    /// @param source object responsible for the transition
    void publishChanged(Object source);

    /// Immutable icon state loaded outside the EDT.
    ///
    /// @param builtInType persisted bundled fallback type
    /// @param customImage existing custom-image path, or `null` when the bundled image is active
    @NotNullByDefault
    record Snapshot(VersionIconType builtInType, @Nullable Path customImage) {
    }
}
