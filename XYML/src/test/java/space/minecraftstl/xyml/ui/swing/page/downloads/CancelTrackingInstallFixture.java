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
import space.minecraftstl.xyml.task.Schedulers;
import space.minecraftstl.xyml.task.Task;
import space.minecraftstl.xyml.task.TaskResource;

import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/// Test fixture creating blocked installation tasks and recording whether cancellation becomes visible.
@NotNullByDefault
final class CancelTrackingInstallFixture implements RemoteAddonInstallLauncher {
    /// Number of submitted installation requests.
    private final AtomicInteger submitted = new AtomicInteger();

    /// Most recently created blocked task.
    private final AtomicReference<@Nullable BlockingTask> lastTask = new AtomicReference<>();

    /// Releases every created task body.
    private final CountDownLatch releaseLatch = new CountDownLatch(1);

    /// Whether any task body observed executor cancellation.
    private final AtomicBoolean cancellationObserved = new AtomicBoolean();

    /// Creates one fixture that returns a fresh blocked task for every submission.
    CancelTrackingInstallFixture() {
    }

    /// Records one submission and returns a new blocked task.
    ///
    /// @param request selected artifact and target
    /// @return new blocked installation task
    @Override
    public Task<?> createInstallTask(RemoteAddonInstallRequest request) {
        Objects.requireNonNull(request, "request");
        BlockingTask task = new BlockingTask(releaseLatch, cancellationObserved);
        lastTask.set(task);
        submitted.incrementAndGet();
        return task;
    }

    /// Returns the number of submitted installation requests.
    ///
    /// @return submitted installation count
    int submitted() {
        return submitted.get();
    }

    /// Waits for the most recently created task body to start.
    ///
    /// @param timeout maximum wait duration
    /// @param unit timeout unit
    /// @return true when the most recently created task started
    /// @throws InterruptedException when interrupted while waiting
    boolean awaitStarted(long timeout, TimeUnit unit) throws InterruptedException {
        @Nullable BlockingTask task = lastTask.get();
        return task != null && task.awaitStarted(timeout, unit);
    }

    /// Releases every blocked task body.
    void release() {
        releaseLatch.countDown();
    }

    /// Waits for the most recently created task body to finish.
    ///
    /// @param timeout maximum wait duration
    /// @param unit timeout unit
    /// @return true when the most recently created task finished
    /// @throws InterruptedException when interrupted while waiting
    boolean awaitFinished(long timeout, TimeUnit unit) throws InterruptedException {
        @Nullable BlockingTask task = lastTask.get();
        return task != null && task.awaitFinished(timeout, unit);
    }

    /// Returns whether any task body observed cancellation.
    ///
    /// @return true when cancellation became visible
    boolean cancellationObserved() {
        return cancellationObserved.get();
    }

    /// One blocked task body shared with its owning fixture.
    @NotNullByDefault
    private static final class BlockingTask extends Task<@Nullable Void> {
        /// Signals that the task body has started.
        private final CountDownLatch startedLatch = new CountDownLatch(1);

        /// Signals that the task body has finished.
        private final CountDownLatch finishedLatch = new CountDownLatch(1);

        /// Shared release gate for every task in one fixture.
        private final CountDownLatch releaseLatch;

        /// Shared cancellation observation flag.
        private final AtomicBoolean cancellationObserved;

        /// Creates one blocked task.
        ///
        /// @param releaseLatch shared release gate
        /// @param cancellationObserved shared cancellation observation flag
        private BlockingTask(CountDownLatch releaseLatch, AtomicBoolean cancellationObserved) {
            this.releaseLatch = Objects.requireNonNull(releaseLatch, "releaseLatch");
            this.cancellationObserved = Objects.requireNonNull(
                    cancellationObserved,
                    "cancellationObserved");
            setExecutor(Schedulers.io());
            setResources(TaskResource.global().readOnly());
        }

        /// Blocks until released and records whether cancellation became visible.
        @Override
        public void execute() throws Exception {
            startedLatch.countDown();
            try {
                releaseLatch.await();
                cancellationObserved.set(isCancelled());
            } catch (InterruptedException interruption) {
                cancellationObserved.set(true);
                Thread.currentThread().interrupt();
                throw interruption;
            } finally {
                finishedLatch.countDown();
            }
        }

        /// Waits for this task body to begin.
        ///
        /// @param timeout maximum wait duration
        /// @param unit timeout unit
        /// @return true when this task body started
        /// @throws InterruptedException when interrupted while waiting
        private boolean awaitStarted(long timeout, TimeUnit unit) throws InterruptedException {
            return startedLatch.await(timeout, unit);
        }

        /// Waits for this task body to finish.
        ///
        /// @param timeout maximum wait duration
        /// @param unit timeout unit
        /// @return true when this task body finished
        /// @throws InterruptedException when interrupted while waiting
        private boolean awaitFinished(long timeout, TimeUnit unit) throws InterruptedException {
            return finishedLatch.await(timeout, unit);
        }
    }
}
