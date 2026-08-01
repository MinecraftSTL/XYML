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
package space.minecraftstl.xyml.ui.swing.page.resourcepacks;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.awt.Component;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletionStage;

/// Owns installed-resource-pack interactions that cross dialog, desktop, and file-system boundaries.
///
/// Dialog methods must be called on the Swing event-dispatch thread. Desktop and file-system
/// methods may be called from any thread and complete asynchronously without blocking the caller.
@NotNullByDefault
public interface ResourcePackCatalogInteractions {
    /// Opens a multi-selection ZIP chooser on the event-dispatch thread.
    ///
    /// @param owner dialog owner
    /// @param currentDirectory installed resource-pack directory used as the chooser location
    /// @return immutable selected source paths, or an empty list after cancellation
    @Unmodifiable List<Path> chooseImportFiles(Component owner, Path currentDirectory);

    /// Confirms enabling one incompatible resource pack on the event-dispatch thread.
    ///
    /// @param owner dialog owner
    /// @param target exact incompatible pack proposed for enabling
    /// @return whether enabling was explicitly confirmed
    boolean confirmEnableIncompatible(Component owner, ResourcePackCatalogItem target);

    /// Confirms enabling a selected resource-pack batch on the event-dispatch thread.
    ///
    /// The batch warning is intentionally generic because selected off-screen rows remain shallow
    /// paths and are not parsed merely to decide whether the confirmation should be shown.
    ///
    /// @param owner dialog owner
    /// @param selectedCount positive selected path count
    /// @return whether batch enabling was explicitly confirmed
    boolean confirmEnableSelected(Component owner, int selectedCount);

    /// Confirms permanently deleting one installed resource pack on the event-dispatch thread.
    ///
    /// @param owner dialog owner
    /// @param target exact pack proposed for deletion
    /// @return whether permanent deletion was explicitly confirmed
    boolean confirmDelete(Component owner, ResourcePackCatalogItem target);

    /// Confirms permanently deleting a selected resource-pack batch on the event-dispatch thread.
    ///
    /// @param owner dialog owner
    /// @param selectedCount positive selected path count
    /// @return whether batch deletion was explicitly confirmed
    boolean confirmDeleteSelected(Component owner, int selectedCount);

    /// Schedules revealing one installed resource pack through platform desktop integration.
    ///
    /// @param target exact pack to reveal
    /// @return stage completed on success or failed with the original desktop or executor error
    CompletionStage<@Nullable Void> reveal(ResourcePackCatalogItem target);

    /// Schedules creation, when needed, and opening of the installed resource-pack directory.
    ///
    /// @param resourcePackDirectory directory to ensure and open
    /// @return stage completed on success or failed with the original file, desktop, or executor error
    CompletionStage<@Nullable Void> openResourcePackDirectory(Path resourcePackDirectory);

    /// Shows one failure message on the event-dispatch thread.
    ///
    /// @param owner dialog owner
    /// @param title localized failure title
    /// @param detail failure detail
    void showFailure(Component owner, String title, String detail);
}
