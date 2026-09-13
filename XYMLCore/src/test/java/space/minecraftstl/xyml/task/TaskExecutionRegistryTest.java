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
import org.junit.jupiter.api.Test;
import space.minecraftstl.xyml.observable.Subscription;

import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies top-level execution aggregation and actual-task detail ownership.
@NotNullByDefault
public final class TaskExecutionRegistryTest {
    /// A recoverable child failure remains in the detail timeline without aborting the workflow.
    @Test
    public void recoverableTaskFailureDoesNotAbortTopLevelExecution() {
        Task<?> root = Task.runAsync("root", () -> { });
        Task<?> child = Task.runAsync("child", () -> { });
        TaskExecutionRegistry registry = new TaskExecutionRegistry();
        TaskExecutionRegistry.Execution execution = registry.begin(
                new ProbeExecutor(root),
                "Workflow",
                true);

        execution.started();
        execution.taskReady(null, root);
        execution.taskRunning(null, root);
        execution.taskReady(root, child);
        execution.taskRunning(root, child);
        execution.taskFailed(root, child, new IllegalStateException("recoverable"));
        execution.taskFinished(null, root);
        execution.stopped(true, null);

        TaskExecutionSnapshot snapshot = singleSnapshot(registry);
        assertEquals(TaskExecutionStatus.SUCCEEDED, snapshot.status());
        assertEquals(2, snapshot.tasks().size());
        TaskExecutionTaskSnapshot childSnapshot = snapshot.tasks().stream()
                .filter(task -> task.name().equals("child"))
                .findFirst()
                .orElseThrow();
        assertEquals(TaskExecutionTaskStatus.FAILED, childSnapshot.status());
        assertTrue(childSnapshot.logs().size() >= 3);
        assertTrue(childSnapshot.logs().stream()
                .anyMatch(log -> log.message().contains("recoverable")));
        TaskExecutionTaskSnapshot rootSnapshot = snapshot.tasks().stream()
                .filter(task -> task.name().equals("root"))
                .findFirst()
                .orElseThrow();
        assertEquals(rootSnapshot.id(), childSnapshot.parentId());
    }

    /// Cancellation is scoped to one execution and becomes an aborted terminal state.
    @Test
    public void cancellationPublishesCancellingThenCancelled() {
        Task<?> root = Task.runAsync("cancel", () -> { });
        TaskExecutionRegistry registry = new TaskExecutionRegistry();
        AtomicBoolean cancellationCalled = new AtomicBoolean();
        TaskExecutionRegistry.Execution execution = registry.begin(
                new ProbeExecutor(root),
                "Cancelable workflow",
                true);
        execution.started();
        execution.setCancellation(() -> cancellationCalled.set(true));

        TaskExecutionSnapshot waiting = singleSnapshot(registry);
        assertEquals(TaskExecutionStatus.WAITING, waiting.status());
        assertTrue(registry.requestCancellation(waiting.id()));
        assertTrue(cancellationCalled.get());
        TaskExecutionSnapshot cancelling = singleSnapshot(registry);
        assertEquals(TaskExecutionStatus.CANCELLING, cancelling.status());
        assertFalse(cancelling.cancelable());
        assertFalse(registry.requestCancellation(waiting.id()));

        execution.stopped(false, new CancellationException("cancelled"));
        assertEquals(TaskExecutionStatus.CANCELLED, singleSnapshot(registry).status());
        assertFalse(registry.requestCancellation(waiting.id()));
    }

    /// A concrete non-cancellation failure wins a cancellation race for the top-level terminal state.
    @Test
    public void concreteFailureWinsCancellationRace() {
        Task<?> root = Task.runAsync("cancel-race", () -> { });
        TaskExecutionRegistry registry = new TaskExecutionRegistry();
        TaskExecutionRegistry.Execution execution = registry.begin(
                new ProbeExecutor(root),
                "Cancellation race",
                true);
        execution.started();
        TaskExecutionSnapshot initial = singleSnapshot(registry);
        assertTrue(registry.requestCancellation(initial.id()));

        execution.stopped(false, new IllegalStateException("real failure"));

        TaskExecutionSnapshot failed = singleSnapshot(registry);
        assertEquals(TaskExecutionStatus.FAILED, failed.status());
        assertTrue(failed.failure().contains("real failure"));
    }

