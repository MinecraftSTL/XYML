// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.choice;

import org.jetbrains.annotations.NotNullByDefault;

/// Produces the visible label for a loaded single-choice row.
///
/// @param <T> the non-null choice value type
@FunctionalInterface
@NotNullByDefault
public interface ChoiceTextProvider<T extends Object> {
    /// Returns the localized row label for a loaded value.
    ///
    /// @param value the loaded choice value
    /// @return the non-null row label
    String getText(T value);
}
