// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.observable.property;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

/// Stores a double value and normalizes nullable boxed writes to zero.
@NotNullByDefault
public final class SimpleDoubleProperty extends AbstractProperty<Double> implements DoubleProperty {
    /// Creates an unnamed, ownerless zero property.
    public SimpleDoubleProperty() {
        this(null, "", 0.0);
    }

    /// Creates an unnamed, ownerless property with the supplied value.
    public SimpleDoubleProperty(double initialValue) {
        this(null, "", initialValue);
    }

    /// Creates a zero property with metadata.
    public SimpleDoubleProperty(@Nullable Object bean, String name) {
        this(bean, name, 0.0);
    }

    /// Creates a property with metadata and its initial value.
    public SimpleDoubleProperty(@Nullable Object bean, String name, double initialValue) {
        super(bean, name, initialValue);
    }

    /// Returns the non-null boxed value required by [DoubleProperty].
    @Override
    public Double getValue() {
        @Nullable Double currentValue = super.getValue();
        return currentValue == null ? 0.0 : currentValue;
    }

    /// Converts null boxed writes to zero.
    @Override
    protected Double normalize(@Nullable Double candidate) {
        return candidate == null ? 0.0 : candidate;
    }
}
