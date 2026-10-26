// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.downloads;

import org.jetbrains.annotations.NotNullByDefault;

import java.util.Objects;

/// Localizable lifecycle text used by [DefaultGameVersionCatalogModel].
///
/// @param idleStatus status before lazy loading starts
/// @param loadingStatus status while one source generation is active
/// @param readyStatus status when visible results are available
/// @param emptyStatus status when a successful catalog has no visible results
/// @param failedStatus status after the latest source generation fails
@NotNullByDefault
public record GameVersionCatalogStatusStrings(
        String idleStatus,
        String loadingStatus,
        String readyStatus,
        String emptyStatus,
        String failedStatus) {
    /// Validates localized status text.
    public GameVersionCatalogStatusStrings {
        Objects.requireNonNull(idleStatus, "idleStatus");
        Objects.requireNonNull(loadingStatus, "loadingStatus");
        Objects.requireNonNull(readyStatus, "readyStatus");
        Objects.requireNonNull(emptyStatus, "emptyStatus");
        Objects.requireNonNull(failedStatus, "failedStatus");
    }
}
