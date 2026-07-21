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
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;

import javax.swing.JList;
import javax.swing.ListCellRenderer;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.util.List;
import java.util.OptionalInt;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests the thin Swing list configuration without creating a native window.
@NotNullByDefault
public final class ViewportChoiceListTest {
    /// Verifies single selection and renderer-component reuse.
    @Test
    public void configuresSingleSelectionAndReusableRenderer() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            ViewportChoiceList<String> choiceList = new ViewportChoiceList<>(
                    new ImmediateDataSource(),
                    value -> value);
            JList<ChoiceListEntry<String>> list = choiceList.getList();
            ListCellRenderer<? super ChoiceListEntry<String>> renderer = list.getCellRenderer();
            ChoiceListEntry<String> firstEntry = ChoiceListEntry.loaded(0, "first");
            ChoiceListEntry<String> secondEntry = ChoiceListEntry.loaded(1, "second");

            Component firstComponent = renderer.getListCellRendererComponent(
                    list, firstEntry, 0, true, true);
            Component secondComponent = renderer.getListCellRendererComponent(
                    list, secondEntry, 1, false, false);

            assertEquals(ListSelectionModel.SINGLE_SELECTION, list.getSelectionMode());
            assertTrue(list.getFixedCellHeight() > 0);
            assertSame(firstComponent, secondComponent);
            choiceList.close();
        });
    }

    /// A bounded data source that completes requests immediately.
    @NotNullByDefault
    private static final class ImmediateDataSource implements ViewportChoiceDataSource<String> {
        /// Returns the exact two-row source size.
        ///
        /// @return two logical rows
        @Override
        public OptionalInt exactItemCount() {
            return OptionalInt.of(2);
        }

        /// Returns values for the exact requested range.
        ///
        /// @param desiredRange the requested range
        /// @param cancellation the cooperative cancellation signal
        /// @return an immediately completed page
        @Override
        public CompletionStage<ChoicePage<String>> load(
                IndexRange desiredRange,
                LoadCancellation cancellation) {
            @Unmodifiable List<String> values = desiredRange.startInclusive() == 0
                    ? List.of("first", "second").subList(0, desiredRange.length())
                    : List.of("second");
            return CompletableFuture.completedFuture(new ChoicePage<>(
                    desiredRange,
                    values,
                    OptionalInt.of(2),
                    desiredRange.endExclusive() == 2));
        }
    }
}
