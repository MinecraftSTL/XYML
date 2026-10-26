// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

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
