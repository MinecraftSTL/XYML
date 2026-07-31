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
import org.junit.jupiter.api.Test;
import space.minecraftstl.xyml.observable.Subscription;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies toolkit-neutral task progress without starting the JavaFX toolkit.
@NotNullByDefault
public final class TaskProgressObservableTest {
    /// Verifies unknown state, worker-thread delivery, terminal progress, listener isolation, and unsubscription.
    @Test
    public void publishesProgressWithoutJavaFxToolkit() throws Exception {
        ProgressTask task = new ProgressTask();
        List<@Nullable Double> observedProgress = new CopyOnWriteArrayList<>();
        List<Thread> listenerThreads = new CopyOnWriteArrayList<>();
        AtomicInteger throwingListenerCalls = new AtomicInteger();

        assertEquals(-1.0, task.progressObservable().getValue());

        Subscription throwingSubscription = task.progressObservable().subscribe(change -> {
            throwingListenerCalls.incrementAndGet();
            throw new IllegalStateException("test listener failure");
        });
        Subscription recordingSubscription = task.progressObservable().subscribe(change -> {
            observedProgress.add(change.currentValue());
            listenerThreads.add(Thread.currentThread());
        });
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            Future<Thread> execution = worker.submit(() -> {
                task.run();
                return Thread.currentThread();
            });
            Thread workerThread = execution.get(10, TimeUnit.SECONDS);

            assertEquals(List.of(0.0, 0.4, 1.0), observedProgress);
            assertEquals(1.0, task.progressObservable().getValue());
            assertEquals(3, throwingListenerCalls.get());
            assertEquals(3, listenerThreads.size());
            listenerThreads.forEach(listenerThread -> assertSame(workerThread, listenerThread));

            recordingSubscription.unsubscribe();
            worker.submit(() -> task.publish(0.75)).get(10, TimeUnit.SECONDS);

            assertEquals(List.of(0.0, 0.4, 1.0), observedProgress);
            assertEquals(4, throwingListenerCalls.get());
        } finally {
            recordingSubscription.unsubscribe();
            throwingSubscription.unsubscribe();
            worker.shutdownNow();
            assertTrue(worker.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    /// Verifies ordered subtask mirroring and cleanup even when an earlier child listener and the child task fail.
    @Test
    public void mirrorsSubtaskProgressAndUnsubscribesAfterFailure() {
        FailingProgressTask child = new FailingProgressTask();
        child.publish(0.2);
        AtomicInteger throwingListenerCalls = new AtomicInteger();
        Subscription throwingSubscription = child.progressObservable().subscribe(change -> {
            throwingListenerCalls.incrementAndGet();
            throw new IllegalStateException("test child listener failure");
        });
        ParentTask parent = new ParentTask(child);
        List<@Nullable Double> parentProgress = new CopyOnWriteArrayList<>();
        Subscription parentSubscription = parent.progressObservable().subscribe(
                change -> parentProgress.add(change.currentValue()));
        try {
            IllegalStateException failure = assertThrows(IllegalStateException.class, parent::run);

            assertEquals("test child failure", failure.getMessage());
            assertEquals(List.of(0.2, 0.4, 0.8), parentProgress);
            assertEquals(2, throwingListenerCalls.get());

            child.publish(0.9);

            assertEquals(List.of(0.2, 0.4, 0.8), parentProgress);
            assertEquals(3, throwingListenerCalls.get());
        } finally {
            parentSubscription.unsubscribe();
            throwingSubscription.unsubscribe();
        }
    }

    /// Verifies concurrent payloads may interleave while each publisher still notifies synchronously on its own thread.
    @Test
    public void deliversConcurrentPublicationsOnCallingThreads() throws Exception {
        ProgressTask task = new ProgressTask();
        CountDownLatch firstListenerEntered = new CountDownLatch(1);
        CountDownLatch releaseFirstListener = new CountDownLatch(1);
        List<@Nullable Double> observedProgress = new CopyOnWriteArrayList<>();
        List<Thread> listenerThreads = new CopyOnWriteArrayList<>();
        Subscription blockingSubscription = task.progressObservable().subscribe(change -> {
            @Nullable Double currentProgress = change.currentValue();
            if (Double.valueOf(0.1).equals(currentProgress)) {
                firstListenerEntered.countDown();
                try {
                    assertTrue(releaseFirstListener.await(10, TimeUnit.SECONDS));
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError("Interrupted while blocking the first progress listener", exception);
                }
            }
        });
        Subscription recordingSubscription = task.progressObservable().subscribe(change -> {
            observedProgress.add(change.currentValue());
            listenerThreads.add(Thread.currentThread());
        });
        ExecutorService publishers = Executors.newFixedThreadPool(2);
        try {
            Future<Thread> firstPublication = publishers.submit(() -> {
                task.publish(0.1);
                return Thread.currentThread();
            });
            assertTrue(firstListenerEntered.await(10, TimeUnit.SECONDS));

            Future<Thread> secondPublication = publishers.submit(() -> {
                task.publish(0.2);
                return Thread.currentThread();
            });
            Thread secondPublisherThread = secondPublication.get(10, TimeUnit.SECONDS);

            assertEquals(0.2, task.progressObservable().getValue());
            assertEquals(List.of(0.2), observedProgress);
            assertEquals(List.of(secondPublisherThread), listenerThreads);

            releaseFirstListener.countDown();
            Thread firstPublisherThread = firstPublication.get(10, TimeUnit.SECONDS);

            assertEquals(List.of(0.2, 0.1), observedProgress);
            assertEquals(List.of(secondPublisherThread, firstPublisherThread), listenerThreads);
            assertEquals(0.2, task.progressObservable().getValue());
        } finally {
            releaseFirstListener.countDown();
            recordingSubscription.unsubscribe();
            blockingSubscription.unsubscribe();
            publishers.shutdownNow();
            assertTrue(publishers.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    /// Verifies a parent mirror reads the child's latest source value instead of replaying a stale concurrent payload.
    @Test
    public void parentMirrorDoesNotRegressAfterConcurrentPayloadInterleaving() throws Exception {
        ConcurrentProgressTask child = new ConcurrentProgressTask();
        ParentTask parent = new ParentTask(child);
        List<@Nullable Double> parentProgress = new CopyOnWriteArrayList<>();
        Subscription parentSubscription = parent.progressObservable().subscribe(
                change -> parentProgress.add(change.currentValue()));
        try {
            parent.run();

            assertEquals(List.of(0.2), parentProgress);
            assertEquals(0.2, parent.progressObservable().getValue());
        } finally {
            parentSubscription.unsubscribe();
        }
    }

    /// Verifies that an escaping listener error does not prevent a later publication from being delivered.
    @Test
    public void recoversPublicationAfterListenerError() {
        ProgressTask task = new ProgressTask();
        AtomicBoolean throwOnce = new AtomicBoolean(true);
        List<@Nullable Double> observedProgress = new CopyOnWriteArrayList<>();
        Subscription errorSubscription = task.progressObservable().subscribe(change -> {
            if (throwOnce.getAndSet(false)) {
                throw new AssertionError("test listener error");
            }
        });
        Subscription recordingSubscription = task.progressObservable().subscribe(
                change -> observedProgress.add(change.currentValue()));
        try {
            AssertionError failure = assertThrows(AssertionError.class, () -> task.publish(0.1));

            assertEquals("test listener error", failure.getMessage());
            assertEquals(List.of(), observedProgress);

            task.publish(0.2);

            assertEquals(List.of(0.2), observedProgress);
        } finally {
            recordingSubscription.unsubscribe();
            errorSubscription.unsubscribe();
        }
    }

    /// Test task that emits deterministic immediate progress updates from its executing thread.
    @NotNullByDefault
    private static final class ProgressTask extends Task<@Nullable Void> {
        /// Publishes the non-terminal and terminal values used by the worker-thread assertions.
        @Override
        public void execute() {
            updateProgressImmediately(0.0);
            updateProgressImmediately(0.4);
            updateProgressImmediately(1.0);
        }

        /// Publishes one additional value for the unsubscription assertion.
        private void publish(double progress) {
            updateProgressImmediately(progress);
        }
    }

    /// Test child task that publishes ordered progress before failing.
    @NotNullByDefault
    private static final class FailingProgressTask extends Task<@Nullable Void> {
        /// Publishes two changes and then fails so the parent must clean up its mirror in a finally block.
        @Override
        public void execute() {
            updateProgressImmediately(0.4);
            updateProgressImmediately(0.8);
            throw new IllegalStateException("test child failure");
        }

        /// Publishes a value before or after execution to verify initial synchronization and cleanup.
        private void publish(double progress) {
            updateProgressImmediately(progress);
        }
    }

    /// Test child task that deterministically interleaves two concurrent progress notification rounds.
    @NotNullByDefault
    private static final class ConcurrentProgressTask extends Task<@Nullable Void> {
        /// Signals that the first publication reached the blocking listener.
        private final CountDownLatch firstListenerEntered = new CountDownLatch(1);

        /// Releases the first publication after the second publication has completed.
        private final CountDownLatch releaseFirstListener = new CountDownLatch(1);

        /// Registration that forces the older payload to reach later listeners after the newer payload.
        private final Subscription blockingSubscription;

        /// Installs the blocking listener before the parent task installs its progress mirror.
        private ConcurrentProgressTask() {
            blockingSubscription = progressObservable().subscribe(change -> {
                @Nullable Double currentProgress = change.currentValue();
                if (Double.valueOf(0.1).equals(currentProgress)) {
                    firstListenerEntered.countDown();
                    try {
                        assertTrue(releaseFirstListener.await(10, TimeUnit.SECONDS));
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError("Interrupted while interleaving child progress", exception);
                    }
                }
            });
        }

        /// Publishes a newer value to completion before allowing the older event to reach the parent mirror.
        @Override
        public void execute() throws Exception {
            ExecutorService publishers = Executors.newFixedThreadPool(2);
            try {
                Future<?> firstPublication = publishers.submit(() -> updateProgressImmediately(0.1));
                assertTrue(firstListenerEntered.await(10, TimeUnit.SECONDS));

                Future<?> secondPublication = publishers.submit(() -> updateProgressImmediately(0.2));
                secondPublication.get(10, TimeUnit.SECONDS);

                releaseFirstListener.countDown();
                firstPublication.get(10, TimeUnit.SECONDS);
            } finally {
                releaseFirstListener.countDown();
                blockingSubscription.unsubscribe();
                publishers.shutdownNow();
                assertTrue(publishers.awaitTermination(10, TimeUnit.SECONDS));
            }
        }
    }

    /// Test parent task whose only prerequisite is the supplied child.
    @NotNullByDefault
    private static final class ParentTask extends Task<@Nullable Void> {
        /// The child whose progress should be mirrored only while it runs.
        private final Task<?> child;

        /// Creates a parent for one child task.
        private ParentTask(Task<?> child) {
            this.child = child;
        }

        /// Returns the child that must execute before the parent body.
        @Override
        public @Unmodifiable List<Task<?>> getDependents() {
            return List.of(child);
        }

        /// Performs no additional work after the child completes.
        @Override
        public void execute() {
        }
    }
}
