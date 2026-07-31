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

import java.util.List;
import java.util.ArrayList;
import java.util.Objects;
import java.util.concurrent.CompletionStage;

/// Supplies cheap stable identifiers in addition to viewport-driven presentation rows.
///
/// Identifier enumeration must not resolve icons, inspect archives, or perform other row-level I/O. It exists so
/// compact selectors can apply an independent persistent ordering while still loading only visible row details.
///
/// @param <T> non-null loaded row type
@NotNullByDefault
public interface IdentifiedChoiceDataSource<T extends Object> extends ViewportChoiceDataSource<T> {
    /// Returns stable identifiers in the source's current page order.
    ///
    /// @return immutable exact identifier list
    default @Unmodifiable List<String> stableItemIds() {
        int count = exactItemCount().orElseThrow(() -> new IllegalStateException(
                "Synthetic stable identifiers require an exact item count"));
        List<String> identifiers = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            identifiers.add(syntheticStableId(index));
        }
        return List.copyOf(identifiers);
    }

    /// Loads one row by stable identifier without materializing unrelated source rows.
    ///
    /// @param stableId current stable identifier
    /// @param cancellation cooperative cancellation signal
    /// @return eventual row value
    default CompletionStage<T> loadItem(String stableId, LoadCancellation cancellation) {
        int index = parseSyntheticStableId(Objects.requireNonNull(stableId, "stableId"));
        Objects.requireNonNull(cancellation, "cancellation");
        return load(IndexRange.ofLength(index, 1), cancellation).thenApply(page -> {
            int offset = index - page.range().startInclusive();
            if (offset < 0 || offset >= page.items().size()) {
                throw new IllegalStateException("Identified source did not return requested row " + index);
            }
            return page.items().get(offset);
        });
    }

    /// Creates a collision-resistant synthetic identifier for an exact indexed source.
    private static String syntheticStableId(int index) {
        return "@source-index:" + index;
    }

    /// Parses one default synthetic identifier into its non-negative source index.
    private static int parseSyntheticStableId(String stableId) {
        String prefix = "@source-index:";
        if (!stableId.startsWith(prefix)) {
            throw new IllegalArgumentException("Unknown synthetic stable identifier: " + stableId);
        }
        try {
            int index = Integer.parseInt(stableId.substring(prefix.length()));
            if (index < 0) {
                throw new IllegalArgumentException("Synthetic stable index must not be negative");
            }
            return index;
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException("Malformed synthetic stable identifier: " + stableId, failure);
        }
    }
}