    /// A cancellation callback installed after the request is delivered exactly once.
    @Test
    public void lateCancellationCallbackIsClaimedOnce() {
        Task<?> root = Task.runAsync("late-cancel", () -> { });
        TaskExecutionRegistry registry = new TaskExecutionRegistry();
        TaskExecutionRegistry.Execution execution = registry.begin(
                new ProbeExecutor(root),
                "Late cancellation",
                true);
        execution.started();
        TaskExecutionSnapshot initial = singleSnapshot(registry);
        assertTrue(registry.requestCancellation(initial.id()));

        AtomicInteger callbackCount = new AtomicInteger();
        execution.setCancellation(callbackCount::incrementAndGet);
        execution.setCancellation(callbackCount::incrementAndGet);

        assertEquals(1, callbackCount.get());
        execution.stopped(false, new CancellationException("cancelled"));
    }

    /// A top-level cancellation closes task rows that have not delivered their own terminal callback yet.
    @Test
    public void cancellationMarksUnfinishedTaskRowsCancelled() {
        Task<?> root = Task.runAsync("unfinished", () -> { });
        TaskExecutionRegistry registry = new TaskExecutionRegistry();
        TaskExecutionRegistry.Execution execution = registry.begin(
                new ProbeExecutor(root),
                "Unfinished cancellation",
                true);
        execution.started();
        execution.taskReady(null, root);
        execution.taskRunning(null, root);

        TaskExecutionSnapshot running = singleSnapshot(registry);
        assertTrue(registry.requestCancellation(running.id()));
        execution.stopped(false, null);

        TaskExecutionSnapshot cancelled = singleSnapshot(registry);
        assertEquals(TaskExecutionStatus.CANCELLED, cancelled.status());
        assertEquals(TaskExecutionTaskStatus.CANCELLED, cancelled.tasks().get(0).status());
        assertTrue(cancelled.tasks().get(0).logs().stream()
                .anyMatch(log -> log.event().equals("cancelled")));
    }

    /// A top-level infrastructure failure closes task rows that have not delivered their own terminal callback.
    @Test
    public void failureMarksUnfinishedTaskRowsFailed() {
        Task<?> root = Task.runAsync("unfinished-failure", () -> { });
        TaskExecutionRegistry registry = new TaskExecutionRegistry();
        TaskExecutionRegistry.Execution execution = registry.begin(
                new ProbeExecutor(root),
                "Unfinished failure",
                true);
        execution.started();
        execution.taskReady(null, root);

        IllegalStateException failure = new IllegalStateException("top-level failure");
        execution.stopped(false, failure);

        TaskExecutionSnapshot failed = singleSnapshot(registry);
        assertEquals(TaskExecutionStatus.FAILED, failed.status());
        assertEquals(TaskExecutionTaskStatus.FAILED, failed.tasks().get(0).status());
        assertTrue(failed.tasks().get(0).failure().contains("top-level failure"));
        assertTrue(failed.tasks().get(0).logs().stream()
                .anyMatch(log -> log.event().equals("failed")));
    }

