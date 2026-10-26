// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.resourcepacks;

import org.jetbrains.annotations.NotNullByDefault;

import java.util.Objects;

/// Localized lifecycle text used by [DefaultResourcePackCatalogModel].
///
/// @param idleStatus status before lazy loading starts
/// @param loadingStatus status while one disk-scan generation is active
/// @param readyStatus status when visible packs are available
/// @param emptyStatus status when a successful scan has no visible packs
/// @param unsupportedStatus status when the Minecraft version cannot use resource packs
/// @param failedStatus prefix after the latest disk scan fails
/// @param unknownFailure fallback detail when a failure has no readable message
/// @param writeBusyStatus status while one local mutation and follow-up scan are active
/// @param writeFailedStatus prefix after the latest local mutation fails
@NotNullByDefault
public record ResourcePackCatalogStatusStrings(
        String idleStatus,
        String loadingStatus,
        String readyStatus,
        String emptyStatus,
        String unsupportedStatus,
        String failedStatus,
        String unknownFailure,
        String writeBusyStatus,
        String writeFailedStatus) {
    /// Validates every localized lifecycle value.
    public ResourcePackCatalogStatusStrings {
        Objects.requireNonNull(idleStatus, "idleStatus");
        Objects.requireNonNull(loadingStatus, "loadingStatus");
        Objects.requireNonNull(readyStatus, "readyStatus");
        Objects.requireNonNull(emptyStatus, "emptyStatus");
        Objects.requireNonNull(unsupportedStatus, "unsupportedStatus");
        Objects.requireNonNull(failedStatus, "failedStatus");
        Objects.requireNonNull(unknownFailure, "unknownFailure");
        Objects.requireNonNull(writeBusyStatus, "writeBusyStatus");
        Objects.requireNonNull(writeFailedStatus, "writeFailedStatus");
    }
}
