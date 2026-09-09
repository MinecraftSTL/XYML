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
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/// Coordinates asynchronous, execution-owner-aware acquisition of semantic task resources.
///
/// The manager never blocks a caller while a resource is occupied. All state transitions happen under a short monitor,
/// while future completion occurs after leaving that monitor. Owners use explicit ancestry rather than thread identity,
/// allowing reentrant task chains to cross executors without making sibling tasks mutually reentrant.
/// This is a process-internal coordination mechanism only: it does not provide inter-process exclusion, hard-link
/// identity protection, or durability guarantees for a filesystem operation.
@NotNullByDefault
final class TaskResourceLockManager {
    /// Process-wide manager used by production task executors.
    static final TaskResourceLockManager SHARED = new TaskResourceLockManager();

    /// Resource states retained only while they have holders or waiters.
    private final Map<TaskResource, ResourceState> resourceStates = new LinkedHashMap<>();

    /// Ordered pending acquisition requests.
    private final List<Waiter> waiters = new ArrayList<>();

    /// Leases whose release failed and can still be retried within this process.
    private final Set<Lease> residualLeases = Collections.newSetFromMap(new IdentityHashMap<>());

    /// Resource ranges blocked while one or more residual leases await cleanup.
    ///
    /// This marker is kept separately from [#resourceStates] because a corrupted or externally repaired state can be
    /// absent by the time the failed release is observed. New writes remain blocked until the owning lease completes a
    /// retry; reads are outside this arbiter and remain unaffected.
    private final Set<TaskResource> residualResources = new HashSet<>();

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
            // Keep lexical descendants until the asynchronous identity resolver has followed symlinks and junctions.
            // A lexical directory can appear to cover a child whose real target lies outside that directory.
            requested = TaskResource.snapshotDeclarations(declarationSnapshot);
        }

        ArrayList<TaskResource> coverage = new ArrayList<>();
        @Nullable Owner activeAncestor = nearestActiveAncestor(parent);
        if (activeAncestor != null) {
            coverage.addAll(activeAncestor.coverageResources);
        }
        coverage.addAll(requested);
        // Coverage is minimized after canonical identity resolution in Owner.prepare.  Retaining the lexical snapshot
        // here prevents a symlink or junction child from disappearing before the resolver can inspect it.
        @Unmodifiable List<TaskResource> normalizedCoverage = TaskResource.snapshotDeclarations(coverage);
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
    /// A single nearest branch boundary must cover every active declaration between the child and that boundary. A union
    /// of disjoint files or directories is not sufficient because an unknown child can write outside every member of
    /// the union. Repository metadata and operation scopes are coordination contracts; they become safely inheritable
    /// only when an active complete directory boundary covers them. Pure orchestration nodes do not contribute
    /// coverage. [Owner#prepare(List)] rejects a global fallback until any narrower active ancestor has handed off its
    /// lease.
    private static @Unmodifiable List<TaskResource> conservativeResourcesFor(@Nullable Owner parent) {
        @Nullable Owner current = nearestActiveAncestor(parent);
        ArrayList<TaskResource> required = new ArrayList<>();
        while (current != null) {
            @Unmodifiable List<TaskResource> declarations = current.requestedResources;
            ArrayList<TaskResource> boundaries = new ArrayList<>();
            for (TaskResource declaration : declarations) {
                if (declaration.getKind() == TaskResource.Kind.GLOBAL) {
                    return List.of(TaskResource.global());
                }
                if (declaration.getKind() != TaskResource.Kind.ORCHESTRATION) {
                    required.add(declaration);
                }
                if (declaration.isCompleteBoundary()) {
                    boundaries.add(declaration);
                }
            }

            if (!boundaries.isEmpty()) {
                for (TaskResource candidate : boundaries) {
                    if (required.stream().allMatch(candidate::permitsNested)) {
                        return List.of(candidate);
                    }
                }
                // This owner explicitly selected a branch, but no single branch covers its complete declaration.
                // Walking past it would turn a multi-resource operation into an incorrectly narrowed unknown write.
                return List.of(TaskResource.global());
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
        return acquire(owner, false);
    }

    /// Acquires one owner lease for terminal callbacks even after cooperative cancellation was requested.
    ///
    /// A task that already released its handoff lease still has to protect post-execution and terminal callbacks. Those
    /// callbacks are part of the committed lifecycle, so their waiter remains eligible after cancellation; cancelling
    /// the returned future still removes it normally when the executor's terminal sentinel closes the lease. The canonical
    /// snapshot captured by the initial acquisition is reused without resolving paths a second time.
    ///
    /// @param owner owner whose prepared resources are reacquired
    /// @return future completed with the terminal lease
    CompletableFuture<Lease> acquireForTerminal(Owner owner) {
        Objects.requireNonNull(owner, "owner");
        Waiter waiter = new Waiter(owner, true);
        waiter.future.whenComplete((@Nullable Lease ignoredLease, @Nullable Throwable ignoredFailure) -> {
            if (waiter.future.isCancelled()) {
                cancelWaiter(waiter);
            }
        });

        ArrayList<Completion> completions = new ArrayList<>();
        @Nullable Throwable terminalFailure = null;
        synchronized (this) {
            if (!owner.prepared) {
                terminalFailure = new IllegalStateException(
                        "Terminal task resource reacquisition requires a prepared owner");
            } else {
                waiters.add(waiter);
                waiter.prepared = true;
                try {
                    addWaiterReferences(waiter);
                    if (hasWaitCycle(owner)) {
                        waiters.remove(waiter);
                        removeWaiterReferences(waiter);
                        terminalFailure = new IllegalStateException(
                                "Task resource wait cycle detected across execution-owner chains");
                    }
                } catch (Throwable failure) {
                    waiters.remove(waiter);
                    removeWaiterReferences(waiter);
                    terminalFailure = failure;
                }
                completions.addAll(processWaiters());
                removeUnusedStates();
            }
        }
        try {
            complete(completions);
        } finally {
            // A different unclaimed lease in this completion batch may fail its cleanup.  The terminal waiter has
            // already been removed from the manager, so publish its own failure even when that unrelated cleanup
            // exception escapes from complete().
            if (terminalFailure != null) {
                completeFailure(waiter.future, terminalFailure);
            }
        }
        return waiter.future;
    }

    /// Enqueues one owner lease request with an optional terminal-callback cancellation exemption.
    ///
    /// @param owner logical owner requesting resources
    /// @param survivesCancellation whether this committed terminal request survives its execution cancellation flag
    /// @return future completed with an internal lease
    private CompletableFuture<Lease> acquire(Owner owner, boolean survivesCancellation) {
        Objects.requireNonNull(owner, "owner");
        Waiter waiter = new Waiter(owner, survivesCancellation);
        boolean cancelled;
        synchronized (this) {
            cancelled = owner.execution.isCancelled() && !survivesCancellation;
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

            if ((waiter.owner.execution.cancelled && !waiter.survivesCancellation) || waiter.future.isCancelled()) {
                waiters.remove(waiter);
                removeWaiterReferences(waiter);
                completions.add(Completion.cancelled(waiter));
            } else if (resolutionFailure != null) {
                waiters.remove(waiter);
                removeWaiterReferences(waiter);
                terminalFailure = resolutionFailure;
            } else {
                try {
                    @Nullable Owner activeAncestor = nearestActiveAncestor(waiter.owner.parent);
                    if (activeAncestor != null && !hasActiveHolder(activeAncestor)) {
                        throw new IllegalStateException(
                                "Nested task resource acquisition requires an active ancestor lease");
                    }
                    waiter.owner.prepare(Objects.requireNonNull(resolvedResources, "resolved resources"));
                    waiter.prepared = true;
                    addWaiterReferences(waiter);
                    if (hasWaitCycle(waiter.owner)) {
                        terminalFailure = new IllegalStateException(
                                "Task resource wait cycle detected across execution-owner chains");
                    }
                } catch (Throwable failure) {
                    terminalFailure = failure;
                }
                if (terminalFailure != null) {
                    waiters.remove(waiter);
                    removeWaiterReferences(waiter);
                    waiter.prepared = false;
                }
            }

            completions.addAll(processWaiters());
            removeUnusedStates();
        }
        try {
            complete(completions);
        } finally {
            // Preserve the resolution failure for the caller even if publishing another completion encounters a
            // residual-cleanup exception.
            if (terminalFailure != null) {
                completeFailure(waiter.future, terminalFailure);
            }
        }
    }

    /// Adds one prepared waiter's references to dynamic resource states.
    private void addWaiterReferences(Waiter waiter) {
        if (waiter.counted) {
            throw new IllegalStateException("Task resource waiter was counted twice");
        }
        int addedCount = 0;
        try {
            for (TaskResource resource : waiter.owner.requestedResources) {
                resourceStates.computeIfAbsent(resource, ignored -> new ResourceState()).waiterCount++;
                addedCount++;
            }
            waiter.counted = true;
        } catch (Throwable failure) {
            // A runtime failure or Error during state creation must not leave a partially counted waiter behind.
            for (int index = addedCount - 1; index >= 0; index--) {
                TaskResource resource = waiter.owner.requestedResources.get(index);
                ResourceState state = Objects.requireNonNull(resourceStates.get(resource), "resource state");
                state.waiterCount--;
            }
            removeUnusedStates();
            throw failure;
        }
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
                if (waiter.owner.execution == execution && !waiter.survivesCancellation) {
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

    /// Returns immutable descriptions of residual leases owned by the supplied execution domains.
    ///
    /// @param executions execution domains whose residual leases are requested
    /// @return stable residual resource descriptions
    @Unmodifiable List<String> residualResourceDescriptions(Set<Execution> executions) {
        Objects.requireNonNull(executions, "executions");
        synchronized (this) {
            return residualLeases.stream()
                    .filter(lease -> executions.contains(lease.owner.execution))
                    .flatMap(lease -> lease.resources.stream()
                            .filter(resource -> !lease.releasedResources.contains(resource)))
                    .map(TaskResource::toString)
                    .distinct()
                    .sorted()
                    .toList();
        }
    }

    /// Retries residual lease release a bounded number of times for the supplied execution domains.
    ///
    /// A failed lease remains in [#residualLeases] and is reported again. Successful leases remove themselves from
    /// that set through [Lease#close()].
    ///
    /// @param executions execution domains whose residual leases should be retried
    /// @return residual resource descriptions after the retry
    @Unmodifiable List<String> retryResidualCleanup(Set<Execution> executions) {
        Objects.requireNonNull(executions, "executions");
        @Unmodifiable List<Lease> candidates;
        synchronized (this) {
            candidates = residualLeases.stream()
                    .filter(lease -> executions.contains(lease.owner.execution))
                    .toList();
        }
        for (Lease lease : candidates) {
            try {
                lease.close();
            } catch (RuntimeException | Error ignored) {
                // The lease remains retained and is reported to the caller for a later bounded retry.
            }
        }
        return residualResourceDescriptions(executions);
    }

    /// Releases one lease in reverse resource order and grants newly unblocked waiters.
    private void release(Lease lease) {
        ArrayList<Completion> completions;
        ArrayList<String> residual = new ArrayList<>();
        synchronized (this) {
            List<TaskResource> resources = lease.resources;
            boolean retryAttempt = residualLeases.contains(lease);
            for (int index = resources.size() - 1; index >= 0; index--) {
                TaskResource resource = resources.get(index);
                if (lease.releasedResources.contains(resource)) {
                    continue;
                }
                @Nullable ResourceState state = resourceStates.get(resource);
                if (state == null) {
                    if (retryAttempt) {
                        // A failed cleanup may have been completed by an external lifecycle callback. Once the
                        // bounded retry confirms that no holder remains, the process-local marker can converge.
                        lease.releasedResources.add(resource);
                    } else {
                        residual.add(resource.toString());
                        residualResources.add(resource);
                    }
                    continue;
                }
                @Nullable Integer count = state.holders.get(lease.owner);
                if (count == null || count <= 0) {
                    if (retryAttempt) {
                        lease.releasedResources.add(resource);
                    } else {
                        residual.add(resource.toString());
                        residualResources.add(resource);
                    }
                    continue;
                }
                if (count == 1) {
                    state.holders.remove(lease.owner);
                } else {
                    state.holders.put(lease.owner, count - 1);
                }
                lease.releasedResources.add(resource);
            }
            if (lease.owner.allowsDetachedChildren && !hasActiveHolder(lease.owner)) {
                lease.owner.detached = true;
            }
            removeUnusedStates();
            completions = new ArrayList<>(processWaiters());
            removeUnusedStates();
        }
        complete(completions);
        if (!residual.isEmpty()) {
            throw new TaskResourceCleanupException(List.copyOf(residual));
        }
    }

    /// Removes one successfully closed residual lease and wakes requests that were blocked by its marker.
    private void completeLeaseClose(Lease lease) {
        ArrayList<Completion> completions;
        synchronized (this) {
            residualLeases.remove(lease);
            pruneResidualResources();
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
            if (waiter.owner.execution.cancelled && !waiter.survivesCancellation) {
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
            // An external request has no ancestry relationship with the current branch. It may be bypassed only
            // after identity resolution proves that it is blocked by a resource held by this branch; otherwise an
            // unresolved or independently blocked waiter would be overtaken and FIFO would be violated.
            return earlier.prepared && isBlockedByCurrentAncestor(earlier, current.owner);
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
            if (residualResources.stream().anyMatch(requested::conflictsWith)) {
                return false;
            }
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

    /// Returns whether a newly prepared owner closes a resource-wait cycle.
    ///
    /// An active parent lifecycle waits for every descendant invocation it starts. A prepared waiter in turn waits for
    /// every conflicting holder outside its ancestry. Walking both edge kinds detects cross-branch ABBA patterns even
    /// when the branches run on different threads or executors.
    ///
    /// @param owner newly prepared waiting owner
    /// @return whether the owner participates in a wait cycle
    private boolean hasWaitCycle(Owner owner) {
        Set<Owner> visited = newIdentityOwnerSet();
        Set<Owner> visiting = newIdentityOwnerSet();
        return hasWaitCycle(owner, visited, visiting);
    }

    /// Performs one depth-first traversal of the current owner wait graph.
    ///
    /// @param owner owner currently being visited
    /// @param visited owners already traversed
    /// @param visiting owners on the active recursion path
    /// @return whether the traversal encounters an active owner twice
    private boolean hasWaitCycle(Owner owner, Set<Owner> visited, Set<Owner> visiting) {
        if (!visiting.add(owner)) {
            return true;
        }
        if (!visited.add(owner)) {
            visiting.remove(owner);
            return false;
        }

        for (Owner dependency : waitDependencies(owner)) {
            if (hasWaitCycle(dependency, visited, visiting)) {
                return true;
            }
        }
        visiting.remove(owner);
        return false;
    }

    /// Returns the current owners whose completion is required before this owner can finish.
    ///
    /// @param owner owner whose outgoing wait edges are requested
    /// @return immutable identity set of owners that must finish first
    private @Unmodifiable Set<Owner> waitDependencies(Owner owner) {
        Set<Owner> dependencies = newIdentityOwnerSet();
        if (hasActiveHolder(owner)) {
            for (Owner candidate : activeOwners()) {
                if (candidate != owner && owner.isAncestorOf(candidate)) {
                    dependencies.add(candidate);
                }
            }
        }

        @Nullable Waiter waiter = waiterFor(owner);
        if (waiter != null && waiter.prepared) {
            for (TaskResource requested : owner.requestedResources) {
                for (Map.Entry<TaskResource, ResourceState> entry : resourceStates.entrySet()) {
                    if (!entry.getValue().holders.isEmpty() && requested.conflictsWith(entry.getKey())) {
                        for (Owner holder : entry.getValue().holders.keySet()) {
                            if (!holder.isAncestorOf(owner)) {
                                dependencies.add(holder);
                            }
                        }
                    }
                }
            }
        }
        return Collections.unmodifiableSet(dependencies);
    }

    /// Returns every holder and prepared waiter represented in the current graph.
    ///
    /// @return immutable identity set of active graph nodes
    private @Unmodifiable Set<Owner> activeOwners() {
        Set<Owner> owners = newIdentityOwnerSet();
        for (ResourceState state : resourceStates.values()) {
            owners.addAll(state.holders.keySet());
        }
        for (Waiter waiter : waiters) {
            if (waiter.prepared) {
                owners.add(waiter.owner);
            }
        }
        return Collections.unmodifiableSet(owners);
    }

    /// Finds the prepared waiter for one owner identity, if the owner is still pending.
    ///
    /// @param owner owner identity to find
    /// @return matching waiter, or null when the owner is not pending
    private @Nullable Waiter waiterFor(Owner owner) {
        for (Waiter waiter : waiters) {
            if (waiter.owner == owner) {
                return waiter;
            }
        }
        return null;
    }

    /// Creates a mutable identity-based owner set.
    ///
    /// @return empty mutable identity set
    private static Set<Owner> newIdentityOwnerSet() {
        return Collections.newSetFromMap(new IdentityHashMap<>());
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
    ///
    /// A caller may cancel a public future after a grant has been recorded but before this method publishes it.  The
    /// unclaimed lease must then be closed; a cleanup failure is retained as a residual marker, but must not prevent
    /// later grants in the same batch from being completed.  Every unclaimed lease is therefore attempted and failures
    /// are aggregated after the batch has been drained.
    private static void complete(Collection<Completion> completions) {
        @Nullable Throwable firstFailure = null;
        for (Completion completion : completions) {
            if (completion.lease != null) {
                if (!completion.waiter.future.complete(completion.lease)) {
                    // A caller may cancel the public future after the grant was recorded but before this
                    // out-of-monitor completion runs. Reclaim the holder in that narrow race.
                    try {
                        completion.lease.close();
                    } catch (RuntimeException | Error failure) {
                        if (firstFailure == null) {
                            firstFailure = failure;
                        } else if (firstFailure != failure) {
                            firstFailure.addSuppressed(failure);
                        }
                    }
                }
            } else {
                completion.waiter.future.cancel(false);
            }
        }
        if (firstFailure instanceof RuntimeException failure) {
            throw failure;
        }
        if (firstFailure instanceof Error failure) {
            throw failure;
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

        /// Lexical resource snapshot before preparation, replaced with canonical exact resources after preparation.
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
                    if (!activeAncestor.permitsNested(resource)) {
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

        /// Returns whether the complete active coverage permits one nested resource request.
        ///
        /// A cache transaction may branch outside the parent filesystem boundary only when it is disjoint from every
        /// retained ancestor resource. Checking the complete set prevents an unrelated parent key from hiding a
        /// partially overlapping cache expansion.
        ///
        /// @param resource normalized nested resource
        /// @return whether the nested request stays covered or is an audited disjoint cache branch
        private boolean permitsNested(TaskResource resource) {
            if (resource.getKind() == TaskResource.Kind.ORCHESTRATION) {
                return true;
            }
            boolean hasFilesystemCoverage = coverageResources.stream()
                    .anyMatch(ancestor -> ancestor.getKind() != TaskResource.Kind.ORCHESTRATION);
            if (!hasFilesystemCoverage) {
                // A genuinely pure orchestration owner contributes no filesystem range. Its explicitly declared child
                // starts a new range; conservative children still resolve to the global fallback at the root.
                return true;
            }
            if (coverageResources.stream()
                    .filter(ancestor -> ancestor.getKind() != TaskResource.Kind.ORCHESTRATION)
                    .anyMatch(ancestor -> ancestor.permitsNested(resource))) {
                return true;
            }
            return resource.getKind() == TaskResource.Kind.CACHE_OPERATION
                    && coverageResources.stream()
                    .filter(ancestor -> ancestor.getKind() != TaskResource.Kind.ORCHESTRATION)
                    .noneMatch(ancestor -> ancestor.conflictsWith(resource));
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

        /// Resources already released by a partially successful cleanup attempt.
        private final Set<TaskResource> releasedResources = new HashSet<>();

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
                try {
                    manager.release(this);
                    manager.completeLeaseClose(this);
                } catch (RuntimeException | Error failure) {
                    synchronized (manager) {
                        boolean hasUnreleasedResources = resources.stream()
                                .anyMatch(resource -> !releasedResources.contains(resource));
                        // The exception may have come from another lease in the same completion batch.  Do not retain
                        // an empty residual lease after this lease's own resources were already released.
                        closed.set(!hasUnreleasedResources);
                        if (hasUnreleasedResources) {
                            manager.residualLeases.add(this);
                        } else {
                            manager.residualLeases.remove(this);
                        }
                        manager.pruneResidualResources();
                    }
                    throw failure;
                }
            }
        }
    }

    /// Removes residual markers no longer backed by an unreleased lease.
    private void pruneResidualResources() {
        residualResources.removeIf(resource -> residualLeases.stream()
                .noneMatch(lease -> lease.resources.contains(resource) && !lease.releasedResources.contains(resource)));
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

        /// Whether this request protects committed terminal callbacks after execution cancellation.
        private final boolean survivesCancellation;

        /// Creates one pending request.
        private Waiter(Owner owner, boolean survivesCancellation) {
            this.owner = owner;
            this.survivesCancellation = survivesCancellation;
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
