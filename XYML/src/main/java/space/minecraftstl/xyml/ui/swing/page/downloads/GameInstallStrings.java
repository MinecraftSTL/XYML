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
package space.minecraftstl.xyml.ui.swing.page.downloads;

import org.jetbrains.annotations.NotNullByDefault;

import java.util.Objects;

/// Localizable controls, task text, and typed validation feedback for vanilla installation.
///
/// @param instanceNameLabel label for the exact destination instance name
/// @param installAction command that starts installation for the loaded selected version
/// @param backToCatalogAction command that dismisses a terminal task and returns to the catalog
/// @param taskTitle title shown by the task-progress surface
/// @param preparingPhase phase shown while the installation task is being prepared
/// @param invalidInstanceNameStatus feedback for an unsupported destination name
/// @param instanceAlreadyExistsStatus feedback for a destination-name conflict
/// @param installationAlreadyRunningStatus feedback when another request owns the single-flight slot
/// @param installationFailedStatus fallback feedback for an unclassified installation failure
@NotNullByDefault
public record GameInstallStrings(
        String instanceNameLabel,
        String installAction,
        String backToCatalogAction,
        String taskTitle,
        String preparingPhase,
        String invalidInstanceNameStatus,
        String instanceAlreadyExistsStatus,
        String installationAlreadyRunningStatus,
        String installationFailedStatus) {
    /// Validates every localized value without inventing fallback text.
    public GameInstallStrings {
        requireText(instanceNameLabel, "instanceNameLabel");
        requireText(installAction, "installAction");
        requireText(backToCatalogAction, "backToCatalogAction");
        requireText(taskTitle, "taskTitle");
        requireText(preparingPhase, "preparingPhase");
        requireText(invalidInstanceNameStatus, "invalidInstanceNameStatus");
        requireText(instanceAlreadyExistsStatus, "instanceAlreadyExistsStatus");
        requireText(installationAlreadyRunningStatus, "installationAlreadyRunningStatus");
        requireText(installationFailedStatus, "installationFailedStatus");
    }

    /// Rejects missing or blank presentation text.
    ///
    /// @param value localized value
    /// @param name record component name
    private static void requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
