/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2026 huangyuhui <huanghongxun2008@126.com> and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
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
