// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.shaderpacks;

import org.jetbrains.annotations.NotNullByDefault;

import java.util.Objects;

/// Localized lifecycle and mutation status text for the shader-pack catalog.
///
/// @param idleText text before lazy loading starts
/// @param loadingText text while a scan is active
/// @param readyText text after a successful non-empty scan
/// @param emptyText text after a successful empty scan
/// @param failureText scan failure prefix
/// @param writingText mutation-in-progress text
/// @param writeFailedText mutation failure prefix
@NotNullByDefault
public record ShaderPackCatalogStatusStrings(
        String idleText,
        String loadingText,
        String readyText,
        String emptyText,
        String failureText,
        String writingText,
        String writeFailedText) {
    /// Rejects missing or blank localized status text.
    public ShaderPackCatalogStatusStrings {
        requireText(idleText, "idleText");
        requireText(loadingText, "loadingText");
        requireText(readyText, "readyText");
        requireText(emptyText, "emptyText");
        requireText(failureText, "failureText");
        requireText(writingText, "writingText");
        requireText(writeFailedText, "writeFailedText");
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
