// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.observable.property;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

/// Stores a long value and normalizes nullable boxed writes to zero.
@NotNullByDefault
public final class SimpleLongProperty extends AbstractProperty<Long> implements LongProperty {
    /// Creates an unnamed, ownerless zero property.
    public SimpleLongProperty() {
        this(null, "", 0L);
    }

    /// Creates an unnamed, ownerless property with the supplied value.
    public SimpleLongProperty(long initialValue) {
        this(null, "", initialValue);
    }

    /// Creates a zero property with metadata.
    public SimpleLongProperty(@Nullable Object bean, String name) {
        this(bean, name, 0L);
    }

    /// Creates a property with metadata and its initial value.
    public SimpleLongProperty(@Nullable Object bean, String name, long initialValue) {
        super(bean, name, initialValue);
    }

    /// Returns the non-null boxed value required by [LongProperty].
    @Override
    public Long getValue() {
        @Nullable Long currentValue = super.getValue();
        return currentValue == null ? 0L : currentValue;
    }

    /// Converts null boxed writes to zero.
    @Override
    protected Long normalize(@Nullable Long candidate) {
        return candidate == null ? 0L : candidate;
    }
}
