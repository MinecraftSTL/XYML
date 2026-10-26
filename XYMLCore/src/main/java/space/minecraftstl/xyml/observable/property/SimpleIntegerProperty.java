// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.observable.property;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

/// Stores an integer value and normalizes nullable boxed writes to zero.
@NotNullByDefault
public final class SimpleIntegerProperty extends AbstractProperty<Integer> implements IntegerProperty {
    /// Creates an unnamed, ownerless zero property.
    public SimpleIntegerProperty() {
        this(null, "", 0);
    }

    /// Creates an unnamed, ownerless property with the supplied value.
    public SimpleIntegerProperty(int initialValue) {
        this(null, "", initialValue);
    }

    /// Creates a zero property with metadata.
    public SimpleIntegerProperty(@Nullable Object bean, String name) {
        this(bean, name, 0);
    }

    /// Creates a property with metadata and its initial value.
    public SimpleIntegerProperty(@Nullable Object bean, String name, int initialValue) {
        super(bean, name, initialValue);
    }

    /// Returns the non-null boxed value required by [IntegerProperty].
    @Override
    public Integer getValue() {
        @Nullable Integer currentValue = super.getValue();
        return currentValue == null ? 0 : currentValue;
    }

    /// Converts null boxed writes to zero.
    @Override
    protected Integer normalize(@Nullable Integer candidate) {
        return candidate == null ? 0 : candidate;
    }
}
