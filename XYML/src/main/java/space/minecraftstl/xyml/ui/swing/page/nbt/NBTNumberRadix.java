// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.nbt;

import org.jetbrains.annotations.NotNullByDefault;

/// Numeric display and input bases supported by the structured NBT value editor.
@NotNullByDefault
enum NBTNumberRadix {
    /// Base-10 signed values and Java decimal floating-point text.
    DECIMAL,

    /// Base-16 bit patterns and Java hexadecimal floating-point text.
    HEXADECIMAL
}
