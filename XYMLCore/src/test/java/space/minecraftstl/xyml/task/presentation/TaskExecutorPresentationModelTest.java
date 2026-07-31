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
package space.minecraftstl.xyml.task.presentation;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import space.minecraftstl.xyml.observable.Subscription;
import space.minecraftstl.xyml.task.Task;
import space.minecraftstl.xyml.task.TaskExecutor;

import java.io.PrintWriter;
import java.util.List;
import java.util.OptionalDouble;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies the toolkit-neutral mapping from task-executor events to presentation snapshots.
@NotNullByDefault
public final class TaskExecutorPresentationModelTest {
    /// Verifies waiting, running, unknown progress, task switching, blank-name fallback, and success semantics.
    @Test
    public void mapsLifecycleCurrentTaskProgressAndSuccess() {
        ProbeTaskExecutor executor = new ProbeTaskExecutor();
        TaskExecutorPresentationModel model = new TaskExecutorPresentationModel(
                executor,
                "Install game",
                "Preparing");
        List<TaskSnapshot> snapshots = new CopyOnWriteArrayList<>();
        Subscription subscription = model.subscribe(change -> snapshots.add(change.currentValue()));
        ProbeTask librariesTask = new ProbeTask("Download libraries");
        ProbeTask unnamedTask = new ProbeTask("");
        ProbeTask metadataTask = new ProbeTask("Finalize metadata");
        try {
            assertEquals(new TaskSnapshot(
                    "Install game",
                    "Preparing",
                    OptionalDouble.empty(),
                    TaskStatus.WAITING,
                    false,
                    ""), model.snapshot());

            executor.fireStart();
            executor.fireReady(librariesTask);
            assertEquals(TaskStatus.RUNNING, model.snapshot().status());
            assertEquals("Download libraries", model.snapshot().phase());
            assertTrue(model.snapshot().progress().isEmpty());
            assertTrue(model.snapshot().cancelable());

            librariesTask.publishProgress(0.25);
            assertEquals(OptionalDouble.of(0.25), model.snapshot().progress());

            executor.fireReady(unnamedTask);
            assertEquals("Preparing", model.snapshot().phase());
            assertTrue(model.snapshot().progress().isEmpty());

            librariesTask.publishProgress(0.5);
            assertTrue(model.snapshot().progress().isEmpty());

            executor.fireRunning(librariesTask);
            assertEquals("Download libraries", model.snapshot().phase());
            assertEquals(OptionalDouble.of(0.5), model.snapshot().progress());

            executor.fireReady(metadataTask);
            assertEquals("Finalize metadata", model.snapshot().phase());
            executor.fireFinished(metadataTask);
            assertEquals("Download libraries", model.snapshot().phase());
            assertEquals(OptionalDouble.of(0.5), model.snapshot().progress());

            executor.fireFinished(unnamedTask);
            assertEquals("Download libraries", model.snapshot().phase());
            librariesTask.publishProgress(0.75);
            assertEquals(OptionalDouble.of(0.75), model.snapshot().progress());

            executor.fireFinished(librariesTask);
            assertEquals(OptionalDouble.of(1.0), model.snapshot().progress());
            assertEquals(TaskStatus.RUNNING, model.snapshot().status());

            executor.fireStop(true);
            assertEquals(TaskStatus.SUCCEEDED, model.snapshot().status());
            assertEquals(OptionalDouble.of(1.0), model.snapshot().progress());
            assertFalse(model.snapshot().cancelable());
            assertEquals("", model.snapshot().details());
            assertTrue(snapshots.stream().anyMatch(snapshot -> snapshot.status() == TaskStatus.RUNNING));
            assertEquals(TaskStatus.SUCCEEDED, snapshots.get(snapshots.size() - 1).status());
        } finally {
            subscription.unsubscribe();
            model.close();
        }
    }

