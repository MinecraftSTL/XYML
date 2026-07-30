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
package space.minecraftstl.xyml.ui.swing.page.instances;

import org.jetbrains.annotations.NotNullByDefault;

import java.util.Objects;

/// Localizable visible and accessible text for the installed-instance page.
///
/// @param pageTitle page heading
/// @param searchText search field label and placeholder
/// @param refreshAction normal refresh command label
/// @param refreshingAction in-progress refresh command label
/// @param addAction add-instance command label
/// @param manageAction manage-selected-instance command label
/// @param emptyText text shown when the selected repository has no installed instances
/// @param noSearchResultsText text shown when installed instances exist but none match the search
@NotNullByDefault
public record InstancesStrings(
        String pageTitle,
        String searchText,
        String refreshAction,
        String refreshingAction,
        String addAction,
        String manageAction,
        String emptyText,
        String noSearchResultsText) {
    /// Validates localized instance-page text.
    public InstancesStrings {
        Objects.requireNonNull(pageTitle, "pageTitle");
        Objects.requireNonNull(searchText, "searchText");
        Objects.requireNonNull(refreshAction, "refreshAction");
        Objects.requireNonNull(refreshingAction, "refreshingAction");
        Objects.requireNonNull(addAction, "addAction");
        Objects.requireNonNull(manageAction, "manageAction");
        Objects.requireNonNull(emptyText, "emptyText");
        Objects.requireNonNull(noSearchResultsText, "noSearchResultsText");
    }
}
