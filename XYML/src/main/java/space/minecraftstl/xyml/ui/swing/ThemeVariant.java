// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing;

import org.jetbrains.annotations.NotNullByDefault;

/// Identifies the concrete light or dark palette currently rendered by Swing.
@NotNullByDefault
public enum ThemeVariant {
    /// Uses the light FlatLaf palette.
    LIGHT,

    /// Uses the dark FlatLaf palette.
    DARK
}