    /// A running task with no progress report remains part of the aggregate while cancellation is draining.
    @Test
    public void cancellingRunningTaskWithoutProgressRetainsAggregateEpochMember() {
        Task<?> root = Task.runAsync("no-progress", () -> { });
        TaskExecutionRegistry registry = new TaskExecutionRegistry();
        TaskExecutionRegistry.Execution execution = registry.begin(
                new ProbeExecutor(root),
                "No progress workflow",
                true);
        execution.started();
        execution.taskReady(null, root);
        execution.taskRunning(null, root);

        TaskExecutionSnapshot running = singleSnapshot(registry);
        assertTrue(running.everRunning());
        assertEquals(1.0D, running.totalProgressWeight());
        assertTrue(registry.requestCancellation(running.id()));

        TaskExecutionSnapshot cancelling = singleSnapshot(registry);
        assertEquals(TaskExecutionStatus.CANCELLING, cancelling.status());
        assertTrue(cancelling.everRunning());
        assertEquals(1.0D, cancelling.totalProgressWeight());
    }

    /// Versioned publications are strictly monotonic and the atomic publication matches the latest callback.
    @Test
    public void versionedPublicationHasMonotonicRevision() {
        Task<?> root = Task.runAsync("versioned", () -> { });
        TaskExecutionRegistry registry = new TaskExecutionRegistry();
        AtomicLong previousRevision = new AtomicLong(-1L);
        AtomicLong callbackRevision = new AtomicLong(-1L);
        Subscription subscription = registry.subscribeVersioned(publication -> {
            assertTrue(publication.revision() > previousRevision.get());
            previousRevision.set(publication.revision());
            callbackRevision.set(publication.revision());
        });
        TaskExecutionRegistry.Execution execution = registry.begin(
                new ProbeExecutor(root),
                "Versioned workflow",
                true);
        execution.started();
        TaskExecutionRegistry.Publication publication = registry.publication();
        subscription.unsubscribe();

        assertEquals(publication.revision(), callbackRevision.get());
        assertEquals(1, publication.snapshots().size());
    }

    /// A cancellation received during the initial publication is not overwritten by the started transition.
    @Test
    public void synchronousInitialCancellationRemainsCancelling() {
        Task<?> root = Task.runAsync("initial-cancel", () -> { });
        TaskExecutionRegistry registry = new TaskExecutionRegistry();
        AtomicBoolean cancellationRequested = new AtomicBoolean();
        Subscription subscription = registry.subscribe(snapshots -> {
            if (cancellationRequested.compareAndSet(false, true) && !snapshots.isEmpty()) {
                registry.requestCancellation(snapshots.get(0).id());
            }
        });
        TaskExecutionRegistry.Execution execution = registry.begin(
                new ProbeExecutor(root),
                "Initial cancellation",
                true);
        subscription.unsubscribe();

        execution.setCancellation(() -> { });
        execution.started();

        assertEquals(TaskExecutionStatus.CANCELLING, singleSnapshot(registry).status());
    }

    /// Count-based progress contributes its total once and replaces it when the task reports a new total.
    @Test
    public void countProgressReplacesAggregateWeight() {
        ProgressTask root = new ProgressTask("counted");
        TaskExecutionRegistry registry = new TaskExecutionRegistry();
        TaskExecutionRegistry.Execution execution = registry.begin(
                new ProbeExecutor(root),
                "Counted workflow",
                true);
        execution.started();
        execution.taskReady(null, root);
        execution.taskRunning(null, root);

        root.report(2, 4);
        execution.taskPropertiesUpdated(null, root);
        TaskExecutionSnapshot first = singleSnapshot(registry);
        assertEquals(4.0D, first.totalProgressWeight());
        assertEquals(2.0D, first.completedProgressWeight());
        assertEquals(0.5D, first.progress().orElseThrow());

        root.report(4, 8);
        execution.taskPropertiesUpdated(null, root);
        TaskExecutionSnapshot replaced = singleSnapshot(registry);
        assertEquals(8.0D, replaced.totalProgressWeight());
        assertEquals(4.0D, replaced.completedProgressWeight());
        assertEquals(0.5D, replaced.progress().orElseThrow());

        root.report(6, 8);
        execution.taskPropertiesUpdated(null, root);
        TaskExecutionSnapshot sameTotal = singleSnapshot(registry);
        assertEquals(8.0D, sameTotal.totalProgressWeight());
        assertEquals(4.0D, sameTotal.completedProgressWeight());
        assertEquals(0.5D, sameTotal.progress().orElseThrow());

        root.report(8, 8);
        execution.taskPropertiesUpdated(null, root);
        TaskExecutionSnapshot complete = singleSnapshot(registry);
        assertEquals(8.0D, complete.totalProgressWeight());
        assertEquals(8.0D, complete.completedProgressWeight());
        assertEquals(1.0D, complete.progress().orElseThrow());

        root.reportNormalized(0.25D);
        execution.taskPropertiesUpdated(null, root);
        TaskExecutionSnapshot normalized = singleSnapshot(registry);
        assertEquals(1.0D, normalized.totalProgressWeight());
        assertEquals(0.25D, normalized.completedProgressWeight());
        assertEquals(0.25D, normalized.progress().orElseThrow());
    }

