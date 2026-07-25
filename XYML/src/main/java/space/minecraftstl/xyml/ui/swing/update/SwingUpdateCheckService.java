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
package space.minecraftstl.xyml.ui.swing.update;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import space.minecraftstl.xyml.observable.Subscription;
import space.minecraftstl.xyml.observable.ValueChange;
import space.minecraftstl.xyml.observable.ValueChangeListener;
import space.minecraftstl.xyml.upgrade.RemoteVersion;
import space.minecraftstl.xyml.util.Lang;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/// Toolkit-neutral, observable launcher update-check service used by the Swing production UI.
///
/// Source work is always submitted to the configured worker executor. Equal concurrent requests share a single
/// completion stage, while distinct requests are serialized in arrival order. Snapshot publication is synchronous
/// on the thread committing the transition, and listener failures are isolated from later listeners.
@NotNullByDefault
public final class SwingUpdateCheckService implements AutoCloseable {
    /// Serializes operation ownership, queue state, snapshot replacement, and closure.
    private final Object stateLock = new Object();

    /// Serializes observable transitions without invoking listeners under [#stateLock].
    private final Object publicationLock = new Object();

    /// Blocking remote-version source invoked only through [#workerExecutor].
    private final UpdateCheckSource source;

    /// Policy that maps a fetched version to update availability.
    private final UpdateAvailabilityPolicy availabilityPolicy;

    /// Executor receiving one active blocking source operation at a time.
    private final Executor workerExecutor;

    /// Clock used to timestamp immutable terminal results.
    private final Clock clock;

    /// Action releasing an owned worker executor, or a no-op for externally owned executors.
    private final Runnable workerCloseAction;

    /// Snapshot listeners retained independently and removable through subscriptions.
    private final CopyOnWriteArrayList<ListenerSlot> listeners = new CopyOnWriteArrayList<>();

    /// All active or queued operations keyed for equal-request deduplication.
    private final Map<UpdateCheckRequest, Operation> operations = new HashMap<>();

    /// Distinct operations waiting behind [#activeOperation].
    private final ArrayDeque<Operation> pendingOperations = new ArrayDeque<>();

    /// Latest immutable observable snapshot.
    private volatile UpdateCheckSnapshot currentSnapshot = UpdateCheckSnapshot.idle();

    /// Operation currently allowed to enter source work, or null while idle.
    private @Nullable Operation activeOperation;

    /// Whether the service has rejected future checks and gated late source results.
    private volatile boolean closed;

    /// Creates a service using an externally owned worker executor.
    ///
    /// Distinct requests remain serialized even when the supplied executor can run tasks concurrently. Closing the
    /// service does not shut down the supplied executor.
    ///
    /// @param source blocking remote-version source
    /// @param availabilityPolicy fetched-version comparison policy
    /// @param workerExecutor worker executor that must not be the Swing EDT
    public SwingUpdateCheckService(
            UpdateCheckSource source,
            UpdateAvailabilityPolicy availabilityPolicy,
            Executor workerExecutor) {
        this(
                source,
                availabilityPolicy,
                workerExecutor,
                Clock.systemUTC(),
                () -> {
                });
    }

    /// Creates the production service with a dedicated daemon worker that is released by [#close()].
    ///
    /// @return production update-check service
    public static SwingUpdateCheckService production() {
        ExecutorService worker = Executors.newSingleThreadExecutor(operation -> {
            Thread thread = new Thread(operation, "Swing Update Checker");
            thread.setDaemon(true);
            return thread;
        });
        return new SwingUpdateCheckService(
                RemoteUpdateCheckSource.production(),
                MetadataUpdateAvailabilityPolicy.production(),
                worker,
                Clock.systemUTC(),
                worker::shutdownNow);
    }

