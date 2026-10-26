// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.observable.property;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

/// Stores nullable text with toolkit-neutral observable and binding behavior.
@NotNullByDefault
public final class SimpleStringProperty extends AbstractProperty<String> implements StringProperty {
    /// Creates an unnamed, ownerless property with a null value.
    public SimpleStringProperty() {
        this(null, "", null);
    }

    /// Creates an unnamed, ownerless property with the supplied value.
    public SimpleStringProperty(@Nullable String initialValue) {
        this(null, "", initialValue);
    }

    /// Creates a property with metadata and a null value.
    public SimpleStringProperty(@Nullable Object bean, String name) {
        this(bean, name, null);
    }

    /// Creates a property with metadata and its initial value.
    public SimpleStringProperty(@Nullable Object bean, String name, @Nullable String initialValue) {
        super(bean, name, initialValue);
    }
}
