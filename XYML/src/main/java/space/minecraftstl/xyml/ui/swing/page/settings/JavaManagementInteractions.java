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
package space.minecraftstl.xyml.ui.swing.page.settings;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.awt.Component;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;

/// Isolates native Java-management interactions from the lifecycle panel that requests them.
@NotNullByDefault
interface JavaManagementInteractions {
    /// Lets the user choose one Java executable or Java home directory.
    ///
    /// @param parent dialog parent component
    /// @return selected path, or null when the chooser is cancelled
    @Nullable Path chooseLocalRuntime(Component parent);

    /// Lets the user choose one local Java installation archive.
    ///
    /// @param parent dialog parent component
    /// @return selected `.zip` or `.tar.gz` path, or null when the chooser is cancelled
    @Nullable Path chooseLocalJavaArchive(Component parent);

    /// Requests confirmation for one destructive Java-management action.
    ///
    /// @param parent dialog parent component
    /// @param message localized confirmation message
    /// @param title localized dialog title
    /// @return true when the user confirms the action
    boolean confirm(Component parent, String message, String title);

    /// Opens one directory in the platform file manager without executing a contained binary.
    ///
    /// @param directory existing directory to open
    /// @throws IOException when the platform file manager cannot open the directory
    void revealDirectory(Path directory) throws IOException;

    /// Opens one validated external Java download URI in the platform browser.
    ///
    /// @param parent owning component used for interaction context
    /// @param uri validated HTTP or HTTPS destination
    /// @throws IOException when browser integration is unavailable or rejects the URI
    void openExternalJavaDownload(Component parent, URI uri) throws IOException;
}
