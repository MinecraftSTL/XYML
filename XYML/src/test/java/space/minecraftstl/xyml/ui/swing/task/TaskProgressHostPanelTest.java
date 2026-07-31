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
package space.minecraftstl.xyml.ui.swing.task;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import space.minecraftstl.xyml.observable.Subscription;
import space.minecraftstl.xyml.observable.ValueChangeListener;
import space.minecraftstl.xyml.task.presentation.TaskPresentationModel;
import space.minecraftstl.xyml.task.presentation.TaskSnapshot;
import space.minecraftstl.xyml.task.presentation.TaskStatus;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;

import java.time.Duration;
import java.util.Objects;
import java.util.OptionalDouble;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests thread-safe task panel binding, identity semantics, and revision-guarded cleanup.
@NotNullByDefault
public final class TaskProgressHostPanelTest {
    /// Verifies that a worker binding is exposed immediately and installed later on the EDT.
    @Test
    public void bindsWorkerModelOnEventDispatchThread() {
        TaskProgressHostPanel host = createHost();
        FakeTaskPresentationModel model = runningModel("Worker task");

        runOnWorker(() -> host.bind(model), "task-host-worker-bind");

        assertSame(model, host.boundModel().orElseThrow());
        flushEventDispatchThread();
        onEventDispatchThread(() -> {
            TaskProgressPanel panel = installedPanel(host);
            assertAll(
                    () -> assertFalse(host.isOpaque()),
                    () -> assertSame(model.snapshot(), panel.getDisplayedSnapshot()),
                    () -> assertEquals(1, model.activeSubscriptionCount()),
                    () -> assertEquals(1, model.subscriptionInvocationCount()));
            host.close();
        });
    }

    /// Verifies that a distinct model closes the previous panel even when both models compare equal.
    @Test
    public void replacesEqualModelByIdentityAndClosesPreviousPanel() {
        TaskProgressHostPanel host = createHost();
        FakeTaskPresentationModel firstModel = runningModel("First task");
        FakeTaskPresentationModel secondModel = runningModel("Second task");

        onEventDispatchThread(() -> host.bind(firstModel));
        TaskProgressPanel firstPanel = onEventDispatchThread(() -> installedPanel(host));
        assertEquals(firstModel, secondModel, "the fake models must exercise identity rather than equality");

        onEventDispatchThread(() -> host.bind(secondModel));

        onEventDispatchThread(() -> {
            TaskProgressPanel secondPanel = installedPanel(host);
            assertAll(
                    () -> assertNotSame(firstPanel, secondPanel),
                    () -> assertSame(secondModel, host.boundModel().orElseThrow()),
                    () -> assertSame(secondModel.snapshot(), secondPanel.getDisplayedSnapshot()),
                    () -> assertEquals(0, firstModel.activeSubscriptionCount()),
                    () -> assertEquals(1, secondModel.activeSubscriptionCount()),
                    () -> assertEquals(0, firstModel.cancellationInvocationCount()));
            host.close();
        });
    }

    /// Verifies that rebinding one model instance neither reconstructs nor resubscribes its panel.
    @Test
    public void ignoresRepeatedBindingOfSameModelInstance() {
        TaskProgressHostPanel host = createHost();
        FakeTaskPresentationModel model = runningModel("Stable task");

        onEventDispatchThread(() -> host.bind(model));
        TaskProgressPanel originalPanel = onEventDispatchThread(() -> installedPanel(host));
        runOnWorker(() -> host.bind(model), "task-host-repeated-bind");
        flushEventDispatchThread();

        onEventDispatchThread(() -> {
            assertAll(
                    () -> assertSame(originalPanel, installedPanel(host)),
                    () -> assertEquals(1, model.subscriptionInvocationCount()),
                    () -> assertEquals(1, model.activeSubscriptionCount()));
            host.close();
        });
    }

