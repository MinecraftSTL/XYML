// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

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
