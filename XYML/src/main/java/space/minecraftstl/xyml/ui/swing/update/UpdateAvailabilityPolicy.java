// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.update;

import org.jetbrains.annotations.NotNullByDefault;
import space.minecraftstl.xyml.upgrade.RemoteVersion;

/// Decides whether one fetched remote version should be offered to the current launcher.
@FunctionalInterface
@NotNullByDefault
public interface UpdateAvailabilityPolicy {
    /// Tests one successful source result.
    ///
    /// @param remoteVersion fetched remote version
    /// @return whether the launcher should offer the release to the user
    boolean isUpdateAvailable(RemoteVersion remoteVersion);
}
