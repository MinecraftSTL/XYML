// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.observable.property;

import org.jetbrains.annotations.NotNullByDefault;

/// Defines an integer property whose boxed value is always non-null.
@NotNullByDefault
public interface IntegerProperty extends Property<Integer> {
    /// Returns the current primitive value.
    default int get() {
        return getValue();
    }

    /// Returns the current boxed value.
    @Override
    Integer getValue();

    /// Replaces the current primitive value.
    default void set(int value) {
        setValue(value);
    }
}
