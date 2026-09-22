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

import com.formdev.flatlaf.FlatLightLaf;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.swing.DefaultListModel;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import java.awt.Dimension;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.MouseEvent;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

/// Verifies exact row hit testing and blank-space selection policies.
@NotNullByDefault
public final class RowBoundsCheckedListTest {
    /// Installs the project's FlatLaf theme before Swing hit tests run.
    @BeforeAll
    public static void installLookAndFeel() throws Exception {
        SwingUtilities.invokeAndWait(FlatLightLaf::setup);
    }

    /// Retaining policy keeps the current row and emits no selection transition for blank space.
    @Test
    public void retainsSelectionWhenClickingBelowLastRow() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            RowBoundsCheckedList<String> list = list(
                    RowBoundsCheckedList.BlankClickPolicy.RETAIN,
                    0);
            AtomicInteger selectionEvents = new AtomicInteger();
            list.addListSelectionListener(event -> {
                if (!event.getValueIsAdjusting()) {
                    selectionEvents.incrementAndGet();
                }
            });
            Point blankPoint = blankBelowLastRow(list);

            click(list, blankPoint);

            assertAll(
                    () -> assertEquals(0, list.getSelectedIndex()),
                    () -> assertEquals(-1, list.locationToIndex(blankPoint)),
                    () -> assertEquals(0, selectionEvents.get()));
        });
    }

    /// Clearing policy removes the current row when blank space is pressed.
    @Test
    public void clearsSelectionWhenClickingBelowLastRow() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            RowBoundsCheckedList<String> list = list(
                    RowBoundsCheckedList.BlankClickPolicy.CLEAR,
                    1);
            AtomicInteger selectionEvents = new AtomicInteger();
            list.addListSelectionListener(event -> {
                if (!event.getValueIsAdjusting()) {
                    selectionEvents.incrementAndGet();
                }
            });
            Point blankPoint = blankBelowLastRow(list);

            click(list, blankPoint);

            assertAll(
                    () -> assertEquals(-1, list.getSelectedIndex()),
                    () -> assertEquals(-1, list.locationToIndex(blankPoint)),
                    () -> assertEquals(1, selectionEvents.get()));
        });
    }

    /// Both policies still select a row when the pointer is inside its actual cell bounds.
    @Test
    public void selectsActualRowInsideCellBounds() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            for (RowBoundsCheckedList.BlankClickPolicy policy
                    : RowBoundsCheckedList.BlankClickPolicy.values()) {
                RowBoundsCheckedList<String> list = list(policy, -1);
                Rectangle secondRow = Objects.requireNonNull(list.getCellBounds(1, 1));

                click(list, new Point(
                        secondRow.x + secondRow.width / 2,
                        secondRow.y + secondRow.height / 2));

                assertEquals(1, list.getSelectedIndex());
            }
        });
    }

    /// Blank clicks on an empty list remain safe under both policies.
    @Test
    public void handlesBlankClickOnEmptyModel() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            for (RowBoundsCheckedList.BlankClickPolicy policy
                    : RowBoundsCheckedList.BlankClickPolicy.values()) {
                DefaultListModel<String> model = new DefaultListModel<>();
                RowBoundsCheckedList<String> list = new RowBoundsCheckedList<>(model, policy);
                list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
                list.setFixedCellHeight(20);
                list.setSize(new Dimension(200, 120));

                click(list, new Point(20, 80));

                assertEquals(-1, list.getSelectedIndex());
            }
        });
    }

    /// Creates one sized list with optional initial selection.
    ///
    /// @param policy blank-click policy
    /// @param selectedIndex initial selection, or -1 for none
    /// @return configured test list
    private static RowBoundsCheckedList<String> list(
            RowBoundsCheckedList.BlankClickPolicy policy,
            int selectedIndex) {
        DefaultListModel<String> model = new DefaultListModel<>();
        model.addElement("first");
        model.addElement("second");
        RowBoundsCheckedList<String> list = new RowBoundsCheckedList<>(model, policy);
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setFixedCellHeight(20);
        list.setSize(new Dimension(200, 120));
        list.setSelectedIndex(selectedIndex);
        return list;
    }

    /// Returns a point below the last row but still inside the list component.
    ///
    /// @param list target list
    /// @return blank point
    private static Point blankBelowLastRow(RowBoundsCheckedList<String> list) {
        Rectangle lastRow = Objects.requireNonNull(list.getCellBounds(
                list.getModel().getSize() - 1,
                list.getModel().getSize() - 1));
        return new Point(lastRow.x + 4, lastRow.y + lastRow.height + 5);
    }

    /// Dispatches one primary-button press and release on the EDT.
    ///
    /// @param list target list
    /// @param point list-coordinate click point
    private static void click(RowBoundsCheckedList<String> list, Point point) {
        long when = System.currentTimeMillis();
        list.dispatchEvent(new MouseEvent(
                list,
                MouseEvent.MOUSE_PRESSED,
                when,
                0,
                point.x,
                point.y,
                1,
                false,
                MouseEvent.BUTTON1));
        list.dispatchEvent(new MouseEvent(
                list,
                MouseEvent.MOUSE_RELEASED,
                when + 1L,
                0,
                point.x,
                point.y,
                1,
                false,
                MouseEvent.BUTTON1));
    }
}
