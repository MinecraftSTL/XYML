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
import org.jetbrains.annotations.Unmodifiable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/// Coordinates asynchronous, execution-owner-aware acquisition of semantic task resources.
///
/// The manager never blocks a caller while a resource is occupied. All state transitions happen under a short monitor,
/// while future completion occurs after leaving that monitor. Owners use explicit ancestry rather than thread identity,
/// allowing reentrant task chains to cross executors without making sibling tasks mutually reentrant.
@NotNullByDefault
final class TaskResourceLockManager {
    /// Process-wide manager used by production task executors.
    static final TaskResourceLockManager SHARED = new TaskResourceLockManager();

    /// Resource states retained only while they have holders or waiters.
    private final Map<TaskResource, ResourceState> resourceStates = new LinkedHashMap<>();

    /// Ordered pending acquisition requests.
    private final List<Waiter> waiters = new ArrayList<>();

    /// Creates one isolated manager.
    TaskResourceLockManager() {
    }

    /// Creates a new cancellation domain for one executor start.
    Execution createExecution() {
        return new Execution();
    }

    /// Resolves declarations and creates one owner node beneath the supplied parent.
    ///
    /// @param execution cancellation domain shared by the complete root chain
    /// @param parent parent owner, or null for the root task
    /// @param declarations immutable task declarations
    /// @return owner carrying requested and ancestor coverage resources
    Owner createOwner(
            Execution execution,
            @Nullable Owner parent,
            @Unmodifiable Collection<TaskResource> declarations) {
        Objects.requireNonNull(execution, "execution");
        Objects.requireNonNull(declarations, "declarations");
        if (declarations.isEmpty()) {
            throw new IllegalArgumentException("Task resource declarations cannot be empty");
        }

        boolean conservative = declarations.stream().anyMatch(TaskResource::isConservative);
        if (conservative && (declarations.size() != 1 || !declarations.iterator().next().isConservative())) {
            throw new IllegalArgumentException("The conservative task resource cannot be combined with explicit resources");
        }

        @Unmodifiable List<TaskResource> requested;
        if (conservative) {
            requested = parent == null ? List.of(TaskResource.global()) : parent.coverageResources;
        } else {
            requested = TaskResource.normalize(declarations);
            if (parent != null) {
                for (TaskResource resource : requested) {
                    boolean covered = parent.coverageResources.stream().anyMatch(ancestor -> ancestor.covers(resource));
                    if (!covered) {
                        throw new IllegalStateException(
                                "Nested task resource is outside its ancestor coverage: " + resource);
                    }
                }
            }
        }

        ArrayList<TaskResource> coverage = new ArrayList<>();
        if (parent != null) {
            coverage.addAll(parent.coverageResources);
        }
        coverage.addAll(requested);
        return new Owner(execution, parent, requested, TaskResource.normalize(coverage));
    }

    /// Requests all resources for one owner without blocking the caller.
    ///
    /// @param owner execution owner containing a normalized resource request
    /// @return future completed with an internal lease after atomic acquisition
    CompletableFuture<Lease> acquire(Owner owner) {
        Objects.requireNonNull(owner, "owner");
        Waiter waiter = new Waiter(owner);
        List<Completion> completions;
        synchronized (this) {
            if (owner.execution.cancelled) {
                completions = List.of(Completion.cancelled(waiter));
            } else {
                waiters.add(waiter);
                for (TaskResource resource : owner.requestedResources) {
                    resourceStates.computeIfAbsent(resource, ignored -> new ResourceState()).waiterCount++;
                }
                completions = processWaiters();
            }
        }
        complete(completions);
        return waiter.future;
    }

    /// Cancels every pending request in one execution domain.
    ///
    /// Already granted leases remain owned until the surrounding task lifecycle reaches its normal release path.
    ///
    /// @param execution cancellation domain to cancel
    void cancel(Execution execution) {
        Objects.requireNonNull(execution, "execution");
        ArrayList<Completion> completions = new ArrayList<>();
        synchronized (this) {
            execution.cancelled = true;
            Iterator<Waiter> iterator = waiters.iterator();
            while (iterator.hasNext()) {
                Waiter waiter = iterator.next();
                if (waiter.owner.execution == execution) {
                    iterator.remove();
                    removeWaiterReferences(waiter);
                    completions.add(Completion.cancelled(waiter));
                }
            }
            completions.addAll(processWaiters());
            removeUnusedStates();
        }
        complete(completions);
    }