    /// Verifies that an initially terminal model remains bound and visible for result inspection.
    @Test
    public void retainsInitiallyTerminalModel() {
        TaskSnapshot failedSnapshot = new TaskSnapshot(
                "Launch game",
                "Process exited before startup",
                OptionalDouble.empty(),
                TaskStatus.FAILED,
                false,
                "Exit code 1");
        FakeTaskPresentationModel model = new FakeTaskPresentationModel(failedSnapshot);
        TaskProgressHostPanel host = createHost();

        onEventDispatchThread(() -> host.bind(model));

        onEventDispatchThread(() -> {
            TaskProgressPanel panel = installedPanel(host);
            assertAll(
                    () -> assertSame(model, host.boundModel().orElseThrow()),
                    () -> assertSame(failedSnapshot, panel.getDisplayedSnapshot()),
                    () -> assertFalse(panel.isProgressIndeterminate()),
                    () -> assertEquals(1, model.activeSubscriptionCount()));
            host.close();
        });
    }

    /// A failed replacement releases its temporary subscription, restores the old binding, and remains retryable.
    @Test
    public void rollsBackFailedReplacementAndAllowsIdentityRetry() {
        TaskProgressHostPanel host = createHost();
        FakeTaskPresentationModel firstModel = runningModel("Stable first task");
        FakeTaskPresentationModel failingModel = runningModel("Initially failing task");
        IllegalStateException expectedFailure = new IllegalStateException("initial snapshot unavailable");
        onEventDispatchThread(() -> host.bind(firstModel));
        TaskProgressPanel firstPanel = onEventDispatchThread(() -> installedPanel(host));
        failingModel.failNextSnapshotRead(expectedFailure);

        IllegalStateException actualFailure = onEventDispatchThread(
                () -> assertThrows(IllegalStateException.class, () -> host.bind(failingModel)));

        onEventDispatchThread(() -> assertAll(
                () -> assertSame(expectedFailure, actualFailure),
                () -> assertSame(firstModel, host.boundModel().orElseThrow()),
                () -> assertSame(firstPanel, installedPanel(host)),
                () -> assertEquals(1, firstModel.activeSubscriptionCount()),
                () -> assertEquals(0, failingModel.activeSubscriptionCount())));

        onEventDispatchThread(() -> host.bind(failingModel));
        onEventDispatchThread(() -> {
            assertAll(
                    () -> assertSame(failingModel, host.boundModel().orElseThrow()),
                    () -> assertNotSame(firstPanel, installedPanel(host)),
                    () -> assertEquals(0, firstModel.activeSubscriptionCount()),
                    () -> assertEquals(1, failingModel.activeSubscriptionCount()));
            host.close();
        });
    }

    /// A clear superseded on the EDT cannot make a failed replacement roll back to the already closed panel.
    @Test
    public void failedBindAfterQueuedClearRollsBackToEmptyState() {
        TaskProgressHostPanel host = createHost();
        FakeTaskPresentationModel firstModel = runningModel("Cleared first task");
        FakeTaskPresentationModel failingModel = runningModel("Failing replacement");
        IllegalStateException expectedFailure = new IllegalStateException("replacement snapshot unavailable");
        onEventDispatchThread(() -> host.bind(firstModel));

        onEventDispatchThread(() -> {
            runOnWorker(host::clear, "task-host-queued-clear");
            failingModel.failNextSnapshotRead(expectedFailure);
            IllegalStateException actualFailure = assertThrows(
                    IllegalStateException.class,
                    () -> host.bind(failingModel));
            assertSame(expectedFailure, actualFailure);
        });
        flushEventDispatchThread();

        onEventDispatchThread(() -> assertAll(
                () -> assertTrue(host.boundModel().isEmpty()),
                () -> assertEquals(0, host.getComponentCount()),
                () -> assertEquals(0, firstModel.activeSubscriptionCount()),
                () -> assertEquals(0, failingModel.activeSubscriptionCount())));

        onEventDispatchThread(() -> host.bind(firstModel));
        onEventDispatchThread(() -> {
            assertAll(
                    () -> assertSame(firstModel, host.boundModel().orElseThrow()),
                    () -> assertEquals(1, host.getComponentCount()),
                    () -> assertEquals(1, firstModel.activeSubscriptionCount()));
            host.close();
        });
    }

