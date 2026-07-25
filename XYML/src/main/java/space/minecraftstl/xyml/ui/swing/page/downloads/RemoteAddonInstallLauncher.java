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

/// Creates one stopped task graph that downloads a selected remote add-on into its selected instance.
@NotNullByDefault
public interface RemoteAddonInstallLauncher {
    /// Builds a direct-install task without starting network or filesystem mutation immediately.
    ///
    /// @param request selected project version and selected-instance destination
    /// @return unstarted task graph
    /// @throws IOException when local task setup cannot reserve a temporary destination
    Task<?> createInstallTask(RemoteAddonInstallRequest request) throws IOException;
}
