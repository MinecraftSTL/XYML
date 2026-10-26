// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.observable.property;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

/// Stores a nullable object value with toolkit-neutral observable and binding behavior.
@NotNullByDefault
public class SimpleObjectProperty<T> extends AbstractProperty<T> implements ObjectProperty<T> {
    /// Creates an unnamed, ownerless property with a null value.
    public SimpleObjectProperty() {
        this(null, "", null);
    }

    /// Creates an unnamed, ownerless property with the supplied value.
    public SimpleObjectProperty(@Nullable T initialValue) {
        this(null, "", initialValue);
    }

    /// Creates a property with metadata and a null value.
    public SimpleObjectProperty(@Nullable Object bean, String name) {
        this(bean, name, null);
    }

    /// Creates a property with metadata and its initial value.
    public SimpleObjectProperty(@Nullable Object bean, String name, @Nullable T initialValue) {
        super(bean, name, initialValue);
    }
}
