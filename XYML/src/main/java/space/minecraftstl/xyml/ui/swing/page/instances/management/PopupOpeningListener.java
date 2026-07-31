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

import javax.swing.event.PopupMenuEvent;
import javax.swing.event.PopupMenuListener;
import java.util.Objects;

/// Runs one callback only when a combo-box popup opens.
@NotNullByDefault
final class PopupOpeningListener implements PopupMenuListener {
    /// Operation invoked immediately before popup choices become visible.
    private final Runnable operation;

    /// Creates a popup-opening listener.
    ///
    /// @param operation operation invoked when the popup opens
    PopupOpeningListener(Runnable operation) {
        this.operation = Objects.requireNonNull(operation, "operation");
    }

    /// Runs the configured popup-opening operation.
    @Override
    public void popupMenuWillBecomeVisible(PopupMenuEvent event) {
        operation.run();
    }

    /// Performs no work when the popup closes.
    @Override
    public void popupMenuWillBecomeInvisible(PopupMenuEvent event) {
    }

    /// Performs no work when popup display is canceled.
    @Override
    public void popupMenuCanceled(PopupMenuEvent event) {
    }
}
