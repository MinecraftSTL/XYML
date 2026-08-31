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
        return createOwner(execution, parent, declarations, false);
    }

    /// Resolves declarations and creates an owner that may detach before its dependencies run.
    ///
    /// @param execution cancellation domain shared by the complete root chain
    /// @param parent parent owner, or null for the root task
    /// @param declarations immutable task declarations
    /// @param allowsDetachedChildren whether this owner may release before child acquisition
    /// @return owner carrying requested and ancestor coverage resources
    Owner createOwner(
            Execution execution,
            @Nullable Owner parent,
            @Unmodifiable Collection<TaskResource> declarations,
            boolean allowsDetachedChildren) {
        Objects.requireNonNull(execution, "execution");
        Objects.requireNonNull(declarations, "declarations");
        @Unmodifiable List<TaskResource> declarationSnapshot = List.copyOf(declarations);
        if (declarationSnapshot.isEmpty()) {
            throw new IllegalArgumentException("Task resource declarations cannot be empty");
        }

        boolean conservative = declarationSnapshot.stream().anyMatch(TaskResource::isConservative);
        if (conservative && (declarationSnapshot.size() != 1 || !declarationSnapshot.get(0).isConservative())) {
            throw new IllegalArgumentException(
                    "The conservative task resource cannot be combined with explicit resources");
        }

        @Unmodifiable List<TaskResource> requested;
        if (conservative) {
            requested = conservativeResourcesFor(parent);
        } else {
            requested = TaskResource.normalize(declarationSnapshot);
        }

        ArrayList<TaskResource> coverage = new ArrayList<>();
        @Nullable Owner activeAncestor = nearestActiveAncestor(parent);
        if (activeAncestor != null) {
            coverage.addAll(activeAncestor.coverageResources);
        }
        coverage.addAll(requested);
        @Unmodifiable List<TaskResource> normalizedCoverage = TaskResource.normalize(coverage);
        return new Owner(
                execution,
                parent,
                requested,
                normalizedCoverage,
                conservative,
                allowsDetachedChildren);
    }

    /// Finds the nearest complete ancestor boundary that can safely bound an unknown nested write.
    ///
    /// A repository metadata or operation scope is only a coordination contract. When it is mixed with a narrow
    /// resource, an unknown child cannot prove that it will stay inside that narrow path, so the child falls back to
    /// the global resource. A repository scope may be inherited only when a game-directory declaration covers the
    /// repository root itself. Pure orchestration nodes are skipped while walking toward an older complete boundary.
    /// [Owner#prepare(List)] rejects a global fallback until any narrower active ancestor has handed off its lease.
    private static @Unmodifiable List<TaskResource> conservativeResourcesFor(@Nullable Owner parent) {
        @Nullable Owner current = nearestActiveAncestor(parent);
        while (current != null) {
            @Unmodifiable List<TaskResource> declarations = current.requestedResources;
            if (declarations.stream().anyMatch(resource -> resource.getKind() == TaskResource.Kind.GLOBAL)) {
                return List.of(TaskResource.global());
            }

            boolean hasRepositoryScope = declarations.stream().anyMatch(resource ->
                    resource.getKind() == TaskResource.Kind.REPOSITORY_METADATA
                            || resource.getKind() == TaskResource.Kind.REPOSITORY_OPERATION);
            ArrayList<TaskResource> directoryBoundaries = declarations.stream()
                    .filter(resource -> resource.getKind() == TaskResource.Kind.GAME_DIRECTORY)
                    .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
            if (hasRepositoryScope) {
                if (directoryBoundaries.isEmpty()) {
                    // A coordination-only node cannot prove that an unknown descendant stays inside an older
                    // detached or narrow boundary. Crossing it would silently turn a repository-wide write into an
                    // instance-only lock, so fail closed instead of walking past the node.
                    return List.of(TaskResource.global());
                }

                boolean repositoryCovered = declarations.stream()
                        .filter(resource -> resource.getKind() == TaskResource.Kind.REPOSITORY_METADATA
                                || resource.getKind() == TaskResource.Kind.REPOSITORY_OPERATION)
                        .allMatch(repository -> directoryBoundaries.stream()
                                .anyMatch(directory -> directory.permitsNested(repository)));
                boolean otherResourcesCovered = declarations.stream()
                        .filter(resource -> resource.getKind() != TaskResource.Kind.REPOSITORY_METADATA
                                && resource.getKind() != TaskResource.Kind.REPOSITORY_OPERATION
                                && resource.getKind() != TaskResource.Kind.ORCHESTRATION)
                        .allMatch(resource -> directoryBoundaries.stream()
                                .anyMatch(directory -> directory.covers(resource)));
                if (!repositoryCovered || !otherResourcesCovered) {
                    return List.of(TaskResource.global());
                }
                return TaskResource.normalize(declarations.stream()
                        .filter(resource -> resource.getKind() != TaskResource.Kind.ORCHESTRATION)
                        .toList());
            }

            ArrayList<TaskResource> exclusive = declarations.stream()
                    .filter(TaskResource::isExclusiveCoverage)
                    .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
            if (!exclusive.isEmpty()) {
                return TaskResource.normalize(exclusive);
            }
            current = nearestActiveAncestor(current.parent);
        }
        return List.of(TaskResource.global());
    }

    /// Finds the closest ancestor that still owns its resource lease.
    ///
    /// Detached nodes remain in the invocation ancestry for sibling identity and diagnostics, but their former
    /// coverage must not be revived by later descendants after an explicit resource handoff.
    ///
    /// @param owner first candidate ancestor, or null for a root invocation
    /// @return nearest active ancestor, or null when the detached chain has no active predecessor
    private static @Nullable Owner nearestActiveAncestor(@Nullable Owner owner) {
        @Nullable Owner current = owner;
        while (current != null && current.detached) {
            current = current.parent;
        }
        return current;
    }

    /// Resolves path aliases and requests all resources for one owner without blocking the caller.
    ///
    /// @param owner execution owner containing a normalized resource request
    /// @return future completed with an internal lease after asynchronous identity resolution and atomic acquisition
    CompletableFuture<Lease> acquire(Owner owner) {
        Objects.requireNonNull(owner, "owner");
        Waiter waiter = new Waiter(owner);
        boolean cancelled;
        synchronized (this) {
            cancelled = owner.execution.isCancelled();
            if (cancelled) {
                // Complete after leaving the monitor so cancellation callbacks cannot re-enter manager state.
            } else {
                // Register before starting identity resolution so FIFO follows acquire invocation order rather than
                // whichever I/O callback happens to complete first.
                waiters.add(waiter);
            }
        }
        if (cancelled) {
            waiter.future.cancel(false);
            return waiter.future;
        }

        waiter.future.whenComplete((@Nullable Lease ignoredLease, @Nullable Throwable ignoredFailure) -> {
            if (waiter.future.isCancelled()) {
                cancelWaiter(waiter);
            }
        });
        try {
            TaskResourcePathIdentity.resolveAsync(owner.requestedResources, Schedulers.io())
                    .whenComplete((resolvedResources, resolutionFailure) -> {
                        finishResolution(waiter, resolvedResources, resolutionFailure);
                    });
        } catch (Throwable failure) {
            // An executor rejection can happen before a CompletableFuture is returned. Remove the already-registered
            // waiter through the same terminal path so it cannot leave a stale FIFO barrier behind.
            finishResolution(waiter, null, failure);
        }
        return waiter.future;
    }

    /// Removes one caller-cancelled waiter and wakes requests that no longer have an ordering predecessor.
    private void cancelWaiter(Waiter waiter) {
        ArrayList<Completion> completions = new ArrayList<>();
        synchronized (this) {
            if (!waiters.remove(waiter)) {
                return;
            }
            removeWaiterReferences(waiter);
            completions.addAll(processWaiters());
            removeUnusedStates();
        }
        complete(completions);
    }

    /// Finishes one asynchronous identity lookup and makes the waiter eligible for atomic acquisition.
    ///
    /// An unresolved root waiter remains in the ordered queue and conservatively blocks later root requests. Without
    /// this barrier, a later callback could acquire a lexical path before an earlier callback discovers that both
    /// paths resolve through the same symbolic link or junction. Nested owners retain their ancestry bypass so a
    /// parent cannot self-deadlock while waiting for a child.
    private void finishResolution(
            Waiter waiter,
            @Nullable List<TaskResource> resolvedResources,
            @Nullable Throwable resolutionFailure) {
        ArrayList<Completion> completions = new ArrayList<>();
        @Nullable Throwable terminalFailure = null;
        synchronized (this) {
            // Cancellation or an earlier terminal failure may have removed this node while I/O was in flight.
            if (!waiters.contains(waiter)) {
                return;
            }

            if (waiter.owner.execution.cancelled || waiter.future.isCancelled()) {
                waiters.remove(waiter);
                removeWaiterReferences(waiter);
                completions.add(Completion.cancelled(waiter));
            } else if (resolutionFailure != null) {
                waiters.remove(waiter);
                removeWaiterReferences(waiter);
                terminalFailure = resolutionFailure;
            } else {
                try {
                    waiter.owner.prepare(Objects.requireNonNull(resolvedResources, "resolved resources"));
                } catch (Throwable failure) {
                    waiters.remove(waiter);
                    removeWaiterReferences(waiter);
                    terminalFailure = failure;
                }
                if (terminalFailure == null) {
                    waiter.prepared = true;
                    addWaiterReferences(waiter);
                }
            }

            completions.addAll(processWaiters());
            removeUnusedStates();
        }
        complete(completions);
        if (terminalFailure != null) {
            completeFailure(waiter.future, terminalFailure);
        }
    }

    /// Adds one prepared waiter's references to dynamic resource states.
    private void addWaiterReferences(Waiter waiter) {
        if (waiter.counted) {
            throw new IllegalStateException("Task resource waiter was counted twice");
        }
        for (TaskResource resource : waiter.owner.requestedResources) {
            resourceStates.computeIfAbsent(resource, ignored -> new ResourceState()).waiterCount++;
        }
        waiter.counted = true;
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
            List<TaskResource> resources = lease.resources;
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
            if (lease.owner.allowsDetachedChildren && !hasActiveHolder(lease.owner)) {
                lease.owner.detached = true;
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

            if (!waiter.prepared) {
                // A not-yet-canonicalized root request may alias any later lexical path. Keep root ordering safe until
                // its identity is known; nested owners may still reenter their active ancestor chain.
                earlierBlocked.add(waiter);
                continue;
            }

            // A nested owner must be able to finish beneath resources already held by its ancestor. Otherwise an
            // unrelated waiter queued between the parent and child would create a parent-child self-deadlock.
            boolean canReenterAncestor = hasActiveAncestor(waiter.owner);
            boolean unresolvedBarrier = earlierBlocked.stream().anyMatch(earlier ->
                    !earlier.prepared && !canBypassEarlier(earlier, waiter, canReenterAncestor));
            boolean overtakesConflict = unresolvedBarrier || earlierBlocked.stream().anyMatch(earlier ->
                    requestsConflict(earlier, waiter) && !canBypassEarlier(earlier, waiter, canReenterAncestor));
            if (!overtakesConflict && canAcquire(waiter.owner)) {
                iterator.remove();
                removeWaiterReferences(waiter);
                waiter.owner.detached = false;
                for (TaskResource resource : waiter.owner.requestedResources) {
                    ResourceState state = resourceStates.computeIfAbsent(resource, ignored -> new ResourceState());
                    state.holders.merge(waiter.owner, 1, Integer::sum);
                }
                completions.add(Completion.granted(
                        waiter,
                        new Lease(this, waiter.owner, waiter.owner.requestedResources)));
            } else {
                earlierBlocked.add(waiter);
            }
        }
        return List.copyOf(completions);
    }

    /// Returns whether a nested waiter may bypass one earlier blocked request without violating sibling FIFO.
    ///
    /// Same-execution siblings normally retain FIFO order. The exception is a descendant that is needed by an active
    /// sibling: when the earlier sibling is blocked by a holder on the current owner's ancestry, waiting for that
    /// sibling would deadlock the active branch. The descendant may then re-enter the ancestor and let the sibling
    /// remain queued until the branch has finished.
    private boolean canBypassEarlier(Waiter earlier, Waiter current, boolean hasActiveAncestor) {
        if (!hasActiveAncestor) {
            return false;
        }
        if (earlier.owner.execution != current.owner.execution) {
            return true;
        }
        // Owners in the same execution domain that are neither ancestors nor descendants are siblings. Preserve their
        // declaration order even when both can reenter a resource held by their common parent, unless the earlier
        // sibling is itself blocked by a holder needed by the current descendant.
        if (earlier.owner.isAncestorOf(current.owner) || current.owner.isAncestorOf(earlier.owner)) {
            return true;
        }
        return earlier.prepared && isBlockedByCurrentAncestor(earlier, current.owner);
    }

    /// Returns whether an earlier request is blocked by a holder that is an ancestor of the current request.
    private boolean isBlockedByCurrentAncestor(Waiter earlier, Owner currentOwner) {
        for (TaskResource requested : earlier.owner.requestedResources) {
            for (Map.Entry<TaskResource, ResourceState> entry : resourceStates.entrySet()) {
                if (!entry.getValue().holders.isEmpty() && requested.conflictsWith(entry.getKey())) {
                    for (Owner holder : entry.getValue().holders.keySet()) {
                        if (!holder.isAncestorOf(earlier.owner) && holder.isAncestorOf(currentOwner)) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    /// Returns whether an owner has an ancestor that still holds at least one resource lease.
    private boolean hasActiveAncestor(Owner owner) {
        @Nullable Owner ancestor = owner.parent;
        while (ancestor != null) {
            for (ResourceState state : resourceStates.values()) {
                if (state.holders.containsKey(ancestor)) {
                    return true;
                }
            }
            ancestor = ancestor.parent;
        }
        return false;
    }

    /// Returns whether any resource state still holds a lease for the supplied owner.
    private boolean hasActiveHolder(Owner owner) {
        return resourceStates.values().stream().anyMatch(state -> state.holders.containsKey(owner));
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
        if (!waiter.counted) {
            return;
        }
        for (TaskResource resource : waiter.owner.requestedResources) {
            ResourceState state = Objects.requireNonNull(resourceStates.get(resource), "resource state");
            state.waiterCount--;
            if (state.waiterCount < 0) {
                throw new IllegalStateException("Negative task resource waiter count: " + resource);
            }
        }
        waiter.counted = false;
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
                if (!completion.waiter.future.complete(completion.lease)) {
                    // A caller may cancel the public future after the grant was recorded but before this
                    // out-of-monitor completion runs. Reclaim the holder in that narrow race.
                    completion.lease.close();
                }
            } else {
                completion.waiter.future.cancel(false);
            }
        }
    }

    /// Completes a public acquisition future while preserving cancellation as a cancelled future state.
    private static void completeFailure(CompletableFuture<Lease> future, Throwable failure) {
        Throwable resolved = failure;
        while (resolved instanceof java.util.concurrent.CompletionException
                && resolved.getCause() != null) {
            resolved = resolved.getCause();
        }
        if (resolved instanceof CancellationException) {
            future.cancel(false);
        } else {
            future.completeExceptionally(failure);
        }
    }

    /// Cancellation domain shared by one started task-executor chain.
    @NotNullByDefault
    static final class Execution {
        /// Whether pending acquisitions in this domain have been cancelled.
        private volatile boolean cancelled;

        /// Creates a live cancellation domain.
        private Execution() {
        }

        /// Returns whether this execution domain has received a cancellation request.
        private boolean isCancelled() {
            return cancelled;
        }
    }

    /// Logical task invocation owner with explicit ancestry and canonical immutable resource snapshots.
    @NotNullByDefault
    static final class Owner {
        /// Cancellation domain shared by the complete task chain.
        private final Execution execution;

        /// Parent owner, or null for a root invocation.
        private final @Nullable Owner parent;

        /// Canonical exact resources acquired by this invocation after preparation.
        private volatile @Unmodifiable List<TaskResource> requestedResources;

        /// Complete normalized coverage retained by this invocation and its ancestors.
        private volatile @Unmodifiable List<TaskResource> coverageResources;

        /// Whether this owner has released its lease but remains as an invocation-ancestry marker for descendants.
        private volatile boolean detached;

        /// Whether this owner is allowed to release before its dependencies acquire resources.
        private final boolean allowsDetachedChildren;

        /// Whether the declaration originated from the conservative fallback contract.
        private final boolean conservative;

        /// Whether this owner has already captured a canonical identity snapshot.
        private boolean prepared;

        /// Creates one owner node with immutable defensive snapshots.
        private Owner(
                Execution execution,
                @Nullable Owner parent,
                @Unmodifiable List<TaskResource> requestedResources,
                @Unmodifiable List<TaskResource> coverageResources,
                boolean conservative,
                boolean allowsDetachedChildren) {
            this.execution = execution;
            this.parent = parent;
            this.requestedResources = List.copyOf(requestedResources);
            this.coverageResources = List.copyOf(coverageResources);
            this.conservative = conservative;
            this.allowsDetachedChildren = allowsDetachedChildren;
        }

        /// Replaces lexical resource paths with canonical identities and validates nested coverage before enqueueing.
        private synchronized void prepare(@Unmodifiable List<TaskResource> resolvedResources) {
            @Unmodifiable List<TaskResource> normalizedRequested = TaskResource.normalize(resolvedResources);
            if (prepared) {
                if (!requestedResources.equals(normalizedRequested)) {
                    throw new IllegalStateException("Task owner was prepared with different resource identities");
                }
                return;
            }
            @Nullable Owner activeAncestor = nearestActiveAncestor(parent);
            if (activeAncestor != null) {
                for (TaskResource resource : normalizedRequested) {
                    boolean covered = activeAncestor.coverageResources.stream().anyMatch(ancestor ->
                            ancestor.permitsNested(resource));
                    if (!covered) {
                        throw new IllegalStateException(
                                "Nested task resource is outside its ancestor coverage: " + resource);
                    }
                }
            }

            ArrayList<TaskResource> coverage = new ArrayList<>();
            if (activeAncestor != null) {
                coverage.addAll(activeAncestor.coverageResources);
            }
            coverage.addAll(normalizedRequested);
            requestedResources = normalizedRequested;
            coverageResources = TaskResource.normalize(coverage);
            prepared = true;
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

        /// Immutable resource snapshot granted to this lease.
        private final @Unmodifiable List<TaskResource> resources;

        /// Whether release has already occurred.
        private final AtomicBoolean closed = new AtomicBoolean();

        /// Creates one granted lease with the exact canonical resources recorded at grant time.
        private Lease(
                TaskResourceLockManager manager,
                Owner owner,
                @Unmodifiable List<TaskResource> resources) {
            this.manager = manager;
            this.owner = owner;
            this.resources = List.copyOf(resources);
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

        /// Whether path identity resolution and nested coverage validation have completed.
        private boolean prepared;

        /// Whether this waiter contributes counts to resource states.
        private boolean counted;

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
