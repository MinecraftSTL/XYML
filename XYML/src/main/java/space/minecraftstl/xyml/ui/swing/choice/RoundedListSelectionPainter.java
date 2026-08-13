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
import org.jetbrains.annotations.NotNullByDefault;

import javax.swing.JList;
import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;

/// Paints list-owned selection backgrounds for custom transparent cell renderers.
///
/// FlatLaf can automatically replace a full rectangular fill only for its standard label renderers. Complex
/// renderer hierarchies delegate here so the active `List.selectionArc` and selection insets remain authoritative.
@NotNullByDefault
public final class RoundedListSelectionPainter {
    /// Prevents utility-class instantiation.
    private RoundedListSelectionPainter() {
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
}
