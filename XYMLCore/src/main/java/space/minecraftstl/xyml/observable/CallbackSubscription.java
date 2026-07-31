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
