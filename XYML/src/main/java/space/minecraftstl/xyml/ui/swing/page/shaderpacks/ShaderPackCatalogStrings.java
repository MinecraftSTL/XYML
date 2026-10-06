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

/// Localized content and detail labels for installed shader packs.
@NotNullByDefault
public record ShaderPackCatalogStrings(
        String pageTitle,
        String refreshAction,
        String refreshingAction,
        String refreshTooltip,
        String retryAction,
        String retryTooltip,
        String detailsTitle,
        String noSelectionText,
        String fileNameLabel,
        String pathLabel,
        String enabledLabel,
        String enabledText,
        String disabledText,
        String invalidText,
        String backendsLabel,
        String noBackendText) {
    /// Rejects missing or blank localized labels.
    public ShaderPackCatalogStrings {
        requireText(pageTitle, "pageTitle");
        requireText(refreshAction, "refreshAction");
        requireText(refreshingAction, "refreshingAction");
        requireText(refreshTooltip, "refreshTooltip");
        requireText(retryAction, "retryAction");
        requireText(retryTooltip, "retryTooltip");
        requireText(detailsTitle, "detailsTitle");
        requireText(noSelectionText, "noSelectionText");
        requireText(fileNameLabel, "fileNameLabel");
        requireText(pathLabel, "pathLabel");
        requireText(enabledLabel, "enabledLabel");
        requireText(enabledText, "enabledText");
        requireText(disabledText, "disabledText");
        requireText(invalidText, "invalidText");
        requireText(backendsLabel, "backendsLabel");
        requireText(noBackendText, "noBackendText");
    }

    /// Returns the enabled-state text.
    ///
    /// @param enabled enabled state
    /// @return localized enabled or disabled state
    public String enabledText(boolean enabled) {
        return enabled ? enabledText : disabledText;
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
