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
package space.minecraftstl.xyml.observable.collection;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import space.minecraftstl.xyml.observable.property.ObservableValue;

import java.util.List;

/// Selects observable dependencies whose changes update one element already stored in an observable list.
///
/// The returned list and its entries must be non-null. An empty list means that only structural list mutations are
/// observed for that element. The observable list owns the resulting subscriptions until the element is removed.
@FunctionalInterface
@NotNullByDefault
public interface ObservableElementExtractor<E> {
    /// Returns the stable observable dependencies to monitor for the supplied element occurrence.
    @Unmodifiable List<? extends ObservableValue<?>> extract(E element);
}
