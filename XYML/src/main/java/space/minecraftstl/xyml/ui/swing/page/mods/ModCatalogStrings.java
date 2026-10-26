// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.mods;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;

import java.util.List;
import java.util.Objects;

/// Localized labels for the independent Swing Mod catalog surface.
///
/// @param title page title
/// @param searchLabel search field label and accessible name
/// @param filterLabel enabled-state filter label and accessible name
/// @param allFilterLabel all-items filter label
/// @param enabledFilterLabel enabled-items filter label
/// @param disabledFilterLabel disabled-items filter label
/// @param emptySelectionText details placeholder without a loaded selection
/// @param idLabel Mod identifier label
/// @param versionLabel Mod version label
/// @param gameVersionLabel target game version label
/// @param loaderLabel detected loader label
/// @param authorsLabel author label
/// @param fileLabel current file label
/// @param descriptionLabel description label
/// @param enabledLabel enabled-state checkbox label
@NotNullByDefault
public record ModCatalogStrings(
        String title,
        String searchLabel,
        String filterLabel,
        String allFilterLabel,
        String enabledFilterLabel,
        String disabledFilterLabel,
        String emptySelectionText,
        String idLabel,
        String versionLabel,
        String gameVersionLabel,
        String loaderLabel,
        String authorsLabel,
        String fileLabel,
        String descriptionLabel,
        String enabledLabel) {
    /// Validates all labels as non-blank presentation text.
    public ModCatalogStrings {
        @Unmodifiable List<String> values = List.of(
                title,
                searchLabel,
                filterLabel,
                allFilterLabel,
                enabledFilterLabel,
                disabledFilterLabel,
                emptySelectionText,
                idLabel,
                versionLabel,
                gameVersionLabel,
                loaderLabel,
                authorsLabel,
                fileLabel,
                descriptionLabel,
                enabledLabel);
        if (values.stream().map(value -> Objects.requireNonNull(value, "catalog string"))
                .anyMatch(String::isBlank)) {
            throw new IllegalArgumentException("Mod catalog strings must not be blank");
        }
    }

    /// Returns the localized display label for one filter value.
    ///
    /// @param filter filter value
    /// @return localized filter label
    public String filterText(ModCatalogFilter filter) {
        return switch (filter) {
            case ALL -> allFilterLabel;
            case ENABLED -> enabledFilterLabel;
            case DISABLED -> disabledFilterLabel;
        };
    }
}
