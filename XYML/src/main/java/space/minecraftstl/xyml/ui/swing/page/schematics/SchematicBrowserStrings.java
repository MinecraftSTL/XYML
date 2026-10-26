// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.schematics;

import org.jetbrains.annotations.NotNullByDefault;

import java.util.Objects;

/// Localizable visible and accessible text for the Swing schematic browser.
///
/// @param pageTitle page heading
/// @param returnAction parent-directory command label
/// @param returnTooltip parent-directory command tooltip
/// @param refreshAction normal refresh command label
/// @param refreshingAction in-progress refresh command label
/// @param refreshTooltip refresh command tooltip
/// @param openDirectoryAction selected-directory command label
/// @param openDirectoryTooltip selected-directory command tooltip
/// @param idleText text before lazy loading starts
/// @param loadingText directory loading text
/// @param emptyText empty-directory text
/// @param errorTitle directory scan failure heading
/// @param retryAction failed scan retry command label
/// @param detailsTitle details-region heading
/// @param noSelectionText details text with no loaded selection
/// @param directorySelectionText selected-directory details text
/// @param unreadableText unreadable-file details heading
/// @param directoryRowPrefix prefix distinguishing directory rows
/// @param metadata metadata labels and formats
/// @param actions file-operation labels, prompts, and failure text
@NotNullByDefault
public record SchematicBrowserStrings(
        String pageTitle,
        String returnAction,
        String returnTooltip,
        String refreshAction,
        String refreshingAction,
        String refreshTooltip,
        String openDirectoryAction,
        String openDirectoryTooltip,
        String idleText,
        String loadingText,
        String emptyText,
        String errorTitle,
        String retryAction,
        String detailsTitle,
        String noSelectionText,
        String directorySelectionText,
        String unreadableText,
        String directoryRowPrefix,
        SchematicMetadataStrings metadata,
        SchematicBrowserActionStrings actions) {
    /// Validates all localized browser text.
    public SchematicBrowserStrings {
        Objects.requireNonNull(pageTitle, "pageTitle");
        Objects.requireNonNull(returnAction, "returnAction");
        Objects.requireNonNull(returnTooltip, "returnTooltip");
        Objects.requireNonNull(refreshAction, "refreshAction");
        Objects.requireNonNull(refreshingAction, "refreshingAction");
        Objects.requireNonNull(refreshTooltip, "refreshTooltip");
        Objects.requireNonNull(openDirectoryAction, "openDirectoryAction");
        Objects.requireNonNull(openDirectoryTooltip, "openDirectoryTooltip");
        Objects.requireNonNull(idleText, "idleText");
        Objects.requireNonNull(loadingText, "loadingText");
        Objects.requireNonNull(emptyText, "emptyText");
        Objects.requireNonNull(errorTitle, "errorTitle");
        Objects.requireNonNull(retryAction, "retryAction");
        Objects.requireNonNull(detailsTitle, "detailsTitle");
        Objects.requireNonNull(noSelectionText, "noSelectionText");
        Objects.requireNonNull(directorySelectionText, "directorySelectionText");
        Objects.requireNonNull(unreadableText, "unreadableText");
        Objects.requireNonNull(directoryRowPrefix, "directoryRowPrefix");
        Objects.requireNonNull(metadata, "metadata");
        Objects.requireNonNull(actions, "actions");
    }
}
