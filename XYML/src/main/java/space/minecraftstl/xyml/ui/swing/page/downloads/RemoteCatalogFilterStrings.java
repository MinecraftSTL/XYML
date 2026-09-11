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
import space.minecraftstl.xyml.addon.RemoteAddonRepository;

import java.util.Objects;

import static space.minecraftstl.xyml.util.i18n.I18n.i18n;

/// Shared category and sort text used by remote content search forms.
///
/// @param categoryLabel category selector label
/// @param allCategoriesLabel unfiltered category option
/// @param sortLabel sort selector label
/// @param relevancySortLabel search relevancy sort option
/// @param popularitySortLabel popularity sort option
/// @param dateCreatedSortLabel creation-date sort option
/// @param lastUpdatedSortLabel last-update sort option
/// @param totalDownloadsSortLabel total-download sort option
/// @param versionSortLabel installable-version ordering label
/// @param recommendedVersionSortLabel recommended version ordering option
/// @param gameVersionSortLabel Minecraft-version ordering option
/// @param modVersionSortLabel mod-version ordering option
@NotNullByDefault
public record RemoteCatalogFilterStrings(
        String categoryLabel,
        String allCategoriesLabel,
        String sortLabel,
        String relevancySortLabel,
        String popularitySortLabel,
        String dateCreatedSortLabel,
        String lastUpdatedSortLabel,
        String totalDownloadsSortLabel,
        String versionSortLabel,
        String recommendedVersionSortLabel,
        String gameVersionSortLabel,
        String modVersionSortLabel) {
    /// Validates the complete filter text bundle.
    public RemoteCatalogFilterStrings {
        Objects.requireNonNull(categoryLabel, "categoryLabel");
        Objects.requireNonNull(allCategoriesLabel, "allCategoriesLabel");
        Objects.requireNonNull(sortLabel, "sortLabel");
        Objects.requireNonNull(relevancySortLabel, "relevancySortLabel");
        Objects.requireNonNull(popularitySortLabel, "popularitySortLabel");
        Objects.requireNonNull(dateCreatedSortLabel, "dateCreatedSortLabel");
        Objects.requireNonNull(lastUpdatedSortLabel, "lastUpdatedSortLabel");
        Objects.requireNonNull(totalDownloadsSortLabel, "totalDownloadsSortLabel");
        Objects.requireNonNull(versionSortLabel, "versionSortLabel");
        Objects.requireNonNull(recommendedVersionSortLabel, "recommendedVersionSortLabel");
        Objects.requireNonNull(gameVersionSortLabel, "gameVersionSortLabel");
        Objects.requireNonNull(modVersionSortLabel, "modVersionSortLabel");
    }

    /// Returns the visible label for one Core-supported catalog sort.
    ///
    /// @param sortType Core repository sort
    /// @return localized visible sort label
    public String sortTypeLabel(RemoteAddonRepository.SortType sortType) {
        return switch (Objects.requireNonNull(sortType, "sortType")) {
            case RELEVANCY -> relevancySortLabel;
            case POPULARITY -> popularitySortLabel;
            case DATE_CREATED -> dateCreatedSortLabel;
            case LAST_UPDATED -> lastUpdatedSortLabel;
            case TOTAL_DOWNLOADS -> totalDownloadsSortLabel;
        };
    }

    /// Returns the visible label for one installable-version ordering mode.
    ///
    /// @param mode version ordering mode
    /// @return localized visible version-order label
    public String versionSortModeLabel(RemoteAddonVersionSortMode mode) {
        return switch (Objects.requireNonNull(mode, "mode")) {
            case RECOMMENDED -> recommendedVersionSortLabel;
            case GAME_VERSION -> gameVersionSortLabel;
            case MOD_VERSION -> modVersionSortLabel;
        };
    }

    /// Creates deterministic English filter text for focused tests and standalone panels.
    ///
    /// @return immutable English filter text
    public static RemoteCatalogFilterStrings english() {
        return new RemoteCatalogFilterStrings(
                "Category",
                "All categories",
                "Sort by",
                "Relevance",
                "Popularity",
                "Date created",
                "Last updated",
                "Total downloads",
                "Version order",
                "Recommended",
                "Game version",
                "Mod version");
    }

    /// Resolves filter labels from the launcher's existing translation keys.
    ///
    /// @return immutable localized filter text
    public static RemoteCatalogFilterStrings launcherLocalized() {
        return new RemoteCatalogFilterStrings(
                i18n("addon.category"),
                i18n("curse.category.0"),
                i18n("search.sort"),
                i18n("swing.download.sort.relevancy"),
                i18n("curse.sort.popularity"),
                i18n("curse.sort.date_created"),
                i18n("curse.sort.last_updated"),
                i18n("curse.sort.total_downloads"),
                i18n("swing.download.version_sort"),
                i18n("swing.download.version_sort.recommended"),
                i18n("swing.download.version_sort.game"),
                i18n("swing.download.version_sort.mod"));
    }
}
