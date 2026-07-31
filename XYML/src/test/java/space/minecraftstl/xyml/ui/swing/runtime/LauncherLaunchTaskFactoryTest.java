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
package space.minecraftstl.xyml.ui.swing.runtime;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import space.minecraftstl.xyml.game.QuickPlayOption;
import space.minecraftstl.xyml.game.launch.LaunchRequest;
import space.minecraftstl.xyml.setting.LauncherVisibility;
import space.minecraftstl.xyml.task.Task;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;
import space.minecraftstl.xyml.util.platform.ManagedProcess;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies launcher-task request dispatch without constructing a real repository.
@NotNullByDefault
final class LauncherLaunchTaskFactoryTest {
    /// Confirms that dispatch and task construction consume the exact immutable request once.
    @Test
    void dispatchesExactRequestAndReturnsExactTask() {
        LaunchRequest request = new LaunchRequest(
                "account:00000000-0000-0000-0000-000000000001",
                "game-directory:00000000-0000-0000-0000-000000000002",
                "instance-a");
        RecordingTask task = new RecordingTask();
        AtomicInteger dispatchCalls = new AtomicInteger();
        AtomicReference<@Nullable LaunchRequest> observedRequest = new AtomicReference<>();
        LauncherLaunchTaskFactory factory = new LauncherLaunchTaskFactory(
                operation -> {
                    dispatchCalls.incrementAndGet();
                    operation.run();
                },
                captured -> {
                    observedRequest.set(captured);
                    return task;
                });

        Task<ManagedProcess> result = factory.create(request);

        assertSame(task, result);
        assertSame(request, observedRequest.get());
        assertEquals(1, dispatchCalls.get());
        assertEquals(0, task.executionCount());
    }

    /// Confirms script export preserves the exact request and normalized target without starting its task.
    @Test
    void dispatchesExactLaunchScriptRequestAndReturnsExactTask() {
        LaunchRequest request = new LaunchRequest(
                "account:00000000-0000-0000-0000-000000000001",
                "game-directory:00000000-0000-0000-0000-000000000002",
                "instance-a");
        Path requestedTarget = Path.of("build", "launch-script-test.bat");
        Path normalizedTarget = requestedTarget.toAbsolutePath().normalize();
        Task<Path> scriptTask = Task.completed(normalizedTarget);
        AtomicInteger dispatchCalls = new AtomicInteger();
        AtomicReference<@Nullable LaunchRequest> observedRequest = new AtomicReference<>();
        AtomicReference<@Nullable Path> observedTarget = new AtomicReference<>();
        LauncherLaunchTaskFactory factory = new LauncherLaunchTaskFactory(
                operation -> {
                    dispatchCalls.incrementAndGet();
                    operation.run();
                },
                ignored -> new RecordingTask(),
                (captured, target) -> {
                    observedRequest.set(captured);
                    observedTarget.set(target);
                    return scriptTask;
                },
                new LaunchVisibilityActions(() -> { }, () -> { }, () -> { }));

        Task<Path> result = factory.createLaunchScriptTask(request, requestedTarget);

        assertSame(scriptTask, result);
        assertSame(request, observedRequest.get());
        assertEquals(normalizedTarget, observedTarget.get());
        assertEquals(1, dispatchCalls.get());
        assertNull(scriptTask.getResult());
    }

    /// Confirms both launch paths can apply the exact immutable single-player target to `LauncherHelper`.
    @Test
    void configuresCapturedSingleplayerQuickPlayTarget() {
        LaunchRequest request = new LaunchRequest(
                "account",
                "directory",
                "instance",
                "World Folder");
        AtomicReference<@Nullable QuickPlayOption> observedOption = new AtomicReference<>();

        LauncherLaunchTaskFactory.configureLaunchModes(request, observedOption::set, () -> { });

        QuickPlayOption.SinglePlayer option = (QuickPlayOption.SinglePlayer) Objects.requireNonNull(
                observedOption.get());
        assertEquals("World Folder", option.worldFolderName());
    }

