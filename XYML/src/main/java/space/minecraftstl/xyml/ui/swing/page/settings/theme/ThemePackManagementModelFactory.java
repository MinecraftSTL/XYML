// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.settings.theme;

import org.jetbrains.annotations.NotNullByDefault;

/// Creates one independently owned theme-pack management model for a settings-page instance.
@FunctionalInterface
@NotNullByDefault
public interface ThemePackManagementModelFactory {
    /// Creates a fresh model that the resulting management panel may close.
    ///
    /// @return fresh theme-pack management model
    ThemePackManagementModel create();
}
