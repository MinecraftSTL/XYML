// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

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

    /// The most recent operation failed without a more specific recovery boundary.
    ERROR,

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
