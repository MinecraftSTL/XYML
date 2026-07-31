/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2020 huangyuhui <huanghongxun2008@126.com> and contributors
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
package space.minecraftstl.xyml.event;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import space.minecraftstl.xyml.observable.Subscription;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

/// Delivers one event type to handlers ordered by priority and registration order.
///
/// Registration, cancellation, and delivery are thread-safe. A cancellable subscription controls
/// exactly one handler registration, including when the same consumer is subscribed more than once.
///
/// @param <T> event type delivered by this manager
@NotNullByDefault
public final class EventManager<T extends Event> {
    /// Number of independently ordered priority bands.
    private static final int PRIORITY_COUNT = EventPriority.values().length;

    /// Reentrant lock preserving delivery and registration ordering across threads.
    private final ReentrantLock lock = new ReentrantLock();

    /// Non-null handler lists indexed by [EventPriority#ordinal()].
    private final @Unmodifiable List<CopyOnWriteArrayList<Consumer<T>>> allHandlers;

    /// Creates an empty event manager with one handler list per priority.
    public EventManager() {
        List<CopyOnWriteArrayList<Consumer<T>>> handlers = new ArrayList<>(PRIORITY_COUNT);
        for (int index = 0; index < PRIORITY_COUNT; index++) {
            handlers.add(new CopyOnWriteArrayList<>());
        }
        allHandlers = List.copyOf(handlers);
    }

    /// Registers a weak consumer at normal priority.
    ///
    /// The returned strong reference must be retained by the caller for as long as delivery is required.
    ///
    /// @param consumer consumer referenced weakly by the manager
    /// @return the same consumer supplied by the caller
    @Contract("_ -> param1")
    public Consumer<T> registerWeak(Consumer<T> consumer) {
        return registerWeak(consumer, EventPriority.NORMAL);
    }

    /// Registers a weak consumer at the requested priority.
    ///
    /// The returned strong reference must be retained by the caller for as long as delivery is required.
    ///
    /// @param consumer consumer referenced weakly by the manager
    /// @param priority delivery priority
    /// @return the same consumer supplied by the caller
    @Contract("_, _ -> param1")
    public Consumer<T> registerWeak(Consumer<T> consumer, EventPriority priority) {
        Consumer<T> validatedConsumer = Objects.requireNonNull(consumer, "consumer");
        addHandler(new WeakListener<>(new WeakReference<>(validatedConsumer)), priority);
        return validatedConsumer;
    }

    /// Permanently registers a consumer at normal priority.
    ///
    /// @param consumer event consumer
    public void register(Consumer<T> consumer) {
        register(consumer, EventPriority.NORMAL);
    }

    /// Permanently registers a consumer at the requested priority.
    ///
    /// @param consumer event consumer
    /// @param priority delivery priority
    public void register(Consumer<T> consumer, EventPriority priority) {
        addHandler(consumer, priority);
    }

    /// Registers a cancellable consumer at normal priority.
    ///
    /// @param consumer event consumer
    /// @return subscription controlling this exact registration
    public Subscription subscribe(Consumer<T> consumer) {
        return subscribe(consumer, EventPriority.NORMAL);
    }

    /// Registers a cancellable consumer at the requested priority.
    ///
    /// @param consumer event consumer
    /// @param priority delivery priority
    /// @return subscription controlling this exact registration
    public Subscription subscribe(Consumer<T> consumer, EventPriority priority) {
        Consumer<T> validatedConsumer = Objects.requireNonNull(consumer, "consumer");
        EventPriority validatedPriority = Objects.requireNonNull(priority, "priority");
        addHandler(validatedConsumer, validatedPriority);
        return Subscription.create(() -> removeHandler(validatedConsumer, validatedPriority));
    }

    /// Permanently registers a no-argument handler at normal priority.
    ///
    /// @param runnable handler invoked for every event
    public void register(Runnable runnable) {
        register(runnable, EventPriority.NORMAL);
    }

    /// Permanently registers a no-argument handler at the requested priority.
    ///
    /// @param runnable handler invoked for every event
    /// @param priority delivery priority
    public void register(Runnable runnable, EventPriority priority) {
        Runnable validatedRunnable = Objects.requireNonNull(runnable, "runnable");
        register(event -> validatedRunnable.run(), priority);
    }

    /// Registers a cancellable no-argument handler at normal priority.
    ///
    /// @param runnable handler invoked for every event
    /// @return subscription controlling this exact registration
    public Subscription subscribe(Runnable runnable) {
        return subscribe(runnable, EventPriority.NORMAL);
    }

    /// Registers a cancellable no-argument handler at the requested priority.
    ///
    /// @param runnable handler invoked for every event
    /// @param priority delivery priority
    /// @return subscription controlling this exact registration
    public Subscription subscribe(Runnable runnable, EventPriority priority) {
        Runnable validatedRunnable = Objects.requireNonNull(runnable, "runnable");
        return subscribe(event -> validatedRunnable.run(), priority);
    }

    /// Delivers an event to every current handler in priority and registration order.
    ///
    /// A handler removed by an earlier handler in the same delivery may still receive the in-flight
    /// event because each priority band uses copy-on-write iteration.
    ///
    /// @param event event to deliver
    /// @return explicit event result when supported, otherwise [Event.Result#DEFAULT]
    public Event.Result fireEvent(T event) {
        T validatedEvent = Objects.requireNonNull(event, "event");
        lock.lock();
        try {
            for (CopyOnWriteArrayList<Consumer<T>> handlers : allHandlers) {
                for (Consumer<T> handler : handlers) {
                    if (handler instanceof WeakListener<T> weakListener) {
                        Consumer<T> consumer = weakListener.reference().get();
                        if (consumer != null) {
                            consumer.accept(validatedEvent);
                        } else {
                            handlers.remove(weakListener);
                        }
                    } else {
                        handler.accept(validatedEvent);
                    }
                }
            }
        } finally {
            lock.unlock();
        }

        return validatedEvent.hasResult() ? validatedEvent.getResult() : Event.Result.DEFAULT;
    }

    /// Adds one handler while excluding concurrent delivery.
    ///
    /// @param consumer handler to add
    /// @param priority target priority band
    private void addHandler(Consumer<T> consumer, EventPriority priority) {
        Consumer<T> validatedConsumer = Objects.requireNonNull(consumer, "consumer");
        EventPriority validatedPriority = Objects.requireNonNull(priority, "priority");
        lock.lock();
        try {
            allHandlers.get(validatedPriority.ordinal()).add(validatedConsumer);
        } finally {
            lock.unlock();
        }
    }

    /// Removes one exact handler occurrence while excluding concurrent delivery.
    ///
    /// @param consumer handler occurrence to remove
    /// @param priority priority band containing the registration
    private void removeHandler(Consumer<T> consumer, EventPriority priority) {
        lock.lock();
        try {
            allHandlers.get(priority.ordinal()).remove(consumer);
        } finally {
            lock.unlock();
        }
    }

    /// Weak wrapper that permits an unretained listener to be collected.
    ///
    /// @param reference weak consumer reference
    /// @param <T> event type accepted by the consumer
    @NotNullByDefault
    private record WeakListener<T>(WeakReference<Consumer<T>> reference) implements Consumer<T> {
        /// Delivers directly when the weak consumer remains reachable.
        ///
        /// @param event event to deliver
        @Override
        public void accept(T event) {
            Consumer<T> listener = reference.get();
            if (listener != null) {
                listener.accept(event);
            }
        }
    }
}