    /// Creates a fully injectable service.
    ///
    /// @param source blocking remote-version source
    /// @param availabilityPolicy fetched-version comparison policy
    /// @param workerExecutor executor receiving serialized source operations
    /// @param clock terminal timestamp clock
    /// @param workerCloseAction action releasing an owned executor
    SwingUpdateCheckService(
            UpdateCheckSource source,
            UpdateAvailabilityPolicy availabilityPolicy,
            Executor workerExecutor,
            Clock clock,
            Runnable workerCloseAction) {
        this.source = Objects.requireNonNull(source, "source");
        this.availabilityPolicy = Objects.requireNonNull(availabilityPolicy, "availabilityPolicy");
        this.workerExecutor = Objects.requireNonNull(workerExecutor, "workerExecutor");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.workerCloseAction = Objects.requireNonNull(workerCloseAction, "workerCloseAction");
    }

    /// Returns the latest immutable service snapshot.
    ///
    /// @return current update-check snapshot
    public UpdateCheckSnapshot snapshot() {
        return currentSnapshot;
    }

    /// Registers a listener for future snapshot transitions.
    ///
    /// The caller can read [#snapshot()] before or after registration when it needs initial state. The controller
    /// supplied by this package subscribes first and then reconciles the snapshot to avoid missing a transition.
    ///
    /// @param listener synchronous transition listener
    /// @return independently removable listener subscription
    public Subscription subscribe(ValueChangeListener<UpdateCheckSnapshot> listener) {
        Objects.requireNonNull(listener, "listener");
        synchronized (stateLock) {
            requireOpen();
            ListenerSlot slot = new ListenerSlot(listener);
            listeners.add(slot);
            return Subscription.create(() -> listeners.remove(slot));
        }
    }

    /// Starts or joins an update check.
    ///
    /// Equal active or queued requests receive the same terminal outcome and invoke the source once. A distinct
    /// request is queued behind existing work instead of racing it or receiving another request's result.
    ///
    /// @param request exact channel and preview request
    /// @return stage completed with the successful result or the original source/policy failure
    public CompletionStage<UpdateCheckResult> check(UpdateCheckRequest request) {
        Objects.requireNonNull(request, "request");
        Operation operation;
        boolean scheduleNow = false;
        synchronized (stateLock) {
            requireOpen();
            @Nullable Operation existing = operations.get(request);
            if (existing != null) {
                return existing.completion();
            }
            operation = new Operation(request);
            operations.put(request, operation);
            if (activeOperation == null) {
                activeOperation = operation;
                scheduleNow = true;
            } else {
                pendingOperations.addLast(operation);
            }
        }
        if (scheduleNow) {
            schedule(operation);
        }
        return operation.completion();
    }

    /// Permanently rejects checks, publishes closure, and completes active and queued stages exceptionally.
    ///
    /// A blocking externally owned source may continue running, but its late result is gated from the snapshot.
    /// The production executor is interrupted through `shutdownNow` after closure has become observable.
    @Override
    public void close() {
        @Unmodifiable List<Operation> operationsToCancel;
        synchronized (publicationLock) {
            UpdateCheckSnapshot previous;
            UpdateCheckSnapshot closedSnapshot;
            synchronized (stateLock) {
                if (closed) {
                    return;
                }
                closed = true;
                previous = currentSnapshot;
                closedSnapshot = new UpdateCheckSnapshot(
                        UpdateCheckSnapshot.Status.CLOSED,
                        Optional.empty(),
                        previous.lastSuccessfulResult(),
                        previous.lastFailure(),
                        previous.revision() + 1L);
                currentSnapshot = closedSnapshot;
                operationsToCancel = List.copyOf(new ArrayList<>(operations.values()));
                operations.clear();
                pendingOperations.clear();
                activeOperation = null;
            }
            publishLocked(new SnapshotTransition(previous, closedSnapshot));
            listeners.clear();
        }

        CancellationException cancellation = new CancellationException("Update check service closed");
        for (Operation operation : operationsToCancel) {
            operation.completion().completeExceptionally(cancellation);
        }
        workerCloseAction.run();
    }

