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
import org.junit.jupiter.api.Test;
import space.minecraftstl.xyml.observable.Subscription;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests cancellable and failure-isolated task-listener registrations.
@NotNullByDefault
public final class TaskExecutorSubscriptionTest {
    /// Verifies the protected listener field retains its historical JVM type and mutable-list operations.
    @Test
    public void protectedListenerListRemainsSourceAndBinaryCompatible() throws NoSuchFieldException {
        assertSame(List.class, TaskExecutor.class.getDeclaredField("taskListeners").getType());

        ProbeTaskExecutor executor = new ProbeTaskExecutor();
        AtomicInteger deliveries = new AtomicInteger();
        StopTaskListener listener = new StopTaskListener(deliveries::incrementAndGet);

        executor.addThroughProtectedList(listener);
        assertEquals(1, executor.protectedListenerCount());
        executor.fireStop();
        assertEquals(1, deliveries.get());

        assertTrue(executor.removeThroughProtectedList(listener));
        assertEquals(0, executor.protectedListenerCount());
        executor.fireStop();
        assertEquals(1, deliveries.get());
    }

    /// Verifies duplicate registrations are independent while the legacy registration API remains permanent.
    @Test
    public void duplicateSubscriptionsAreIndependentAndLegacyRegistrationStillWorks() {
        ProbeTaskExecutor executor = new ProbeTaskExecutor();
        AtomicInteger deliveries = new AtomicInteger();
        StopTaskListener listener = new StopTaskListener(deliveries::incrementAndGet);
        Subscription first = executor.subscribeTaskListener(listener);
        Subscription second = executor.subscribeTaskListener(listener);

        first.unsubscribe();
        executor.fireStop();

        assertEquals(1, deliveries.get());
        assertFalse(first.isSubscribed());
        assertTrue(second.isSubscribed());

        executor.addTaskListener(listener);
        second.unsubscribe();
        executor.fireStop();

        assertEquals(2, deliveries.get());
        assertFalse(second.isSubscribed());
    }

    /// Verifies a start listener can synchronously cancel before the terminal future is assigned.
    @Test
    public void startListenerCanCancelSynchronously() {
        Task<@Nullable Void> task = Task.runAsync(Runnable::run, () -> {
        });
        AsyncTaskExecutor executor = new AsyncTaskExecutor(task);
        executor.subscribeTaskListener(new StartTaskListener(executor::cancel));

        assertFalse(executor.test());
        assertTrue(executor.isCancelled());
    }

    /// Verifies a ready listener can synchronously cancel without depending on terminal-future visibility.
    @Test
    public void readyListenerCanCancelSynchronously() {
        Task<@Nullable Void> task = Task.runAsync(Runnable::run, () -> {
        });
        AsyncTaskExecutor executor = new AsyncTaskExecutor(task);
        executor.subscribeTaskListener(new ReadyTaskListener(executor::cancel));

        assertFalse(executor.test());
        assertTrue(executor.isCancelled());
    }

