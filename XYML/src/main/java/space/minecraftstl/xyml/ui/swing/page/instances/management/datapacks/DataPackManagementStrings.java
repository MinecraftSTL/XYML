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
package space.minecraftstl.xyml.ui.swing.page.instances.management.datapacks;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;

import java.util.List;
import java.util.Objects;

import static space.minecraftstl.xyml.util.i18n.I18n.i18n;

/// Immutable visible text for the independent per-world data-pack manager.
///
/// Keeping production localization and deterministic English fallback text together prevents action
/// labels, statuses, and native-dialog text from drifting apart.
///
/// @param title page title
/// @param worldsLabel heading for the lazy world selector
/// @param dataPacksLabel heading for the selected world's data packs
/// @param selectWorldText placeholder shown before a readable world is selected
/// @param loadingPacksText status shown while the selected world is inspected off the EDT
/// @param unsupportedWorldText status shown for worlds older than data-pack support
/// @param unreadableWorldText status shown when Core cannot reopen the selected world
/// @param noPacksText status shown for an empty data-pack directory
/// @param packsReadyFormat ready-status format containing one pack-count placeholder
/// @param activeText suffix for enabled packs
/// @param inactiveText suffix for disabled packs
/// @param refreshTooltip refresh the lazy world index
/// @param openSavesTooltip reveal the instance saves directory
/// @param importTooltip import a local data-pack ZIP into the selected world
/// @param openDataPacksTooltip reveal the selected world's data-pack directory
/// @param deleteTooltip permanently remove the selected data pack
/// @param importDialogTitle archive chooser title
/// @param archiveDescription archive chooser filter description
/// @param deleteConfirmationFormat deletion confirmation containing one pack-id placeholder
/// @param deleteDialogTitle deletion confirmation title
/// @param failureTitle operation failure dialog title
@NotNullByDefault
public record DataPackManagementStrings(
        String title,
        String worldsLabel,
        String dataPacksLabel,
        String selectWorldText,
        String loadingPacksText,
        String unsupportedWorldText,
        String unreadableWorldText,
        String noPacksText,
        String packsReadyFormat,
        String activeText,
        String inactiveText,
        String refreshTooltip,
        String openSavesTooltip,
        String importTooltip,
        String openDataPacksTooltip,
        String deleteTooltip,
        String importDialogTitle,
        String archiveDescription,
        String deleteConfirmationFormat,
        String deleteDialogTitle,
        String failureTitle) {
    /// Shared English fallback used until the presentation catalog supplies this page's messages.
    private static final DataPackManagementStrings ENGLISH = new DataPackManagementStrings(
            "Data Packs",
            "Worlds",
            "Data packs",
            "Select a world to manage its data packs.",
            "Loading data packs...",
            "This world does not support data packs.",
            "The selected world could not be read.",
            "No data packs installed.",
            "%d data packs",
            "Enabled",
            "Disabled",
            "Refresh worlds",
            "Open saves folder",
            "Import data-pack ZIP",
            "Open selected data-pack folder",
            "Delete selected data pack",
            "Import data-pack ZIP",
            "Data-pack ZIP archives",
            "Permanently delete data pack \"%s\"?",
            "Delete data pack",
            "Data-pack operation failed");

    /// Validates every visible string eagerly so a partially configured page cannot reach users.
    public DataPackManagementStrings {
        @Unmodifiable List<String> values = List.of(
                title,
                worldsLabel,
                dataPacksLabel,
                selectWorldText,
                loadingPacksText,
                unsupportedWorldText,
                unreadableWorldText,
                noPacksText,
                packsReadyFormat,
                activeText,
                inactiveText,
                refreshTooltip,
                openSavesTooltip,
                importTooltip,
                openDataPacksTooltip,
                deleteTooltip,
                importDialogTitle,
                archiveDescription,
                deleteConfirmationFormat,
                deleteDialogTitle,
                failureTitle);
        if (values.stream().map(value -> Objects.requireNonNull(value, "data-pack text"))
                .anyMatch(String::isBlank)) {
            throw new IllegalArgumentException("Data-pack management strings must not be blank");
        }
    }

    /// Returns the shared English fallback text.
    ///
    /// @return immutable fallback text bundle
    public static DataPackManagementStrings english() {
        return ENGLISH;
    }

    /// Returns production text resolved from the launcher's current locale.
    ///
    /// @return current-locale data-pack management text
    public static DataPackManagementStrings localized() {
        return new DataPackManagementStrings(
                i18n("swing.datapack_management.title"),
                i18n("swing.datapack_management.worlds"),
                i18n("swing.datapack_management.data_packs"),
                i18n("swing.datapack_management.select_world"),
                i18n("swing.datapack_management.loading"),
                i18n("swing.datapack_management.unsupported"),
                i18n("swing.datapack_management.unreadable"),
                i18n("swing.datapack_management.empty"),
                i18n("swing.datapack_management.ready"),
                i18n("swing.datapack_management.active"),
                i18n("swing.datapack_management.inactive"),
                i18n("swing.datapack_management.refresh"),
                i18n("swing.datapack_management.open_saves"),
                i18n("swing.datapack_management.import"),
                i18n("swing.datapack_management.open_folder"),
                i18n("swing.datapack_management.delete"),
                i18n("swing.datapack_management.import_title"),
                i18n("swing.datapack_management.archive_description"),
                i18n("swing.datapack_management.delete_confirmation"),
                i18n("swing.datapack_management.delete_title"),
                i18n("swing.datapack_management.failure_title"));
    }

    /// Formats the selected world's exact discovered data-pack count.
    ///
    /// @param count non-negative number of installed data packs
    /// @return non-blank ready status
    public String packsReadyText(int count) {
        if (count < 0) {
            throw new IllegalArgumentException("count must not be negative");
        }
        return count == 0 ? noPacksText : packsReadyFormat.formatted(count);
    }
}
