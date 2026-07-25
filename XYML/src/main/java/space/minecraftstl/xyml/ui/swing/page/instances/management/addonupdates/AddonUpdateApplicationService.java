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
package space.minecraftstl.xyml.ui.swing.page.instances.management.addonupdates;

import org.jetbrains.annotations.NotNullByDefault;
import space.minecraftstl.xyml.task.Task;

import java.util.Collection;

/// Builds deferred tasks that apply exact add-on updates selected from a completed scan.
@NotNullByDefault
public interface AddonUpdateApplicationService {
    /// Creates a stopped task for the exact selected update objects in caller order.
    ///
    /// Per-item preparation, download, and restoration failures are captured in the successful
    /// aggregate task result so callers can report partial completion and rescan authoritative state.
    /// Cancellation or an unrecoverable task-framework error can still terminate the aggregate task.
    ///
    /// @param updates exact selected update items
    /// @return stopped task that produces ordered successes and failures after explicit execution
    Task<AddonUpdateApplicationResult> applyUpdates(Collection<AddonUpdateItem> updates);
}