    /// Returns the number of dynamic resource entries currently retained.
    int trackedResourceCount() {
        synchronized (this) {
            return resourceStates.size();
        }
    }

    /// Returns the number of pending acquisition requests.
    int pendingWaiterCount() {
        synchronized (this) {
            return waiters.size();
        }
    }

    /// Releases one lease in reverse resource order and grants newly unblocked waiters.
    private void release(Lease lease) {
        ArrayList<Completion> completions;
        synchronized (this) {
            List<TaskResource> resources = lease.owner.requestedResources;
            for (int index = resources.size() - 1; index >= 0; index--) {
                TaskResource resource = resources.get(index);
                ResourceState state = Objects.requireNonNull(resourceStates.get(resource), "resource state");
                @Nullable Integer count = state.holders.get(lease.owner);
                if (count == null || count <= 0) {
                    throw new IllegalStateException("Task resource was released without ownership: " + resource);
                }
                if (count == 1) {
                    state.holders.remove(lease.owner);
                } else {
                    state.holders.put(lease.owner, count - 1);
                }
            }
            removeUnusedStates();
            completions = new ArrayList<>(processWaiters());
            removeUnusedStates();
        }
        complete(completions);
    }

    /// Grants all currently eligible waiters while preserving order between conflicting requests.
    private @Unmodifiable List<Completion> processWaiters() {
        ArrayList<Completion> completions = new ArrayList<>();
        ArrayList<Waiter> earlierBlocked = new ArrayList<>();
        Iterator<Waiter> iterator = waiters.iterator();
        while (iterator.hasNext()) {
            Waiter waiter = iterator.next();
            if (waiter.owner.execution.cancelled) {
                iterator.remove();
                removeWaiterReferences(waiter);
                completions.add(Completion.cancelled(waiter));
                continue;
            }

            boolean overtakesConflict = earlierBlocked.stream().anyMatch(earlier -> requestsConflict(earlier, waiter));
            if (!overtakesConflict && canAcquire(waiter.owner)) {
                iterator.remove();
                removeWaiterReferences(waiter);
                for (TaskResource resource : waiter.owner.requestedResources) {
                    ResourceState state = resourceStates.computeIfAbsent(resource, ignored -> new ResourceState());
                    state.holders.merge(waiter.owner, 1, Integer::sum);
                }
                completions.add(Completion.granted(waiter, new Lease(this, waiter.owner)));
            } else {
                earlierBlocked.add(waiter);
            }
        }
        return List.copyOf(completions);
    }

    /// Returns whether every conflicting holder belongs to the requester or one of its ancestors.
    private boolean canAcquire(Owner owner) {
        for (TaskResource requested : owner.requestedResources) {
            for (Map.Entry<TaskResource, ResourceState> entry : resourceStates.entrySet()) {
                if (!entry.getValue().holders.isEmpty() && requested.conflictsWith(entry.getKey())) {
                    for (Owner holder : entry.getValue().holders.keySet()) {
                        if (!holder.isAncestorOf(owner)) {
                            return false;
                        }
                    }
                }
            }
        }
        return true;
    }

    /// Returns whether two pending requests contain at least one conflicting resource pair.
    private static boolean requestsConflict(Waiter first, Waiter second) {
        for (TaskResource firstResource : first.owner.requestedResources) {
            for (TaskResource secondResource : second.owner.requestedResources) {
                if (firstResource.conflictsWith(secondResource)) {
                    return true;
                }
            }
        }
        return false;
    }

    /// Removes one waiter's reference counts from every requested resource state.
    private void removeWaiterReferences(Waiter waiter) {
        for (TaskResource resource : waiter.owner.requestedResources) {
            ResourceState state = Objects.requireNonNull(resourceStates.get(resource), "resource state");
            state.waiterCount--;
            if (state.waiterCount < 0) {
                throw new IllegalStateException("Negative task resource waiter count: " + resource);
            }
        }
    }

