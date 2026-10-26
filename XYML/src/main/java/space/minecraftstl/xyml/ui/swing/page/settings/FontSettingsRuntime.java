// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.settings;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

/// Applies immediately visible font preferences to the running Swing application.
@FunctionalInterface
@NotNullByDefault
public interface FontSettingsRuntime {
    /// Replaces the launcher UI family while preserving component styles and sizes.
    ///
    /// @param family selected family, or `null` for the look-and-feel default
    void applyLauncherFontFamily(@Nullable String family);
}
