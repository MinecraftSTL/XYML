// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.downloads;

import org.jetbrains.annotations.NotNullByDefault;

/// Describes the lifecycle of the complete game-version catalog load.
@NotNullByDefault
public enum GameVersionCatalogStatus {
    /// No source load has been requested yet.
    IDLE,

    /// The latest source generation is still loading.
    LOADING,

    /// The latest source generation completed successfully.
    READY,

    /// The latest source generation failed and may be retried.
    FAILED
}
