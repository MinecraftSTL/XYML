// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.update;

import org.jetbrains.annotations.NotNullByDefault;
import space.minecraftstl.xyml.upgrade.RemoteVersion;

import java.time.Instant;
import java.util.Objects;

/// Immutable successful result of one remote launcher update check.
///
/// @param request exact request that produced the result
/// @param remoteVersion latest version returned by the update service
/// @param updateAvailable whether policy considers the remote version newer or otherwise required
/// @param checkedAt completion time of the successful check
@NotNullByDefault
public record UpdateCheckResult(
        UpdateCheckRequest request,
        RemoteVersion remoteVersion,
        boolean updateAvailable,
        Instant checkedAt) {
    /// Validates one successful update result.
    public UpdateCheckResult {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(remoteVersion, "remoteVersion");
        Objects.requireNonNull(checkedAt, "checkedAt");
    }
}
