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

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import javax.swing.Icon;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import java.awt.Component;
import java.awt.Container;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/// Measures the handle through its actual Swing paint callback, independently of production hit-area calculations.
@NotNullByDefault
public final class CatalogDragHitAssertions {
    /// Prevents construction of this stateless geometry fixture.
    private CatalogDragHitAssertions() {
    }

    /// Captures the icon rectangle from the badge's paint operation at the row's current allocated position.
    ///
    /// @param list populated list with a shared rich renderer
    /// @param index row to paint
    /// @param <T> list element type
    /// @return actual handle rectangle in list coordinates
    public static <T> Rectangle paintedHandle(JList<T> list, int index) {
        Rectangle cell = Objects.requireNonNull(list.getCellBounds(index, index));
        Component component = list.getCellRenderer().getListCellRendererComponent(
                list, list.getModel().getElementAt(index), index, list.isSelectedIndex(index), false);
        JComponent row = (JComponent) component;
        row.setSize(cell.width, cell.height);
        row.doLayout();
        JLabel badge = Objects.requireNonNull(findLabel(row, "richChoiceListBadge"));
        Icon original = Objects.requireNonNull(badge.getIcon());
        RecordingIcon recording = new RecordingIcon(original);
        badge.setIcon(recording);
        BufferedImage image = new BufferedImage(Math.max(1, badge.getWidth()),
                Math.max(1, badge.getHeight()), BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try {
            badge.paint(graphics);
        } finally {
            graphics.dispose();
            badge.setIcon(original);
        }
        assertNotNull(recording.painted);
        Rectangle result = new Rectangle(Objects.requireNonNull(recording.painted));
        result.translate(cell.x + badge.getX(), cell.y + badge.getY());
        assertFalse(result.isEmpty());
        return result;
    }

    /// Includes the icon corners, center, six dots, and points five pixels beyond each edge.
    ///
    /// @param icon painted rectangle
    /// @return immutable point list used independently of the production hit helper
    public static @Unmodifiable List<Point> handlePoints(Rectangle icon) {
        List<Point> points = new ArrayList<>(List.of(
                new Point(icon.x, icon.y), new Point(icon.x + icon.width - 1, icon.y),
                new Point(icon.x, icon.y + icon.height - 1),
                new Point(icon.x + icon.width - 1, icon.y + icon.height - 1),
                new Point(icon.x + icon.width / 2, icon.y + icon.height / 2),
                new Point(icon.x - 5, icon.y + icon.height / 2),
                new Point(icon.x + icon.width + 5, icon.y + icon.height / 2),
                new Point(icon.x + icon.width / 2, icon.y - 5),
                new Point(icon.x + icon.width / 2, icon.y + icon.height + 5)));
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 2; column++) {
                points.add(new Point(icon.x + 5 + column * 6, icon.y + 4 + row * 6));
            }
        }
        return List.copyOf(points);
    }

    /// Finds the mounted badge rather than constructing a detached replacement row.
    ///
    /// @param root renderer hierarchy
    /// @param name badge name
    /// @return label, or null when absent
    private static @Nullable JLabel findLabel(Container root, String name) {
        for (Component child : root.getComponents()) {
            if (child instanceof JLabel label && name.equals(label.getName())) return label;
            if (child instanceof Container container) {
                @Nullable JLabel found = findLabel(container, name);
                if (found != null) return found;
            }
        }
        return null;
    }

    /// Wraps an icon without changing its geometry, capturing the coordinates chosen by Swing's label UI.
    @NotNullByDefault
    private static final class RecordingIcon implements Icon {
        /// Original unchanged icon.
        private final Icon delegate;

        /// Last rectangle actually painted, or null before painting.
        private @Nullable Rectangle painted;

        /// Keeps the original icon size and paint behavior.
        ///
        /// @param delegate icon to observe
        private RecordingIcon(Icon delegate) {
            this.delegate = delegate;
        }

        /// Captures actual coordinates before painting the original icon.
        @Override
        public void paintIcon(Component component, Graphics graphics, int x, int y) {
            painted = new Rectangle(x, y, getIconWidth(), getIconHeight());
            delegate.paintIcon(component, graphics, x, y);
        }

        /// Returns the original icon width.
        @Override
        public int getIconWidth() {
            return delegate.getIconWidth();
        }

        /// Returns the original icon height.
        @Override
        public int getIconHeight() {
            return delegate.getIconHeight();
        }
    }
}
