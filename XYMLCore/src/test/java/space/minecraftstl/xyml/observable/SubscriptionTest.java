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
