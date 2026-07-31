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
package space.minecraftstl.xyml.ui.swing.page.downloads;

import org.jetbrains.annotations.NotNullByDefault;
import space.minecraftstl.xyml.task.Task;

import java.io.IOException;

/// Creates the task chain that downloads and installs a selected remote modpack version.
///
/// The Swing panel owns task presentation and executor lifecycle; implementations only assemble the
/// real launcher task graph. This separation makes it possible to verify selected-version handoff
/// without downloading an archive in a headless component test.
@NotNullByDefault
public interface RemoteModpackInstallLauncher {
    /// Creates one unstarted installation task for a user-confirmed request.
    ///
    /// @param request selected remote project, version, and target instance identifier
    /// @return non-null task graph ready for presentation and execution
    /// @throws IOException when temporary archive preparation cannot start
    Task<?> createInstallTask(RemoteModpackInstallRequest request) throws IOException;
}
