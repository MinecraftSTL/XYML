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
package space.minecraftstl.xyml.observable;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/// Manages thread-safe subscriptions for one strongly typed observable value.
///
/// Delivery is synchronous and ordered by subscription time. This class performs no UI dispatching; a UI listener
/// must use a [space.minecraftstl.xyml.ui.UiDispatcher] when it needs to update toolkit state. If a listener throws,
/// the exception is propagated and later listeners are not invoked for that change.
@NotNullByDefault
public final class ValueChangeSupport<T> {
    /// The model or property reported as the source of every emitted change.
    private final Object source;

    /// Copy-on-write registrations used to make publication safe across concurrent subscribe and unsubscribe calls.
    private final CopyOnWriteArrayList<ListenerSlot<T>> listeners = new CopyOnWriteArrayList<>();

    /// Creates change support for the supplied source.
    public ValueChangeSupport(Object source) {
        this.source = Objects.requireNonNull(source, "source");
    }

    /// Registers a listener and returns the handle that removes this exact registration.
    ///
    /// A listener may be registered more than once; each returned subscription controls only its own registration.
    public Subscription subscribe(ValueChangeListener<T> listener) {
        ListenerSlot<T> slot = new ListenerSlot<>(listener);
        listeners.add(slot);
        return Subscription.create(() -> listeners.remove(slot));
    }

    /// Publishes a transition when the previous and current values are not equal.
    ///
    /// Listeners are called synchronously on the calling thread. A cancellation concurrent with an already-running
    /// publication may still receive that in-flight change.
    ///
    /// @return true when a change was published, or false when both values were equal
    public boolean fireChange(@Nullable T previousValue, @Nullable T currentValue) {
        if (Objects.equals(previousValue, currentValue)) {
            return false;
        }

        ValueChange<T> change = new ValueChange<>(source, previousValue, currentValue);
        for (ListenerSlot<T> slot : listeners) {
            slot.notifyListener(change);
        }
        return true;
    }

    /// Returns whether at least one listener is currently registered.
    public boolean hasSubscribers() {
        return !listeners.isEmpty();
    }

    /// Keeps duplicate listener registrations independently removable.
    @NotNullByDefault
    private static final class ListenerSlot<T> {
        /// The listener owned by this registration.
        private final ValueChangeListener<T> listener;

        /// Creates a listener registration.
        private ListenerSlot(ValueChangeListener<T> listener) {
            this.listener = Objects.requireNonNull(listener, "listener");
        }

        /// Delivers a change to the registered listener.
        private void notifyListener(ValueChange<T> change) {
            listener.onChange(change);
        }
    }
}
