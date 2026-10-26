// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.startup;

import org.jetbrains.annotations.NotNullByDefault;

/// Closed family of prompt-specific decisions returned by [StartupPromptPresenter].
@NotNullByDefault
public sealed interface StartupPromptDecision {
    /// Decision for the mandatory agreement gate.
    @NotNullByDefault
    enum Agreement implements StartupPromptDecision {
        /// Accept the current agreement and continue startup.
        ACCEPT,

        /// Decline the agreement, close the application, and stop the prompt queue.
        DECLINE
    }

    /// Decision for notices that require only explicit acknowledgement.
    @NotNullByDefault
    enum Acknowledgement implements StartupPromptDecision {
        /// Acknowledge the notice and continue startup.
        ACKNOWLEDGE
    }

    /// Decision for warnings that optionally support permanent suppression.
    @NotNullByDefault
    enum Suppression implements StartupPromptDecision {
        /// Continue this launch without changing persisted suppression state.
        CONTINUE,

        /// Continue and suppress the same warning on future launches.
        DO_NOT_SHOW_AGAIN
    }

    /// Decision for the optional April Fools language invitation.
    @NotNullByDefault
    enum AprilFools implements StartupPromptDecision {
        /// Persist the target language and execute the save-and-restart sequence.
        SWITCH_LANGUAGE,

        /// Keep the current language and only mark the invitation as resolved for this year.
        KEEP_LANGUAGE
    }
}
