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
package space.minecraftstl.xyml.ui.swing.page.instances.management.maintenance;

import org.jetbrains.annotations.NotNullByDefault;
import space.minecraftstl.xyml.game.launch.LaunchSession;

import java.nio.file.Path;
import java.util.concurrent.CompletionStage;

/// Starts launch-related maintenance operations through application-owned command services.
@NotNullByDefault
public interface InstanceMaintenanceLaunchActions {
    /// Starts a test-mode launch for the exact managed instance and currently selected account.
    ///
    /// @return observable launch-preparation session
    LaunchSession testLaunch();

    /// Exports a standalone launch script for the exact managed instance and selected account.
    ///
    /// @param scriptFile local destination selected through the native interaction boundary
    /// @return completion yielding the exact normalized generated script path
    CompletionStage<Path> exportLaunchScript(Path scriptFile);
}
