// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.observable;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests cancellation and lifecycle semantics of [Subscription].
@NotNullByDefault
public final class SubscriptionTest {
    /// Verifies that repeated cancellation invokes the backing action only once.
    @Test
    public void cancelsOnlyOnce() {
        AtomicInteger cancellations = new AtomicInteger();
        Subscription subscription = Subscription.create(cancellations::incrementAndGet);

        assertTrue(subscription.isSubscribed());

        subscription.unsubscribe();
        subscription.close();

        assertFalse(subscription.isSubscribed());
        assertEquals(1, cancellations.get());
    }

    /// Verifies that a throwing action cannot leave the subscription active.
    @Test
    public void staysCancelledWhenActionThrows() {
        Subscription subscription = Subscription.create(() -> {
            throw new IllegalStateException("expected");
        });

        assertThrows(IllegalStateException.class, subscription::unsubscribe);
        assertFalse(subscription.isSubscribed());

        subscription.unsubscribe();
    }
}
