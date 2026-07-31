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
/// @param popularitySortLabel popularity sort option
/// @param nameSortLabel name sort option
/// @param dateCreatedSortLabel creation-date sort option
/// @param lastUpdatedSortLabel last-update sort option
/// @param authorSortLabel author sort option
/// @param totalDownloadsSortLabel total-download sort option
@NotNullByDefault
public record RemoteCatalogFilterStrings(
        String categoryLabel,
        String allCategoriesLabel,
        String sortLabel,
        String popularitySortLabel,
        String nameSortLabel,
        String dateCreatedSortLabel,
        String lastUpdatedSortLabel,
        String authorSortLabel,
        String totalDownloadsSortLabel) {
    /// Validates the complete filter text bundle.
    public RemoteCatalogFilterStrings {
        Objects.requireNonNull(categoryLabel, "categoryLabel");
        Objects.requireNonNull(allCategoriesLabel, "allCategoriesLabel");
        Objects.requireNonNull(sortLabel, "sortLabel");
        Objects.requireNonNull(popularitySortLabel, "popularitySortLabel");
        Objects.requireNonNull(nameSortLabel, "nameSortLabel");
        Objects.requireNonNull(dateCreatedSortLabel, "dateCreatedSortLabel");
        Objects.requireNonNull(lastUpdatedSortLabel, "lastUpdatedSortLabel");
        Objects.requireNonNull(authorSortLabel, "authorSortLabel");
        Objects.requireNonNull(totalDownloadsSortLabel, "totalDownloadsSortLabel");
    }

    /// Returns the visible label for one Core-supported catalog sort.
    ///
    /// @param sortType Core repository sort
    /// @return localized visible sort label
    public String sortTypeLabel(RemoteAddonRepository.SortType sortType) {
        return switch (Objects.requireNonNull(sortType, "sortType")) {
            case POPULARITY -> popularitySortLabel;
            case NAME -> nameSortLabel;
            case DATE_CREATED -> dateCreatedSortLabel;
            case LAST_UPDATED -> lastUpdatedSortLabel;
            case AUTHOR -> authorSortLabel;
            case TOTAL_DOWNLOADS -> totalDownloadsSortLabel;
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
                "Popularity",
                "Name",
                "Date created",
                "Last updated",
                "Author",
                "Total downloads");
    }

    /// Resolves filter labels from the launcher's existing translation keys.
    ///
    /// @return immutable localized filter text
    public static RemoteCatalogFilterStrings launcherLocalized() {
        return new RemoteCatalogFilterStrings(
                i18n("addon.category"),
                i18n("curse.category.0"),
                i18n("search.sort"),
                i18n("curse.sort.popularity"),
                i18n("curse.sort.name"),
                i18n("curse.sort.date_created"),
                i18n("curse.sort.last_updated"),
                i18n("curse.sort.author"),
                i18n("curse.sort.total_downloads"));
    }
}
