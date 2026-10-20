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
package space.minecraftstl.xyml.mcp;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import space.minecraftstl.xyml.task.Task;
import space.minecraftstl.xyml.task.TaskResource;

import java.awt.EventQueue;
import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies the blocking MCP bridge preserves Task arbitration and terminal failure semantics.
@NotNullByDefault
public final class McpTaskExecutionTest {
    /// Verifies a package caller cannot accidentally block the Swing event thread.
    @Test
    public void rejectsAwtEventDispatchThread() throws Exception {
        AtomicBoolean executed = new AtomicBoolean();
        AtomicReference<@Nullable Throwable> observedFailure = new AtomicReference<>();
        Task<String> task = Task.supplyAsync(() -> {
            executed.set(true);
            return "unexpected";
        }).setResources(TaskResource.configuration(pathFor("awt-thread")));

        EventQueue.invokeAndWait(() -> {
            try {
                McpTaskExecution.execute(task);
            } catch (Throwable failure) {
                observedFailure.set(failure);
            }
        });

        assertInstanceOf(IllegalStateException.class, observedFailure.get());
        assertFalse(executed.get());
    }

    /// Verifies a successful task is started through its executor and produces its result.
    @Test
    public void returnsSuccessfulTaskResult() throws Exception {
        AtomicBoolean executed = new AtomicBoolean();
        Task<String> task = Task.supplyAsync(() -> {
            executed.set(true);
            return "completed";
        }).setResources(TaskResource.configuration(pathFor("success")));

        assertEquals("completed", McpTaskExecution.execute(task));
        assertTrue(executed.get());
    }

    /// Verifies a checked task failure is rethrown without replacement or wrapping.
    @Test
    public void preservesCheckedExceptionIdentity() {
        IOException expected = new IOException("expected checked failure");
        Task<String> task = Task.<String>supplyAsync(() -> {
            throw expected;
        }).setResources(TaskResource.configuration(pathFor("exception")));

        IOException thrown = assertThrows(IOException.class, () -> McpTaskExecution.execute(task));
        assertSame(expected, thrown);
    }

