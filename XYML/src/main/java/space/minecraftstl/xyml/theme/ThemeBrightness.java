// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.theme;

import org.jetbrains.annotations.NotNullByDefault;

import java.util.Locale;

/// Describes the effective light or dark appearance without depending on a UI toolkit.
@NotNullByDefault
public enum ThemeBrightness {
    /// Light surfaces and dark foreground content.
    LIGHT,

    /// Dark surfaces and light foreground content.
    DARK;

    /// Parses the historical lowercase manifest representation.
    ///
    /// @param value serialized brightness
    /// @return parsed brightness
    /// @throws IllegalArgumentException when the value is unsupported
    public static ThemeBrightness parse(String value) {
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "light" -> LIGHT;
            case "dark" -> DARK;
            default -> throw new IllegalArgumentException("Unsupported theme brightness: " + value);
        };
    }

    /// Returns the canonical manifest representation.
    ///
    /// @return lowercase serialized value
    public String serializedName() {
        return name().toLowerCase(Locale.ROOT);
    }
}
