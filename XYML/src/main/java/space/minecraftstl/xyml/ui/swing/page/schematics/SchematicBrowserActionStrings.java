// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.schematics;

import org.jetbrains.annotations.NotNullByDefault;

import java.util.Formattable;
import java.util.Formatter;
import java.util.IllegalFormatException;
import java.util.Objects;

/// Localizable text for schematic file-system actions and their failure feedback.
///
/// @param importAction import command label
/// @param importTooltip import command tooltip
/// @param importDialogTitle import file chooser title
/// @param litematicFileDescription Litematic chooser filter description
/// @param createDirectoryAction create-directory command label and dialog title
/// @param createDirectoryTooltip create-directory command tooltip
/// @param createDirectoryPrompt create-directory input prompt
/// @param deleteAction delete command label and confirmation title
/// @param deleteTooltip delete command tooltip
/// @param deleteConfirmationFormat confirmation format accepting the target file name as one string argument
/// @param revealAction reveal-in-file-manager command label
/// @param revealTooltip reveal-in-file-manager command tooltip
/// @param writingStatus status shown while a file-system mutation is running
/// @param writeFailedStatus status shown after a file-system mutation fails
/// @param operationFailedTitle generic mutation failure dialog title
/// @param revealFailedTitle reveal failure dialog title
@NotNullByDefault
public record SchematicBrowserActionStrings(
        String importAction,
        String importTooltip,
        String importDialogTitle,
        String litematicFileDescription,
        String createDirectoryAction,
        String createDirectoryTooltip,
        String createDirectoryPrompt,
        String deleteAction,
        String deleteTooltip,
        String deleteConfirmationFormat,
        String revealAction,
        String revealTooltip,
        String writingStatus,
        String writeFailedStatus,
        String operationFailedTitle,
        String revealFailedTitle) {
    /// Rejects missing, blank, or unusable localized action text.
    public SchematicBrowserActionStrings {
        requireText(importAction, "importAction");
        requireText(importTooltip, "importTooltip");
        requireText(importDialogTitle, "importDialogTitle");
        requireText(litematicFileDescription, "litematicFileDescription");
        requireText(createDirectoryAction, "createDirectoryAction");
        requireText(createDirectoryTooltip, "createDirectoryTooltip");
        requireText(createDirectoryPrompt, "createDirectoryPrompt");
        requireText(deleteAction, "deleteAction");
        requireText(deleteTooltip, "deleteTooltip");
        requireText(deleteConfirmationFormat, "deleteConfirmationFormat");
        requireText(revealAction, "revealAction");
        requireText(revealTooltip, "revealTooltip");
        requireText(writingStatus, "writingStatus");
        requireText(writeFailedStatus, "writeFailedStatus");
        requireText(operationFailedTitle, "operationFailedTitle");
        requireText(revealFailedTitle, "revealFailedTitle");

        StringArgumentProbe probe = new StringArgumentProbe();
        try {
            deleteConfirmationFormat.formatted("");
            deleteConfirmationFormat.formatted(probe);
        } catch (IllegalFormatException failure) {
            throw new IllegalArgumentException(
                    "deleteConfirmationFormat must accept one string argument", failure);
        }
        if (probe.usageCount() != 1) {
            throw new IllegalArgumentException(
                    "deleteConfirmationFormat must consume exactly one string argument");
        }
    }

    /// Rejects missing or blank presentation text.
    ///
    /// @param value localized value
    /// @param name record component name
    private static void requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

    /// Counts compatible string-conversion placeholders without relying on formatted output.
    @NotNullByDefault
    private static final class StringArgumentProbe implements Formattable {
        /// Number of string conversions that consumed this probe.
        private int usageCount;

        /// Creates an unused probe.
        private StringArgumentProbe() {
        }

        /// Records one compatible `%s` or `%S` conversion.
        @Override
        public void formatTo(Formatter formatter, int flags, int width, int precision) {
            usageCount++;
        }

        /// Returns the number of compatible conversions that consumed the probe.
        ///
        /// @return usage count
        private int usageCount() {
            return usageCount;
        }
    }
}
