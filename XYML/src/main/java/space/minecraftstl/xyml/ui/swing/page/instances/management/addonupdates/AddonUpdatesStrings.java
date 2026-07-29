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
package space.minecraftstl.xyml.ui.swing.page.instances.management.addonupdates;

import org.jetbrains.annotations.NotNullByDefault;

import java.util.Objects;

import static space.minecraftstl.xyml.util.i18n.I18n.i18n;

/// Stable localized labels used by the installed add-on update page.
///
/// Existing launcher translation keys cover established update terminology; short new action
/// labels remain deliberately generic so this page does not add duplicated locale resources.
///
/// @param title page heading
/// @param checkButtonText explicit network-check command text
/// @param fileColumn file-name column title
/// @param currentVersionColumn installed-version column title
/// @param targetVersionColumn available-version column title
/// @param sourceColumn remote-source column title
/// @param resultColumn per-row result column title
/// @param checkingText active scan status
/// @param emptyText no-update status
/// @param failedText scan failure status
/// @param selectionColumnText per-row update-selection column label
/// @param selectAllText select-all checkbox label
/// @param updateButtonText selected-update command text
/// @param updatingText active update status
/// @param updateSucceededText completed update status
/// @param failedDownloadText partial update failure status
/// @param sourceTooltip source-page icon tooltip
/// @param localFileTooltip local-file icon tooltip
/// @param failureDialogTitle native action failure dialog title
@NotNullByDefault
public record AddonUpdatesStrings(
        String title,
        String checkButtonText,
        String fileColumn,
        String currentVersionColumn,
        String targetVersionColumn,
        String sourceColumn,
        String resultColumn,
        String checkingText,
        String emptyText,
        String failedText,
        String selectionColumnText,
        String selectAllText,
        String updateButtonText,
        String updatingText,
        String updateSucceededText,
        String failedDownloadText,
        String sourceTooltip,
        String localFileTooltip,
        String failureDialogTitle) {
    /// Validates stable visible strings.
    public AddonUpdatesStrings {
        title = requireText(title, "title");
        checkButtonText = requireText(checkButtonText, "checkButtonText");
        fileColumn = requireText(fileColumn, "fileColumn");
        currentVersionColumn = requireText(currentVersionColumn, "currentVersionColumn");
        targetVersionColumn = requireText(targetVersionColumn, "targetVersionColumn");
        sourceColumn = requireText(sourceColumn, "sourceColumn");
        resultColumn = requireText(resultColumn, "resultColumn");
        checkingText = requireText(checkingText, "checkingText");
        emptyText = requireText(emptyText, "emptyText");
        failedText = requireText(failedText, "failedText");
        selectionColumnText = requireText(selectionColumnText, "selectionColumnText");
        selectAllText = requireText(selectAllText, "selectAllText");
        updateButtonText = requireText(updateButtonText, "updateButtonText");
        updatingText = requireText(updatingText, "updatingText");
        updateSucceededText = requireText(updateSucceededText, "updateSucceededText");
        failedDownloadText = requireText(failedDownloadText, "failedDownloadText");
        sourceTooltip = requireText(sourceTooltip, "sourceTooltip");
        localFileTooltip = requireText(localFileTooltip, "localFileTooltip");
        failureDialogTitle = requireText(failureDialogTitle, "failureDialogTitle");
    }

    /// Creates labels backed by the established launcher locale catalog.
    ///
    /// @return current-locale page labels
    public static AddonUpdatesStrings localized() {
        return new AddonUpdatesStrings(
                i18n("addon.check_update"),
                i18n("addon.check_update.button"),
                i18n("addon.check_update.file"),
                i18n("addon.check_update.current_version"),
                i18n("addon.check_update.target_version"),
                i18n("addon.check_update.source"),
                i18n("message.error"),
                i18n("update.checking"),
                i18n("addon.check_update.empty"),
                i18n("addon.check_update.failed_check"),
                i18n("selector.choose"),
                i18n("button.select_all"),
                i18n("addon.check_update.confirm"),
                i18n("addon.check_update.confirm"),
                i18n("install.success"),
                i18n("addon.check_update.failed_download"),
                i18n("button.view"),
                i18n("button.view"),
                i18n("message.error"));
    }

    /// Creates deterministic English labels for headless tests.
    ///
    /// @return stable English page labels
    public static AddonUpdatesStrings english() {
        return new AddonUpdatesStrings(
                "File update process",
                "Check updates",
                "File",
                "Current Version",
                "Target Version",
                "Source",
                "Result",
                "Checking for updates",
                "All files are up-to-date",
                "Failed to check for updates.",
                "Choose",
                "Select all",
                "Update",
                "Updating selected files",
                "Updates installed",
                "Some files could not be updated",
                "View source page",
                "Show local file",
                "Error");
    }

    /// Requires one non-blank visible string.
    ///
    /// @param value candidate string
    /// @param name field name for diagnostics
    /// @return validated string
    private static String requireText(String value, String name) {
        String text = Objects.requireNonNull(value, name);
        if (text.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return text;
    }
}
