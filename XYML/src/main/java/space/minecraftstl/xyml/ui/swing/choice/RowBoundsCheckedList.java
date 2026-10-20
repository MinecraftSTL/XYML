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

import javax.swing.JList;
import javax.swing.ListModel;
import javax.swing.SwingUtilities;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.MouseEvent;
import java.util.Objects;

/// A [JList] that never resolves a point outside a row to that row's nearest index.
///
/// Swing's default list hit testing returns the final row for points below the last cell. That
/// behavior makes blank space select the last item. This class returns `-1` for blank space and
/// applies the configured policy to primary-button presses outside all rows.
///
/// @param <E> the non-null list element type
@NotNullByDefault
public final class RowBoundsCheckedList<E extends Object> extends JList<E> {
    /// Behavior applied when one primary-button press falls outside every logical row.
    @NotNullByDefault
    public enum BlankClickPolicy {
        /// Keep the current selection unchanged.
        RETAIN,

        /// Clear the current selection.
        CLEAR
    }

    /// Policy applied to primary-button presses outside all rows.
    private final BlankClickPolicy blankClickPolicy;

    /// Creates a list over a model with the requested blank-click policy.
    ///
    /// @param dataModel list model
    /// @param blankClickPolicy blank-click selection policy
    public RowBoundsCheckedList(
            ListModel<E> dataModel,
            BlankClickPolicy blankClickPolicy) {
        super(dataModel);
        this.blankClickPolicy = Objects.requireNonNull(blankClickPolicy, "blankClickPolicy");
    }

    /// Creates a list over an array with the requested blank-click policy.
    ///
    /// @param listData initial list values
    /// @param blankClickPolicy blank-click selection policy
    public RowBoundsCheckedList(
            E[] listData,
            BlankClickPolicy blankClickPolicy) {
        super(listData);
        this.blankClickPolicy = Objects.requireNonNull(blankClickPolicy, "blankClickPolicy");
    }

    /// Returns a row index only when the point is inside that row's actual cell bounds.
    ///
    /// @param location list-coordinate point
    /// @return exact row index, or `-1` for blank space
    @Override
    public int locationToIndex(Point location) {
        return actualIndexAt(Objects.requireNonNull(location, "location"));
    }

    /// Applies the configured selection policy before normal list mouse handling.
    ///
    /// @param event mouse event delivered to this list
    @Override
    protected void processMouseEvent(MouseEvent event) {
        MouseEvent mouseEvent = Objects.requireNonNull(event, "event");
        if (mouseEvent.getID() == MouseEvent.MOUSE_PRESSED
                && SwingUtilities.isLeftMouseButton(mouseEvent)
                && isEnabled()
                && actualIndexAt(mouseEvent.getPoint()) < 0) {
            if (blankClickPolicy == BlankClickPolicy.CLEAR) {
                clearSelection();
            }
            requestFocusInWindow();
            mouseEvent.consume();
            return;
        }
        super.processMouseEvent(mouseEvent);
    }

    /// Returns Swing's nearest row index without applying the blank-space correction.
    ///
    /// Viewport loading uses this method to preserve its existing nearest-row planning behavior.
    ///
    /// @param location list-coordinate point
    /// @return nearest row index, or `-1` when unavailable
    int nearestIndexAt(Point location) {
        return super.locationToIndex(Objects.requireNonNull(location, "location"));
    }

    /// Resolves a point to an exact row only when it lies inside that row's cell rectangle.
    ///
    /// @param point list-coordinate point
    /// @return exact row index, or `-1` for blank space
    private int actualIndexAt(Point point) {
        int index = super.locationToIndex(point);
        if (index < 0) {
            return -1;
        }
        Rectangle bounds = super.getCellBounds(index, index);
        return bounds != null && bounds.contains(point) ? index : -1;
    }
}
