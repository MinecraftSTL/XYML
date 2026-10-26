// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.settings;

import org.jetbrains.annotations.NotNullByDefault;

import java.util.Objects;

/// Localized text for the settings restart status and action.
///
/// @param promptText explanation shown before a restart-sensitive setting changes
/// @param requiredText confirmation shown after a restart-sensitive setting changes
/// @param actionText restart button label
/// @param inProgressText status shown while saves and process startup are pending
/// @param failedText retryable status shown when the restart command fails
@NotNullByDefault
public record SettingsRestartStrings(
        String promptText,
        String requiredText,
        String actionText,
        String inProgressText,
        String failedText) {
    /// Validates every localized restart string.
    public SettingsRestartStrings {
        Objects.requireNonNull(promptText, "promptText");
        Objects.requireNonNull(requiredText, "requiredText");
        Objects.requireNonNull(actionText, "actionText");
        Objects.requireNonNull(inProgressText, "inProgressText");
        Objects.requireNonNull(failedText, "failedText");
    }
}
