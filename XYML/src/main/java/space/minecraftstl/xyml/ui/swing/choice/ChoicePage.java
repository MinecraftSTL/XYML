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
import java.util.OptionalInt;

/// A contiguous data-source result that may use the source's own page boundaries.
///
/// The returned range may be wider than the requested viewport range, allowing a remote data
/// source to retain its native page size and alignment without exposing those details to the UI.
///
/// @param range the actual half-open range represented by the returned values
/// @param items the values ordered by their stable data-source index
/// @param exactItemCount the exact total item count, when known
/// @param endOfData whether this page reaches the end of a previously unbounded data source
/// @param <T> the non-null choice value type
@NotNullByDefault
public record ChoicePage<T extends Object>(
        IndexRange range,
        @Unmodifiable List<T> items,
        OptionalInt exactItemCount,
        boolean endOfData) {
    /// Validates page consistency and stores an immutable item list.
    public ChoicePage {
        items = List.copyOf(items);
        if (items.size() != range.length()) {
            throw new IllegalArgumentException("items must exactly cover range");
        }
        if (exactItemCount.isPresent() && exactItemCount.getAsInt() < range.endExclusive()) {
            throw new IllegalArgumentException("exactItemCount must contain the returned range");
        }
        if (endOfData) {
            if (exactItemCount.isEmpty()) {
                exactItemCount = OptionalInt.of(range.endExclusive());
            } else if (exactItemCount.getAsInt() != range.endExclusive()) {
                throw new IllegalArgumentException("An end page must end at exactItemCount");
            }
        }
    }
}