    /// Verifies that clearing releases presentation resources without forwarding cancellation.
    @Test
    public void clearsPresentationWithoutCancellingTask() {
        TaskProgressHostPanel host = createHost();
        FakeTaskPresentationModel model = runningModel("Clear task");
        onEventDispatchThread(() -> host.bind(model));

        runOnWorker(host::clear, "task-host-clear");

        assertTrue(host.boundModel().isEmpty());
        flushEventDispatchThread();
        onEventDispatchThread(() -> {
            assertAll(
                    () -> assertEquals(0, host.getComponentCount()),
                    () -> assertEquals(0, model.activeSubscriptionCount()),
                    () -> assertEquals(0, model.cancellationInvocationCount()));
            host.close();
        });
    }

    /// A child unsubscribe failure is propagated only after the host removes the closed presentation panel.
    @Test
    public void clearRemovesPanelWhenChildCleanupFails() {
        TaskProgressHostPanel host = createHost();
        FakeTaskPresentationModel model = runningModel("Failing cleanup");
        IllegalStateException expectedFailure = new IllegalStateException("task unsubscribe failed");
        onEventDispatchThread(() -> host.bind(model));
        model.failNextUnsubscribe(expectedFailure);

        IllegalStateException actualFailure = onEventDispatchThread(
                () -> assertThrows(IllegalStateException.class, host::clear));

        onEventDispatchThread(() -> assertAll(
                () -> assertSame(expectedFailure, actualFailure),
                () -> assertTrue(host.boundModel().isEmpty()),
                () -> assertEquals(0, host.getComponentCount()),
                () -> assertEquals(0, model.activeSubscriptionCount())));
        host.close();
    }

    /// Verifies that close invalidates queued equal-model binds before either can install a panel.
    @Test
    public void closeWinsRaceWithQueuedIdentityBindings() {
        TaskProgressHostPanel host = createHost();
        FakeTaskPresentationModel firstModel = runningModel("Queued first task");
        FakeTaskPresentationModel secondModel = runningModel("Queued second task");

        onEventDispatchThread(() -> {
            runOnWorker(() -> host.bind(firstModel), "task-host-first-racing-bind");
            runOnWorker(() -> host.bind(secondModel), "task-host-second-racing-bind");
            runOnWorker(host::close, "task-host-racing-close");
            assertTrue(host.boundModel().isEmpty());
        });
        flushEventDispatchThread();

        onEventDispatchThread(() -> assertAll(
                () -> assertEquals(0, host.getComponentCount()),
                () -> assertEquals(0, firstModel.activeSubscriptionCount()),
                () -> assertEquals(0, secondModel.activeSubscriptionCount()),
                () -> assertEquals(0, firstModel.cancellationInvocationCount()),
                () -> assertEquals(0, secondModel.cancellationInvocationCount())));
    }

    /// Closing waits for an in-flight panel constructor and then releases its completed subscription.
    @Test
    public void closeWaitsForInFlightPanelConstruction() throws InterruptedException {
        TaskProgressHostPanel host = createHost();
        FakeTaskPresentationModel model = runningModel("Blocked construction");
        CountDownLatch snapshotEntered = new CountDownLatch(1);
        CountDownLatch releaseSnapshot = new CountDownLatch(1);
        CountDownLatch bindingReturned = new CountDownLatch(1);
        CountDownLatch closeReturned = new CountDownLatch(1);
        AtomicReference<@Nullable Throwable> bindingFailure = new AtomicReference<>();
        model.blockNextSnapshotRead(snapshotEntered, releaseSnapshot);

        Thread binder = new Thread(() -> {
            try {
                onEventDispatchThread(() -> host.bind(model));
            } catch (Throwable failure) {
                bindingFailure.set(failure);
            } finally {
                bindingReturned.countDown();
            }
        }, "task-host-blocked-constructor");
        Thread closer = new Thread(() -> {
            host.close();
            closeReturned.countDown();
        }, "task-host-construction-close");
        binder.start();
        try {
            assertTrue(snapshotEntered.await(5, TimeUnit.SECONDS));
            closer.start();
            assertFalse(closeReturned.await(100, TimeUnit.MILLISECONDS));
        } finally {
            releaseSnapshot.countDown();
        }

        assertTrue(bindingReturned.await(5, TimeUnit.SECONDS));
        assertTrue(closeReturned.await(5, TimeUnit.SECONDS));
        binder.join();
        closer.join();
        flushEventDispatchThread();
        onEventDispatchThread(() -> assertAll(
                () -> assertNull(bindingFailure.get()),
                () -> assertTrue(host.boundModel().isEmpty()),
                () -> assertEquals(0, host.getComponentCount()),
                () -> assertEquals(0, model.activeSubscriptionCount())));
    }

