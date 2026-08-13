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
package space.minecraftstl.xyml.ui.swing.choice;

import com.formdev.flatlaf.ui.FlatListUI;
import com.formdev.flatlaf.ui.FlatUIUtils;
import com.formdev.flatlaf.util.UIScale;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import javax.swing.BorderFactory;
import javax.swing.JList;
import javax.swing.UIManager;
import javax.swing.border.Border;
import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;

/// Paints list-owned selection backgrounds and focus outlines for custom transparent cell renderers.
///
/// FlatLaf can automatically replace a full rectangular fill only for its standard label renderers. Complex
/// renderer hierarchies delegate here so the active list style's selection arc and insets remain authoritative.
@NotNullByDefault
public final class RoundedListSelectionPainter {
    /// Prevents utility-class instantiation.
    private RoundedListSelectionPainter() {
    }

    /// Creates a non-painting border with the active list style's cell margins.
    ///
    /// Custom renderers paint their focus outline here instead of installing FlatLaf's rectangular cell border,
    /// but retain identical content placement by preserving its margins.
    ///
    /// @param list owning list whose UI supplies the cell margins
    /// @return empty border preserving the current cell margins
    public static Border createCellInsetsBorder(JList<?> list) {
        if (list.getUI() instanceof FlatListUI flatListUI) {
            @Nullable Object margins = flatListUI.getStyleableValue(list, "cellMargins");
            if (margins instanceof Insets insets) {
                Insets scaledInsets = UIScale.scale(insets);
                return BorderFactory.createEmptyBorder(
                        scaledInsets.top,
                        scaledInsets.left,
                        scaledInsets.bottom,
                        scaledInsets.right);
            }
        }

        @Nullable Border cellBorder = UIManager.getBorder("List.cellNoFocusBorder");
        Insets insets = cellBorder == null
                ? new Insets(1, 1, 1, 1)
                : cellBorder.getBorderInsets(list);
        return BorderFactory.createEmptyBorder(insets.top, insets.left, insets.bottom, insets.right);
    }

    /// Paints one selected row using the current list UI, with a rectangular fallback for other look and feels.
    ///
    /// @param list owning list whose UI supplies the selection geometry
    /// @param graphics destination graphics
    /// @param row logical selected row
    /// @param width renderer width
    /// @param height renderer height
    /// @param background selected-row background color
    public static void paintSelectedBackground(
            JList<?> list,
            Graphics graphics,
            int row,
            int width,
            int height,
            Color background) {
        Graphics2D copy = (Graphics2D) graphics.create();
        try {
            copy.setColor(background);
            if (list.getUI() instanceof FlatListUI) {
                FlatListUI.paintCellSelection(list, copy, row, 0, 0, width, height);
            } else {
                copy.fillRect(0, 0, width, height);
            }
        } finally {
            copy.dispose();
        }
    }

    /// Paints one focused row outline using the same arc and insets as its selection background.
    ///
    /// @param list owning list whose UI supplies the focus palette and selection geometry
    /// @param graphics destination graphics
    /// @param width renderer width
    /// @param height renderer height
    public static void paintFocusOutline(
            JList<?> list,
            Graphics graphics,
            int width,
            int height) {
        if (!(list.getUI() instanceof FlatListUI flatListUI)) {
            paintFallbackFocusBorder(list, graphics, width, height);
            return;
        }

        @Nullable Object styledColor = flatListUI.getStyleableValue(list, "cellFocusColor");
        @Nullable Color focusColor = styledColor instanceof Color color
                ? color
                : UIManager.getColor("List.cellFocusColor");
        if (focusColor == null) {
            return;
        }

        @Nullable Object styledInsets = flatListUI.getStyleableValue(list, "selectionInsets");
        @Nullable Insets selectionInsets = styledInsets instanceof Insets insets
                ? insets
                : UIManager.getInsets("List.selectionInsets");
        Insets scaledInsets = selectionInsets == null
                ? new Insets(0, 0, 0, 0)
                : UIScale.scale(selectionInsets);
        int outlineWidth = width - scaledInsets.left - scaledInsets.right;
        int outlineHeight = height - scaledInsets.top - scaledInsets.bottom;
        if (outlineWidth <= 0 || outlineHeight <= 0) {
            return;
        }

        @Nullable Object styledArc = flatListUI.getStyleableValue(list, "selectionArc");
        int selectionArc = styledArc instanceof Integer arc
                ? arc
                : UIManager.getInt("List.selectionArc");
        Graphics2D copy = (Graphics2D) graphics.create();
        try {
            FlatUIUtils.setRenderingHints(copy);
            copy.setColor(focusColor);
            FlatUIUtils.paintOutline(
                    copy,
                    scaledInsets.left,
                    scaledInsets.top,
                    outlineWidth,
                    outlineHeight,
                    UIScale.scale(1.0F),
                    UIScale.scale(Math.max(0, selectionArc)));
        } finally {
            copy.dispose();
        }
    }

    /// Paints the current look and feel's native focus border when the list does not use FlatLaf.
    ///
    /// @param list owning list and fallback border component
    /// @param graphics destination graphics
    /// @param width renderer width
    /// @param height renderer height
    private static void paintFallbackFocusBorder(
            JList<?> list,
            Graphics graphics,
            int width,
            int height) {
        @Nullable Border focusBorder = UIManager.getBorder("List.focusCellHighlightBorder");
        if (focusBorder != null) {
            focusBorder.paintBorder(list, graphics, 0, 0, width, height);
        }
    }
}