    /// Confirms ordinary requests leave the helper's persisted quick-play behavior untouched.
    @Test
    void ordinaryRequestDoesNotOverrideQuickPlayTarget() {
        AtomicInteger setterCalls = new AtomicInteger();

        LauncherLaunchTaskFactory.configureLaunchModes(
                new LaunchRequest("account", "directory", "instance"),
                ignored -> setterCalls.incrementAndGet(),
                setterCalls::incrementAndGet);

        assertEquals(0, setterCalls.get());
    }

    /// Confirms a test request enables only the launcher's isolated test-game policy.
    @Test
    void configuresCapturedTestMode() {
        AtomicInteger quickPlaySetterCalls = new AtomicInteger();
        AtomicInteger testModeSetterCalls = new AtomicInteger();

        LauncherLaunchTaskFactory.configureLaunchModes(
                LaunchRequest.test("account", "directory", "instance"),
                ignored -> quickPlaySetterCalls.incrementAndGet(),
                testModeSetterCalls::incrementAndGet);

        assertEquals(0, quickPlaySetterCalls.get());
        assertEquals(1, testModeSetterCalls.get());
    }

    /// Confirms that a caller on the Swing EDT is rejected before any blocking launcher dispatch.
    @Test
    void rejectsSwingEdtBeforeLegacyDispatch() {
        AtomicInteger dispatchCalls = new AtomicInteger();
        LauncherLaunchTaskFactory factory = new LauncherLaunchTaskFactory(
                operation -> {
                    dispatchCalls.incrementAndGet();
                    operation.run();
                },
                ignored -> new RecordingTask());
        AtomicReference<@Nullable Throwable> observedFailure = new AtomicReference<>();

        EdtDispatcher.executeAndWait(() -> {
            try {
                factory.create(new LaunchRequest("account", "directory", "instance"));
            } catch (RuntimeException | Error failure) {
                observedFailure.set(failure);
            }
        });

        assertThrows(
                IllegalStateException.class,
                () -> rethrow(observedFailure.get()));
        assertEquals(0, dispatchCalls.get());
    }

    /// Confirms that task-builder failures preserve their original identity.
    @Test
    void preservesBuilderFailureIdentity() {
        IllegalArgumentException failure = new IllegalArgumentException("unknown stable target");
        LauncherLaunchTaskFactory factory = new LauncherLaunchTaskFactory(
                Runnable::run,
                ignored -> {
                    throw failure;
                });

        IllegalArgumentException observed = assertThrows(
                IllegalArgumentException.class,
                () -> factory.create(new LaunchRequest("account", "directory", "instance")));

        assertSame(failure, observed);
    }

    /// Confirms that an unloaded repository is refreshed before exact-instance validation runs.
    @Test
    void refreshesUnloadedRepositoryBeforeBuildingExactTask() {
        AtomicInteger refreshExecutions = new AtomicInteger();
        AtomicInteger loadedBuilderCalls = new AtomicInteger();
        RecordingTask loadedTask = new RecordingTask();
        Task<ManagedProcess> task = LauncherLaunchTaskFactory.afterRepositoryReady(
                false,
                () -> Task.runAsync(Runnable::run, refreshExecutions::incrementAndGet),
                () -> {
                    assertEquals(1, refreshExecutions.get());
                    loadedBuilderCalls.incrementAndGet();
                    return loadedTask;
                },
                Runnable::run);

        assertEquals(0, refreshExecutions.get());
        assertEquals(0, loadedBuilderCalls.get());
        assertTrue(task.executor().test());
        assertEquals(1, refreshExecutions.get());
        assertEquals(1, loadedBuilderCalls.get());
        assertEquals(1, loadedTask.executionCount());
    }

