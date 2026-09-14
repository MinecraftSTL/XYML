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
import org.jetbrains.annotations.Nullable;
import space.minecraftstl.xyml.observable.Subscription;

import java.util.Objects;
import java.util.OptionalDouble;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

/// Provides an invocation-scoped view of one top-level task execution's aggregate progress.
///
/// The handle is bound once to the exact registry execution created for its owning executor. Repeated executor starts
/// therefore cannot move an already-bound consumer to a newer invocation.
@NotNullByDefault
public final class TaskExecutionProgressHandle {
    /// Shared inert handle used by executors without a registry-backed execution.
    private static final TaskExecutionProgressHandle UNAVAILABLE = new TaskExecutionProgressHandle(null);

    /// Registry owning the bound execution, or null for the inert handle.
    private final @Nullable TaskExecutionRegistry registry;

    /// Exact top-level invocation selected by the owning executor.
    private final AtomicReference<@Nullable UUID> executionId = new AtomicReference<>();

    /// Listeners that must be invalidated when the handle is first bound.
    private final CopyOnWriteArrayList<ListenerSlot> listeners = new CopyOnWriteArrayList<>();

    /// Creates one progress handle for an optional task registry.
    private TaskExecutionProgressHandle(@Nullable TaskExecutionRegistry registry) {
        this.registry = registry;
    }

    /// Returns an inert handle for executors with no invocation registry.
    ///
    /// @return unavailable progress handle
    public static TaskExecutionProgressHandle unavailable() {
        return UNAVAILABLE;
    }

    /// Creates a handle that can be bound to one invocation in the supplied registry.
    ///
    /// @param registry registry containing the exact invocation
    /// @return invocation-scoped progress handle
    static TaskExecutionProgressHandle forRegistry(TaskExecutionRegistry registry) {
        return new TaskExecutionProgressHandle(Objects.requireNonNull(registry, "registry"));
    }

    /// Returns the bound invocation's aggregate progress.
    ///
    /// @return normalized aggregate progress, or empty before binding or after eviction
    public OptionalDouble progress() {
        @Nullable TaskExecutionRegistry currentRegistry = registry;
        @Nullable UUID currentExecutionId = executionId.get();
        if (currentRegistry == null || currentExecutionId == null) {
            return OptionalDouble.empty();
        }
        @Nullable TaskExecutionSnapshot snapshot = currentRegistry.snapshot(currentExecutionId);
        return snapshot == null ? OptionalDouble.empty() : snapshot.progress();
    }

    /// Registers a listener for changes to this exact invocation.
    ///
    /// @param listener invalidation callback
    /// @return independently cancellable subscription
    public Subscription subscribe(Runnable listener) {
        Objects.requireNonNull(listener, "listener");
        @Nullable TaskExecutionRegistry currentRegistry = registry;
        if (currentRegistry == null) {
            return Subscription.create(() -> { });
        }
        ListenerSlot slot = new ListenerSlot(listener);
        listeners.add(slot);
        Subscription registrySubscription = currentRegistry.subscribeVersioned(publication -> {
            @Nullable UUID currentExecutionId = executionId.get();
            if (currentExecutionId == null) {
                return;
            }
            for (TaskExecutionSnapshot snapshot : publication.snapshots()) {
                if (snapshot.id().equals(currentExecutionId)) {
                    listener.run();
                    return;
                }
            }
        });
        if (executionId.get() != null) {
            listener.run();
        }
        return Subscription.create(() -> {
            listeners.remove(slot);
            registrySubscription.unsubscribe();
        });
    }

    /// Binds this handle exactly once to one registry execution.
    ///
    /// @param invocationId exact top-level execution ID
    void bind(UUID invocationId) {
        Objects.requireNonNull(invocationId, "invocationId");
        if (registry == null) {
            return;
        }
        if (!executionId.compareAndSet(null, invocationId)) {
            return;
        }
        for (ListenerSlot slot : listeners) {
            slot.listener().run();
        }
    }

    /// Owns one listener so binding invalidation can be isolated from registration removal.
    @NotNullByDefault
    private record ListenerSlot(Runnable listener) {
        /// Validates one listener slot.
        private ListenerSlot {
            Objects.requireNonNull(listener, "listener");
        }
    }
}
