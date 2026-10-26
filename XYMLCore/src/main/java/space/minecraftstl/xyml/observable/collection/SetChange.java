// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.observable.collection;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/// Immutable description of the elements actually added to or removed from an observable set.
@NotNullByDefault
public final class SetChange<E> {
    /// Classifies how set contents changed.
    @NotNullByDefault
    public enum Kind {
        /// One or more absent elements were added.
        ADD,
        /// One or more present elements were removed.
        REMOVE,
        /// All previous elements were removed.
        CLEAR
    }

    /// Set that emitted the change.
    private final ObservableSet<E> source;

    /// Mutation classification.
    private final Kind kind;

    /// Removed elements in their former iteration order.
    private final @Unmodifiable Set<E> removedElements;

    /// Added elements in their resulting iteration order.
    private final @Unmodifiable Set<E> addedElements;

    /// Creates an immutable set change from defensive ordered snapshots.
    SetChange(
            ObservableSet<E> source,
            Kind kind,
            Set<? extends E> removedElements,
            Set<? extends E> addedElements) {
        this.source = Objects.requireNonNull(source, "source");
        this.kind = Objects.requireNonNull(kind, "kind");
        this.removedElements = immutableCopy(removedElements);
        this.addedElements = immutableCopy(addedElements);
    }

    /// Returns the set that emitted this change.
    public ObservableSet<E> source() {
        return source;
    }

    /// Returns the mutation classification.
    public Kind kind() {
        return kind;
    }

    /// Returns removed elements in their former iteration order.
    public @Unmodifiable Set<E> removedElements() {
        return removedElements;
    }

    /// Returns added elements in their resulting iteration order.
    public @Unmodifiable Set<E> addedElements() {
        return addedElements;
    }

    /// Copies a set while preserving iteration order and preventing mutation.
    private static <E> @Unmodifiable Set<E> immutableCopy(Set<? extends E> elements) {
        return Collections.unmodifiableSet(new LinkedHashSet<>(elements));
    }
}
