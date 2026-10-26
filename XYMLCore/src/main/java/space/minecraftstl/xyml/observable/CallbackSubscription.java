// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.observable;

import org.jetbrains.annotations.NotNullByDefault;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/// Thread-safe [Subscription] implementation backed by a callback.
@NotNullByDefault
final class CallbackSubscription implements Subscription {
    /// Marker stored after cancellation so the callback can be released without using an implicit null value.
    private static final Runnable UNSUBSCRIBED = () -> {
    };

    /// The pending cancellation action, or [#UNSUBSCRIBED] after cancellation.
    private final AtomicReference<Runnable> unsubscribeAction;

    /// Creates a callback-backed subscription.
    CallbackSubscription(Runnable unsubscribeAction) {
        this.unsubscribeAction = new AtomicReference<>(Objects.requireNonNull(unsubscribeAction, "unsubscribeAction"));
    }

    /// Returns whether the cancellation action is still pending.
    @Override
    public boolean isSubscribed() {
        return unsubscribeAction.get() != UNSUBSCRIBED;
    }

    /// Atomically claims and invokes the cancellation action at most once.
    @Override
    public void unsubscribe() {
        Runnable action = unsubscribeAction.getAndSet(UNSUBSCRIBED);
        if (action != UNSUBSCRIBED) {
            action.run();
        }
    }
}
