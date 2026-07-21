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

import java.util.Collection;
import java.util.List;

/// A toolkit-neutral mutable list that emits immutable changes after successful mutations.
///
/// The list owns its storage, preserves encounter order, and rejects null elements. It is not thread-safe and must
/// be confined or externally synchronized. Notifications are synchronous on the mutating thread after state has
/// changed; listeners may inspect the completed state but should avoid reentrant mutation when event order matters.
@NotNullByDefault
public interface ObservableList<E> extends List<E> {
    /// Registers a synchronous listener and returns its independently cancellable subscription.
    Subscription subscribe(CollectionChangeListener<ListChange<E>> listener);

    /// Replaces the entire contents with a copy of the supplied elements.
    ///
    /// @return true when the logical contents changed, or false when they were already equal
    boolean setAll(Collection<? extends E> elements);

    /// Removes the half-open index range as one semantic change.
    void removeRange(int fromIndex, int toIndex);
}
