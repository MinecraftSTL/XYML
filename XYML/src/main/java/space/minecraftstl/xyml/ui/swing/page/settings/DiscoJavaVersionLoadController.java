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
package space.minecraftstl.xyml.ui.swing.page.settings;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import space.minecraftstl.xyml.download.java.JavaPackageType;
import space.minecraftstl.xyml.download.java.disco.DiscoJavaDistribution;
import space.minecraftstl.xyml.download.java.disco.DiscoJavaRemoteVersion;
import space.minecraftstl.xyml.observable.Subscription;
import space.minecraftstl.xyml.task.Task;
import space.minecraftstl.xyml.task.TaskExecutor;
import space.minecraftstl.xyml.task.TaskListener;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;
import space.minecraftstl.xyml.ui.swing.SwingUiDispatcher;

import java.util.List;
import java.util.Objects;

/// Owns the independently cancellable third-party version-fetch task lifecycle for one Java management page.
///
/// Both a parent operation identity and the child panel's selection revision protect every result publication.
@NotNullByDefault
final class DiscoJavaVersionLoadController implements AutoCloseable {
    /// Service that creates stopped third-party version tasks.
    private final DiscoJavaRuntimeAcquisitionService service;

    /// Current independently cancellable fetch executor, or null while idle.
    private @Nullable TaskExecutor executor;

    /// Listener subscription owned by the current executor.
    private @Nullable Subscription completionSubscription;

    /// Monotonic operation identity used to reject replaced callbacks.
    private long operationSequence;

    /// Whether this controller rejects new work and every late callback.
    private boolean closed;

    /// Creates one version-load controller for the supplied service.
    ///
    /// @param service third-party acquisition service
    DiscoJavaVersionLoadController(DiscoJavaRuntimeAcquisitionService service) {
        this.service = Objects.requireNonNull(service, "service");
    }

    /// Starts one explicit version request after cancelling any replaced request.
    ///
    /// @param revision child-panel selection revision
    /// @param requestedPanel exact result destination
    /// @param distribution explicit distribution
    /// @param packageType explicit non-JavaFX package type
    void load(
            long revision,
            JavaRuntimeAcquisitionPanel requestedPanel,
            DiscoJavaDistribution distribution,
            JavaPackageType packageType) {
        EdtDispatcher.requireEventDispatchThread();
        JavaRuntimeAcquisitionPanel destination = Objects.requireNonNull(requestedPanel, "requestedPanel");
        if (closed) {
            return;
        }
        cancel();
        final Task<@Unmodifiable List<DiscoJavaRemoteVersion>> task;
        try {
            task = Objects.requireNonNull(
                    service.loadVersions(distribution, packageType),
                    "Disco acquisition service returned null version task");
        } catch (RuntimeException failure) {
            destination.applyDiscoVersionLoadFailure(revision, distribution, packageType);
            return;
        }

        long operation = ++operationSequence;
        TaskExecutor newExecutor = task.executor();
        Subscription subscription = newExecutor.subscribeTaskListener(new CompletionListener(
                operation,
                revision,
                destination,
                newExecutor,
                task,
                distribution,
                packageType));
        executor = newExecutor;
        completionSubscription = subscription;
        try {
            newExecutor.start();
        } catch (RuntimeException | Error startFailure) {
            if (executor == newExecutor) {
                unsubscribe(completionSubscription);
                completionSubscription = null;
                executor = null;
                destination.applyDiscoVersionLoadFailure(revision, distribution, packageType);
            }
        }
    }

    /// Cancels and detaches the current version fetch, if any.
    void cancel() {
        EdtDispatcher.requireEventDispatchThread();
        operationSequence++;
        unsubscribe(completionSubscription);
        completionSubscription = null;
        @Nullable TaskExecutor currentExecutor = executor;
        executor = null;
        if (currentExecutor != null) {
            try {
                currentExecutor.cancel();
            } catch (RuntimeException ignored) {
                // Operation identity still rejects a late callback when cancellation integration fails.
            }
        }
    }

