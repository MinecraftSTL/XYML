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
package space.minecraftstl.xyml.ui.swing.page.instances.management;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import space.minecraftstl.xyml.game.GameInstanceID;

import java.awt.Component;

/// Separates native Swing dialogs from lifecycle operations so the panel remains deterministic in tests.
///
/// All methods are invoked only from the Swing event-dispatch thread. Returning `null` from an input
/// request represents cancellation and never schedules a background mutation.
@NotNullByDefault
public interface InstanceLifecycleInteractions {
    /// Requests a replacement instance name from the user.
    ///
    /// @param owner native dialog owner
    /// @param sourceId current instance identifier
    /// @return raw requested destination, or `null` after cancellation
    @Nullable String requestRename(Component owner, GameInstanceID sourceId);

    /// Requests a duplicate destination and whether worlds should be copied.
    ///
    /// @param owner native dialog owner
    /// @param sourceId current instance identifier
    /// @return confirmed duplicate request, or `null` after cancellation
    @Nullable InstanceLifecycleDuplicateRequest requestDuplicate(Component owner, GameInstanceID sourceId);

    /// Asks for explicit approval before deleting one instance.
    ///
    /// @param owner native dialog owner
    /// @param sourceId current instance identifier
    /// @return whether deletion is approved
    boolean confirmDelete(Component owner, GameInstanceID sourceId);

    /// Displays one terminal lifecycle failure.
    ///
    /// @param owner native dialog owner
    /// @param title visible failure title
    /// @param detail non-blank failure detail
    void showFailure(Component owner, String title, String detail);
}
