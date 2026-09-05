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

/// Lifecycle states exposed by the NBT editor controller.
@NotNullByDefault
public enum NBTEditorStatus {
    /// No source has been selected.
    EMPTY,

    /// A source is being parsed on the background executor.
    OPENING,

    /// A document is available for inspection and supported edits.
    READY,

    /// A transactional edit is running on the background executor.
    EDITING,

    /// A fatal failure left the in-memory edit state untrustworthy until reload.
    EDIT_UNCERTAIN,

    /// A document snapshot is being written on the background executor.
    SAVING,

    /// The most recent operation failed without proving an external modification.
    ERROR,

    /// Saving was rejected because the source changed outside the editor.
    CONFLICT,

    /// A region save committed an ordered prefix while later chunks remain dirty.
    PARTIAL_SAVE,

    /// Publication state cannot be determined until the source is closed and reopened.
    COMMIT_UNCERTAIN,

    /// The controller has permanently stopped accepting operations.
    CLOSED;

    /// Returns whether this state represents a running blocking operation.
    ///
    /// @return whether controls that mutate or replace the document must remain disabled
    public boolean busy() {
        return this == OPENING || this == EDITING || this == SAVING;
    }
}
