// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.settings.theme;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import space.minecraftstl.xyml.theme.ThemeReference;

import java.util.concurrent.CompletionStage;

/// Persists and activates one exact theme reference without exposing global launcher settings to the panel.
@FunctionalInterface
@NotNullByDefault
public interface ThemePackApplication {
    /// Applies one exact package and theme reference.
    ///
    /// @param reference exact selected reference
    /// @return completion stage resolved after persistence and runtime application finish
    CompletionStage<@Nullable Void> apply(ThemeReference reference);
}
