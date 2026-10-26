// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.instances;

import org.jetbrains.annotations.NotNullByDefault;

import java.util.Objects;

/// Localizable repository status and fallback text used by [RepositoryInstancesModel].
///
/// @param loadingStatus initial repository-loading status
/// @param readyStatus repository-ready status
/// @param refreshingStatus active refresh status
/// @param refreshFailedStatus refresh failure status
/// @param unknownVersionDetail fallback detail when a game version cannot be identified
@NotNullByDefault
public record RepositoryInstancesStatusStrings(
        String loadingStatus,
        String readyStatus,
        String refreshingStatus,
        String refreshFailedStatus,
        String unknownVersionDetail) {
    /// Validates localized repository text.
    public RepositoryInstancesStatusStrings {
        Objects.requireNonNull(loadingStatus, "loadingStatus");
        Objects.requireNonNull(readyStatus, "readyStatus");
        Objects.requireNonNull(refreshingStatus, "refreshingStatus");
        Objects.requireNonNull(refreshFailedStatus, "refreshFailedStatus");
        Objects.requireNonNull(unknownVersionDetail, "unknownVersionDetail");
    }
}