    /// Removes resource entries no longer referenced by holders or waiters.
    private void removeUnusedStates() {
        resourceStates.entrySet().removeIf(entry ->
                entry.getValue().waiterCount == 0 && entry.getValue().holders.isEmpty());
    }

    /// Completes grant and cancellation futures outside the state monitor.
    private static void complete(Collection<Completion> completions) {
        for (Completion completion : completions) {
            if (completion.lease != null) {
                completion.waiter.future.complete(completion.lease);
            } else {
                completion.waiter.future.completeExceptionally(new CancellationException("Cancelled by user"));
            }
        }
    }

    /// Cancellation domain shared by one started task-executor chain.
    @NotNullByDefault
    static final class Execution {
        /// Whether pending acquisitions in this domain have been cancelled.
        private boolean cancelled;

        /// Creates a live cancellation domain.
        private Execution() {
        }
    }

    /// Logical task invocation owner with explicit ancestry and immutable resource coverage.
    @NotNullByDefault
    static final class Owner {
        /// Cancellation domain shared by the complete task chain.
        private final Execution execution;

        /// Parent owner, or null for a root invocation.
        private final @Nullable Owner parent;

        /// Exact resources acquired by this invocation.
        private final @Unmodifiable List<TaskResource> requestedResources;

        /// Complete normalized resource coverage retained by this invocation and its ancestors.
        private final @Unmodifiable List<TaskResource> coverageResources;

        /// Creates one immutable owner node.
        private Owner(
                Execution execution,
                @Nullable Owner parent,
                @Unmodifiable List<TaskResource> requestedResources,
                @Unmodifiable List<TaskResource> coverageResources) {
            this.execution = execution;
            this.parent = parent;
            this.requestedResources = List.copyOf(requestedResources);
            this.coverageResources = List.copyOf(coverageResources);
        }

        /// Returns whether this owner is the same as, or an ancestor of, another owner.
        private boolean isAncestorOf(Owner other) {
            @Nullable Owner current = other;
            while (current != null) {
                if (current == this) {
                    return true;
                }
                current = current.parent;
            }
            return false;
        }
    }

    /// Idempotent internal ownership lease never exposed to a concrete task.
    @NotNullByDefault
    static final class Lease implements AutoCloseable {
        /// Owning manager.
        private final TaskResourceLockManager manager;

        /// Logical owner whose counts must be released.
        private final Owner owner;

        /// Whether release has already occurred.
        private final AtomicBoolean closed = new AtomicBoolean();

        /// Creates one granted lease.
        private Lease(TaskResourceLockManager manager, Owner owner) {
            this.manager = manager;
            this.owner = owner;
        }

        /// Releases all resources exactly once.
        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                manager.release(this);
            }
        }
    }

    /// Mutable state retained for one exact declared resource key.
    @NotNullByDefault
    private static final class ResourceState {
        /// Reentrant hold counts keyed by logical owner identity.
        private final IdentityHashMap<Owner, Integer> holders = new IdentityHashMap<>();

        /// Number of pending requests that explicitly contain this resource key.
        private int waiterCount;
    }

    /// Pending asynchronous acquisition request.
    @NotNullByDefault
    private static final class Waiter {
        /// Logical request owner.
        private final Owner owner;

        /// Future completed after grant or cancellation.
        private final CompletableFuture<Lease> future = new CompletableFuture<>();

        /// Creates one pending request.
        private Waiter(Owner owner) {
            this.owner = owner;
        }
    }

    /// Deferred future completion produced under the manager monitor.
    ///
    /// @param waiter pending request to complete
    /// @param lease granted lease, or null for cancellation
    @NotNullByDefault
    private record Completion(Waiter waiter, @Nullable Lease lease) {
        /// Creates a successful completion.
        private static Completion granted(Waiter waiter, Lease lease) {
            return new Completion(waiter, lease);
        }

        /// Creates a cancellation completion.
        private static Completion cancelled(Waiter waiter) {
            return new Completion(waiter, null);
        }
    }
}