    /// Confirms that a refresh failure prevents stale instance validation and preserves its failure identity.
    @Test
    void refreshFailurePreventsLoadedTaskConstruction() {
        IllegalStateException failure = new IllegalStateException("refresh failed");
        AtomicInteger loadedBuilderCalls = new AtomicInteger();
        Task<ManagedProcess> task = LauncherLaunchTaskFactory.afterRepositoryReady(
                false,
                () -> Task.runAsync(Runnable::run, () -> {
                    throw failure;
                }),
                () -> {
                    loadedBuilderCalls.incrementAndGet();
                    return new RecordingTask();
                },
                Runnable::run);

        assertFalse(task.executor().test());
        assertSame(failure, task.getException());
        assertEquals(0, loadedBuilderCalls.get());
    }

    /// Confirms that an already loaded repository avoids refresh and returns the exact built task.
    @Test
    void loadedRepositoryBypassesRefresh() {
        AtomicInteger refreshTaskRequests = new AtomicInteger();
        RecordingTask loadedTask = new RecordingTask();

        Task<ManagedProcess> result = LauncherLaunchTaskFactory.afterRepositoryReady(
                true,
                () -> {
                    refreshTaskRequests.incrementAndGet();
                    return Task.completed(null);
                },
                () -> loadedTask,
                Runnable::run);

        assertSame(loadedTask, result);
        assertEquals(0, refreshTaskRequests.get());
        assertEquals(0, loadedTask.executionCount());
    }

    /// Confirms that `CLOSE` closes the runtime at process creation without installing an exit callback.
    @Test
    void closeVisibilityClosesAtProcessCreation() {
        VisibilityFixture fixture = new VisibilityFixture();

        ManagedProcess result = fixture.execute(LauncherVisibility.CLOSE);

        assertSame(fixture.managedProcess(), result);
        assertEquals(1, fixture.closeCount());
        assertEquals(0, fixture.hideCount());
        assertEquals(0, fixture.showCount());
        assertEquals(0, fixture.rawProcess().onExitCalls());
    }

    /// Confirms that `KEEP` leaves the window untouched before and after process exit.
    @Test
    void keepVisibilityLeavesRuntimeUntouched() {
        VisibilityFixture fixture = new VisibilityFixture();

        fixture.execute(LauncherVisibility.KEEP);
        fixture.rawProcess().completeExit();
        fixture.completeLifecycle();

        assertEquals(0, fixture.closeCount());
        assertEquals(0, fixture.hideCount());
        assertEquals(0, fixture.showCount());
        assertEquals(0, fixture.rawProcess().onExitCalls());
    }

    /// Confirms that `HIDE` hides immediately and closes the runtime only after process exit.
    @Test
    void hideVisibilityClosesAfterProcessExit() {
        VisibilityFixture fixture = new VisibilityFixture();

        fixture.execute(LauncherVisibility.HIDE);

        assertEquals(1, fixture.hideCount());
        assertEquals(0, fixture.closeCount());
        assertEquals(0, fixture.rawProcess().onExitCalls());
        fixture.rawProcess().completeExit();
        assertEquals(0, fixture.closeCount());
        fixture.completeLifecycle();
        assertEquals(1, fixture.closeCount());
        assertEquals(0, fixture.showCount());
    }

    /// Confirms that `HIDE_AND_REOPEN` hides immediately and shows the same runtime after process exit.
    @Test
    void hideAndReopenVisibilityShowsAfterProcessExit() {
        VisibilityFixture fixture = new VisibilityFixture();

        fixture.execute(LauncherVisibility.HIDE_AND_REOPEN);

        assertEquals(1, fixture.hideCount());
        assertEquals(0, fixture.showCount());
        assertEquals(0, fixture.rawProcess().onExitCalls());
        fixture.rawProcess().completeExit();
        assertEquals(0, fixture.showCount());
        fixture.completeLifecycle();
        assertEquals(1, fixture.showCount());
        assertEquals(0, fixture.closeCount());
    }

