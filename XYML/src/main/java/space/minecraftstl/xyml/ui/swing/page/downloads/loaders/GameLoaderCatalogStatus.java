// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.downloads.loaders;

import org.jetbrains.annotations.NotNullByDefault;

/// Describes the toolkit-neutral lifecycle of one selected loader catalog.
@NotNullByDefault
public enum GameLoaderCatalogStatus {
    /// No Minecraft version has been explicitly selected.
    AWAITING_GAME_VERSION,

    /// A Minecraft version is selected but no compatible loader catalog is selected.
    AWAITING_LOADER,

    /// A game version and loader kind are selected but no refresh has been requested.
    IDLE,

    /// One explicit VersionList refresh is in flight.
    LOADING,

    /// The latest explicit refresh completed and supplied immutable loader versions.
    READY,

    /// The latest explicit refresh failed; details are available from the snapshot.
    FAILED
}
