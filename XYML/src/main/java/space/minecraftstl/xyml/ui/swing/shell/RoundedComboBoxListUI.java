// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

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
