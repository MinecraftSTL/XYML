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
package space.minecraftstl.xyml.ui.swing.page.nbt;

import org.jetbrains.annotations.NotNullByDefault;

import javax.swing.JTree;
import javax.swing.tree.TreePath;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.Objects;

/// Selects the row under a platform popup trigger before the menu opens.
@NotNullByDefault
final class NBTTreePopupMouseListener extends MouseAdapter {
    /// Tree whose selection is updated.
    private final JTree tree;

    /// Creates a listener for one tree.
    NBTTreePopupMouseListener(JTree tree) {
        this.tree = Objects.requireNonNull(tree, "tree");
    }

    /// Selects the row under a popup-trigger press.
    @Override
    public void mousePressed(MouseEvent event) {
        selectPopupRow(event);
    }

    /// Selects the row under a popup-trigger release.
    @Override
    public void mouseReleased(MouseEvent event) {
        selectPopupRow(event);
    }

    /// Selects the event row when it triggers a popup.
    private void selectPopupRow(MouseEvent event) {
        MouseEvent mouseEvent = Objects.requireNonNull(event, "event");
        if (!mouseEvent.isPopupTrigger()) {
            return;
        }
        TreePath path = tree.getPathForLocation(mouseEvent.getX(), mouseEvent.getY());
        if (path != null) {
            tree.setSelectionPath(path);
        }
    }
}
