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

/// Stable English fallback text for the independent instance-world management page.
///
/// The existing Swing composition has no world text bundle yet. Keeping the temporary fallback in
/// this immutable object avoids scattering literals through filesystem, dialog, and rendering code.
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
        String failureTitle) {
    /// Shared production fallback until the presentation catalog owns world-management text.
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
            "World operation failed");

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
                failureTitle);
        if (values.stream().map(value -> Objects.requireNonNull(value, "world catalog string"))
                .anyMatch(String::isBlank)) {
            throw new IllegalArgumentException("World catalog strings must not be blank");
        }
    }

    /// Returns the fallback text bundle used by the current production page.
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

    /// Returns the active copy operation text.
    ///
    /// @return non-blank copy status
    public String copyingText() {
        return "Copying world...";
    }

    /// Returns the active export operation text.
    ///
    /// @return non-blank export status
    public String exportingText() {
        return "Exporting world...";
    }

    /// Returns the selected-world copy tooltip.
    ///
    /// @return non-blank copy tooltip
    public String copyTooltip() {
        return "Copy selected world";
    }

    /// Returns the selected-world export tooltip.
    ///
    /// @return non-blank export tooltip
    public String exportTooltip() {
        return "Export selected world";
    }

    /// Returns the copy-name prompt.
    ///
    /// @return non-blank copy-name prompt
    public String copyNamePrompt() {
        return "New world name:";
    }

    /// Returns the copy dialog title.
    ///
    /// @return non-blank copy dialog title
    public String copyDialogTitle() {
        return "Copy world";
    }

    /// Returns the export chooser title.
    ///
    /// @return non-blank export chooser title
    public String exportDialogTitle() {
        return "Export world";
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
        return "Replace existing archive \"" + checkedName + "\"?";
    }

    /// Returns the selected-world quick-play tooltip.
    ///
    /// @return non-blank quick-play tooltip
    public String quickPlayTooltip() {
        return "Launch and enter selected world";
    }

    /// Returns the selected-world standalone script tooltip.
    ///
    /// @return non-blank quick-play script tooltip
    public String launchScriptTooltip() {
        return "Generate quick-play launch script";
    }

    /// Returns the status shown while quick-play process preparation owns this page.
    ///
    /// @return non-blank launch preparation status
    public String launchingText() {
        return "Preparing quick play...";
    }

    /// Returns the status shown while quick-play script generation owns this page.
    ///
    /// @return non-blank script preparation status
    public String generatingLaunchScriptText() {
        return "Generating quick-play script...";
    }

    /// Returns the standalone quick-play script chooser and success-dialog title.
    ///
    /// @return non-blank dialog title
    public String launchScriptDialogTitle() {
        return "Launch script";
    }

    /// Formats one successfully generated script path for native feedback.
    ///
    /// @param scriptFile exact generated script path
    /// @return non-blank success message
    public String launchScriptSuccess(Path scriptFile) {
        Path destination = Objects.requireNonNull(scriptFile, "scriptFile").toAbsolutePath().normalize();
        return "Launch script saved to " + destination;
    }
}