    /// Verifies that task failure details are retained while only the executor stop event makes failure terminal.
    @Test
    public void mapsFailureDetailsAtExecutorStop() {
        ProbeTaskExecutor executor = new ProbeTaskExecutor();
        TaskExecutorPresentationModel model = new TaskExecutorPresentationModel(
                executor,
                "Install mod",
                "Preparing");
        ProbeTask task = new ProbeTask("Write files");
        IllegalStateException failure = new IllegalStateException("disk is read-only");
        try {
            executor.fireStart();
            executor.fireReady(task);
            executor.fireRunning(task);
            task.publishProgress(0.4);
            executor.fireFailed(task, failure);

            assertEquals(TaskStatus.RUNNING, model.snapshot().status());
            assertEquals("Write files", model.snapshot().phase());
            assertEquals(OptionalDouble.of(0.4), model.snapshot().progress());
            assertTrue(model.snapshot().details().contains("disk is read-only"));

            executor.recordFailure(failure);
            executor.fireStop(false);

            assertEquals(TaskStatus.FAILED, model.snapshot().status());
            assertFalse(model.snapshot().cancelable());
            assertTrue(model.snapshot().details().contains(IllegalStateException.class.getName()));
            assertTrue(model.snapshot().details().contains("disk is read-only"));

            task.publishProgress(0.9);
            assertEquals(OptionalDouble.of(0.4), model.snapshot().progress());
        } finally {
            model.close();
        }
    }

    /// Verifies an executor-level error remains available to terminal presentation without an Exception surrogate.
    @Test
    public void mapsTerminalErrorDetailsFromExecutorFailure() {
        ProbeTaskExecutor executor = new ProbeTaskExecutor();
        TaskExecutorPresentationModel model = new TaskExecutorPresentationModel(
                executor,
                "Launch game",
                "Preparing");
        AssertionError failure = new AssertionError("native bridge failed");
        try {
            executor.recordTerminalFailure(failure);
            executor.fireStop(false);

            assertEquals(TaskStatus.FAILED, model.snapshot().status());
            assertTrue(model.snapshot().details().contains(AssertionError.class.getName()));
            assertTrue(model.snapshot().details().contains("native bridge failed"));
        } finally {
            model.close();
        }
    }

    /// Verifies a throwable that rejects stack-trace rendering cannot block task or executor lifecycle mapping.
    @Test
    public void formattingFailureFallsBackToEmptyDetailsAndPreservesLifecycle() {
        ProbeTaskExecutor executor = new ProbeTaskExecutor();
        TaskExecutorPresentationModel model = new TaskExecutorPresentationModel(
                executor,
                "Launch game",
                "Preparing");
        ProbeTask task = new ProbeTask("Create process");
        StackTraceThrowingError failure = new StackTraceThrowingError();
        try {
            executor.fireReady(task);
            executor.fireFailed(task, failure);

            assertEquals(TaskStatus.RUNNING, model.snapshot().status());
            assertEquals("", model.snapshot().details());

            executor.recordTerminalFailure(failure);
            executor.fireStop(false);

            assertEquals(TaskStatus.FAILED, model.snapshot().status());
            assertFalse(model.snapshot().cancelable());
            assertEquals("", model.snapshot().details());
        } finally {
            model.close();
        }
    }

    /// Verifies idempotent cancellation forwarding and the cancelled terminal state.
    @Test
    public void forwardsCancellationOnceAndMapsCancelledStop() {
        ProbeTaskExecutor executor = new ProbeTaskExecutor();
        TaskExecutorPresentationModel model = new TaskExecutorPresentationModel(
                executor,
                "Launch game",
                "Preparing");
        ProbeTask task = new ProbeTask("Resolve account");
        try {
            model.requestCancellation();
            assertEquals(0, executor.cancellationRequests());

            executor.fireStart();
            executor.fireReady(task);
            model.requestCancellation();
            model.requestCancellation();

            assertEquals(1, executor.cancellationRequests());
            assertEquals(TaskStatus.RUNNING, model.snapshot().status());
            assertFalse(model.snapshot().cancelable());

            executor.fireStop(false);

            assertEquals(TaskStatus.CANCELLED, model.snapshot().status());
            assertFalse(model.snapshot().cancelable());
            assertEquals("", model.snapshot().details());

            model.requestCancellation();
            assertEquals(1, executor.cancellationRequests());
        } finally {
            model.close();
        }
    }