    /// A process that already exited still hides before it is reopened.
    @Test
    void completedExitCannotReopenBeforeHide() {
        ControllableProcess rawProcess = new ControllableProcess();
        rawProcess.completeExit();
        ManagedProcess process = new ManagedProcess(rawProcess, List.of("java", "test.Main"));
        CompletableFuture<@Nullable Void> lifecycleCompletion = new CompletableFuture<>();
        List<String> actions = new ArrayList<>();

        LauncherLaunchTaskFactory.applyVisibilityPolicy(
                process,
                LauncherVisibility.HIDE_AND_REOPEN,
                new LaunchVisibilityActions(
                        () -> actions.add("close"),
                        () -> actions.add("hide"),
                        () -> actions.add("show")),
                lifecycleCompletion);

        assertEquals(List.of("hide"), actions);
        lifecycleCompletion.complete(null);
        assertEquals(List.of("hide", "show"), actions);
    }

    /// Exit-observation failure is reported but cannot strand an already hidden launcher.
    @Test
    void exceptionalExitStillReopensHiddenLauncher() {
        ControllableProcess rawProcess = new ControllableProcess();
        rawProcess.completeExit();
        ManagedProcess process = new ManagedProcess(rawProcess, List.of("java", "test.Main"));
        CompletableFuture<@Nullable Void> lifecycleCompletion = new CompletableFuture<>();
        lifecycleCompletion.completeExceptionally(
                new IllegalStateException("exit observation failed"));
        List<String> actions = new ArrayList<>();

        LauncherLaunchTaskFactory.applyVisibilityPolicy(
                process,
                LauncherVisibility.HIDE_AND_REOPEN,
                new LaunchVisibilityActions(
                        () -> actions.add("close"),
                        () -> actions.add("hide"),
                        () -> actions.add("show")),
                lifecycleCompletion);

        assertEquals(List.of("hide", "show"), actions);
    }

    /// A failed hide attempt does not prevent the later close policy from being registered.
    @Test
    void hideFailureStillClosesAfterExit() {
        ControllableProcess rawProcess = new ControllableProcess();
        ManagedProcess process = new ManagedProcess(rawProcess, List.of("java", "test.Main"));
        CompletableFuture<@Nullable Void> lifecycleCompletion = new CompletableFuture<>();
        AtomicInteger closeCount = new AtomicInteger();

        LauncherLaunchTaskFactory.applyVisibilityPolicy(
                process,
                LauncherVisibility.HIDE,
                new LaunchVisibilityActions(
                        closeCount::incrementAndGet,
                        () -> {
                            throw new IllegalStateException("hide failed");
                        },
                        () -> { }),
                lifecycleCompletion);

        rawProcess.completeExit();
        assertEquals(0, closeCount.get());
        lifecycleCompletion.complete(null);
        assertEquals(1, closeCount.get());
    }

