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
package space.minecraftstl.xyml.observable.property;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

/// Defines a writable property with one-way and bidirectional binding support.
@NotNullByDefault
public interface Property<T> extends ReadOnlyProperty<T> {
    /// Replaces the current value unless this property is one-way bound.
    void setValue(@Nullable T value);

    /// Binds this property to the source and immediately adopts the source value.
    ///
    /// Rebinding to the same source has no effect. Binding to a different source first removes the old binding.
    void bind(ObservableValue<T> source);

    /// Removes the current one-way binding while retaining the last propagated value.
    void unbind();

    /// Returns whether this property currently has a one-way binding.
    boolean isBound();

    /// Connects this property and the other property in both directions.
    ///
    /// The initial synchronization copies the other property's value into this property. Repeating the same binding
    /// pair has no effect.
    void bindBidirectional(Property<T> other);

    /// Removes the bidirectional connection between this property and the other property.
    void unbindBidirectional(Property<T> other);
}
