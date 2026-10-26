// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.downloads.loaders;

import org.jetbrains.annotations.NotNullByDefault;

/// Classifies one selectable loader entry without imposing Swing presentation details.
///
/// The categories intentionally preserve the distinctions exposed by the historical installer:
/// companion APIs, Legacy Fabric entries, and Cleanroom are not folded into a generic loader.
@NotNullByDefault
public enum GameLoaderCategory {
    /// A current-generation mod loader.
    MOD_LOADER,

    /// An API add-on intended to accompany a compatible mod loader.
    API,

    /// A loader retained for older Minecraft releases.
    LEGACY_LOADER,

    /// An API add-on intended for a legacy loader.
    LEGACY_API,

    /// The 1.12.2-era Cleanroom loader.
    CLEANROOM,

    /// A client optimization component rather than a conventional mod loader.
    OPTIMIZATION
}
