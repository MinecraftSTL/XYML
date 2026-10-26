// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.observable.property;

import org.jetbrains.annotations.NotNullByDefault;

/// Defines a double property whose boxed value is always non-null.
@NotNullByDefault
public interface DoubleProperty extends Property<Double> {
    /// Returns the current primitive value.
    default double get() {
        return getValue();
    }

    /// Returns the current boxed value.
    @Override
    Double getValue();

    /// Replaces the current primitive value.
    default void set(double value) {
        setValue(value);
    }
}
