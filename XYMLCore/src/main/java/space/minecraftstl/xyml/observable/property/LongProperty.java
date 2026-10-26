// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.observable.property;

import org.jetbrains.annotations.NotNullByDefault;

/// Defines a long property whose boxed value is always non-null.
@NotNullByDefault
public interface LongProperty extends Property<Long> {
    /// Returns the current primitive value.
    default long get() {
        return getValue();
    }

    /// Returns the current boxed value.
    @Override
    Long getValue();

    /// Replaces the current primitive value.
    default void set(long value) {
        setValue(value);
    }
}