    /// Rethrows one captured EDT failure without changing its unchecked type.
    ///
    /// @param failure captured failure, or null
    private static void rethrow(@Nullable Throwable failure) {
        if (failure instanceof RuntimeException runtimeException) {
            throw runtimeException;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        if (failure == null) {
            throw new AssertionError("Expected EDT failure was not captured");
        }
        throw new AssertionError("Unexpected checked failure", failure);
    }

    /// Owns a deterministic managed process and records all visibility actions.
    @NotNullByDefault
    private static final class VisibilityFixture {
        /// Controllable raw process.
        private final ControllableProcess rawProcess = new ControllableProcess();

        /// Managed process passed through the task boundary.
        private final ManagedProcess managedProcess = new ManagedProcess(rawProcess, List.of("java", "test.Main"));

        /// Controllable completion of the full process-listener lifecycle.
        private final CompletableFuture<@Nullable Void> lifecycleCompletion = new CompletableFuture<>();

        /// Number of close actions.
        private final AtomicInteger closeCount = new AtomicInteger();

        /// Number of hide actions.
        private final AtomicInteger hideCount = new AtomicInteger();

        /// Number of show actions.
        private final AtomicInteger showCount = new AtomicInteger();

        /// Executes one policy task synchronously and returns its exact result.
        ///
        /// @param visibility policy under test
        /// @return exact managed process result
        private ManagedProcess execute(LauncherVisibility visibility) {
            LauncherLaunchTaskFactory.applyVisibilityPolicy(
                    managedProcess,
                    visibility,
                    new LaunchVisibilityActions(
                            closeCount::incrementAndGet,
                            hideCount::incrementAndGet,
                            showCount::incrementAndGet),
                    lifecycleCompletion);
            return managedProcess;
        }

        /// Completes the full listener lifecycle independently from raw process exit.
        private void completeLifecycle() {
            lifecycleCompletion.complete(null);
        }

        /// Returns the controllable raw process.
        ///
        /// @return raw process
        private ControllableProcess rawProcess() {
            return rawProcess;
        }

        /// Returns the managed-process identity.
        ///
        /// @return managed process
        private ManagedProcess managedProcess() {
            return managedProcess;
        }

        /// Returns the close-action count.
        ///
        /// @return close count
        private int closeCount() {
            return closeCount.get();
        }

        /// Returns the hide-action count.
        ///
        /// @return hide count
        private int hideCount() {
            return hideCount.get();
        }

        /// Returns the show-action count.
        ///
        /// @return show count
        private int showCount() {
            return showCount.get();
        }
    }

    /// Provides a manually completed process-exit future without starting an operating-system process.
    @NotNullByDefault
    private static final class ControllableProcess extends Process {
        /// Completion stage returned from `onExit()`.
        private final CompletableFuture<Process> exitFuture = new CompletableFuture<>();

        /// Number of non-blocking exit subscriptions.
        private final AtomicInteger onExitCalls = new AtomicInteger();

        /// Whether the fake process remains alive.
        private volatile boolean alive = true;

        /// Creates a running fake process.
        private ControllableProcess() {
        }

        /// Returns a sink for unused process input.
        ///
        /// @return null output stream
        @Override
        public OutputStream getOutputStream() {
            return OutputStream.nullOutputStream();
        }

        /// Returns an empty process output stream.
        ///
        /// @return empty input stream
        @Override
        public InputStream getInputStream() {
            return InputStream.nullInputStream();
        }

        /// Returns an empty process error stream.
        ///
        /// @return empty input stream
        @Override
        public InputStream getErrorStream() {
            return InputStream.nullInputStream();
        }

        /// Completes the fake process and returns its zero exit code.
        ///
        /// @return zero exit code
        @Override
        public int waitFor() {
            completeExit();
            return 0;
        }

        /// Returns the exit code after completion.
        ///
        /// @return zero exit code
        @Override
        public int exitValue() {
            if (alive) {
                throw new IllegalThreadStateException("process is still running");
            }
            return 0;
        }

        /// Terminates the fake process.
        @Override
        public void destroy() {
            completeExit();
        }

        /// Returns the controllable non-blocking exit future.
        ///
        /// @return process exit future
        @Override
        public CompletableFuture<Process> onExit() {
            onExitCalls.incrementAndGet();
            return exitFuture;
        }

        /// Completes the process and its exit future once.
        private void completeExit() {
            alive = false;
            exitFuture.complete(this);
        }

        /// Returns the number of `onExit()` subscriptions.
        ///
        /// @return subscription count
        private int onExitCalls() {
            return onExitCalls.get();
        }
    }

    /// Stopped task fixture that records accidental execution.
    @NotNullByDefault
    private static final class RecordingTask extends Task<ManagedProcess> {
        /// Number of task-body executions.
        private final AtomicInteger executions = new AtomicInteger();

        /// Records execution without producing a process because tests never start this task.
        @Override
        public void execute() {
            executions.incrementAndGet();
        }

        /// Returns the task-body execution count.
        ///
        /// @return execution count
        private int executionCount() {
            return executions.get();
        }
    }
}
