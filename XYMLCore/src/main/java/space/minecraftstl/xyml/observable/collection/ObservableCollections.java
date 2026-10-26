// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

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
