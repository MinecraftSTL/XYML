// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.mods;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;

import java.util.List;
import java.util.Objects;

/// Localized status text used by the toolkit-neutral Mod catalog model.
///
/// @param loadingText full-index refresh text
/// @param emptyText ready text when no filtered rows remain
/// @param readyCountFormat ready text containing one integer count placeholder
/// @param loadFailureFormat refresh failure text containing one detail placeholder
/// @param importingText import progress text
/// @param enablingText enable progress text
/// @param disablingText disable progress text
/// @param deletingText deletion progress text
/// @param writeFailureFormat mutation failure text containing one detail placeholder
@NotNullByDefault
public record ModCatalogStatusStrings(
        String loadingText,
        String emptyText,
        String readyCountFormat,
        String loadFailureFormat,
        String importingText,
        String enablingText,
        String disablingText,
        String deletingText,
        String writeFailureFormat) {
    /// Validates all status strings as non-blank presentation text.
    public ModCatalogStatusStrings {
        @Unmodifiable List<String> values = List.of(
                loadingText,
                emptyText,
                readyCountFormat,
                loadFailureFormat,
                importingText,
                enablingText,
                disablingText,
                deletingText,
                writeFailureFormat);
        if (values.stream().map(value -> Objects.requireNonNull(value, "status string"))
                .anyMatch(String::isBlank)) {
            throw new IllegalArgumentException("Mod catalog status strings must not be blank");
        }
    }

    /// Formats one successful ready count.
    ///
    /// @param count exact filtered item count
    /// @return localized ready text
    public String readyText(int count) {
        return count == 0 ? emptyText : readyCountFormat.formatted(count);
    }

    /// Formats one refresh failure.
    ///
    /// @param detail normalized failure detail
    /// @return localized failure text
    public String loadFailureText(String detail) {
        return loadFailureFormat.formatted(detail);
    }

    /// Formats one mutation failure.
    ///
    /// @param detail normalized failure detail
    /// @return localized mutation failure text
    public String writeFailureText(String detail) {
        return writeFailureFormat.formatted(detail);
    }
}
