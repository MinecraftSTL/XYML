/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2026 huangyuhui <huanghongxun2008@126.com> and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
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
