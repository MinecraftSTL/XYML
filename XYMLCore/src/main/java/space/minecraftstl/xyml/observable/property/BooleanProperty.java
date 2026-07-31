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
