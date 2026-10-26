// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

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