    /// A valid task property total replaces the default aggregate unit when no count API is active.
    @Test
    public void propertyTotalReplacesDefaultAggregateWeight() {
        Task<?> root = Task.runAsync("property-total", () -> { });
        root.getProperties().put("total", 7);
        TaskExecutionRegistry registry = new TaskExecutionRegistry();
        TaskExecutionRegistry.Execution execution = registry.begin(
                new ProbeExecutor(root),
                "Property total workflow",
                true);
        execution.started();
        execution.taskReady(null, root);
        execution.taskRunning(null, root);
        execution.taskPropertiesUpdated(null, root);

        TaskExecutionSnapshot snapshot = singleSnapshot(registry);
        assertEquals(7.0D, snapshot.totalProgressWeight());
        assertEquals(0.0D, snapshot.completedProgressWeight());
    }

    /// A task cancelled while waiting does not dilute work contributed by tasks that actually started running.
    @Test
    public void waitingTaskCancellationDoesNotEnterAggregate() {
        Task<?> root = Task.runAsync("root", () -> { });
        Task<?> waitingChild = Task.runAsync("waiting-child", () -> { });
        TaskExecutionRegistry registry = new TaskExecutionRegistry();
        TaskExecutionRegistry.Execution execution = registry.begin(
                new ProbeExecutor(root),
                "Waiting cancellation",
                true);
        execution.started();
        execution.taskReady(null, root);
        execution.taskRunning(null, root);
        execution.taskReady(root, waitingChild);
        execution.taskFailed(root, waitingChild, new CancellationException("not started"));

        TaskExecutionSnapshot snapshot = singleSnapshot(registry);
        assertEquals(1.0D, snapshot.totalProgressWeight());
        assertEquals(0.0D, snapshot.completedProgressWeight());
    }

    /// Terminal history is bounded without evicting an active execution.
    @Test
    public void terminalHistoryIsBounded() {
        TaskExecutionRegistry registry = new TaskExecutionRegistry();
        for (int index = 0; index < TaskExecutionRegistry.TERMINAL_HISTORY_LIMIT + 1; index++) {
            Task<?> root = Task.runAsync("workflow-" + index, () -> { });
            TaskExecutionRegistry.Execution execution = registry.begin(
                    new ProbeExecutor(root),
                    "Workflow " + index,
                    true);
            execution.started();
            execution.stopped(true, null);
        }

        assertEquals(TaskExecutionRegistry.TERMINAL_HISTORY_LIMIT, registry.snapshots().size());
    }

    /// Removes only terminal records and leaves active records available to their lifecycle callbacks.
    @Test
    public void removeRejectsActiveExecutionAndRemovesTerminalRecord() {
        Task<?> root = Task.runAsync("remove", () -> { });
        TaskExecutionRegistry registry = new TaskExecutionRegistry();
        TaskExecutionRegistry.Execution execution = registry.begin(
                new ProbeExecutor(root),
                "Removable workflow",
                true);

        assertFalse(registry.remove(execution.id()));
        assertNotNull(registry.snapshot(execution.id()));

        execution.stopped(true, null);
        assertTrue(registry.remove(execution.id()));
        assertNull(registry.snapshot(execution.id()));
        assertFalse(registry.remove(execution.id()));
    }

