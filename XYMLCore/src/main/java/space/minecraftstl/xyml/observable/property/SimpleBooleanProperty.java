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

/// Stores a boolean value and normalizes nullable boxed writes to false.
@NotNullByDefault
public final class SimpleBooleanProperty extends AbstractProperty<Boolean> implements BooleanProperty {
    /// Creates an unnamed, ownerless false property.
    public SimpleBooleanProperty() {
        this(null, "", false);
    }

    /// Creates an unnamed, ownerless property with the supplied value.
    public SimpleBooleanProperty(boolean initialValue) {
        this(null, "", initialValue);
    }

    /// Creates a false property with metadata.
    public SimpleBooleanProperty(@Nullable Object bean, String name) {
        this(bean, name, false);
    }

    /// Creates a property with metadata and its initial value.
    public SimpleBooleanProperty(@Nullable Object bean, String name, boolean initialValue) {
        super(bean, name, initialValue);
    }

    /// Returns the non-null boxed value required by [BooleanProperty].
    @Override
    public Boolean getValue() {
        return Boolean.TRUE.equals(super.getValue());
    }

    /// Converts null boxed writes to false.
    @Override
    protected Boolean normalize(@Nullable Boolean candidate) {
        return Boolean.TRUE.equals(candidate);
    }
}