    /// Verifies concurrent cancellation, subscription, and terminal notification use a stable delivery snapshot.
    @Test
    public void concurrentTerminalNotificationUsesStableSnapshot() throws Exception {
        ProbeTaskExecutor executor = new ProbeTaskExecutor();
        CountDownLatch notificationStarted = new CountDownLatch(1);
        CountDownLatch continueNotification = new CountDownLatch(1);
        AtomicBoolean waitTimedOut = new AtomicBoolean();
        AtomicBoolean waitInterrupted = new AtomicBoolean();
        AtomicInteger cancelledDeliveries = new AtomicInteger();
        AtomicInteger lateDeliveries = new AtomicInteger();

        executor.subscribeTaskListener(new StopTaskListener(() -> {
            notificationStarted.countDown();
            try {
                if (!continueNotification.await(5, TimeUnit.SECONDS)) {
                    waitTimedOut.set(true);
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                waitInterrupted.set(true);
            }
        }));
        Subscription cancelled = executor.subscribeTaskListener(
                new StopTaskListener(cancelledDeliveries::incrementAndGet));

        ExecutorService publisher = Executors.newSingleThreadExecutor();
        try {
            Future<?> inFlightNotification = publisher.submit(executor::fireStop);
            assertTrue(notificationStarted.await(5, TimeUnit.SECONDS));

            cancelled.unsubscribe();
            executor.subscribeTaskListener(new StopTaskListener(lateDeliveries::incrementAndGet));
            continueNotification.countDown();
            inFlightNotification.get(5, TimeUnit.SECONDS);

            assertFalse(waitTimedOut.get());
            assertFalse(waitInterrupted.get());
            assertEquals(1, cancelledDeliveries.get());
            assertEquals(0, lateDeliveries.get());

            executor.fireStop();

            assertEquals(1, cancelledDeliveries.get());
            assertEquals(1, lateDeliveries.get());
        } finally {
            continueNotification.countDown();
            publisher.shutdownNow();
        }
    }

    /// Verifies a non-fatal listener failure is reported without stopping later listeners or real task execution.
    @Test
    public void listenerFailureDoesNotInterruptTaskExecution() {
        AtomicInteger taskRuns = new AtomicInteger();
        AtomicInteger laterDeliveries = new AtomicInteger();
        IllegalStateException listenerFailure = new IllegalStateException("listener failure");
        AtomicReference<@Nullable Throwable> reportedFailure = new AtomicReference<>();
        Thread currentThread = Thread.currentThread();
        @Nullable Thread.UncaughtExceptionHandler previousHandler = currentThread.getUncaughtExceptionHandler();
        currentThread.setUncaughtExceptionHandler((thread, failure) -> reportedFailure.compareAndSet(null, failure));

        try {
            TaskExecutor executor = Task.runAsync(Runnable::run, taskRuns::incrementAndGet).executor();
            executor.subscribeTaskListener(new StartTaskListener(() -> {
                throw listenerFailure;
            }));
            executor.subscribeTaskListener(new StartTaskListener(laterDeliveries::incrementAndGet));

            assertTrue(executor.test());

            assertEquals(1, taskRuns.get());
            assertEquals(1, laterDeliveries.get());
            assertSame(listenerFailure, reportedFailure.get());
        } finally {
            currentThread.setUncaughtExceptionHandler(previousHandler);
        }
    }

    /// Verifies a runtime failure from the uncaught-exception handler does not stop later listeners or the task.
    @Test
    public void reportingRuntimeFailureDoesNotInterruptTaskExecution() {
        AtomicInteger taskRuns = new AtomicInteger();
        AtomicInteger laterDeliveries = new AtomicInteger();
        AtomicInteger reportingAttempts = new AtomicInteger();
        Thread currentThread = Thread.currentThread();
        @Nullable Thread.UncaughtExceptionHandler previousHandler = currentThread.getUncaughtExceptionHandler();
        currentThread.setUncaughtExceptionHandler((thread, failure) -> {
            reportingAttempts.incrementAndGet();
            throw new IllegalStateException("reporting failure");
        });

        try {
            TaskExecutor executor = Task.runAsync(Runnable::run, taskRuns::incrementAndGet).executor();
            executor.subscribeTaskListener(new StartTaskListener(() -> {
                throw new IllegalArgumentException("listener failure");
            }));
            executor.subscribeTaskListener(new StartTaskListener(laterDeliveries::incrementAndGet));

            assertTrue(executor.test());

            assertEquals(1, reportingAttempts.get());
            assertEquals(1, laterDeliveries.get());
            assertEquals(1, taskRuns.get());
        } finally {
            currentThread.setUncaughtExceptionHandler(previousHandler);
        }
    }

    /// Verifies listener errors are rethrown unchanged and stop the current notification snapshot.
    @Test
    public void listenerErrorIsRethrown() {
        ProbeTaskExecutor executor = new ProbeTaskExecutor();
        AssertionError listenerFailure = new AssertionError("listener failure");
        AtomicInteger laterDeliveries = new AtomicInteger();
        executor.subscribeTaskListener(new StopTaskListener(() -> {
            throw listenerFailure;
        }));
        executor.subscribeTaskListener(new StopTaskListener(laterDeliveries::incrementAndGet));

        AssertionError thrown = assertThrows(AssertionError.class, executor::fireStop);

        assertSame(listenerFailure, thrown);
        assertEquals(0, laterDeliveries.get());
    }

    /// Verifies an error from the uncaught-exception handler is rethrown unchanged.
    @Test
    public void reportingErrorIsRethrown() {
        ProbeTaskExecutor executor = new ProbeTaskExecutor();
        AssertionError reportingFailure = new AssertionError("reporting failure");
        AtomicInteger laterDeliveries = new AtomicInteger();
        Thread currentThread = Thread.currentThread();
        @Nullable Thread.UncaughtExceptionHandler previousHandler = currentThread.getUncaughtExceptionHandler();
        currentThread.setUncaughtExceptionHandler((thread, failure) -> {
            throw reportingFailure;
        });

        try {
            executor.subscribeTaskListener(new StopTaskListener(() -> {
                throw new IllegalStateException("listener failure");
            }));
            executor.subscribeTaskListener(new StopTaskListener(laterDeliveries::incrementAndGet));

            AssertionError thrown = assertThrows(AssertionError.class, executor::fireStop);

            assertSame(reportingFailure, thrown);
            assertEquals(0, laterDeliveries.get());
        } finally {
            currentThread.setUncaughtExceptionHandler(previousHandler);
        }
    }

    /// Minimal executor that exposes synchronous terminal publication for deterministic concurrency tests.
    @NotNullByDefault
    private static final class ProbeTaskExecutor extends TaskExecutor {
        /// Creates a probe backed by a no-op task.
        private ProbeTaskExecutor() {
            super(Task.runAsync(Runnable::run, () -> {
            }));
        }

        /// Publishes a successful terminal event to the current listener snapshot.
        private void fireStop() {
            taskListeners.forEach(listener -> listener.onStop(true, this));
        }

        /// Adds a listener through the historical protected list API.
        private void addThroughProtectedList(TaskListener listener) {
            taskListeners.add(listener);
        }

        /// Removes a listener through the historical protected list API.
        private boolean removeThroughProtectedList(TaskListener listener) {
            return taskListeners.remove(listener);
        }

        /// Returns the listener count through the historical protected list API.
        private int protectedListenerCount() {
            return taskListeners.size();
        }

        /// Returns this probe without starting asynchronous work.
        @Override
        public TaskExecutor start() {
            return this;
        }

        /// Reports a successful result for the no-op probe.
        @Override
        public boolean test() {
            return true;
        }

        /// Records a cancellation request for the no-op probe.
        @Override
        public void cancel() {
            cancelled = true;
        }
    }

    /// Task listener that invokes one callback at task-chain start.
    @NotNullByDefault
    private static final class StartTaskListener extends TaskListener {
        /// Callback invoked for a start notification.
        private final Runnable callback;

        /// Creates a start listener backed by the supplied callback.
        private StartTaskListener(Runnable callback) {
            this.callback = callback;
        }

        /// Invokes the configured start callback.
        @Override
        public void onStart() {
            callback.run();
        }
    }

    /// Task listener that invokes one callback when a task becomes ready.
    @NotNullByDefault
    private static final class ReadyTaskListener extends TaskListener {
        /// Callback invoked for a ready notification.
        private final Runnable callback;

        /// Creates a ready listener backed by the supplied callback.
        private ReadyTaskListener(Runnable callback) {
            this.callback = callback;
        }

        /// Invokes the configured ready callback.
        @Override
        public void onReady(Task<?> task) {
            callback.run();
        }
    }

    /// Task listener that invokes one callback at task-chain termination.
    @NotNullByDefault
    private static final class StopTaskListener extends TaskListener {
        /// Callback invoked for a terminal notification.
        private final Runnable callback;

        /// Creates a terminal listener backed by the supplied callback.
        private StopTaskListener(Runnable callback) {
            this.callback = callback;
        }

        /// Invokes the configured terminal callback.
        @Override
        public void onStop(boolean success, TaskExecutor executor) {
            callback.run();
        }
    }
}
