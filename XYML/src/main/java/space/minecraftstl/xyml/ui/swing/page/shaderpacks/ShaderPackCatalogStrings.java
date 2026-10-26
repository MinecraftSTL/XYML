// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.shaderpacks;

import org.jetbrains.annotations.NotNullByDefault;

import java.util.Objects;

import static space.minecraftstl.xyml.util.i18n.I18n.i18n;

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

    /// Returns the localized description field label.
    public String descriptionLabel() {
        return i18n("shaderpack.description");
    }

    /// Returns the localized fallback used when package metadata is absent.
    public String descriptionUnavailableText() {
        return i18n("shaderpack.description.unavailable");
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
