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
package space.minecraftstl.xyml.ui.swing.update;

import org.jetbrains.annotations.NotNullByDefault;
import space.minecraftstl.xyml.upgrade.RemoteVersion;

/// Loads one remote launcher version without imposing a UI toolkit or scheduling policy.
@FunctionalInterface
@NotNullByDefault
public interface UpdateCheckSource {
    /// Performs the blocking source request.
    ///
    /// [SwingUpdateCheckService] guarantees that this method runs on its configured worker executor and serializes
    /// distinct requests. Implementations may therefore perform blocking integrity and network operations.
    ///
    /// @param request exact update request
    /// @return fetched remote version
    /// @throws Exception when integrity verification, request construction, or network loading fails
    RemoteVersion fetch(UpdateCheckRequest request) throws Exception;
}
