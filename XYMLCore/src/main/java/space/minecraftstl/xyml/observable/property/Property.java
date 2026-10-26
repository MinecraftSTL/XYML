// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.observable.property;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

/// Defines a writable property with one-way and bidirectional binding support.
@NotNullByDefault
public interface Property<T> extends ReadOnlyProperty<T> {
    /// Replaces the current value unless this property is one-way bound.
    void setValue(@Nullable T value);

    /// Binds this property to the source and immediately adopts the source value.
    ///
    /// Rebinding to the same source has no effect. Binding to a different source first removes the old binding.
    void bind(ObservableValue<T> source);

    /// Removes the current one-way binding while retaining the last propagated value.
    void unbind();

    /// Returns whether this property currently has a one-way binding.
    boolean isBound();

    /// Connects this property and the other property in both directions.
    ///
    /// The initial synchronization copies the other property's value into this property. Repeating the same binding
    /// pair has no effect.
    void bindBidirectional(Property<T> other);

    /// Removes the bidirectional connection between this property and the other property.
    void unbindBidirectional(Property<T> other);
}
