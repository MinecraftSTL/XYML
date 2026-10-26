// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.nbt;

import org.jetbrains.annotations.NotNullByDefault;

import java.util.Objects;

/// Identity key for one asynchronously formatted numeric value.
///
/// @param node revision-bound selected row
/// @param radix numeric display radix represented by the loaded text
@NotNullByDefault
record ValueLoadKey(NBTEditorTreeNode node, NBTNumberRadix radix) {
    /// Validates the immutable load key.
    ///
    /// @param node revision-bound selected row
    /// @param radix numeric display radix represented by the loaded text
    ValueLoadKey {
        Objects.requireNonNull(node, "node");
        Objects.requireNonNull(radix, "radix");
    }
}