    /// Creates an empty host on the Swing event dispatch thread.
    ///
    /// @return a new test host
    private static TaskProgressHostPanel createHost() {
        return onEventDispatchThread(() -> new TaskProgressHostPanel(
                TaskProgressStrings.english(),
                null,
                Duration.ZERO));
    }

    /// Creates a cancellable running fake model with indeterminate progress.
    ///
    /// @param title the task title
    /// @return the new fake model
    private static FakeTaskPresentationModel runningModel(String title) {
        return new FakeTaskPresentationModel(new TaskSnapshot(
                title,
                "Running",
                OptionalDouble.empty(),
                TaskStatus.RUNNING,
                true,
                ""));
    }

    /// Returns the sole task panel installed in a host.
    ///
    /// This helper must be called on the Swing event dispatch thread.
    ///
    /// @param host the host to inspect
    /// @return the installed task panel
    private static TaskProgressPanel installedPanel(TaskProgressHostPanel host) {
        assertEquals(1, host.getComponentCount());
        return (TaskProgressPanel) host.getComponent(0);
    }

    /// Waits until all previously queued EDT operations have completed.
    private static void flushEventDispatchThread() {
        EdtDispatcher.executeAndWait(() -> { });
    }

    /// Executes one operation on a worker thread and waits for it to finish.
    ///
    /// @param operation the operation to execute
    /// @param threadName the diagnostic worker thread name
    private static void runOnWorker(Runnable operation, String threadName) {
        AtomicReference<@Nullable Throwable> failure = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try {
                operation.run();
            } catch (Throwable throwable) {
                failure.set(throwable);
            }
        }, threadName);
        worker.start();
        try {
            worker.join();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while waiting for test worker", exception);
        }

        @Nullable Throwable workerFailure = failure.get();
        if (workerFailure != null) {
            throw new AssertionError("Test worker failed", workerFailure);
        }
    }

    /// Executes a value-producing operation synchronously on the Swing event dispatch thread.
    ///
    /// @param operation the operation to execute
    /// @param <T> the non-null result type
    /// @return the operation result
    private static <T extends Object> T onEventDispatchThread(Supplier<T> operation) {
        AtomicReference<@Nullable T> result = new AtomicReference<>();
        EdtDispatcher.executeAndWait(() -> result.set(operation.get()));
        return Objects.requireNonNull(result.get(), "EDT operation did not provide a result");
    }

    /// Executes an operation synchronously on the Swing event dispatch thread.
    ///
    /// @param operation the operation to execute
    private static void onEventDispatchThread(Runnable operation) {
        EdtDispatcher.executeAndWait(operation);
    }

    /// Thread-safe fake model whose instances compare equal so host identity semantics remain observable.
    @NotNullByDefault
    private static final class FakeTaskPresentationModel implements TaskPresentationModel {
        /// Most recent immutable task state.
        private final AtomicReference<TaskSnapshot> currentSnapshot;

        /// Active snapshot listeners registered by presentation panels.
        private final CopyOnWriteArrayList<ValueChangeListener<TaskSnapshot>> listeners =
                new CopyOnWriteArrayList<>();

        /// Total number of subscriptions created by this fake.
        private final AtomicInteger subscriptionInvocations = new AtomicInteger();

        /// Total number of cancellation requests received by this fake.
        private final AtomicInteger cancellationInvocations = new AtomicInteger();

        /// Failure thrown by the next snapshot read, or null when reads should succeed.
        private final AtomicReference<@Nullable RuntimeException> nextSnapshotFailure = new AtomicReference<>();

        /// Signal emitted when the next explicitly blocked snapshot read starts.
        private final AtomicReference<@Nullable CountDownLatch> nextSnapshotEntered = new AtomicReference<>();

        /// Signal releasing the next explicitly blocked snapshot read.
        private final AtomicReference<@Nullable CountDownLatch> nextSnapshotRelease = new AtomicReference<>();

        /// Failure thrown after the next listener removal, or null when cleanup succeeds.
        private final AtomicReference<@Nullable RuntimeException> nextUnsubscribeFailure = new AtomicReference<>();

        /// Creates a fake model with one immutable initial state.
        ///
        /// @param initialSnapshot the initial task state
        private FakeTaskPresentationModel(TaskSnapshot initialSnapshot) {
            currentSnapshot = new AtomicReference<>(initialSnapshot);
        }

        /// Returns the current fake task state.
        ///
        /// @return the current snapshot
        @Override
        public TaskSnapshot snapshot() {
            @Nullable RuntimeException failure = nextSnapshotFailure.getAndSet(null);
            if (failure != null) {
                throw failure;
            }
            @Nullable CountDownLatch entered = nextSnapshotEntered.getAndSet(null);
            if (entered != null) {
                CountDownLatch release = Objects.requireNonNull(
                        nextSnapshotRelease.getAndSet(null),
                        "blocked snapshot release latch");
                entered.countDown();
                try {
                    release.await();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError("Interrupted while blocking a fake host snapshot", exception);
                }
            }
            return currentSnapshot.get();
        }

        /// Registers a listener and returns a removal subscription.
        ///
        /// @param listener the task transition listener
        /// @return the listener subscription
        @Override
        public Subscription subscribe(ValueChangeListener<TaskSnapshot> listener) {
            listeners.add(Objects.requireNonNull(listener, "listener"));
            subscriptionInvocations.incrementAndGet();
            return Subscription.create(() -> {
                listeners.remove(listener);
                @Nullable RuntimeException failure = nextUnsubscribeFailure.getAndSet(null);
                if (failure != null) {
                    throw failure;
                }
            });
        }

        /// Records a cancellation request without changing task state.
        @Override
        public void requestCancellation() {
            cancellationInvocations.incrementAndGet();
        }

        /// Returns how many presentation subscriptions have been created.
        ///
        /// @return total subscription invocation count
        private int subscriptionInvocationCount() {
            return subscriptionInvocations.get();
        }

        /// Returns how many presentation subscriptions remain active.
        ///
        /// @return active listener count
        private int activeSubscriptionCount() {
            return listeners.size();
        }

        /// Returns how many cancellation requests the fake received.
        ///
        /// @return cancellation invocation count
        private int cancellationInvocationCount() {
            return cancellationInvocations.get();
        }

        /// Configures the next snapshot read to fail once.
        ///
        /// @param failure failure thrown by the next read
        private void failNextSnapshotRead(RuntimeException failure) {
            nextSnapshotFailure.set(Objects.requireNonNull(failure, "failure"));
        }

        /// Configures the next snapshot read to expose and wait on deterministic test latches.
        ///
        /// @param entered signal emitted when the read starts
        /// @param release signal allowing the read to finish
        private void blockNextSnapshotRead(CountDownLatch entered, CountDownLatch release) {
            nextSnapshotRelease.set(release);
            nextSnapshotEntered.set(entered);
        }

        /// Configures the next listener cleanup to fail after removing the registration.
        ///
        /// @param failure cleanup failure
        private void failNextUnsubscribe(RuntimeException failure) {
            nextUnsubscribeFailure.set(Objects.requireNonNull(failure, "failure"));
        }

        /// Treats all fake models as value-equal so tests can distinguish equality from identity.
        ///
        /// @param other the object to compare, possibly null
        /// @return `true` for any fake task presentation model
        @Override
        public boolean equals(@Nullable Object other) {
            return other instanceof FakeTaskPresentationModel;
        }

        /// Returns the shared hash code required by the fake equality contract.
        ///
        /// @return a stable shared hash code
        @Override
        public int hashCode() {
            return FakeTaskPresentationModel.class.hashCode();
        }
    }
}
