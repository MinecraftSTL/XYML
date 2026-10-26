// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.shell;

import org.jetbrains.annotations.NotNullByDefault;

import java.util.Objects;

/// Localized navigation presentation for one stable shell destination.
///
/// @param label the localized visible and accessible label
/// @param mnemonic the Swing key code used with the platform menu shortcut modifier, or zero for none
@NotNullByDefault
public record ShellPagePresentation(String label, int mnemonic) {
    /// Validates one localized page presentation.
    public ShellPagePresentation {
        Objects.requireNonNull(label, "label");
        if (label.isBlank()) {
            throw new IllegalArgumentException("label must not be blank");
        }
        if (mnemonic < 0) {
            throw new IllegalArgumentException("mnemonic must not be negative");
        }
    }
}
