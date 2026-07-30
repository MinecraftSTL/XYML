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
package space.minecraftstl.xyml.ui.swing.page.instances.management.worlds;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

import static space.minecraftstl.xyml.util.i18n.I18n.i18n;

/// Localizable text for the independent instance-world management page.
///
/// Keeping page, action, status, and native-dialog text in one immutable object prevents production
/// localization from diverging from deterministic English component tests.
///
/// @param title page and tab title
/// @param emptySelectionText details placeholder without a loaded row
/// @param directoryNameLabel world-directory name label
/// @param pathLabel normalized path label
/// @param gameVersionLabel recorded game version label
/// @param lastPlayedLabel last-played timestamp label
/// @param lockedLabel session-lock state label
/// @param readabilityLabel metadata readability label
/// @param unavailableValue display value for missing optional metadata
/// @param readableValue display value for successfully decoded metadata
/// @param unreadableValue display value for rows Core could not parse
/// @param lockedValue display value for locked worlds
/// @param unlockedValue display value for unlocked worlds
/// @param loadingText shallow-index loading text
/// @param readyTextFormat shallow-index ready text containing one count placeholder
/// @param loadFailureTextFormat shallow-index failure text containing one detail placeholder
/// @param importingText active import text
/// @param deletingText active delete text
/// @param refreshTooltip refresh command tooltip
/// @param importTooltip ZIP import command tooltip
/// @param openSavesTooltip managed saves-directory command tooltip
/// @param openWorldTooltip selected world directory command tooltip
/// @param deleteTooltip selected world deletion command tooltip
/// @param importDialogTitle archive chooser title
/// @param archiveDescription archive chooser filter description
/// @param worldNamePrompt import target-name prompt
/// @param deleteConfirmationFormat delete confirmation containing one display-name placeholder
/// @param deleteDialogTitle delete confirmation title
/// @param failureTitle failure dialog title
/// @param copyingText active copy operation text
/// @param exportingText active export operation text
/// @param copyTooltip selected-world copy tooltip
/// @param exportTooltip selected-world export tooltip
/// @param copyNamePrompt copy destination-name prompt
/// @param copyDialogTitle copy dialog title
/// @param copyNameFormat suggested copy-name format containing one directory-name placeholder
/// @param exportDialogTitle export chooser title
/// @param overwriteConfirmationFormat replacement confirmation containing one file-name placeholder
/// @param quickPlayTooltip selected-world quick-play tooltip
/// @param launchScriptTooltip selected-world script-generation tooltip
/// @param launchingText active quick-play preparation text
/// @param generatingLaunchScriptText active launch-script generation text
/// @param launchScriptDialogTitle launch-script chooser and success-dialog title
/// @param launchScriptSuccessFormat generated-script success text containing one path placeholder
@NotNullByDefault
public record WorldCatalogStrings(
        String title,
        String emptySelectionText,
        String directoryNameLabel,
        String pathLabel,
        String gameVersionLabel,
        String lastPlayedLabel,
        String lockedLabel,
        String readabilityLabel,
        String unavailableValue,
        String readableValue,
        String unreadableValue,
        String lockedValue,
        String unlockedValue,
        String loadingText,
        String readyTextFormat,
        String loadFailureTextFormat,
        String importingText,
        String deletingText,
        String refreshTooltip,
        String importTooltip,
        String openSavesTooltip,
        String openWorldTooltip,
        String deleteTooltip,
        String importDialogTitle,
        String archiveDescription,
        String worldNamePrompt,
        String deleteConfirmationFormat,
        String deleteDialogTitle,
        String failureTitle,
        String copyingText,
        String exportingText,
        String copyTooltip,
        String exportTooltip,
        String copyNamePrompt,
        String copyDialogTitle,
        String copyNameFormat,
        String exportDialogTitle,
        String overwriteConfirmationFormat,
        String quickPlayTooltip,
        String launchScriptTooltip,
        String launchingText,
        String generatingLaunchScriptText,
        String launchScriptDialogTitle,
        String launchScriptSuccessFormat) {
    /// Shared deterministic English text for tests and explicit fallback use.
    private static final WorldCatalogStrings ENGLISH = new WorldCatalogStrings(
            "Worlds",
            "Select a world",
            "Directory",
            "Path",
            "Game version",
            "Last played",
            "Locked",
            "Metadata",
            "Not recorded",
            "Available",
            "Unreadable",
            "Yes",
            "No",
            "Loading world folders...",
            "%d worlds",
            "Unable to load worlds: %s",
            "Importing world...",
            "Deleting world...",
            "Refresh worlds",
            "Import world archive",
            "Open saves folder",
            "Open selected world folder",
            "Delete selected world",
            "Import world archive",
            "World ZIP archives",
            "World name:",
            "Permanently delete world \"%s\"?",
            "Delete world",
            "World operation failed",
            "Copying world...",
            "Exporting world...",
            "Copy selected world",
            "Export selected world",
            "New world name:",
            "Copy world",
            "%s - Copy",
            "Export world",
            "Replace existing archive \"%s\"?",
            "Launch and enter selected world",
            "Generate quick-play launch script",
            "Preparing quick play...",
            "Generating quick-play script...",
            "Launch script",
            "Launch script saved to %s");

    /// Validates all visible and dialog text at construction time.
    public WorldCatalogStrings {
        @Unmodifiable List<String> values = List.of(
                title,
                emptySelectionText,
                directoryNameLabel,
                pathLabel,
                gameVersionLabel,
                lastPlayedLabel,
                lockedLabel,
                readabilityLabel,
                unavailableValue,
                readableValue,
                unreadableValue,
                lockedValue,
                unlockedValue,
                loadingText,
                readyTextFormat,
                loadFailureTextFormat,
                importingText,
                deletingText,
                refreshTooltip,
                importTooltip,
                openSavesTooltip,
                openWorldTooltip,
                deleteTooltip,
                importDialogTitle,
                archiveDescription,
                worldNamePrompt,
                deleteConfirmationFormat,
                deleteDialogTitle,
                failureTitle,
                copyingText,
                exportingText,
                copyTooltip,
                exportTooltip,
                copyNamePrompt,
                copyDialogTitle,
                copyNameFormat,
                exportDialogTitle,
                overwriteConfirmationFormat,
                quickPlayTooltip,
                launchScriptTooltip,
                launchingText,
                generatingLaunchScriptText,
                launchScriptDialogTitle,
                launchScriptSuccessFormat);
        if (values.stream().map(value -> Objects.requireNonNull(value, "world catalog string"))
                .anyMatch(String::isBlank)) {
            throw new IllegalArgumentException("World catalog strings must not be blank");
        }
    }

    /// Returns production text resolved from the launcher's current locale.
    ///
    /// @return current-locale world-management text
    public static WorldCatalogStrings localized() {
        return new WorldCatalogStrings(
                i18n("swing.world_catalog.title"),
                i18n("swing.world_catalog.empty_selection"),
                i18n("swing.world_catalog.directory"),
                i18n("swing.world_catalog.path"),
                i18n("swing.world_catalog.game_version"),
                i18n("swing.world_catalog.last_played"),
                i18n("swing.world_catalog.locked"),
                i18n("swing.world_catalog.metadata"),
                i18n("swing.world_catalog.unavailable"),
                i18n("swing.world_catalog.readable"),
                i18n("swing.world_catalog.unreadable"),
                i18n("swing.world_catalog.locked_yes"),
                i18n("swing.world_catalog.locked_no"),
                i18n("swing.world_catalog.loading"),
                i18n("swing.world_catalog.ready"),
                i18n("swing.world_catalog.load_failed"),
                i18n("swing.world_catalog.importing"),
                i18n("swing.world_catalog.deleting"),
                i18n("swing.world_catalog.refresh"),
                i18n("swing.world_catalog.import"),
                i18n("swing.world_catalog.open_saves"),
                i18n("swing.world_catalog.open_world"),
                i18n("swing.world_catalog.delete"),
                i18n("swing.world_catalog.import_title"),
                i18n("swing.world_catalog.archive_description"),
                i18n("swing.world_catalog.world_name_prompt"),
                i18n("swing.world_catalog.delete_confirmation"),
                i18n("swing.world_catalog.delete_title"),
                i18n("swing.world_catalog.failure_title"),
                i18n("swing.world_catalog.copying"),
                i18n("swing.world_catalog.exporting"),
                i18n("swing.world_catalog.copy"),
                i18n("swing.world_catalog.export"),
                i18n("swing.world_catalog.copy_name_prompt"),
                i18n("swing.world_catalog.copy_title"),
                i18n("swing.world_catalog.copy_name_format"),
                i18n("swing.world_catalog.export_title"),
                i18n("swing.world_catalog.overwrite_confirmation"),
                i18n("swing.world_catalog.quick_play"),
                i18n("swing.world_catalog.launch_script"),
                i18n("swing.world_catalog.launching"),
                i18n("swing.world_catalog.generating_launch_script"),
                i18n("swing.world_catalog.launch_script_title"),
                i18n("swing.world_catalog.launch_script_success"));
    }

    /// Returns the stable English text bundle for deterministic tests.
    ///
    /// @return shared immutable English text
    public static WorldCatalogStrings english() {
        return ENGLISH;
    }

    /// Formats ready status for an exact shallow-index count.
    ///
    /// @param count non-negative direct-child directory count
    /// @return non-blank ready message
    public String readyText(int count) {
        if (count < 0) {
            throw new IllegalArgumentException("count must not be negative");
        }
        return readyTextFormat.formatted(count);
    }

    /// Formats one shallow-index failure for concise status display.
    ///
    /// @param detail non-blank failure detail
    /// @return non-blank retryable failure message
    public String loadFailureText(String detail) {
        String checkedDetail = Objects.requireNonNull(detail, "detail");
        if (checkedDetail.isBlank()) {
            throw new IllegalArgumentException("detail must not be blank");
        }
        return loadFailureTextFormat.formatted(checkedDetail);
    }

    /// Returns the overwrite confirmation message.
    ///
    /// @param fileName selected existing archive file name
    /// @return non-blank overwrite confirmation
    public String overwriteConfirmation(String fileName) {
        String checkedName = Objects.requireNonNull(fileName, "fileName");
        if (checkedName.isBlank()) {
            throw new IllegalArgumentException("fileName must not be blank");
        }
        return overwriteConfirmationFormat.formatted(checkedName);
    }

    /// Formats a localized default sibling name for a copied world.
    ///
    /// @param directoryName selected world's directory name
    /// @return non-blank suggested copy name
    public String copyName(String directoryName) {
        String checkedName = Objects.requireNonNull(directoryName, "directoryName");
        if (checkedName.isBlank()) {
            throw new IllegalArgumentException("directoryName must not be blank");
        }
        return copyNameFormat.formatted(checkedName);
    }

    /// Formats one successfully generated script path for native feedback.
    ///
    /// @param scriptFile exact generated script path
    /// @return non-blank success message
    public String launchScriptSuccess(Path scriptFile) {
        Path destination = Objects.requireNonNull(scriptFile, "scriptFile").toAbsolutePath().normalize();
        return launchScriptSuccessFormat.formatted(destination);
    }
}
