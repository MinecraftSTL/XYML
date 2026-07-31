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
import space.minecraftstl.xyml.observable.Subscription;

import java.util.Map;

/// A toolkit-neutral mutable map that preserves key insertion order and emits immutable changes.
///
/// The map owns its storage and rejects null keys and values. It is not thread-safe and must be confined or
/// externally synchronized. Notifications are synchronous on the mutating thread after state has changed.
@NotNullByDefault
public interface ObservableMap<K, V> extends Map<K, V> {
    /// Registers a synchronous listener and returns its independently cancellable subscription.
    Subscription subscribe(CollectionChangeListener<MapChange<K, V>> listener);
}
