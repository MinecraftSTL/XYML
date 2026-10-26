// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.event;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import space.minecraftstl.xyml.observable.Subscription;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests independently cancellable event-manager registrations.
@NotNullByDefault
public final class EventManagerSubscriptionTest {
    /// A cancelled subscription stops delivery and remains safely idempotent.
    @Test
    public void cancellationStopsFutureDelivery() {
        EventManager<TestEvent> manager = new EventManager<>();
        AtomicInteger deliveries = new AtomicInteger();
        Subscription subscription = manager.subscribe(event -> deliveries.incrementAndGet());

        manager.fireEvent(new TestEvent(this));
        subscription.unsubscribe();
        subscription.unsubscribe();
        manager.fireEvent(new TestEvent(this));

        assertEquals(1, deliveries.get());
        assertFalse(subscription.isSubscribed());
    }

    /// Duplicate subscriptions of one consumer remain independently cancellable.
    @Test
    public void duplicateSubscriptionsAreIndependent() {
        EventManager<TestEvent> manager = new EventManager<>();
        AtomicInteger deliveries = new AtomicInteger();
        Consumer<TestEvent> consumer = event -> deliveries.incrementAndGet();
        Subscription first = manager.subscribe(consumer);
        Subscription second = manager.subscribe(consumer);

        first.unsubscribe();
        manager.fireEvent(new TestEvent(this));

        assertEquals(1, deliveries.get());
        assertFalse(first.isSubscribed());
        assertTrue(second.isSubscribed());
        second.unsubscribe();
    }

    /// Cancellable registrations preserve priority ordering with permanent registrations.
    @Test
    public void preservesPriorityAndRegistrationOrder() {
        EventManager<TestEvent> manager = new EventManager<>();
        List<String> order = new ArrayList<>();
        manager.register(event -> order.add("normal-one"), EventPriority.NORMAL);
        Subscription high = manager.subscribe(event -> order.add("high"), EventPriority.HIGHEST);
        manager.subscribe(event -> order.add("normal-two"), EventPriority.NORMAL);

        manager.fireEvent(new TestEvent(this));

        assertEquals(List.of("high", "normal-one", "normal-two"), order);
        high.unsubscribe();
    }

    /// Minimal event used to verify manager registration behavior.
    @NotNullByDefault
    private static final class TestEvent extends Event {
        /// Creates a result-free test event.
        ///
        /// @param source event source
        private TestEvent(Object source) {
            super(source);
        }
    }
}
