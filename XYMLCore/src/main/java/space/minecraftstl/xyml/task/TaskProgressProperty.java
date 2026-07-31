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
package space.minecraftstl.xyml.task;

import org.jetbrains.annotations.NotNullByDefault;
import space.minecraftstl.xyml.observable.Subscription;
import space.minecraftstl.xyml.observable.ValueChange;
import space.minecraftstl.xyml.observable.ValueChangeListener;
import space.minecraftstl.xyml.observable.property.ReadOnlyProperty;

import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

import static space.minecraftstl.xyml.util.logging.Logger.LOG;

/// Stores and publishes one task's toolkit-neutral progress while isolating listener failures.
///
/// Delivery is synchronous on each publishing thread and follows subscription order within one change. Concurrent
/// commits are atomic, but their lock-free listener-notification rounds may interleave; every event still describes
/// its exact committed transition. A runtime exception from one listener is logged at that listener's boundary so
/// later listeners, including a parent task's subtask mirror, still receive the same change. This deliberately differs
/// from the general-purpose observable contract only for task progress notifications.
@NotNullByDefault
final class TaskProgressProperty implements ReadOnlyProperty<Double> {
    /// Serializes the current progress value across worker threads.
    private final Object stateLock = new Object();

    /// The task exposed as this property's bean and used to identify failed listeners in logs.
    private final Task<?> task;

    /// The stable property name.
    private final String name;

    /// Ordered listener registrations that remain safe during concurrent subscription changes.
    private final CopyOnWriteArrayList<ListenerSlot> listeners = new CopyOnWriteArrayList<>();

    /// The latest published progress value.
    private double value;

    /// Creates a progress property for the supplied task.
    TaskProgressProperty(Task<?> task, String name, double initialValue) {
        this.task = Objects.requireNonNull(task, "task");
        this.name = Objects.requireNonNull(name, "name");
        value = initialValue;
    }

    /// Returns the task that owns this property.
    @Override
    public Task<?> getBean() {
        return task;
    }

    /// Returns the stable progress property name.
    @Override
    public String getName() {
        return name;
    }

    /// Returns the latest progress value under the property state lock.
    @Override
    public Double getValue() {
        synchronized (stateLock) {
            return value;
        }
    }

    /// Registers one ordered synchronous progress listener.
    @Override
    public Subscription subscribe(ValueChangeListener<Double> listener) {
        ListenerSlot slot = new ListenerSlot(listener);
        listeners.add(slot);
        return Subscription.create(() -> listeners.remove(slot));
    }

    /// Atomically commits and synchronously publishes a distinct value from the calling thread.
    ///
    /// The copy-on-write iterator captures this publication's listener snapshot before its first callback. Concurrent
    /// publishers may notify their snapshots at the same time after each has committed its exact transition.
    void publish(double currentValue) {
        double previousValue;
        synchronized (stateLock) {
            if (Double.compare(value, currentValue) == 0) {
                return;
            }
            previousValue = value;
            value = currentValue;
        }

        ValueChange<Double> change = new ValueChange<>(this, previousValue, currentValue);
        for (ListenerSlot listener : listeners) {
            listener.notifySafely(change, task);
        }
    }

    /// Owns one independently removable progress listener registration.
    @NotNullByDefault
    private static final class ListenerSlot {
        /// The listener invoked for this registration.
        private final ValueChangeListener<Double> listener;

        /// Creates a registration for one listener.
        private ListenerSlot(ValueChangeListener<Double> listener) {
            this.listener = Objects.requireNonNull(listener, "listener");
        }

        /// Delivers one change and prevents a failed listener from interrupting later registrations.
        private void notifySafely(ValueChange<Double> change, Task<?> task) {
            try {
                listener.onChange(change);
            } catch (RuntimeException exception) {
                LOG.warning("A UI-neutral progress listener failed for task " + task.getName(), exception);
            }
        }
    }
}
