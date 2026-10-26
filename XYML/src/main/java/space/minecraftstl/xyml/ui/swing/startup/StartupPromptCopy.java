// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.startup;

import org.jetbrains.annotations.NotNullByDefault;

import java.util.Objects;

/// Immutable localized heading and body for one startup prompt.
///
/// @param title localized dialog title
/// @param message localized dialog message
@NotNullByDefault
public record StartupPromptCopy(String title, String message) {
    /// Rejects null text while allowing intentionally empty localized titles.
    public StartupPromptCopy {
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(message, "message");
    }
}
