// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.observable.property;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

/// Defines a nullable object property with concise get and set aliases.
@NotNullByDefault
public interface ObjectProperty<T> extends Property<T> {
    /// Returns the current object value, or null when the property is empty.
    default @Nullable T get() {
        return getValue();
    }

    /// Replaces the current object value.
    default void set(@Nullable T value) {
        setValue(value);
    }
}
