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
import java.nio.file.Path;

/// Separates native directory-selection and confirmation UI from game-directory state changes.
@NotNullByDefault
interface GameDirectoryManagementInteraction {
    /// Opens a native directory chooser on the EDT.
    ///
    /// @param owner chooser parent component
    /// @param initialDirectory suggested initial directory, or `null` when no usable suggestion is available
    /// @return chosen directory, or `null` when the chooser is cancelled
    @Nullable Path chooseDirectory(Component owner, @Nullable Path initialDirectory);

    /// Confirms backup and overwrite of a read-only game-directory settings file.
    ///
    /// @param owner confirmation parent component
    /// @return whether the protected mutation may continue
    boolean confirmReadOnlyOverwrite(Component owner);

    /// Confirms permanent removal of one directory configuration entry.
    ///
    /// @param owner confirmation parent component
    /// @param entry directory being removed
    /// @return whether the removal may continue
    boolean confirmRemoval(Component owner, GameDirectoryManagementEntry entry);

    /// Shows one terminal management failure.
    ///
    /// @param owner dialog parent component
    /// @param detail localized or diagnostic failure detail
    void showFailure(Component owner, String detail);
}