    /// Verifies a rejected cancellation is retryable and cannot turn a later real failure into cancellation.
    @Test
    public void rollsBackRejectedCancellationWithoutMisclassifyingFailure() {
        ProbeTaskExecutor executor = new ProbeTaskExecutor();
        TaskExecutorPresentationModel model = new TaskExecutorPresentationModel(
                executor,
                "Launch game",
                "Preparing");
        ProbeTask task = new ProbeTask("Resolve account");
        IllegalStateException launchFailure = new IllegalStateException("account unavailable");
        try {
            executor.fireReady(task);
            executor.rejectNextCancellation();

            assertThrows(IllegalStateException.class, model::requestCancellation);

            assertTrue(model.snapshot().cancelable());
            assertEquals(1, executor.cancellationRequests());

            executor.recordFailure(launchFailure);
            executor.fireStop(false);

            assertEquals(TaskStatus.FAILED, model.snapshot().status());
            assertTrue(model.snapshot().details().contains("account unavailable"));
        } finally {
            model.close();
        }
    }

    /// Verifies an accepted cancellation request cannot hide a concrete non-cancellation failure.
    @Test
    public void preservesRealFailureAfterAcceptedCancellation() {
        ProbeTaskExecutor executor = new ProbeTaskExecutor();
        TaskExecutorPresentationModel model = new TaskExecutorPresentationModel(
                executor,
                "Launch game",
                "Preparing");
        IllegalStateException launchFailure = new IllegalStateException("launch state is corrupt");
        try {
            executor.fireReady(new ProbeTask("Create process"));
            model.requestCancellation();
            executor.recordFailure(launchFailure);

            executor.fireStop(false);

            assertEquals(TaskStatus.FAILED, model.snapshot().status());
            assertTrue(model.snapshot().details().contains("launch state is corrupt"));
        } finally {
            model.close();
        }
    }

    /// Verifies that closing releases lifecycle and progress registrations and rejects later subscriptions.
    @Test
    public void closeStopsFurtherPublication() {
        ProbeTaskExecutor executor = new ProbeTaskExecutor();
        TaskExecutorPresentationModel model = new TaskExecutorPresentationModel(
                executor,
                "Verify game",
                "Preparing");
        ProbeTask task = new ProbeTask("Hash files");
        executor.fireReady(task);
        TaskSnapshot snapshotAtClose = model.snapshot();

        model.close();
        model.close();
        task.publishProgress(0.8);
        executor.fireStop(true);

        assertEquals(snapshotAtClose, model.snapshot());
        assertThrows(IllegalStateException.class, () -> model.subscribe(change -> { }));
        model.requestCancellation();
        assertEquals(0, executor.cancellationRequests());
    }

    /// Verifies one failed presentation subscriber cannot block later subscribers from the same snapshot.
    @Test
    public void isolatesSnapshotListenerRuntimeFailuresPerRegistration() {
        ProbeTaskExecutor executor = new ProbeTaskExecutor();
        TaskExecutorPresentationModel model = new TaskExecutorPresentationModel(
                executor,
                "Install game",
                "Preparing");
        AtomicInteger failingDeliveries = new AtomicInteger();
        List<TaskSnapshot> recordedSnapshots = new CopyOnWriteArrayList<>();
        Subscription failingSubscription = model.subscribe(change -> {
            failingDeliveries.incrementAndGet();
            throw new IllegalStateException("test presentation listener failure");
        });
        Subscription recordingSubscription = model.subscribe(
                change -> recordedSnapshots.add(change.currentValue()));
        try {
            executor.fireReady(new ProbeTask("Resolve files"));

            assertEquals(1, failingDeliveries.get());
            assertEquals(1, recordedSnapshots.size());
            assertEquals("Resolve files", recordedSnapshots.get(0).phase());
            assertEquals(TaskStatus.RUNNING, recordedSnapshots.get(0).status());
        } finally {
            recordingSubscription.unsubscribe();
            failingSubscription.unsubscribe();
            model.close();
        }
    }

