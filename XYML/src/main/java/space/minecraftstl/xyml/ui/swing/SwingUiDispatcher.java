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
package space.minecraftstl.xyml.ui.swing;

import org.jetbrains.annotations.NotNullByDefault;
import space.minecraftstl.xyml.ui.UiDispatcher;

import javax.swing.SwingUtilities;
import java.util.Objects;

/// Adapts the toolkit-neutral [UiDispatcher] contract to the Swing event dispatch thread.
@NotNullByDefault
public enum SwingUiDispatcher implements UiDispatcher {
    /// Shared stateless dispatcher used by Swing-facing services.
    INSTANCE;

    /// Returns whether the caller is currently running on the Swing event dispatch thread.
    @Override
    public boolean isDispatchThread() {
        return SwingUtilities.isEventDispatchThread();
    }

    /// Queues an operation on the Swing event dispatch thread.
    ///
    /// @param operation the non-null operation to queue
    @Override
    public void dispatch(Runnable operation) {
        SwingUtilities.invokeLater(Objects.requireNonNull(operation, "operation"));
    }
}
