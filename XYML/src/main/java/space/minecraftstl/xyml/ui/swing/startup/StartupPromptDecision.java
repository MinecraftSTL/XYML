/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2026 huangyuhui <huanghongxun2008@126.com> and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
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
