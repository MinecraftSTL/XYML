/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2020  huangyuhui <huanghongxun2008@126.com> and contributors
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
import space.minecraftstl.xyml.util.Lang;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.function.Function;

import static space.minecraftstl.xyml.util.logging.Logger.LOG;

/// @author huangyuhui
@NotNullByDefault
public final class Schedulers {
    /// Prevents instantiation of this scheduler registry.
    private Schedulers() {
    }

    /// Reflective virtual-thread executor factory, or null on Java versions below 21.
    private static final @Nullable Function<String, ExecutorService> NEW_VIRTUAL_THREAD_PER_TASK_EXECUTOR;

    /// Application UI executor installed by the active presentation toolkit.
    private static volatile Executor uiExecutor = Runnable::run;

    /// Initializes the optional virtual-thread executor factory without linking Java 17 to newer APIs.
    static {
        if (Runtime.version().feature() >= 21) {
            try {
                MethodHandles.Lookup lookup = MethodHandles.publicLookup();

                Class<?> vtBuilderCls = Class.forName("java.lang.Thread$Builder$OfVirtual");

                MethodHandle ofVirtualHandle = lookup.findStatic(Thread.class, "ofVirtual", MethodType.methodType(vtBuilderCls));
                MethodHandle setNameHandle = lookup.findVirtual(vtBuilderCls, "name", MethodType.methodType(vtBuilderCls, String.class, long.class));
                MethodHandle toFactoryHandle = lookup.findVirtual(vtBuilderCls, "factory", MethodType.methodType(ThreadFactory.class));
                MethodHandle newThreadPerTaskExecutorFactory = lookup.findStatic(Executors.class, "newThreadPerTaskExecutor", MethodType.methodType(ExecutorService.class, ThreadFactory.class));

                NEW_VIRTUAL_THREAD_PER_TASK_EXECUTOR = name -> {
                    try {
                        Object virtualThreadBuilder = ofVirtualHandle.invoke();
                        setNameHandle.invoke(virtualThreadBuilder, name, 1L);
                        ThreadFactory threadFactory = (ThreadFactory) toFactoryHandle.invoke(virtualThreadBuilder);

                        return (ExecutorService) newThreadPerTaskExecutorFactory.invokeExact(threadFactory);
                    } catch (Throwable e) {
                        throw new AssertionError("Unreachable", e);
                    }
                };
            } catch (Throwable e) {
                throw new AssertionError("Unreachable", e);
            }
        } else {
            NEW_VIRTUAL_THREAD_PER_TASK_EXECUTOR = null;
        }
    }

    /// Creates a virtual-thread-per-task executor when supported.
    ///
    /// @param name worker thread name prefix
    /// @return executor, or null on Java versions below 21
    public static @Nullable ExecutorService newVirtualThreadPerTaskExecutor(String name) {
        if (NEW_VIRTUAL_THREAD_PER_TASK_EXECUTOR == null) {
            return null;
        }

        return NEW_VIRTUAL_THREAD_PER_TASK_EXECUTOR.apply(name);
    }

    /// This thread pool is suitable for network and local I/O operations.
    ///
    /// For Java 21 or later, all tasks will be dispatched to virtual threads.
    ///
    /// @return Thread pool for I/O operations.
    public static ExecutorService io() {
        return Holder.IO_EXECUTOR;
    }

    /// Installs the executor used for presentation-thread continuations.
    ///
    /// Swing production startup installs the EDT dispatcher. Calls made before toolkit startup execute directly,
    /// which keeps headless tasks usable.
    ///
    /// @param executor presentation-thread executor
    public static void installUiExecutor(Executor executor) {
        uiExecutor = Objects.requireNonNull(executor, "executor");
    }

    /// Returns the executor for presentation-thread continuations.
    ///
    /// @return currently installed UI executor
    public static Executor ui() {
        return uiExecutor;
    }

    /// Default thread pool, equivalent to [ForkJoinPool#commonPool()].
    ///
    /// It is recommended to perform computation tasks on this thread pool. For I/O operations, please use [#io()].
    public static Executor defaultScheduler() {
        return ForkJoinPool.commonPool();
    }

    /// Logs scheduler shutdown; shared daemon and common-pool executors require no blocking termination.
    public static void shutdown() {
        LOG.info("Shutting down executor services.");

        // shutdownNow will interrupt all threads.
        // So when we want to close the app, no threads need to be waited for finish.
        // Sometimes it resolves the problem that the app does not exit.
    }

    /// Lazily initializes the process-wide I/O executor.
    @NotNullByDefault
    private static final class Holder {
        /// Shared I/O executor backed by virtual threads when the running JDK supports them.
        private static final ExecutorService IO_EXECUTOR;

        /// Selects the runtime-appropriate I/O executor implementation.
        static {
            //noinspection resource
            @Nullable ExecutorService vtExecutor = newVirtualThreadPerTaskExecutor("IO");
            IO_EXECUTOR = vtExecutor != null
                    ? vtExecutor
                    : Executors.newCachedThreadPool(Lang.counterThreadFactory("IO", true));
        }
    }

}
