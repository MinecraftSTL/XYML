// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.observable.property;

import org.jetbrains.annotations.NotNullByDefault;

/// Defines a boolean property whose boxed value is always non-null.
@NotNullByDefault
public interface BooleanProperty extends Property<Boolean> {
    /// Returns the current primitive value.
    default boolean get() {
        return getValue();
    }

    /// Returns the current boxed value.
    @Override
    Boolean getValue();

    /// Replaces the current primitive value.
    default void set(boolean value) {
        setValue(value);
    }
}
