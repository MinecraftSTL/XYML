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
