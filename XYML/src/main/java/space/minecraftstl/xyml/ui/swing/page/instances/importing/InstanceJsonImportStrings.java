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
package space.minecraftstl.xyml.ui.swing.page.instances.importing;

import org.jetbrains.annotations.NotNullByDefault;

import java.util.Objects;

import static space.minecraftstl.xyml.util.i18n.I18n.i18n;

/// Localizable text for the single-instance JSON import workflow.
///
/// @param dialogTitle modeless import-window title
/// @param sourceLabel selected JSON source label
/// @param instanceIdLabel destination instance ID label
/// @param importAction command beginning the import
/// @param readyStatus idle validation guidance
/// @param importingStatus active task summary
/// @param succeededStatus successful terminal feedback
/// @param cancelledStatus cancelled terminal feedback
/// @param malformedJsonStatus malformed or unreadable source feedback
/// @param invalidInstanceIdStatus invalid destination ID feedback
/// @param instanceAlreadyExistsStatus destination conflict feedback
/// @param failedStatus fallback task failure feedback
@NotNullByDefault
public record InstanceJsonImportStrings(
        String dialogTitle,
        String sourceLabel,
        String instanceIdLabel,
        String importAction,
        String readyStatus,
        String importingStatus,
        String succeededStatus,
        String cancelledStatus,
        String malformedJsonStatus,
        String invalidInstanceIdStatus,
        String instanceAlreadyExistsStatus,
        String failedStatus) {
    /// Validates and creates the immutable text bundle.
    public InstanceJsonImportStrings {
        Objects.requireNonNull(dialogTitle, "dialogTitle");
        Objects.requireNonNull(sourceLabel, "sourceLabel");
        Objects.requireNonNull(instanceIdLabel, "instanceIdLabel");
        Objects.requireNonNull(importAction, "importAction");
        Objects.requireNonNull(readyStatus, "readyStatus");
        Objects.requireNonNull(importingStatus, "importingStatus");
        Objects.requireNonNull(succeededStatus, "succeededStatus");
        Objects.requireNonNull(cancelledStatus, "cancelledStatus");
        Objects.requireNonNull(malformedJsonStatus, "malformedJsonStatus");
        Objects.requireNonNull(invalidInstanceIdStatus, "invalidInstanceIdStatus");
        Objects.requireNonNull(instanceAlreadyExistsStatus, "instanceAlreadyExistsStatus");
        Objects.requireNonNull(failedStatus, "failedStatus");
    }

    /// Loads the current locale from the launcher resource bundle.
    ///
    /// @return localized immutable text
    public static InstanceJsonImportStrings localized() {
        return new InstanceJsonImportStrings(
                i18n("swing.instance_json.title"),
                i18n("swing.instance_json.source"),
                i18n("swing.instance_json.instance_id"),
                i18n("swing.instance_json.import"),
                i18n("swing.instance_json.ready"),
                i18n("swing.instance_json.importing"),
                i18n("swing.instance_json.succeeded"),
                i18n("swing.instance_json.cancelled"),
                i18n("swing.instance_json.malformed"),
                i18n("swing.instance_json.invalid_id"),
                i18n("swing.instance_json.exists"),
                i18n("swing.instance_json.failed"));
    }

    /// Returns deterministic English text for focused tests and fallback integrations.
    ///
    /// @return immutable English text
    public static InstanceJsonImportStrings english() {
        return new InstanceJsonImportStrings(
                "Import instance JSON",
                "Instance manifest JSON",
                "Instance name",
                "Import",
                "Review the instance name, then start the import.",
                "Preparing and downloading the instance...",
                "Instance imported and selected.",
                "Import cancelled.",
                "The selected file is not a valid game instance manifest JSON.",
                "Enter a valid instance name.",
                "An instance with this name already exists.",
                "Instance import failed.");
    }
}
