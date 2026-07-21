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

/// Stores nullable text with toolkit-neutral observable and binding behavior.
@NotNullByDefault
public final class SimpleStringProperty extends AbstractProperty<String> implements StringProperty {
    /// Creates an unnamed, ownerless property with a null value.
    public SimpleStringProperty() {
        this(null, "", null);
    }

    /// Creates an unnamed, ownerless property with the supplied value.
    public SimpleStringProperty(@Nullable String initialValue) {
        this(null, "", initialValue);
    }

    /// Creates a property with metadata and a null value.
    public SimpleStringProperty(@Nullable Object bean, String name) {
        this(bean, name, null);
    }

    /// Creates a property with metadata and its initial value.
    public SimpleStringProperty(@Nullable Object bean, String name, @Nullable String initialValue) {
        super(bean, name, initialValue);
    }
}
