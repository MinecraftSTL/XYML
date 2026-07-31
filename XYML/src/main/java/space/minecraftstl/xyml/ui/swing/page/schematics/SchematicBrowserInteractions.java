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
package space.minecraftstl.xyml.ui.swing.page.schematics;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.awt.Component;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletionStage;

/// Owns schematic browser interactions that cross the panel, dialog, and desktop boundary.
///
/// Dialog methods must be called on the Swing event-dispatch thread. [#reveal] may be called
/// from any thread and reports desktop work asynchronously without blocking the caller.
@NotNullByDefault
public interface SchematicBrowserInteractions {
    /// Opens a multi-selection Litematic chooser on the event-dispatch thread.
    ///
    /// @param owner dialog owner
    /// @param currentDirectory browser directory used as the chooser location
    /// @return immutable selected source paths, or an empty list after cancellation
    @Unmodifiable List<Path> chooseImportFiles(Component owner, Path currentDirectory);

    /// Prompts for one child-directory name on the event-dispatch thread.
    ///
    /// @param owner dialog owner
    /// @return entered text, or null after cancellation
    @Nullable String promptDirectoryName(Component owner);

    /// Confirms deletion of one browser row on the event-dispatch thread.
    ///
    /// @param owner dialog owner
    /// @param target exact row proposed for deletion
    /// @return whether deletion was explicitly confirmed
    boolean confirmDelete(Component owner, SchematicBrowserItem target);

    /// Schedules revealing one row through the platform desktop integration.
    ///
    /// @param target exact row to reveal
    /// @return stage completed on success or failed with the original desktop or executor error
    CompletionStage<@Nullable Void> reveal(SchematicBrowserItem target);

    /// Shows one failure message on the event-dispatch thread.
    ///
    /// @param owner dialog owner
    /// @param title localized failure title
    /// @param detail failure detail
    void showFailure(Component owner, String title, String detail);
}