    /// Deterministic executor exposing synchronous lifecycle publication to the adapter tests.
    @NotNullByDefault
    private static final class ProbeTaskExecutor extends TaskExecutor {
        /// Number of cancellation requests received by this probe.
        private final AtomicInteger cancellationRequests = new AtomicInteger();

        /// Whether the next cancellation request must be rejected before changing executor state.
        private final AtomicBoolean rejectNextCancellation = new AtomicBoolean();

        /// Creates a probe around a no-op root task.
        private ProbeTaskExecutor() {
            super(new ProbeTask("Root"));
        }

        /// Publishes the executor start event.
        private void fireStart() {
            notifyTaskListeners(listener -> listener.onStart());
        }

        /// Publishes one ready-task event.
        ///
        /// @param task task entering ready state
        private void fireReady(Task<?> task) {
            notifyTaskListeners(listener -> listener.onReady(task));
        }

        /// Publishes one running-task event.
        ///
        /// @param task task entering running state
        private void fireRunning(Task<?> task) {
            notifyTaskListeners(listener -> listener.onRunning(task));
        }

        /// Publishes one successful task completion event.
        ///
        /// @param task task that completed
        private void fireFinished(Task<?> task) {
            notifyTaskListeners(listener -> listener.onFinished(task));
        }

        /// Publishes one failed task completion event.
        ///
        /// @param task task that failed
        /// @param failure failure to publish
        private void fireFailed(Task<?> task, Throwable failure) {
            notifyTaskListeners(listener -> listener.onFailed(task, failure));
        }

        /// Publishes the executor terminal event.
        ///
        /// @param success whether the complete chain succeeded
        private void fireStop(boolean success) {
            notifyTaskListeners(listener -> listener.onStop(success, this));
        }

        /// Stores the executor-level failure exposed during terminal mapping.
        ///
        /// @param failure failure to expose
        private void recordFailure(Exception failure) {
            exception = failure;
        }

        /// Stores a complete executor-level terminal failure, including an error.
        ///
        /// @param terminalFailure failure to expose
        private void recordTerminalFailure(Throwable terminalFailure) {
            failure = terminalFailure;
        }

        /// Returns the number of cancellation calls forwarded by the model.
        ///
        /// @return cancellation call count
        private int cancellationRequests() {
            return cancellationRequests.get();
        }

        /// Configures the next cancellation request to fail before it is accepted.
        private void rejectNextCancellation() {
            rejectNextCancellation.set(true);
        }

        /// Returns this probe without launching asynchronous work.
        @Override
        public TaskExecutor start() {
            return this;
        }

        /// Reports success without launching asynchronous work.
        @Override
        public boolean test() {
            return true;
        }

        /// Records an idempotence-observable cancellation call and marks the executor cancelled.
        @Override
        public void cancel() {
            cancellationRequests.incrementAndGet();
            if (rejectNextCancellation.compareAndSet(true, false)) {
                throw new IllegalStateException("cancellation is not ready");
            }
            cancelled = true;
        }
    }

    /// No-op task exposing deterministic progress and a caller-provided display name.
    @NotNullByDefault
    private static final class ProbeTask extends Task<@Nullable Void> {
        /// Creates a probe with the exact task name under test.
        ///
        /// @param name task name, which may be blank
        private ProbeTask(String name) {
            setName(name);
        }

        /// Performs no work because lifecycle events are driven by the probe executor.
        @Override
        public void execute() {
        }

        /// Publishes an immediate neutral progress value.
        ///
        /// @param progress normalized progress value
        private void publishProgress(double progress) {
            updateProgressImmediately(progress);
        }
    }

    /// Error fixture that rejects stack-trace rendering through the writer API used by the presentation model.
    @NotNullByDefault
    private static final class StackTraceThrowingError extends AssertionError {
        /// Creates one stable failure fixture.
        private StackTraceThrowingError() {
            super("stack trace unavailable");
        }

        /// Throws instead of rendering diagnostic text.
        ///
        /// @param writer ignored diagnostic destination
        @Override
        public void printStackTrace(PrintWriter writer) {
            throw new AssertionError("test formatting failure");
        }
    }
}