    /// Cancels current work and permanently rejects new loads and late results.
    @Override
    public void close() {
        EdtDispatcher.requireEventDispatchThread();
        if (!closed) {
            closed = true;
            cancel();
        }
    }

    /// Completes one fetch when its controller identity and exact destination remain current.
    ///
    /// @param operation controller operation identity
    /// @param revision child selection revision
    /// @param destination exact result destination
    /// @param sourceExecutor completed executor
    /// @param task result-bearing task
    /// @param distribution requested distribution
    /// @param packageType requested package type
    /// @param succeeded whether the task chain succeeded
    private void completed(
            long operation,
            long revision,
            JavaRuntimeAcquisitionPanel destination,
            TaskExecutor sourceExecutor,
            Task<@Unmodifiable List<DiscoJavaRemoteVersion>> task,
            DiscoJavaDistribution distribution,
            JavaPackageType packageType,
            boolean succeeded) {
        SwingUiDispatcher.INSTANCE.dispatchOrRun(() -> {
            if (closed || operation != operationSequence || executor != sourceExecutor) {
                return;
            }
            unsubscribe(completionSubscription);
            completionSubscription = null;
            executor = null;
            if (succeeded) {
                @Nullable List<DiscoJavaRemoteVersion> result = task.getResult();
                if (result == null) {
                    destination.applyDiscoVersionLoadFailure(revision, distribution, packageType);
                } else {
                    destination.applyDiscoVersions(revision, distribution, packageType, result);
                }
            } else if (!sourceExecutor.isCancelled()) {
                destination.applyDiscoVersionLoadFailure(revision, distribution, packageType);
            }
        });
    }

    /// Removes one optional completion subscription.
    ///
    /// @param subscription subscription to remove, or null
    private static void unsubscribe(@Nullable Subscription subscription) {
        if (subscription != null) {
            subscription.unsubscribe();
        }
    }

    /// Bridges one task executor's terminal result to the controller's revision gates.
    @NotNullByDefault
    private final class CompletionListener extends TaskListener {
        /// Controller operation identity.
        private final long operation;

        /// Child-panel selection revision.
        private final long revision;

        /// Exact result destination.
        private final JavaRuntimeAcquisitionPanel destination;

        /// Source executor.
        private final TaskExecutor sourceExecutor;

        /// Result-bearing task.
        private final Task<@Unmodifiable List<DiscoJavaRemoteVersion>> task;

        /// Requested distribution.
        private final DiscoJavaDistribution distribution;

        /// Requested package type.
        private final JavaPackageType packageType;

        /// Creates one exact completion bridge.
        ///
        /// @param operation controller operation identity
        /// @param revision child selection revision
        /// @param destination exact result destination
        /// @param sourceExecutor source executor
        /// @param task result-bearing task
        /// @param distribution requested distribution
        /// @param packageType requested package type
        private CompletionListener(
                long operation,
                long revision,
                JavaRuntimeAcquisitionPanel destination,
                TaskExecutor sourceExecutor,
                Task<@Unmodifiable List<DiscoJavaRemoteVersion>> task,
                DiscoJavaDistribution distribution,
                JavaPackageType packageType) {
            this.operation = operation;
            this.revision = revision;
            this.destination = Objects.requireNonNull(destination, "destination");
            this.sourceExecutor = Objects.requireNonNull(sourceExecutor, "sourceExecutor");
            this.task = Objects.requireNonNull(task, "task");
            this.distribution = Objects.requireNonNull(distribution, "distribution");
            this.packageType = Objects.requireNonNull(packageType, "packageType");
        }

        /// Delivers the terminal task-chain state.
        ///
        /// @param succeeded whether the task chain succeeded
        /// @param stoppedExecutor executor reporting the event
        @Override
        public void onStop(boolean succeeded, TaskExecutor stoppedExecutor) {
            if (stoppedExecutor == sourceExecutor) {
                completed(
                        operation,
                        revision,
                        destination,
                        sourceExecutor,
                        task,
                        distribution,
                        packageType,
                        succeeded);
            }
        }
    }
}
