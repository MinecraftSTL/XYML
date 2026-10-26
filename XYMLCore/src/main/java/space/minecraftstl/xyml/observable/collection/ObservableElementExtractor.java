// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.observable.collection;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import space.minecraftstl.xyml.observable.property.ObservableValue;

import java.util.List;

/// Selects observable dependencies whose changes update one element already stored in an observable list.
///
/// The returned list and its entries must be non-null. An empty list means that only structural list mutations are
/// observed for that element. The observable list owns the resulting subscriptions until the element is removed.
@FunctionalInterface
@NotNullByDefault
public interface ObservableElementExtractor<E> {
    /// Returns the stable observable dependencies to monitor for the supplied element occurrence.
    @Unmodifiable List<? extends ObservableValue<?>> extract(E element);
}
