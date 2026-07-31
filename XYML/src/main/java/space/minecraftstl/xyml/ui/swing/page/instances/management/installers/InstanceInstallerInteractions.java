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
package space.minecraftstl.xyml.ui.swing.page.instances.management.installers;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.awt.Component;
import java.nio.file.Path;

/// Separates native local-file and destructive-action dialogs from installer management behavior.
///
/// All operations are called on the Swing event-dispatch thread. A null installer result represents
/// user cancellation and must not cause the panel to construct or start a Core task.
@NotNullByDefault
public interface InstanceInstallerInteractions {
    /// Opens a native chooser restricted to supported local installer-file extensions.
    ///
    /// @param owner native dialog owner
    /// @return chosen local `.jar` or `.exe` file, or null when the chooser is cancelled
    @Nullable Path chooseOfflineInstaller(Component owner);

    /// Requests explicit confirmation before removing one structurally clear library.
    ///
    /// The panel validates its current recognized or third-party row is structurally clear before invoking this
    /// callback, so no external or uncertain library identifier reaches this destructive boundary.
    ///
    /// @param owner native dialog owner
    /// @param libraryId exact clear Core library identifier proposed for removal
    /// @return whether the mutation is approved
    boolean confirmRemoval(Component owner, String libraryId);

    /// Shows a concise terminal operation failure.
    ///
    /// @param owner native dialog owner
    /// @param title visible failure title
    /// @param detail non-blank failure detail
    void showFailure(Component owner, String title, String detail);
}
