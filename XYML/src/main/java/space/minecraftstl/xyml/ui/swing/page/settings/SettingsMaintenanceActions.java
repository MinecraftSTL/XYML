// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.settings;

import org.jetbrains.annotations.NotNullByDefault;
import space.minecraftstl.xyml.ui.swing.update.UpdateCheckRequest;
import space.minecraftstl.xyml.ui.swing.update.UpdateCheckResult;

import java.nio.file.Path;
import java.util.concurrent.CompletionStage;

/// Performs asynchronous launcher-maintenance actions requested by the settings center.
///
/// Implementations own any update-check resources they create and must never perform cache I/O on the Swing EDT.
@NotNullByDefault
interface SettingsMaintenanceActions extends AutoCloseable {
    /// Checks the requested release channel and presents an available release to the user.
    ///
    /// @param request exact release-channel and preview selection
    /// @return stage completed with the successful remote check after any update prompt finishes
    CompletionStage<UpdateCheckResult> checkForUpdates(UpdateCheckRequest request);

    /// Removes all entries under the launcher's cache child directory without deleting the common directory.
    ///
    /// @param commonDirectory effective launcher common directory
    /// @return stage completed with whether the cache directory was cleaned successfully
    CompletionStage<Boolean> clearCache(Path commonDirectory);

    /// Releases resources owned by these maintenance actions.
    @Override
    void close();
}
