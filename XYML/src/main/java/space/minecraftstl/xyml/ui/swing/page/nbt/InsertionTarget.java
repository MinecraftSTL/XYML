// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.nbt;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import space.minecraftstl.xyml.library.nbt.tag.TagType;

import java.util.List;
import java.util.Objects;

/// Immutable constraints for one NBT insertion destination.
@NotNullByDefault
record InsertionTarget(
        NBTEditorTreeNode parent,
        int index,
        @Unmodifiable List<TagType<?>> types,
        boolean nameRequired) {
    /// Validates and snapshots insertion constraints.
    InsertionTarget {
        Objects.requireNonNull(parent, "parent");
        types = List.copyOf(Objects.requireNonNull(types, "types"));
        if (index < 0 || index > parent.childCount() || types.isEmpty()) {
            throw new IllegalArgumentException("Invalid insertion target");
        }
    }
}
