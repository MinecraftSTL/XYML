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
package space.minecraftstl.xyml.ui.swing.shell;

import com.formdev.flatlaf.ui.FlatListUI;
import com.formdev.flatlaf.ui.FlatUIUtils;
import org.jetbrains.annotations.NotNullByDefault;

import javax.swing.JComponent;
import javax.swing.ListCellRenderer;
import javax.swing.ListModel;
import javax.swing.ListSelectionModel;
import javax.swing.UIManager;
import java.awt.Component;
import java.awt.Container;
import java.awt.Graphics;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/// Paints rounded combo-box selections for both standard and arbitrary custom renderers.
///
/// FlatLaf automatically replaces rectangular selection fills only for its two standard label renderer classes.
/// Launcher pages also use localized lambda and panel renderers, so this UI paints the selection first and temporarily
/// suppresses only renderer surfaces that would cover it with the same selection color.
@NotNullByDefault
final class RoundedComboBoxListUI extends FlatListUI {
    /// Paints one combo-box row while retaining the active FlatLaf selection radius for custom renderers.
    ///
    /// @param graphics destination list graphics
    /// @param row row index
    /// @param rowBounds row bounds
    /// @param cellRenderer active combo-box renderer
    /// @param dataModel combo-box list model
    /// @param selectionModel list selection model
    /// @param leadIndex keyboard lead index
    @Override
    @SuppressWarnings({"rawtypes", "unchecked"})
    protected void paintCell(
            Graphics graphics,
            int row,
            Rectangle rowBounds,
            ListCellRenderer cellRenderer,
            ListModel dataModel,
            ListSelectionModel selectionModel,
            int leadIndex) {
        // Read live tokens so an already-created popup follows a radius change before its next repaint.
        selectionInsets = UIManager.getInsets("ComboBox.selectionInsets");
        selectionArc = UIManager.getInt("ComboBox.selectionArc");
        if (!selectionModel.isSelectedIndex(row)) {
            super.paintCell(
                    graphics,
                    row,
                    rowBounds,
                    cellRenderer,
                    dataModel,
                    selectionModel,
                    leadIndex);
            return;
        }

        Component renderer = cellRenderer.getListCellRendererComponent(
                list,
                dataModel.getElementAt(row),
                row,
                true,
                FlatUIUtils.isPermanentFocusOwner(list) && row == leadIndex);
        List<OpaqueState> suppressedSurfaces = new ArrayList<>();
        suppressSelectionSurfaces(renderer, suppressedSurfaces);
        graphics.setColor(list.getSelectionBackground());
        paintCellSelection(
                graphics,
                row,
                rowBounds.x,
                rowBounds.y,
                rowBounds.width,
                rowBounds.height);
        try {
            rendererPane.paintComponent(
                    graphics,
                    renderer,
                    list,
                    rowBounds.x,
                    rowBounds.y,
                    rowBounds.width,
                    rowBounds.height,
                    true);
        } finally {
            for (OpaqueState state : suppressedSurfaces) {
                state.component().setOpaque(state.opaque());
            }
        }
    }

    /// Temporarily makes selection-colored renderer surfaces transparent without hiding independent badges.
    ///
    /// @param component current renderer subtree
    /// @param suppressedSurfaces mutable collection of surfaces that must be restored after painting
    private void suppressSelectionSurfaces(Component component, List<OpaqueState> suppressedSurfaces) {
        if (component instanceof JComponent swingComponent
                && swingComponent.isOpaque()
                && Objects.equals(swingComponent.getBackground(), list.getSelectionBackground())) {
            suppressedSurfaces.add(new OpaqueState(swingComponent, swingComponent.isOpaque()));
            swingComponent.setOpaque(false);
        }
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                suppressSelectionSurfaces(child, suppressedSurfaces);
            }
        }
    }

    /// Preserves one renderer surface's original opacity while the list paints its rounded selection underneath.
    ///
    /// @param component renderer surface
    /// @param opaque original opacity
    @NotNullByDefault
    private record OpaqueState(JComponent component, boolean opaque) {
    }
}
