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
package space.minecraftstl.xyml.ui.swing.page.nbt;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.nio.file.Path;
import java.util.List;

/// Toolkit-neutral boundary for file selection, drop choice, and destructive replacement prompts.
@NotNullByDefault
public interface NBTEditorInteractions {
    /// Selects one candidate NBT source without reading it.
    ///
    /// @param currentFile current source, or `null` before a successful open
    /// @return selected path, or `null` when cancelled
    @Nullable Path chooseFile(@Nullable Path currentFile);

    /// Selects one path from an immutable drop payload without reading it.
    ///
    /// @param candidates normalized lexical candidate paths
    /// @return accepted source, or `null` when the transfer shape is unsupported
    @Nullable Path chooseDroppedFile(@Unmodifiable List<Path> candidates);

    /// Confirms replacing a dirty in-memory document.
    ///
    /// @param currentFile current dirty source
    /// @return whether unsaved edits may be discarded
    boolean confirmDiscardChanges(Path currentFile);
}
