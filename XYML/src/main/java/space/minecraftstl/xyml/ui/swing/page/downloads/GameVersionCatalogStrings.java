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

/// Localizable visible and accessible text for the game-version catalog page.
///
/// @param pageTitle page heading
/// @param searchLabel version-ID search label
/// @param filterLabel version-kind filter label
/// @param allFilter label for all game versions
/// @param releaseFilter label for normal releases
/// @param snapshotFilter label for snapshots and pre-releases
/// @param aprilFoolsFilter label for April Fools' Day versions
/// @param oldFilter label for historical versions
/// @param refreshAction normal refresh command label
/// @param refreshingAction in-progress refresh command label
@NotNullByDefault
public record GameVersionCatalogStrings(
        String pageTitle,
        String searchLabel,
        String filterLabel,
        String allFilter,
        String releaseFilter,
        String snapshotFilter,
        String aprilFoolsFilter,
        String oldFilter,
        String refreshAction,
        String refreshingAction) {
    /// Validates localized game-version catalog text.
    public GameVersionCatalogStrings {
        Objects.requireNonNull(pageTitle, "pageTitle");
        Objects.requireNonNull(searchLabel, "searchLabel");
        Objects.requireNonNull(filterLabel, "filterLabel");
        Objects.requireNonNull(allFilter, "allFilter");
        Objects.requireNonNull(releaseFilter, "releaseFilter");
        Objects.requireNonNull(snapshotFilter, "snapshotFilter");
        Objects.requireNonNull(aprilFoolsFilter, "aprilFoolsFilter");
        Objects.requireNonNull(oldFilter, "oldFilter");
        Objects.requireNonNull(refreshAction, "refreshAction");
        Objects.requireNonNull(refreshingAction, "refreshingAction");
    }

    /// Returns the localized label for one filter value.
    ///
    /// @param filter filter value to present
    /// @return localized filter label
    public String filterText(GameVersionFilter filter) {
        return switch (Objects.requireNonNull(filter, "filter")) {
            case ALL -> allFilter;
            case RELEASE -> releaseFilter;
            case SNAPSHOT -> snapshotFilter;
            case APRIL_FOOLS -> aprilFoolsFilter;
            case OLD -> oldFilter;
        };
    }

    /// Returns the localized classification shown in one loaded version row.
    ///
    /// @param kind exact source-derived version classification
    /// @return localized classification label
    public String kindText(GameVersionKind kind) {
        return switch (Objects.requireNonNull(kind, "kind")) {
            case RELEASE -> releaseFilter;
            case SNAPSHOT -> snapshotFilter;
            case APRIL_FOOLS -> aprilFoolsFilter;
            case OLD -> oldFilter;
        };
    }
}
