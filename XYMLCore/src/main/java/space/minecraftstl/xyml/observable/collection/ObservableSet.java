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

import java.util.Set;

/// A toolkit-neutral mutable set that preserves insertion order and emits immutable changes.
///
/// The set owns its storage and rejects null elements. It is not thread-safe and must be confined or externally
/// synchronized. Notifications are synchronous on the mutating thread after state has changed.
@NotNullByDefault
public interface ObservableSet<E> extends Set<E> {
    /// Registers a synchronous listener and returns its independently cancellable subscription.
    Subscription subscribe(CollectionChangeListener<SetChange<E>> listener);
}
