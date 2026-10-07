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
package space.minecraftstl.xyml.ui.swing.page.shaderpacks;

import org.jetbrains.annotations.NotNullByDefault;

import java.util.Objects;

/// Localized action, confirmation, and backend-selection text for shader packs.
@NotNullByDefault
public record ShaderPackCatalogActionStrings(
        String importAction,
        String importTooltip,
        String importDialogTitle,
        String zipFileDescription,
        String enableAction,
        String enableTooltip,
        String disableAction,
        String disableTooltip,
        String deleteAction,
        String deleteTooltip,
        String deleteConfirmationFormat,
        String batchDeleteConfirmationFormat,
        String revealAction,
        String revealTooltip,
        String openDirectoryAction,
        String openDirectoryTooltip,
        String operationFailedTitle,
        String revealFailedTitle,
        String openDirectoryFailedTitle,
        String backendDialogTitle,
        String backendDialogPrompt) {
    /// Rejects missing or blank localized action text.
    public ShaderPackCatalogActionStrings {
        requireText(importAction, "importAction");
        requireText(importTooltip, "importTooltip");
        requireText(importDialogTitle, "importDialogTitle");
        requireText(zipFileDescription, "zipFileDescription");
        requireText(enableAction, "enableAction");
        requireText(enableTooltip, "enableTooltip");
        requireText(disableAction, "disableAction");
        requireText(disableTooltip, "disableTooltip");
        requireText(deleteAction, "deleteAction");
        requireText(deleteTooltip, "deleteTooltip");
        requireText(deleteConfirmationFormat, "deleteConfirmationFormat");
        requireText(batchDeleteConfirmationFormat, "batchDeleteConfirmationFormat");
        requireText(revealAction, "revealAction");
        requireText(revealTooltip, "revealTooltip");
        requireText(openDirectoryAction, "openDirectoryAction");
        requireText(openDirectoryTooltip, "openDirectoryTooltip");
        requireText(operationFailedTitle, "operationFailedTitle");
        requireText(revealFailedTitle, "revealFailedTitle");
        requireText(openDirectoryFailedTitle, "openDirectoryFailedTitle");
        requireText(backendDialogTitle, "backendDialogTitle");
        requireText(backendDialogPrompt, "backendDialogPrompt");
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
}