    /// Submits one still-active operation to the worker executor.
    ///
    /// @param operation operation selected as active
    private void schedule(Operation operation) {
        synchronized (stateLock) {
            if (closed || activeOperation != operation) {
                return;
            }
        }
        try {
            workerExecutor.execute(() -> runOperation(operation));
        } catch (RuntimeException schedulingFailure) {
            finishFailure(operation, schedulingFailure);
        }
    }

    /// Performs one source request and commits exactly one terminal outcome.
    ///
    /// @param operation active operation
    private void runOperation(Operation operation) {
        if (!beginOperation(operation)) {
            return;
        }
        synchronized (stateLock) {
            if (closed || activeOperation != operation) {
                return;
            }
        }

        try {
            RemoteVersion remoteVersion = Objects.requireNonNull(
                    source.fetch(operation.request()),
                    "update source returned null");
            boolean updateAvailable = availabilityPolicy.isUpdateAvailable(remoteVersion);
            UpdateCheckResult result = new UpdateCheckResult(
                    operation.request(),
                    remoteVersion,
                    updateAvailable,
                    clock.instant());
            finishSuccess(operation, result);
        } catch (Throwable failure) {
            finishFailure(operation, failure);
            if (failure instanceof Error error) {
                throw error;
            }
        }
    }

    /// Publishes the active checking state before blocking source work begins.
    ///
    /// @param operation active operation
    /// @return whether the operation still owns source work after publication
    private boolean beginOperation(Operation operation) {
        synchronized (publicationLock) {
            SnapshotTransition transition;
            synchronized (stateLock) {
                if (closed || activeOperation != operation) {
                    return false;
                }
                UpdateCheckSnapshot previous = currentSnapshot;
                UpdateCheckSnapshot checking = new UpdateCheckSnapshot(
                        UpdateCheckSnapshot.Status.CHECKING,
                        Optional.of(operation.request()),
                        previous.lastSuccessfulResult(),
                        Optional.empty(),
                        previous.revision() + 1L);
                currentSnapshot = checking;
                transition = new SnapshotTransition(previous, checking);
            }
            publishLocked(transition);
        }
        synchronized (stateLock) {
            return !closed && activeOperation == operation;
        }
    }

    /// Commits a successful result, completes its stage, and promotes the next distinct request.
    ///
    /// @param operation completing active operation
    /// @param result immutable successful result
    private void finishSuccess(Operation operation, UpdateCheckResult result) {
        @Nullable Operation next;
        synchronized (publicationLock) {
            SnapshotTransition transition;
            synchronized (stateLock) {
                if (closed || activeOperation != operation) {
                    return;
                }
                UpdateCheckSnapshot previous = currentSnapshot;
                UpdateCheckSnapshot succeeded = new UpdateCheckSnapshot(
                        UpdateCheckSnapshot.Status.SUCCEEDED,
                        Optional.empty(),
                        Optional.of(result),
                        Optional.empty(),
                        previous.revision() + 1L);
                currentSnapshot = succeeded;
                next = advanceLocked(operation);
                transition = new SnapshotTransition(previous, succeeded);
            }
            publishLocked(transition);
        }
        operation.completion().complete(result);
        if (next != null) {
            schedule(next);
        }
    }

    /// Commits a failed attempt while preserving the most recent successful result.
    ///
    /// @param operation completing active operation
    /// @param failure original source, policy, or scheduling failure
    private void finishFailure(Operation operation, Throwable failure) {
        Objects.requireNonNull(failure, "failure");
        @Nullable Operation next;
        synchronized (publicationLock) {
            SnapshotTransition transition;
            synchronized (stateLock) {
                if (closed || activeOperation != operation) {
                    return;
                }
                UpdateCheckSnapshot previous = currentSnapshot;
                UpdateCheckSnapshot failed = new UpdateCheckSnapshot(
                        UpdateCheckSnapshot.Status.FAILED,
                        Optional.empty(),
                        previous.lastSuccessfulResult(),
                        Optional.of(describeFailure(operation.request(), failure)),
                        previous.revision() + 1L);
                currentSnapshot = failed;
                next = advanceLocked(operation);
                transition = new SnapshotTransition(previous, failed);
            }
            publishLocked(transition);
        }
        operation.completion().completeExceptionally(failure);
        if (next != null) {
            schedule(next);
        }
    }

