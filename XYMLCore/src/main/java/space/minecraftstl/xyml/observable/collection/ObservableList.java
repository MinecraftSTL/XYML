// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.observable.collection;

import org.jetbrains.annotations.NotNullByDefault;
import space.minecraftstl.xyml.observable.Subscription;

import java.util.Collection;
import java.util.List;

/// A toolkit-neutral mutable list that emits immutable changes after successful mutations.
///
/// The list owns its storage, preserves encounter order, and rejects null elements. It is not thread-safe and must
/// be confined or externally synchronized. Notifications are synchronous on the mutating thread after state has
/// changed; listeners may inspect the completed state but should avoid reentrant mutation when event order matters.
@NotNullByDefault
public interface ObservableList<E> extends List<E> {
    /// Registers a synchronous listener and returns its independently cancellable subscription.
    Subscription subscribe(CollectionChangeListener<ListChange<E>> listener);

    /// Replaces the entire contents with a copy of the supplied elements.
    ///
    /// @return true when the logical contents changed, or false when they were already equal
    boolean setAll(Collection<? extends E> elements);

    /// Removes the half-open index range as one semantic change.
    void removeRange(int fromIndex, int toIndex);
}
