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