    /// Removes one terminal operation and promotes the oldest queued distinct request.
    ///
    /// This method must run under [#stateLock].
    ///
    /// @param operation completing operation
    /// @return promoted operation, or null when the queue is empty
    private @Nullable Operation advanceLocked(Operation operation) {
        operations.remove(operation.request(), operation);
        activeOperation = pendingOperations.pollFirst();
        return activeOperation;
    }

    /// Converts a mutable throwable into immutable snapshot diagnostics.
    ///
    /// @param request failed request
    /// @param failure original failure
    /// @return immutable failure description
    private UpdateCheckSnapshot.Failure describeFailure(
            UpdateCheckRequest request,
            Throwable failure) {
        String failureType = failure.getClass().getName();
        @Nullable String detail = failure.getMessage();
        String message = detail == null || detail.isBlank() ? failureType : detail;
        Instant failedAt = clock.instant();
        return new UpdateCheckSnapshot.Failure(
                request,
                failureType,
                message,
                failedAt);
    }

    /// Delivers one committed transition to currently subscribed listeners.
    ///
    /// This method must run under [#publicationLock] and never under [#stateLock].
    ///
    /// @param transition committed snapshot transition
    private void publishLocked(SnapshotTransition transition) {
        ValueChange<UpdateCheckSnapshot> change = new ValueChange<>(
                this,
                transition.previous(),
                transition.current());
        for (ListenerSlot listener : listeners) {
            listener.notifySafely(change);
        }
    }

    /// Rejects a command after service closure.
    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("Update check service is closed");
        }
    }

    /// One deduplicated active or queued request and its shared terminal future.
    @NotNullByDefault
    private static final class Operation {
        /// Exact request identity.
        private final UpdateCheckRequest request;

        /// Shared terminal future returned to every equal concurrent caller.
        private final CompletableFuture<UpdateCheckResult> completion = new CompletableFuture<>();

        /// Creates one operation.
        ///
        /// @param request exact request identity
        private Operation(UpdateCheckRequest request) {
            this.request = Objects.requireNonNull(request, "request");
        }

        /// Returns the exact request identity.
        ///
        /// @return request identity
        private UpdateCheckRequest request() {
            return request;
        }

        /// Returns the shared mutable future owned only by the service.
        ///
        /// @return terminal future
        private CompletableFuture<UpdateCheckResult> completion() {
            return completion;
        }
    }

    /// One committed before/after snapshot pair.
    ///
    /// @param previous snapshot before the transition
    /// @param current snapshot after the transition
    @NotNullByDefault
    private record SnapshotTransition(
            UpdateCheckSnapshot previous,
            UpdateCheckSnapshot current) {
        /// Validates one transition pair.
        private SnapshotTransition {
            Objects.requireNonNull(previous, "previous");
            Objects.requireNonNull(current, "current");
        }
    }

    /// Independently removable listener registration with runtime-failure isolation.
    @NotNullByDefault
    private static final class ListenerSlot {
        /// Listener owned by this exact registration.
        private final ValueChangeListener<UpdateCheckSnapshot> listener;

        /// Creates one isolated listener registration.
        ///
        /// @param listener listener to own
        private ListenerSlot(ValueChangeListener<UpdateCheckSnapshot> listener) {
            this.listener = Objects.requireNonNull(listener, "listener");
        }

        /// Delivers one transition while allowing later listeners to survive a runtime failure.
        ///
        /// @param change immutable update snapshot transition
        private void notifySafely(ValueChange<UpdateCheckSnapshot> change) {
            try {
                listener.onChange(change);
            } catch (RuntimeException listenerFailure) {
                try {
                    Lang.handleUncaughtException(listenerFailure);
                } catch (RuntimeException ignored) {
                    // Listener diagnostics must not break update state publication.
                }
            }
        }
    }
}
