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
package space.minecraftstl.xyml.ui.swing.page.downloads;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import space.minecraftstl.xyml.download.DownloadProvider;
import space.minecraftstl.xyml.download.DownloadProviderWrapper;
import space.minecraftstl.xyml.download.RemoteVersion;
import space.minecraftstl.xyml.download.VersionList;
import space.minecraftstl.xyml.download.game.GameRemoteVersion;
import space.minecraftstl.xyml.observable.Subscription;
import space.minecraftstl.xyml.task.Task;
import space.minecraftstl.xyml.task.TaskExecutor;
import space.minecraftstl.xyml.task.TaskListener;
import space.minecraftstl.xyml.ui.swing.choice.LoadCancellation;
import space.minecraftstl.xyml.util.versioning.GameVersionNumber;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/// Adapts the launcher's mutable game-version download list to an immutable Swing catalog source.
///
/// The underlying download providers cache mutable [VersionList] instances, so this adapter permits
/// at most one refresh task to run at a time. A newer request cancels the running result and replaces
/// any request that was already waiting, but does not start until the old task reports its terminal
/// event. Provider-wrapper changes are snapshotted once per request and cannot mix refresh and read
/// operations from different providers.
@NotNullByDefault
public final class DownloadProviderGameVersionCatalogSource
        implements GameVersionCatalogSource, AutoCloseable {
    /// Download-provider list identifier for the Minecraft game catalog.
    private static final String VERSION_LIST_ID = "game";

    /// Stable list scope used for both refresh and the subsequent snapshot read.
    private static final String VERSION_LIST_SCOPE = "game";

    /// Lock protecting lifecycle state and the active/latest-waiting operation slots.
    private final Object stateLock = new Object();

    /// Reentrant barrier surrounding every provider, task, mapping, and cancellation invocation.
    ///
    /// A single monitor prevents provider and task callbacks on different threads from acquiring two
    /// lifecycle barriers in opposite order while both re-enter [#close()].
    private final Object externalInvocationLock = new Object();

    /// Reentrant barrier making detached-resource cleanup observable to every concurrent close caller.
    private final Object cleanupInvocationLock = new Object();

    /// Stable configured provider or wrapper resolved again for every request.
    private final DownloadProvider configuredProvider;

    /// Factory boundary allowing deterministic executor lifecycle tests without changing production scheduling.
    private final TaskExecutorFactory executorFactory;

    /// The only operation allowed to create or run a core refresh task, or null while idle.
    private @Nullable LoadOperation activeOperation;

    /// Latest request waiting for the active task to stop, or null when no request is queued.
    private @Nullable LoadOperation pendingOperation;

    /// Whether this source permanently rejects new work and discards all late task events.
    private boolean closed;

    /// First detached-resource cleanup failure replayed to every later close caller.
    private @Nullable Throwable closeFailure;

    /// Creates a catalog source using the task executor supplied by each core refresh task.
    ///
    /// @param downloadProvider stable configured provider, which may be a mutable provider wrapper
    public DownloadProviderGameVersionCatalogSource(DownloadProvider downloadProvider) {
        this(downloadProvider, task -> task.executor());
    }

    /// Creates a catalog source with an explicit task-executor factory for package-level tests.
    ///
    /// The factory is caller-owned and is never closed by this source.
    ///
    /// @param downloadProvider stable configured provider, which may be a mutable provider wrapper
    /// @param executorFactory factory for one stopped executor per refresh task
    DownloadProviderGameVersionCatalogSource(
            DownloadProvider downloadProvider,
            TaskExecutorFactory executorFactory) {
        this.configuredProvider = Objects.requireNonNull(downloadProvider, "downloadProvider");
        this.executorFactory = Objects.requireNonNull(executorFactory, "executorFactory");
    }

    /// Starts or queues a latest-wins game-catalog refresh.
    ///
    /// @param cancellation cooperative signal owned by the catalog model
    /// @return minimal completion-stage view of the immutable mapped catalog
    @Override
    public CompletionStage<@Unmodifiable List<GameVersionCatalogItem>> load(
            LoadCancellation cancellation) {
        Objects.requireNonNull(cancellation, "cancellation");
        final LoadOperation operation;
        @Nullable LoadOperation supersededPending = null;
        @Nullable LoadOperation supersededActive = null;
        boolean startNow = false;
        synchronized (externalInvocationLock) {
            synchronized (stateLock) {
                requireOpen();
            }
            if (cancellation.isCancelled()) {
                return cancelledStage();
            }

            final VersionList<?> versionList;
            try {
                DownloadProvider requestProvider = unwrapProvider(configuredProvider);
                @Nullable VersionList<?> selectedList = requestProvider.getVersionListById(VERSION_LIST_ID);
                versionList = Objects.requireNonNull(
                        selectedList,
                        "download provider returned null version list");
            } catch (RuntimeException failure) {
                return failedStage(failure);
            }

            operation = new LoadOperation(cancellation, versionList);
            synchronized (stateLock) {
                requireOpen();
                if (activeOperation == null) {
                    activeOperation = operation;
                    operation.startPending = true;
                    startNow = true;
                } else {
                    supersededPending = pendingOperation;
                    if (supersededPending != null) {
                        supersededPending.cancelRequested = true;
                        supersededPending.terminal = true;
                    }
                    pendingOperation = operation;

                    supersededActive = activeOperation;
                    supersededActive.cancelRequested = true;
                }
            }
        }

        cancelStage(supersededPending);
        cancelStage(supersededActive);
        @Nullable Throwable controlFailure = null;
        synchronized (externalInvocationLock) {
            @Nullable TaskExecutor executorToCancel;
            synchronized (stateLock) {
                executorToCancel = closed || supersededActive == null
                        ? null
                        : takeCancellationExecutorLocked(supersededActive);
            }
            if (executorToCancel != null) {
                try {
                    executorToCancel.cancel();
                } catch (RuntimeException | Error failure) {
                    controlFailure = failure;
                    synchronized (stateLock) {
                        if (activeOperation == supersededActive && !supersededActive.terminal) {
                            supersededActive.cancellationForwarded = false;
                        }
                    }
                }
            }

            if (controlFailure != null) {
                failPreparedOperation(operation, controlFailure);
            } else if (startNow) {
                startOperation(operation);
            }
        }
        if (controlFailure instanceof Error error) {
            throw error;
        }
        return operation.stage();
    }

    /// Cancels active and waiting results and prevents all late task callbacks from starting more work.
    ///
    /// Every caller first publishes closure, crosses the unified external-invocation barrier, and
    /// then crosses the idempotent cleanup barrier. The monitors are reentrant, so a close invoked by
    /// a synchronous provider, task, or completion callback cannot wait on itself. A concurrent close
    /// cannot return before whichever caller owns detached-resource cleanup leaves that final barrier.
    @Override
    public void close() {
        synchronized (stateLock) {
            closed = true;
        }
        synchronized (externalInvocationLock) {
            // Crossing this monitor waits for every provider or task invocation that began before close.
        }

        synchronized (cleanupInvocationLock) {
            @Nullable LoadOperation active;
            @Nullable LoadOperation pending;
            @Nullable Subscription activeSubscription;
            @Nullable TaskExecutor executorToCancel;
            synchronized (stateLock) {
                active = activeOperation;
                activeOperation = null;
                pending = pendingOperation;
                pendingOperation = null;

                activeSubscription = null;
                executorToCancel = null;
                if (active != null) {
                    active.cancelRequested = true;
                    activeSubscription = active.subscription;
                    active.subscription = null;
                    executorToCancel = takeCancellationExecutorLocked(active);
                    active.terminal = true;
                }
                if (pending != null) {
                    pending.cancelRequested = true;
                    pending.terminal = true;
                }
            }

            @Nullable Throwable cleanupFailure = null;
            cleanupFailure = unsubscribeCollecting(activeSubscription, cleanupFailure);
            cancelStage(active);
            cancelStage(pending);
            if (executorToCancel != null) {
                cleanupFailure = cancelCollecting(executorToCancel, cleanupFailure);
            }
            if (cleanupFailure != null && closeFailure == null) {
                closeFailure = cleanupFailure;
            }
            rethrowUnchecked(closeFailure);
        }
    }

    /// Creates, subscribes, installs, and starts the task for one promoted operation.
    ///
    /// @param operation operation already occupying the active slot
    private void startOperation(LoadOperation operation) {
        synchronized (externalInvocationLock) {
            startOperationWithinBarrier(operation);
        }
    }

    /// Performs one complete external task-start invocation while its reentrant close barrier is held.
    ///
    /// @param operation operation already occupying the active slot
    private void startOperationWithinBarrier(LoadOperation operation) {
        if (!mayCreateTask(operation)) {
            finishOperation(operation, LoadResult.cancelledResult());
            return;
        }

        final Task<?> refreshTask;
        try {
            operation.cancellation.throwIfCancelled();
            refreshTask = Objects.requireNonNull(
                    operation.versionList.refreshAsync(VERSION_LIST_SCOPE),
                    "version list returned null refresh task");
        } catch (CancellationException cancellation) {
            finishOperation(operation, LoadResult.cancelledResult());
            return;
        } catch (RuntimeException failure) {
            finishOperation(operation, LoadResult.failed(failure));
            return;
        } catch (Error error) {
            finishOperation(operation, LoadResult.failed(error));
            throw error;
        }

        final TaskExecutor executor;
        final Subscription subscription;
        try {
            executor = Objects.requireNonNull(
                    executorFactory.create(refreshTask),
                    "task executor factory returned null");
            subscription = Objects.requireNonNull(
                    executor.subscribeTaskListener(new CompletionListener(operation)),
                    "task executor returned null subscription");
        } catch (RuntimeException failure) {
            finishOperation(operation, LoadResult.failed(failure));
            return;
        } catch (Error error) {
            finishOperation(operation, LoadResult.failed(error));
            throw error;
        }

        boolean installed;
        synchronized (stateLock) {
            installed = !closed
                    && activeOperation == operation
                    && !operation.terminal
                    && !operation.cancelRequested
                    && !operation.cancellation.isCancelled();
            if (installed) {
                operation.executor = executor;
                operation.subscription = subscription;
            }
        }
        if (!installed) {
            @Nullable Throwable cleanupFailure = unsubscribeCollecting(subscription, null);
            finishOperation(operation, LoadResult.cancelledResult());
            rethrowUnchecked(cleanupFailure);
            return;
        }

        @Nullable Throwable startFailure = null;
        try {
            executor.start();
        } catch (RuntimeException | Error failure) {
            startFailure = failure;
        }

        boolean cancelAfterStart;
        synchronized (stateLock) {
            operation.startPending = false;
            cancelAfterStart = operation.cancelRequested
                    && !operation.cancellationForwarded;
            cancelAfterStart = cancelAfterStart && !operation.stopObserved;
            operation.cancellationForwarded = operation.cancellationForwarded || cancelAfterStart;
        }

        if (startFailure != null) {
            boolean shouldCancelAfterStartFailure;
            synchronized (stateLock) {
                shouldCancelAfterStartFailure = !operation.stopObserved;
            }
            @Nullable Throwable cancellationFailure = shouldCancelAfterStartFailure
                    ? cancelCollecting(executor, null)
                    : null;
            Throwable resolvedFailure = cancellationFailure == null
                    ? startFailure
                    : accumulateFailure(startFailure, cancellationFailure);
            finishOperation(operation, LoadResult.failed(resolvedFailure));
            if (resolvedFailure instanceof Error error) {
                throw error;
            }
            return;
        }
        if (cancelAfterStart) {
            try {
                executor.cancel();
            } catch (RuntimeException | Error cancellationFailure) {
                boolean failureBelongsToClose;
                synchronized (stateLock) {
                    failureBelongsToClose = closed;
                    if (activeOperation == operation && !operation.terminal) {
                        operation.cancellationForwarded = false;
                    }
                }
                failLatestPendingOperation(cancellationFailure);
                if (failureBelongsToClose) {
                    recordCloseFailure(cancellationFailure);
                }
                throw cancellationFailure;
            }
        }
    }

    /// Returns whether a promoted operation is still allowed to create external task state.
    ///
    /// @param operation promoted operation
    /// @return whether task creation may proceed
    private boolean mayCreateTask(LoadOperation operation) {
        synchronized (stateLock) {
            return !closed
                    && activeOperation == operation
                    && !operation.terminal
                    && !operation.cancelRequested
                    && !operation.cancellation.isCancelled();
        }
    }

    /// Claims one legal executor-cancellation call while [#stateLock] is held.
    ///
    /// The claim is reset by the caller if the external cancellation invocation throws, allowing a
    /// later close or superseding request to retry it without issuing duplicate successful calls.
    ///
    /// @param operation operation whose cancellation was requested
    /// @return started installed executor to cancel, or null when no invocation is currently legal
    private static @Nullable TaskExecutor takeCancellationExecutorLocked(LoadOperation operation) {
        if (!operation.cancelRequested
                || operation.startPending
                || operation.cancellationForwarded
                || operation.stopObserved
                || operation.terminal) {
            return null;
        }
        @Nullable TaskExecutor executor = operation.executor;
        if (executor != null) {
            operation.cancellationForwarded = true;
        }
        return executor;
    }

    /// Removes and fails one request whose attempt to cancel the preceding operation failed.
    ///
    /// @param operation new request that cannot safely follow the still-running operation
    /// @param failure exact cancellation-control failure
    private void failPreparedOperation(LoadOperation operation, Throwable failure) {
        boolean finishActive = false;
        boolean completeDetached = false;
        synchronized (stateLock) {
            if (activeOperation == operation && !operation.terminal) {
                finishActive = true;
            } else if (pendingOperation == operation) {
                pendingOperation = null;
                operation.terminal = true;
                completeDetached = true;
            } else if (!operation.terminal) {
                operation.terminal = true;
                completeDetached = true;
            }
        }
        if (finishActive) {
            finishOperation(operation, LoadResult.failed(failure));
        } else if (completeDetached) {
            operation.completion.completeExceptionally(failure);
        }
    }

    /// Removes and fails the latest request waiting behind a deferred cancellation that threw.
    ///
    /// @param failure exact deferred-cancellation failure
    private void failLatestPendingOperation(Throwable failure) {
        @Nullable LoadOperation pending;
        synchronized (stateLock) {
            pending = pendingOperation;
            pendingOperation = null;
            if (pending != null) {
                pending.cancelRequested = true;
                pending.terminal = true;
            }
        }
        if (pending != null) {
            pending.completion.completeExceptionally(failure);
        }
    }

    /// Persists one deferred cleanup failure for every current or later close caller.
    ///
    /// @param failure deferred close-related cancellation failure
    private void recordCloseFailure(Throwable failure) {
        synchronized (cleanupInvocationLock) {
            closeFailure = closeFailure == null
                    ? failure
                    : accumulateFailure(closeFailure, failure);
        }
    }

    /// Maps one executor terminal event to a source result without touching Swing state.
    ///
    /// @param operation operation whose listener received the event
    /// @param success core executor success flag
    /// @param executor executor emitting the event
    private void operationStopped(
            LoadOperation operation,
            boolean success,
            TaskExecutor executor) {
        synchronized (stateLock) {
            operation.stopObserved = true;
        }
        synchronized (externalInvocationLock) {
            operationStoppedWithinBarrier(operation, success, executor);
        }
    }

    /// Resolves one terminal event while the task-operation close barrier is held.
    ///
    /// @param operation operation whose listener received the event
    /// @param success core executor success flag
    /// @param executor executor emitting the event
    private void operationStoppedWithinBarrier(
            LoadOperation operation,
            boolean success,
            TaskExecutor executor) {
        synchronized (stateLock) {
            if (closed || activeOperation != operation || operation.terminal) {
                return;
            }
        }
        @Nullable Throwable failure = executor.getFailure();
        if (failure instanceof CancellationException) {
            finishOperation(operation, LoadResult.cancelledResult());
            return;
        }
        if (failure != null) {
            finishOperation(operation, LoadResult.failed(failure));
            return;
        }
        if (executor.isCancelled()) {
            finishOperation(operation, LoadResult.cancelledResult());
            return;
        }
        if (!success) {
            finishOperation(operation, LoadResult.failed(
                    new IllegalStateException("Game-version refresh stopped without a failure cause")));
            return;
        }

        try {
            operation.cancellation.throwIfCancelled();
            @Unmodifiable List<GameVersionCatalogItem> items = mapVersions(operation.versionList);
            operation.cancellation.throwIfCancelled();
            finishOperation(operation, LoadResult.succeeded(items));
        } catch (CancellationException cancellation) {
            finishOperation(operation, LoadResult.cancelledResult());
        } catch (RuntimeException failureDuringMapping) {
            finishOperation(operation, LoadResult.failed(failureDuringMapping));
        } catch (Error errorDuringMapping) {
            finishOperation(operation, LoadResult.failed(errorDuringMapping));
            throw errorDuringMapping;
        }
    }

    /// Atomically releases a terminal operation, promotes the latest waiter, then publishes completion.
    ///
    /// @param operation operation reaching a terminal state
    /// @param requestedResult result resolved from its task lifecycle
    private void finishOperation(LoadOperation operation, LoadResult requestedResult) {
        @Nullable LoadOperation nextOperation = null;
        @Nullable LoadOperation discardedPending = null;
        @Nullable Subscription subscription;
        LoadResult resolvedResult = requestedResult;
        synchronized (stateLock) {
            if (activeOperation != operation || operation.terminal) {
                return;
            }

            if (closed || operation.cancelRequested || operation.cancellation.isCancelled()) {
                resolvedResult = LoadResult.cancelledResult();
            }

            operation.terminal = true;
            activeOperation = null;
            subscription = operation.subscription;
            operation.subscription = null;

            @Nullable LoadOperation waiting = pendingOperation;
            pendingOperation = null;
            if (waiting != null) {
                if (closed || waiting.cancelRequested || waiting.cancellation.isCancelled()) {
                    waiting.cancelRequested = true;
                    waiting.terminal = true;
                    discardedPending = waiting;
                } else {
                    waiting.startPending = true;
                    activeOperation = waiting;
                    nextOperation = waiting;
                }
            }
        }

        @Nullable Throwable cleanupFailure = unsubscribeCollecting(subscription, null);
        completeStage(operation, resolvedResult);
        cancelStage(discardedPending);
        if (nextOperation != null) {
            try {
                startOperation(nextOperation);
            } catch (RuntimeException | Error nextStartFailure) {
                cleanupFailure = accumulateFailure(cleanupFailure, nextStartFailure);
            }
        }
        rethrowUnchecked(cleanupFailure);
    }

    /// Converts the exact refreshed list snapshot into immutable, sorted, unique catalog items.
    ///
    /// @param versionList exact list instance used to create the completed refresh task
    /// @return newest-first immutable catalog with duplicate stable IDs removed
    private static @Unmodifiable List<GameVersionCatalogItem> mapVersions(VersionList<?> versionList) {
        List<GameVersionCatalogItem> mappedItems = new ArrayList<>();
        for (@Nullable RemoteVersion remoteVersion : versionList.getVersions(VERSION_LIST_SCOPE)) {
            if (remoteVersion == null) {
                throw new IllegalStateException("Game version list returned a null entry");
            }
            if (!(remoteVersion instanceof GameRemoteVersion gameVersion)) {
                throw new IllegalStateException(
                        "Game version list returned unsupported entry type: "
                                + remoteVersion.getClass().getName());
            }
            @Nullable Instant releaseDate = gameVersion.getReleaseDate();
            mappedItems.add(new GameVersionCatalogItem(
                    gameVersion.getSelfVersion(),
                    classify(gameVersion),
                    Optional.ofNullable(releaseDate)));
        }

        mappedItems.sort(DownloadProviderGameVersionCatalogSource::compareCatalogItems);
        Map<String, GameVersionCatalogItem> uniqueItems = new LinkedHashMap<>();
        for (GameVersionCatalogItem item : mappedItems) {
            uniqueItems.putIfAbsent(item.versionId(), item);
        }
        return List.copyOf(uniqueItems.values());
    }

    /// Classifies a core game version with the launcher's established release rules.
    ///
    /// @param version core game version
    /// @return toolkit-neutral catalog kind
    private static GameVersionKind classify(GameRemoteVersion version) {
        return switch (version.getVersionType()) {
            case RELEASE -> GameVersionKind.RELEASE;
            case SNAPSHOT -> GameVersionNumber.asGameVersion(version.getGameVersion()).isAprilFools()
                    ? GameVersionKind.APRIL_FOOLS
                    : GameVersionKind.SNAPSHOT;
            case PENDING, UNOBFUSCATED -> GameVersionKind.SNAPSHOT;
            case UNCATEGORIZED, OLD -> GameVersionKind.OLD;
        };
    }

    /// Orders items by date, parsed game version, stable ID, and classification.
    ///
    /// Missing dates are placed after dated entries. The final kind comparison makes duplicate-ID
    /// selection deterministic even when an upstream manifest supplies conflicting equal-date rows.
    ///
    /// @param left first item
    /// @param right second item
    /// @return comparator result for newest-first display order
    private static int compareCatalogItems(
            GameVersionCatalogItem left,
            GameVersionCatalogItem right) {
        Optional<Instant> leftDate = left.releaseDate();
        Optional<Instant> rightDate = right.releaseDate();
        if (leftDate.isPresent() && rightDate.isPresent()) {
            int dateComparison = rightDate.orElseThrow().compareTo(leftDate.orElseThrow());
            if (dateComparison != 0) {
                return dateComparison;
            }
        } else if (leftDate.isPresent()) {
            return -1;
        } else if (rightDate.isPresent()) {
            return 1;
        }

        int versionComparison = GameVersionNumber.compare(right.versionId(), left.versionId());
        if (versionComparison != 0) {
            return versionComparison;
        }
        int idComparison = left.versionId().compareTo(right.versionId());
        if (idComparison != 0) {
            return idComparison;
        }
        return left.kind().compareTo(right.kind());
    }

    /// Resolves nested provider wrappers while rejecting an accidental identity cycle.
    ///
    /// @param provider configured provider or wrapper
    /// @return concrete provider snapshot for one load request
    private static DownloadProvider unwrapProvider(DownloadProvider provider) {
        DownloadProvider current = provider;
        Set<DownloadProvider> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        while (current instanceof DownloadProviderWrapper wrapper) {
            if (!visited.add(current)) {
                throw new IllegalStateException("Download-provider wrapper cycle detected");
            }
            @Nullable DownloadProvider nestedProvider = wrapper.getProvider();
            current = Objects.requireNonNull(nestedProvider, "download-provider wrapper contains null");
        }
        return current;
    }

    /// Completes one operation's internal future according to its resolved terminal result.
    ///
    /// @param operation terminal operation
    /// @param result resolved result
    private static void completeStage(LoadOperation operation, LoadResult result) {
        if (result.cancelled()) {
            operation.completion.cancel(false);
        } else if (result.failure() != null) {
            operation.completion.completeExceptionally(result.failure());
        } else {
            operation.completion.complete(Objects.requireNonNull(result.items(), "successful result items"));
        }
    }

    /// Cancels an operation's result stage when the operation exists.
    ///
    /// @param operation operation to cancel, or null
    private static void cancelStage(@Nullable LoadOperation operation) {
        if (operation != null) {
            operation.completion.cancel(false);
        }
    }

    /// Creates an already-cancelled minimal stage.
    ///
    /// @return cancelled stage
    private static CompletionStage<@Unmodifiable List<GameVersionCatalogItem>> cancelledStage() {
        CompletableFuture<@Unmodifiable List<GameVersionCatalogItem>> completion = new CompletableFuture<>();
        completion.cancel(false);
        return completion.minimalCompletionStage();
    }

    /// Creates an already-failed minimal stage.
    ///
    /// @param failure failure preserved as the stage cause
    /// @return failed stage
    private static CompletionStage<@Unmodifiable List<GameVersionCatalogItem>> failedStage(
            Throwable failure) {
        CompletableFuture<@Unmodifiable List<GameVersionCatalogItem>> completion = new CompletableFuture<>();
        completion.completeExceptionally(Objects.requireNonNull(failure, "failure"));
        return completion.minimalCompletionStage();
    }

    /// Removes one listener registration while preserving an earlier cleanup failure.
    ///
    /// @param subscription registration to remove, or null
    /// @param previousFailure earlier cleanup failure, or null
    /// @return accumulated cleanup failure, or null
    private static @Nullable Throwable unsubscribeCollecting(
            @Nullable Subscription subscription,
            @Nullable Throwable previousFailure) {
        if (subscription == null) {
            return previousFailure;
        }
        try {
            subscription.unsubscribe();
            return previousFailure;
        } catch (RuntimeException | Error failure) {
            return accumulateFailure(previousFailure, failure);
        }
    }

    /// Requests executor cancellation while preserving an earlier cleanup failure.
    ///
    /// @param executor started executor to cancel
    /// @param previousFailure earlier cleanup failure, or null
    /// @return accumulated cleanup failure, or null
    private static @Nullable Throwable cancelCollecting(
            TaskExecutor executor,
            @Nullable Throwable previousFailure) {
        try {
            executor.cancel();
            return previousFailure;
        } catch (RuntimeException | Error failure) {
            return accumulateFailure(previousFailure, failure);
        }
    }

    /// Combines failures while preserving Error severity over RuntimeException ordering.
    ///
    /// @param previousFailure first failure, or null
    /// @param currentFailure later failure
    /// @return severity-preserving primary failure with the other failure suppressed
    private static Throwable accumulateFailure(
            @Nullable Throwable previousFailure,
            Throwable currentFailure) {
        Objects.requireNonNull(currentFailure, "currentFailure");
        if (previousFailure == null) {
            return currentFailure;
        }
        if (previousFailure == currentFailure) {
            return previousFailure;
        }
        if (currentFailure instanceof Error && !(previousFailure instanceof Error)) {
            currentFailure.addSuppressed(previousFailure);
            return currentFailure;
        }
        previousFailure.addSuppressed(currentFailure);
        return previousFailure;
    }

    /// Rethrows one unchecked lifecycle failure after all required cleanup has run.
    ///
    /// @param failure failure to rethrow, or null
    private static void rethrowUnchecked(@Nullable Throwable failure) {
        if (failure == null) {
            return;
        }
        if (failure instanceof RuntimeException runtimeException) {
            throw runtimeException;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        throw new IllegalStateException("Unexpected checked lifecycle failure", failure);
    }

    /// Rejects work after the source has been closed.
    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("Game-version catalog source is closed");
        }
    }

    /// Creates a stopped executor for one core refresh task.
    @FunctionalInterface
    @NotNullByDefault
    interface TaskExecutorFactory {
        /// Creates one stopped executor.
        ///
        /// @param task core refresh task
        /// @return stopped executor
        TaskExecutor create(Task<?> task);
    }

    /// Mutable lifecycle state for one source request guarded by [#stateLock].
    @NotNullByDefault
    private static final class LoadOperation {
        /// Caller-owned cooperative cancellation signal.
        private final LoadCancellation cancellation;

        /// Exact provider list used for both refresh creation and terminal snapshot reading.
        private final VersionList<?> versionList;

        /// Internal mutable completion retained after exposing only its minimal stage view.
        private final CompletableFuture<@Unmodifiable List<GameVersionCatalogItem>> completion =
                new CompletableFuture<>();

        /// Installed executor, or null before executor preparation finishes.
        private @Nullable TaskExecutor executor;

        /// Installed completion-listener registration, or null outside task ownership.
        private @Nullable Subscription subscription;

        /// Whether an executor start call is pending or currently on the stack.
        private boolean startPending;

        /// Whether supersession or closure requires cancellation after a pending start returns.
        private boolean cancelRequested;

        /// Whether cancellation has already been forwarded to the installed executor.
        private boolean cancellationForwarded;

        /// Whether the executor has emitted its terminal stop event, including a late event after close.
        private boolean stopObserved;

        /// Whether the operation has permanently released its active or pending slot.
        private boolean terminal;

        /// Creates one request before it is assigned to an operation slot.
        ///
        /// @param cancellation caller-owned cooperative cancellation signal
        /// @param versionList exact request provider list
        private LoadOperation(LoadCancellation cancellation, VersionList<?> versionList) {
            this.cancellation = Objects.requireNonNull(cancellation, "cancellation");
            this.versionList = Objects.requireNonNull(versionList, "versionList");
        }

        /// Returns a minimal view that cannot directly mutate the internal completion.
        ///
        /// @return operation completion stage
        private CompletionStage<@Unmodifiable List<GameVersionCatalogItem>> stage() {
            return completion.minimalCompletionStage();
        }
    }

    /// Validated terminal result before it is applied to an operation future.
    ///
    /// @param items successful immutable items, or null for failure and cancellation
    /// @param failure non-cancellation failure, or null otherwise
    /// @param cancelled whether the result represents cancellation
    @NotNullByDefault
    private record LoadResult(
            @Nullable @Unmodifiable List<GameVersionCatalogItem> items,
            @Nullable Throwable failure,
            boolean cancelled) {
        /// Creates a successful result.
        ///
        /// @param items immutable mapped catalog
        /// @return successful result
        private static LoadResult succeeded(@Unmodifiable List<GameVersionCatalogItem> items) {
            return new LoadResult(List.copyOf(items), null, false);
        }

        /// Creates a failed result retaining the original throwable.
        ///
        /// @param failure original failure
        /// @return failed result
        private static LoadResult failed(Throwable failure) {
            return new LoadResult(null, Objects.requireNonNull(failure, "failure"), false);
        }

        /// Creates a cancelled result.
        ///
        /// @return cancelled result
        private static LoadResult cancelledResult() {
            return new LoadResult(null, null, true);
        }

    }

    /// Receives exactly one executor's authoritative terminal event.
    @NotNullByDefault
    private final class CompletionListener extends TaskListener {
        /// Operation owning this registration.
        private final LoadOperation operation;

        /// Creates a listener for one prepared operation.
        ///
        /// @param operation owning operation
        private CompletionListener(LoadOperation operation) {
            this.operation = Objects.requireNonNull(operation, "operation");
        }

        /// Maps the terminal executor event to an immutable catalog result.
        ///
        /// @param success whether the core task chain succeeded
        /// @param executor executor emitting the terminal event
        @Override
        public void onStop(boolean success, TaskExecutor executor) {
            operationStopped(operation, success, executor);
        }
    }
}
