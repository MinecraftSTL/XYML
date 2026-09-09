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
import space.minecraftstl.xyml.library.nbt.io.NBTReadReport;
import space.minecraftstl.xyml.library.nbt.io.StorageProfile;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

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

    /// Confirms a save which will strictly rewrite a source opened through tolerant recovery.
    ///
    /// Implementations must fail closed when no graphical confirmation is available. The default is deliberately
    /// `false`, so headless callers must opt into repair publication explicitly rather than accidentally replacing a
    /// damaged source. A clean report is never passed by the panel.
    ///
    /// @param currentFile current source
    /// @param report immutable diagnostics captured during open
    /// @return whether strict repair publication was explicitly approved
    default boolean confirmRepairSave(Path currentFile, NBTReadReport report) {
        Objects.requireNonNull(currentFile, "currentFile");
        Objects.requireNonNull(report, "report");
        return false;
    }

    /// Confirms a repair save while exposing the immutable storage profile for diagnostics.
    ///
    /// The two-argument method remains the compatibility hook for existing non-Swing callers;
    /// implementations which do not need region metadata may continue overriding it.
    ///
    /// @param currentFile current source
    /// @param report immutable diagnostics captured during open
    /// @param storageProfile immutable standalone or region profile
    /// @return whether strict repair publication was explicitly approved
    default boolean confirmRepairSave(Path currentFile, NBTReadReport report, StorageProfile storageProfile) {
        Objects.requireNonNull(storageProfile, "storageProfile");
        return confirmRepairSave(currentFile, report);
    }

    /// Confirms clearing the fixed compound root of one Region chunk slot.
    ///
    /// The fail-closed default keeps existing non-interactive integrations from approving a
    /// destructive chunk clear without an explicit policy.
    ///
    /// @param currentFile current Region source
    /// @param localIndex fixed chunk slot from 0 through 1023
    /// @return whether the root may be cleared
    default boolean confirmClearChunk(Path currentFile, int localIndex) {
        Objects.requireNonNull(currentFile, "currentFile");
        if (localIndex < 0 || localIndex >= 1024) {
            throw new IndexOutOfBoundsException("localIndex: " + localIndex);
        }
        return false;
    }
}
