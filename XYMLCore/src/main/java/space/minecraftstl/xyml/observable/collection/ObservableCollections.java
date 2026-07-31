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

import java.util.Collection;
import java.util.Map;

/// Creates observable collections with private storage owned by the returned instance.
///
/// Copy factories preserve the input's encounter order at the time of construction. Later changes to the input are
/// deliberately not reflected, because wrapping externally mutable storage would permit mutations without events.
@NotNullByDefault
public final class ObservableCollections {
    /// Prevents instantiation of this factory class.
    private ObservableCollections() {
    }

    /// Creates an empty insertion-ordered observable list.
    public static <E> ObservableList<E> observableList() {
        return new ObservableArrayList<>();
    }

    /// Creates an observable list by copying the supplied elements in encounter order.
    public static <E> ObservableList<E> observableListCopy(Collection<? extends E> elements) {
        return new ObservableArrayList<>(elements);
    }

    /// Creates an empty observable list that reports updates from extracted element dependencies.
    public static <E> ObservableList<E> observableList(ObservableElementExtractor<? super E> extractor) {
        return new ObservableArrayList<>(extractor);
    }

    /// Copies elements into an observable list that reports updates from extracted element dependencies.
    public static <E> ObservableList<E> observableListCopy(
            Collection<? extends E> elements,
            ObservableElementExtractor<? super E> extractor) {
        return new ObservableArrayList<>(elements, extractor);
    }

    /// Returns a live read-only observable view of the supplied list.
    public static <E> ObservableList<E> unmodifiableObservableList(ObservableList<E> list) {
        return new UnmodifiableObservableList<>(list);
    }

    /// Creates an empty insertion-ordered observable set.
    public static <E> ObservableSet<E> observableSet() {
        return new ObservableLinkedHashSet<>();
    }

    /// Creates an observable set by copying the supplied elements in encounter order.
    public static <E> ObservableSet<E> observableSetCopy(Collection<? extends E> elements) {
        return new ObservableLinkedHashSet<>(elements);
    }

    /// Creates an empty insertion-ordered observable map.
    public static <K, V> ObservableMap<K, V> observableMap() {
        return new ObservableLinkedHashMap<>();
    }

    /// Creates an observable map by copying the supplied mappings in key encounter order.
    public static <K, V> ObservableMap<K, V> observableMapCopy(Map<? extends K, ? extends V> entries) {
        return new ObservableLinkedHashMap<>(entries);
    }
}
