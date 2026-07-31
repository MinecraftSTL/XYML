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
package space.minecraftstl.xyml.ui.swing.page.resourcepacks;

import org.jetbrains.annotations.NotNullByDefault;

import java.util.Formattable;
import java.util.Formatter;
import java.util.IllegalFormatException;
import java.util.Objects;

/// Localizable text for installed-resource-pack commands, confirmations, and failure feedback.
///
/// @param importAction import command label
/// @param importTooltip import command tooltip
/// @param importDialogTitle import file chooser title
/// @param zipFileDescription ZIP chooser filter description
/// @param enableAction enable command label
/// @param enableTooltip enable command tooltip
/// @param disableAction disable command label
/// @param disableTooltip disable command tooltip
/// @param incompatibleEnableTitle incompatible-pack confirmation title
/// @param incompatibleEnableConfirmationFormat confirmation format accepting one pack file name
/// @param deleteAction permanent-delete command label and confirmation title
/// @param deleteTooltip permanent-delete command tooltip
/// @param deleteConfirmationFormat confirmation format accepting one pack file name
/// @param revealAction reveal-in-file-manager command label
/// @param revealTooltip reveal-in-file-manager command tooltip
/// @param openDirectoryAction open resource-pack directory command label
/// @param openDirectoryTooltip open resource-pack directory command tooltip
/// @param operationFailedTitle generic mutation failure dialog title
/// @param revealFailedTitle reveal failure dialog title
/// @param openDirectoryFailedTitle resource-pack directory failure dialog title
@NotNullByDefault
public record ResourcePackCatalogActionStrings(
        String importAction,
        String importTooltip,
        String importDialogTitle,
        String zipFileDescription,
        String enableAction,
        String enableTooltip,
        String disableAction,
        String disableTooltip,
        String incompatibleEnableTitle,
        String incompatibleEnableConfirmationFormat,
        String deleteAction,
        String deleteTooltip,
        String deleteConfirmationFormat,
        String revealAction,
        String revealTooltip,
        String openDirectoryAction,
        String openDirectoryTooltip,
        String operationFailedTitle,
        String revealFailedTitle,
        String openDirectoryFailedTitle) {
    /// Rejects missing, blank, or unusable localized action text.
    public ResourcePackCatalogActionStrings {
        requireText(importAction, "importAction");
        requireText(importTooltip, "importTooltip");
        requireText(importDialogTitle, "importDialogTitle");
        requireText(zipFileDescription, "zipFileDescription");
        requireText(enableAction, "enableAction");
        requireText(enableTooltip, "enableTooltip");
        requireText(disableAction, "disableAction");
        requireText(disableTooltip, "disableTooltip");
        requireText(incompatibleEnableTitle, "incompatibleEnableTitle");
        requireText(incompatibleEnableConfirmationFormat, "incompatibleEnableConfirmationFormat");
        requireText(deleteAction, "deleteAction");
        requireText(deleteTooltip, "deleteTooltip");
        requireText(deleteConfirmationFormat, "deleteConfirmationFormat");
        requireText(revealAction, "revealAction");
        requireText(revealTooltip, "revealTooltip");
        requireText(openDirectoryAction, "openDirectoryAction");
        requireText(openDirectoryTooltip, "openDirectoryTooltip");
        requireText(operationFailedTitle, "operationFailedTitle");
        requireText(revealFailedTitle, "revealFailedTitle");
        requireText(openDirectoryFailedTitle, "openDirectoryFailedTitle");

        requireSingleStringArgument(
                incompatibleEnableConfirmationFormat,
                "incompatibleEnableConfirmationFormat");
        requireSingleStringArgument(deleteConfirmationFormat, "deleteConfirmationFormat");
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

    /// Verifies that one format consumes exactly one string-compatible argument.
    ///
    /// @param format localized confirmation format
    /// @param name record component name
    private static void requireSingleStringArgument(String format, String name) {
        StringArgumentProbe probe = new StringArgumentProbe();
        try {
            format.formatted("");
            format.formatted(probe);
        } catch (IllegalFormatException failure) {
            throw new IllegalArgumentException(name + " must accept one string argument", failure);
        }
        if (probe.usageCount() != 1) {
            throw new IllegalArgumentException(name + " must consume exactly one string argument");
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
