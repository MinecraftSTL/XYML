// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.util;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/// Holds one mutable value without imposing framework-specific behavior on that value.
///
/// A newly created empty holder contains `null`. The public field intentionally keeps this
/// utility suitable for small closure-local mutable state.
@NotNullByDefault
public final class Holder<T> {
    /// The currently held value, or `null` when the holder is empty.
    public @Nullable T value;

    /// Creates an empty holder.
    public Holder() {
    }

    /// Creates a holder containing the supplied value.
    ///
    /// @param value the initial value
    public Holder(T value) {
        this.value = value;
    }

    /// Returns a hash code derived from the currently held value.
    ///
    /// @return the held value's hash code, or zero when empty
    @Override
    public int hashCode() {
        return Objects.hashCode(value);
    }

    /// Compares holders by their currently held values.
    ///
    /// @param obj the object to compare with
    /// @return `true` when `obj` is a holder containing an equal value
    @Override
    public boolean equals(@Nullable Object obj) {
        if (this == obj)
            return true;

        if (!(obj instanceof Holder<?> other))
            return false;

        return Objects.equals(this.value, other.value);
    }

    /// Returns a diagnostic representation of the currently held value.
    ///
    /// @return a string in the form `Holder[value]`
    @Override
    public String toString() {
        return "Holder[" + value + "]";
    }
}
