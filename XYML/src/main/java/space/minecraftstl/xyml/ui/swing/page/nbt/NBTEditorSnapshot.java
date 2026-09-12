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
import space.minecraftstl.xyml.nbt.NBTDocument;

import java.nio.file.Path;
import java.util.Objects;

/// Immutable state rendered by one NBT editor page.
@NotNullByDefault
public record NBTEditorSnapshot(
        NBTEditorStatus status,
        @Nullable Path file,
        @Nullable NBTDocument document,
        boolean dirty,
        @Nullable String message,
        long revision) {
    /// Validates one state transition value.
    ///
    /// @param status lifecycle state
    /// @param file selected source, or `null` before selection
    /// @param document loaded document, or `null` before a successful open
    /// @param dirty whether the in-memory document differs from its last saved baseline
    /// @param message operation detail, or `null` when no detail is active
    /// @param revision monotonic visible-state revision
    public NBTEditorSnapshot {
        Objects.requireNonNull(status, "status");
        if (revision < 0L) {
            throw new IllegalArgumentException("revision must not be negative");
        }
        if (document == null && dirty) {
            throw new IllegalArgumentException("a missing document cannot be dirty");
        }
        if (status == NBTEditorStatus.EMPTY && (file != null || document != null || dirty)) {
            throw new IllegalArgumentException("empty state cannot retain a source or document");
        }
    }

    /// Creates the initial empty state.
    ///
    /// @return empty revision-zero state
    public static NBTEditorSnapshot empty() {
        return new NBTEditorSnapshot(NBTEditorStatus.EMPTY, null, null, false, null, 0L);
    }

    /// Returns whether a blocking operation is active.
    ///
    /// @return whether open, reload, or save is running
    public boolean busy() {
        return status.busy();
    }

    /// Returns whether the current document still requires a save attempt.
    ///
    /// A recovered source remains saveable even when no in-memory edit has been made, because the
    /// explicit repair save is what rewrites it into strict Java Edition NBT. A partial Region
    /// save remains recoverable even when undo returns the editor to its original savepoint,
    /// because already committed chunks may still differ from that in-memory state.
    ///
    /// @return whether save should remain available
    public boolean requiresSave() {
        return dirty
                || status == NBTEditorStatus.PARTIAL_SAVE
                || document != null && document.requiresRepair();
    }
}
