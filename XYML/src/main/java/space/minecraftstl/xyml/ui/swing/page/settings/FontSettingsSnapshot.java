// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.settings;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import space.minecraftstl.xyml.ui.swing.FontAntialiasingMode;

import java.util.Objects;

/// Immutable global font preferences displayed by [FontSettingsPanel].
///
/// @param launcherFontFamily launcher UI family, or `null` for the active look-and-feel default
/// @param logFontFamily game-log family, or `null` for the platform monospaced family
/// @param logFontSize positive finite game-log font size
/// @param antialiasingMode persisted text antialiasing preference
/// @param launcherSettingsWritable whether launcher and log font values can be persisted
/// @param userSettingsWritable whether the per-user antialiasing value can be persisted
@NotNullByDefault
record FontSettingsSnapshot(
        @Nullable String launcherFontFamily,
        @Nullable String logFontFamily,
        double logFontSize,
        FontAntialiasingMode antialiasingMode,
        boolean launcherSettingsWritable,
        boolean userSettingsWritable) {
    /// Validates non-null and numeric invariants.
    FontSettingsSnapshot {
        Objects.requireNonNull(antialiasingMode, "antialiasingMode");
        if (!Double.isFinite(logFontSize) || logFontSize <= 0) {
            throw new IllegalArgumentException("logFontSize must be positive and finite");
        }
    }
}
