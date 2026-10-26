// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.mods;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;

import java.util.List;
import java.util.Objects;

/// Localized command, tooltip, chooser, confirmation, and error text.
///
/// @param refreshAction accessible refresh command name
/// @param refreshTooltip refresh tooltip
/// @param importAction accessible import command name
/// @param importTooltip import tooltip
/// @param openDirectoryAction accessible directory-open command name
/// @param openDirectoryTooltip directory-open tooltip
/// @param revealAction accessible reveal command name
/// @param revealTooltip reveal tooltip
/// @param deleteAction accessible delete command name
/// @param deleteTooltip delete tooltip
/// @param importDialogTitle Mod file chooser title
/// @param modFileDescription Mod file chooser filter description
/// @param deleteConfirmationFormat confirmation containing one file-name placeholder
/// @param errorTitle failure dialog title
@NotNullByDefault
public record ModCatalogActionStrings(
        String refreshAction,
        String refreshTooltip,
        String importAction,
        String importTooltip,
        String openDirectoryAction,
        String openDirectoryTooltip,
        String revealAction,
        String revealTooltip,
        String deleteAction,
        String deleteTooltip,
        String importDialogTitle,
        String modFileDescription,
        String deleteConfirmationFormat,
        String errorTitle) {
    /// Validates all command strings as non-blank presentation text.
    public ModCatalogActionStrings {
        @Unmodifiable List<String> values = List.of(
                refreshAction,
                refreshTooltip,
                importAction,
                importTooltip,
                openDirectoryAction,
                openDirectoryTooltip,
                revealAction,
                revealTooltip,
                deleteAction,
                deleteTooltip,
                importDialogTitle,
                modFileDescription,
                deleteConfirmationFormat,
                errorTitle);
        if (values.stream().map(value -> Objects.requireNonNull(value, "action string"))
                .anyMatch(String::isBlank)) {
            throw new IllegalArgumentException("Mod catalog action strings must not be blank");
        }
    }
}