    /// Retries preserve the aborted history row while the executor creates a fresh top-level execution ID.
    @Test
    public void retryCreatesNewExecutionAndRejectsSuccessfulHistory() {
        Task<?> root = Task.runAsync("retry", () -> { });
        TaskExecutionRegistry registry = new TaskExecutionRegistry();
        RetryableProbeExecutor executor = new RetryableProbeExecutor(registry, root);
        TaskExecutionRegistry.Execution first = registry.begin(executor, "Retryable workflow", true);
        first.started();
        first.stopped(false, new IllegalStateException("first attempt failed"));

        assertTrue(registry.retry(first.id()));
        assertEquals(1, executor.starts.get());
        assertEquals(2, registry.snapshots().size());
        TaskExecutionSnapshot second = registry.snapshots().get(1);
        assertNotEquals(first.id(), second.id());
        assertEquals(TaskExecutionStatus.SUCCEEDED, second.status());
        assertFalse(registry.retry(second.id()));
        assertTrue(registry.remove(first.id()));
    }

    /// Returns the only retained execution from an isolated registry.
    ///
    /// @param registry isolated registry
    /// @return current execution snapshot
    private static TaskExecutionSnapshot singleSnapshot(TaskExecutionRegistry registry) {
        List<TaskExecutionSnapshot> snapshots = registry.snapshots();
        assertEquals(1, snapshots.size());
        TaskExecutionSnapshot snapshot = snapshots.get(0);
        assertNotNull(snapshot);
        return snapshot;
    }

    /// Minimal executor required by the package-private registry start boundary.
    @NotNullByDefault
    private static final class ProbeExecutor extends TaskExecutor {
        /// Creates a probe rooted at one task.
        private ProbeExecutor(Task<?> task) {
            super(task);
        }

        /// Returns this probe without scheduling work.
        @Override
        public TaskExecutor start() {
            return this;
        }

        /// Reports a successful no-op result.
        @Override
        public boolean test() {
            return true;
        }

        /// Records a cooperative cancellation request.
        @Override
        public void cancel() {
            cancelled = true;
        }
    }

    /// Probe executor that materializes a successful fresh registry execution for retry assertions.
    @NotNullByDefault
    private static final class RetryableProbeExecutor extends TaskExecutor {
        /// Registry receiving each fresh retry invocation.
        private final TaskExecutionRegistry registry;

        /// Number of starts requested by the registry.
        private final AtomicInteger starts = new AtomicInteger();

        /// Creates a retry-aware probe.
        private RetryableProbeExecutor(TaskExecutionRegistry registry, Task<?> task) {
            super(task);
            this.registry = registry;
        }

        /// Registers and completes one fresh top-level invocation.
        @Override
        public TaskExecutor start() {
            starts.incrementAndGet();
            TaskExecutionRegistry.Execution execution = registry.begin(this, "Retryable workflow", true);
            execution.started();
            execution.stopped(true, null);
            return this;
        }

        /// Reports a successful probe result.
        @Override
        public boolean test() {
            return true;
        }

        /// Records a cooperative cancellation request.
        @Override
        public void cancel() {
            cancelled = true;
        }
    }

    /// Small task exposing count-based progress to this package-level registry test.
    @NotNullByDefault
    private static final class ProgressTask extends Task<Void> {
        /// Creates a named major progress task.
        private ProgressTask(String name) {
            setName(name);
        }

        /// Publishes one count/total update.
        private void report(long count, long total) {
            updateProgress(count, total);
        }

        /// Switches this task back to normalized progress so the count weight is cleared.
        private void reportNormalized(double progress) {
            updateProgressImmediately(progress);
        }

        /// This test task has no body because the registry drives its lifecycle directly.
        @Override
        public void execute() {
        }
    }

}
