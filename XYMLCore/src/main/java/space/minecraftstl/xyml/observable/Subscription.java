// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.observable;

import org.jetbrains.annotations.NotNullByDefault;

/// Represents a registration that can be cancelled exactly once.
///
/// Subscriptions are thread-safe and can be used with try-with-resources. Cancelling an already cancelled
/// subscription has no effect.
@NotNullByDefault
public interface Subscription extends AutoCloseable {
    /// Creates a subscription backed by an action that runs at most once when cancelled.
    static Subscription create(Runnable unsubscribeAction) {
        return new CallbackSubscription(unsubscribeAction);
    }

    /// Returns whether this subscription has not yet been cancelled.
    boolean isSubscribed();

    /// Cancels this subscription and releases its cancellation action.
    ///
    /// If the action throws, the subscription remains cancelled and the exception is propagated to the caller.
    void unsubscribe();

    /// Cancels this subscription.
    @Override
    default void close() {
        unsubscribe();
    }
}
