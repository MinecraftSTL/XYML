// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.theme;

import org.jetbrains.annotations.NotNullByDefault;

/// Toolkit-neutral contrast level in the inclusive `-1` to `1` range.
///
/// @param value normalized contrast value
@NotNullByDefault
public record ThemeContrast(double value) {
    /// Low-contrast preset.
    public static final ThemeContrast LOW = new ThemeContrast(-1.0);

    /// Standard contrast preset.
    public static final ThemeContrast STANDARD = new ThemeContrast(0.0);

    /// Medium-contrast preset.
    public static final ThemeContrast MEDIUM = new ThemeContrast(0.5);

    /// High-contrast preset.
    public static final ThemeContrast HIGH = new ThemeContrast(1.0);

    /// Validates one contrast value.
    ///
    /// @param value normalized contrast value
    public ThemeContrast {
        if (!Double.isFinite(value) || value < -1.0 || value > 1.0) {
            throw new IllegalArgumentException("Theme contrast must be between -1 and 1: " + value);
        }
    }
}
