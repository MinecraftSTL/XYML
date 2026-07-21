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
