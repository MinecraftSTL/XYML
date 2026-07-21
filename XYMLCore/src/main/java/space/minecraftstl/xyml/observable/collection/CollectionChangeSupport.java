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

import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/// Stores independently cancellable collection listeners and publishes changes synchronously.
///
/// Listener registration and cancellation are thread-safe, but this helper does not make its owning collection
/// thread-safe. If a listener throws, the exception is propagated and later listeners do not receive that change.
@NotNullByDefault
final class CollectionChangeSupport<C> {
    /// Registrations in subscription order.
    private final CopyOnWriteArrayList<ListenerSlot<C>> listeners = new CopyOnWriteArrayList<>();

    /// Registers one listener and returns a handle for that exact registration.
    Subscription subscribe(CollectionChangeListener<C> listener) {
        ListenerSlot<C> slot = new ListenerSlot<>(listener);
        listeners.add(slot);
        return Subscription.create(() -> listeners.remove(slot));
    }

    /// Publishes a completed change synchronously in subscription order.
    void fire(C change) {
        Objects.requireNonNull(change, "change");
        for (ListenerSlot<C> slot : listeners) {
            slot.notifyListener(change);
        }
    }

    /// Keeps duplicate listener registrations independently removable.
    @NotNullByDefault
    private static final class ListenerSlot<C> {
        /// Listener owned by this registration.
        private final CollectionChangeListener<C> listener;

        /// Creates a listener registration.
        private ListenerSlot(CollectionChangeListener<C> listener) {
            this.listener = Objects.requireNonNull(listener, "listener");
        }

        /// Delivers a change to the listener.
        private void notifyListener(C change) {
            listener.onChange(change);
        }
    }
}
