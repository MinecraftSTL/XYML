// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

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
