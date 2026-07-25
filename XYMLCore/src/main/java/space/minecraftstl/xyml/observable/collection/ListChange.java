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
package space.minecraftstl.xyml.observable.collection;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;

import java.util.List;
import java.util.Objects;

/// Immutable description of one completed observable-list mutation.
///
/// For contiguous edits, the half-open range identifies the affected span and the item lists contain only that
/// span. For [Kind#RESET], the range starts at zero, ends at the larger snapshot size, and both item lists contain
/// the complete previous and current contents.
@NotNullByDefault
public final class ListChange<E> {
    /// Classifies how list contents changed.
    @NotNullByDefault
    public enum Kind {
        /// One contiguous sequence was inserted.
        ADD,
        /// One contiguous sequence was removed.
        REMOVE,
        /// One contiguous sequence was replaced without changing its length.
        REPLACE,
        /// An observable dependency of one existing element changed without replacing the element.
        UPDATE,
        /// The complete contents changed or a non-contiguous batch edit occurred.
        RESET,
        /// All previous contents were removed.
        CLEAR
    }

    /// List that emitted the change.
    private final ObservableList<E> source;

    /// Mutation classification.
    private final Kind kind;

    /// Inclusive start of the affected span.
    private final int fromIndex;

    /// Exclusive end of the affected span in the larger affected state.
    private final int toIndex;

    /// Immutable previous items for the affected span or complete reset snapshot.
    private final @Unmodifiable List<E> previousItems;

    /// Immutable current items for the affected span or complete reset snapshot.
    private final @Unmodifiable List<E> currentItems;

    /// Creates an immutable list change from defensive item snapshots.
    ListChange(
            ObservableList<E> source,
            Kind kind,
            int fromIndex,
            int toIndex,
            List<? extends E> previousItems,
            List<? extends E> currentItems) {
        this.source = Objects.requireNonNull(source, "source");
        this.kind = Objects.requireNonNull(kind, "kind");
        if (fromIndex < 0 || toIndex < fromIndex) {
            throw new IllegalArgumentException("Invalid affected range: [" + fromIndex + ", " + toIndex + ")");
        }
        this.fromIndex = fromIndex;
        this.toIndex = toIndex;
        this.previousItems = List.copyOf(previousItems);
        this.currentItems = List.copyOf(currentItems);
    }

    /// Returns the list that emitted this change.
    public ObservableList<E> source() {
        return source;
    }

    /// Returns the mutation classification.
    public Kind kind() {
        return kind;
    }

    /// Returns the inclusive start of the affected span.
    public int fromIndex() {
        return fromIndex;
    }

    /// Returns the exclusive end of the affected span in the larger affected state.
    public int toIndex() {
        return toIndex;
    }

    /// Returns immutable previous items for the affected span or complete reset snapshot.
    public @Unmodifiable List<E> previousItems() {
        return previousItems;
    }

    /// Returns immutable current items for the affected span or complete reset snapshot.
    public @Unmodifiable List<E> currentItems() {
        return currentItems;
    }
}