    /// Verifies an Error escapes the protocol bridge as the original object.
    @Test
    public void preservesErrorIdentity() {
        AssertionError expected = new AssertionError("expected error");
        Task<String> task = Task.<String>supplyAsync(() -> {
            throw expected;
        }).setResources(TaskResource.configuration(pathFor("error")));

        @Nullable Thread.UncaughtExceptionHandler previousHandler = Thread.getDefaultUncaughtExceptionHandler();
        AtomicReference<@Nullable Throwable> reportedFailure = new AtomicReference<>();
        Thread.setDefaultUncaughtExceptionHandler((thread, failure) -> reportedFailure.set(failure));
        try {
            AssertionError thrown = assertThrows(AssertionError.class, () -> McpTaskExecution.execute(task));
            assertSame(expected, thrown);
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previousHandler);
        }
        assertSame(expected, reportedFailure.get());
    }

    /// Verifies cooperative task cancellation retains its established exception identity.
    @Test
    public void preservesCancellationIdentity() {
        CancellationException expected = new CancellationException("expected cancellation");
        Task<String> task = Task.<String>supplyAsync(() -> {
            throw expected;
        }).setResources(TaskResource.configuration(pathFor("cancellation")));

        CancellationException thrown = assertThrows(
                CancellationException.class, () -> McpTaskExecution.execute(task));
        assertSame(expected, thrown);
    }

    /// Verifies the bridge waits behind a conflicting shared Task resource instead of bypassing arbitration.
    @Test
    public void participatesInSharedResourceArbitration() throws Exception {
        TaskResource resource = TaskResource.configuration(pathFor("arbitration"));
        CountDownLatch holderStarted = new CountDownLatch(1);
        CountDownLatch releaseHolder = new CountDownLatch(1);
        CountDownLatch holderStopped = new CountDownLatch(1);
        CountDownLatch contenderStarted = new CountDownLatch(1);
        Task<@Nullable Void> holder = Task.runAsync(() -> {
            holderStarted.countDown();
            releaseHolder.await();
        }).setResources(resource);
        holder.onDone().register(holderStopped::countDown);
        Task<String> contender = Task.supplyAsync(() -> {
            contenderStarted.countDown();
            return "contender";
        }).setResources(resource);
        ExecutorService caller = Executors.newSingleThreadExecutor();

        holder.start();
        assertTrue(holderStarted.await(5, TimeUnit.SECONDS));
        Future<String> result = caller.submit(() -> McpTaskExecution.execute(contender));
        try {
            assertFalse(contenderStarted.await(250, TimeUnit.MILLISECONDS));
            releaseHolder.countDown();
            assertEquals("contender", result.get(5, TimeUnit.SECONDS));
            assertTrue(holderStopped.await(5, TimeUnit.SECONDS));
        } finally {
            releaseHolder.countDown();
            caller.shutdownNow();
            assertTrue(caller.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    /// Verifies an MCP operation can run while an unrelated Task resource remains occupied.
    @Test
    public void allowsNonConflictingResourcesToRunInParallel() throws Exception {
        CountDownLatch holderStarted = new CountDownLatch(1);
        CountDownLatch releaseHolder = new CountDownLatch(1);
        CountDownLatch holderStopped = new CountDownLatch(1);
        Task<@Nullable Void> holder = Task.runAsync(() -> {
            holderStarted.countDown();
            releaseHolder.await();
        }).setResources(TaskResource.configuration(pathFor("parallel-holder")));
        holder.onDone().register(holderStopped::countDown);
        Task<String> unrelated = Task.supplyAsync(() -> "parallel")
                .setResources(TaskResource.configuration(pathFor("parallel-unrelated")));

        holder.start();
        assertTrue(holderStarted.await(5, TimeUnit.SECONDS));
        try {
            assertEquals("parallel", McpTaskExecution.execute(unrelated));
        } finally {
            releaseHolder.countDown();
        }
        assertTrue(holderStopped.await(5, TimeUnit.SECONDS));
    }

    /// Verifies interruption cancels the executor wait and remains observable on the calling thread.
    @Test
    public void preservesWaitingThreadInterruption() throws Exception {
        CountDownLatch taskStarted = new CountDownLatch(1);
        CountDownLatch releaseTask = new CountDownLatch(1);
        CountDownLatch taskStopped = new CountDownLatch(1);
        AtomicReference<@Nullable Throwable> observedFailure = new AtomicReference<>();
        AtomicBoolean interruptRetained = new AtomicBoolean();
        Task<@Nullable Void> task = Task.runAsync(() -> {
            taskStarted.countDown();
            releaseTask.await();
        }).setResources(TaskResource.configuration(pathFor("interruption")));
        task.onDone().register(taskStopped::countDown);
        Thread caller = new Thread(() -> {
            try {
                McpTaskExecution.execute(task);
            } catch (Throwable failure) {
                observedFailure.set(failure);
                interruptRetained.set(Thread.currentThread().isInterrupted());
            }
        }, "mcp-task-execution-test");

        caller.start();
        try {
            assertTrue(taskStarted.await(5, TimeUnit.SECONDS));
            caller.interrupt();
            caller.join(TimeUnit.SECONDS.toMillis(5));
            assertFalse(caller.isAlive());
            assertInstanceOf(InterruptedException.class, observedFailure.get());
            assertTrue(interruptRetained.get());
        } finally {
            releaseTask.countDown();
            caller.interrupt();
        }
        assertTrue(taskStopped.await(5, TimeUnit.SECONDS));
    }

    /// Returns a unique normalized test path for one resource scenario.
    ///
    /// @param scenario scenario identifier
    /// @return path used only as a semantic resource key
    private static Path pathFor(String scenario) {
        return Path.of("build", "mcp-task-execution", scenario);
    }
}
