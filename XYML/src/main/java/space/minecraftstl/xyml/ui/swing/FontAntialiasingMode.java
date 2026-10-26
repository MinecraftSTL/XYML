// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/// Supported launcher text antialiasing preferences and their persisted identifiers.
@NotNullByDefault
public enum FontAntialiasingMode {
    /// Lets AWT choose the platform text-rendering strategy.
    AUTO(null),

    /// Requests sub-pixel LCD text rendering.
    LCD("lcd"),

    /// Requests grayscale text rendering.
    GRAY("gray");

    /// Persisted identifier, or `null` for automatic selection.
    private final @Nullable String persistedValue;

    /// Creates one mode from its persisted identifier.
    ///
    /// @param persistedValue identifier, or `null` for automatic selection
    FontAntialiasingMode(@Nullable String persistedValue) {
        this.persistedValue = persistedValue;
    }

    /// Returns the value written to `user-settings.json`.
    ///
    /// @return persisted identifier, or `null` for automatic selection
    public @Nullable String persistedValue() {
        return persistedValue;
    }

    /// Maps a persisted value without rewriting unknown future values during a read.
    ///
    /// @param persistedValue stored identifier, or `null`
    /// @return corresponding supported mode, defaulting to automatic selection
    public static FontAntialiasingMode fromPersistedValue(@Nullable String persistedValue) {
        if (persistedValue == null) {
            return AUTO;
        }
        return switch (persistedValue.trim().toLowerCase(Locale.ROOT)) {
            case "lcd" -> LCD;
            case "gray" -> GRAY;
            default -> AUTO;
        };
    }
}
