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
package space.minecraftstl.xyml.ui.swing.startup;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import space.minecraftstl.xyml.ui.UiDispatcher;

import java.net.URI;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies startup prompt policy, state mutations, scheduling boundaries, and terminal effects.
@NotNullByDefault
final class StartupPromptCoordinatorTest {
    /// Confirms that all seven eligible prompts are presented in the required policy order.
    @Test
    void presentsAllEligiblePromptsInFixedOrder() {
        TestSchedulers schedulers = new TestSchedulers();
        RecordingGateway gateway = new RecordingGateway(schedulers, fullyEligibleSnapshot());
        RecordingPresenter presenter = new RecordingPresenter(
                schedulers,
                StartupPromptDecision.Agreement.ACCEPT,
                StartupPromptDecision.Suppression.DO_NOT_SHOW_AGAIN,
                StartupPromptDecision.Suppression.DO_NOT_SHOW_AGAIN,
                StartupPromptDecision.AprilFools.KEEP_LANGUAGE);
        RecordingEffects effects = new RecordingEffects(schedulers);
        StartupPromptCoordinator coordinator = coordinator(
                schedulers,
                fullyEligibleEnvironment(StartupPlatformPrompt.WINDOWS_ARM64),
                gateway,
                presenter,
                effects);

        CompletionStage<@Nullable Void> completion = coordinator.start();
        schedulers.drain();

        assertEquals(List.of(
                StartupPromptKind.AGREEMENT,
                StartupPromptKind.INVALID_CACHE_DIRECTORY,
                StartupPromptKind.PLATFORM,
                StartupPromptKind.DEPRECATED_JAVA,
                StartupPromptKind.INTERPRETED_JAVA,
                StartupPromptKind.SOFTWARE_RENDERING,
                StartupPromptKind.APRIL_FOOLS), presenter.presentedKinds);
        assertEquals(List.of(
                "read",
                "agreement:1",
                "cache:default",
                "platform:1",
                "java:17",
                "interpreted:suppress",
                "software:suppress",
                "april:2026"), gateway.events);
        assertTrue(completion.toCompletableFuture().isDone());
        assertFalse(completion.toCompletableFuture().isCompletedExceptionally());
        assertTrue(coordinator.agreementGate().toCompletableFuture().join());
        assertTrue(coordinator.isClosed());
    }

    /// Confirms that satisfied state and inactive signals suppress every corresponding prompt.
    @Test
    void skipsEveryPromptWhoseConditionIsAlreadySatisfied() {
        TestSchedulers schedulers = new TestSchedulers();
        StartupPromptSnapshot snapshot = new StartupPromptSnapshot(
                1,
                false,
                1,
                OptionalInt.of(17),
                true,
                true,
                OptionalInt.of(2026));
        RecordingGateway gateway = new RecordingGateway(schedulers, snapshot);
        RecordingPresenter presenter = defaultPresenter(schedulers);
        RecordingEffects effects = new RecordingEffects(schedulers);
        StartupPromptCoordinator coordinator = coordinator(
                schedulers,
                fullyEligibleEnvironment(StartupPlatformPrompt.WINDOWS_ARM64),
                gateway,
                presenter,
                effects);

        coordinator.start();
        schedulers.drain();

        assertEquals(List.of(), presenter.presentedKinds);
        assertEquals(List.of("read"), gateway.events);
        assertEquals(List.of(), effects.events);
    }

    /// Confirms that a supported classified platform is marked without inventing a visible prompt.
    @Test
    void marksSupportedPlatformSilently() {
        TestSchedulers schedulers = new TestSchedulers();
        StartupPromptSnapshot snapshot = new StartupPromptSnapshot(
                1,
                false,
                0,
                OptionalInt.of(17),
                true,
                true,
                OptionalInt.of(2026));
        RecordingGateway gateway = new RecordingGateway(schedulers, snapshot);
        RecordingPresenter presenter = defaultPresenter(schedulers);
        StartupPromptCoordinator coordinator = coordinator(
                schedulers,
                fullyEligibleEnvironment(StartupPlatformPrompt.MARK_SUPPORTED),
                gateway,
                presenter,
                new RecordingEffects(schedulers));

        coordinator.start();
        schedulers.drain();

        assertEquals(List.of(), presenter.presentedKinds);
        assertEquals(List.of("read", "platform:1"), gateway.events);
    }

    /// A failed cache restore never presents a false success notice and does not block later prompts.
    @Test
    void cacheRestoreFailureDoesNotClaimSuccessAndContinuesQueue() {
        TestSchedulers schedulers = new TestSchedulers();
        StartupPromptSnapshot snapshot = new StartupPromptSnapshot(
                1,
                true,
                0,
                OptionalInt.of(17),
                true,
                true,
                OptionalInt.of(2026));
        RecordingGateway gateway = new RecordingGateway(schedulers, snapshot);
        RuntimeException cacheFailure = new IllegalStateException("cache restore failed");
        gateway.failures.put("cache:default", cacheFailure);
        RecordingPresenter presenter = defaultPresenter(schedulers);
        RecordingEffects effects = new RecordingEffects(schedulers);
        StartupPromptCoordinator coordinator = coordinator(
                schedulers,
                fullyEligibleEnvironment(StartupPlatformPrompt.WINDOWS_ARM64),
                gateway,
                presenter,
                effects);

        CompletionStage<@Nullable Void> completion = coordinator.start();
        schedulers.drain();

        assertEquals(List.of(StartupPromptKind.PLATFORM), presenter.presentedKinds);
        assertEquals(List.of("read", "cache:default", "platform:1"), gateway.events);
        assertEquals(List.of(StartupPromptKind.INVALID_CACHE_DIRECTORY), effects.reportedKinds);
        assertTrue(completion.toCompletableFuture().isDone());
        assertFalse(completion.toCompletableFuture().isCompletedExceptionally());
        assertTrue(coordinator.isClosed());
    }

    /// Confirms that declining the agreement closes the application and terminates the queue.
    @Test
    void agreementDeclineClosesApplicationAndStopsLaterPrompts() {
        TestSchedulers schedulers = new TestSchedulers();
        RecordingGateway gateway = new RecordingGateway(schedulers, fullyEligibleSnapshot());
        RecordingPresenter presenter = new RecordingPresenter(
                schedulers,
                StartupPromptDecision.Agreement.DECLINE,
                StartupPromptDecision.Suppression.CONTINUE,
                StartupPromptDecision.Suppression.CONTINUE,
                StartupPromptDecision.AprilFools.KEEP_LANGUAGE);
        RecordingEffects effects = new RecordingEffects(schedulers);
        StartupPromptCoordinator coordinator = coordinator(
                schedulers,
                fullyEligibleEnvironment(StartupPlatformPrompt.OTHER_UNSUPPORTED),
                gateway,
                presenter,
                effects);

        coordinator.start();
        schedulers.drain();

        assertEquals(List.of(StartupPromptKind.AGREEMENT), presenter.presentedKinds);
        assertEquals(List.of("read"), gateway.events);
        assertEquals(List.of("close"), effects.events);
        assertFalse(coordinator.agreementGate().toCompletableFuture().join());
    }

    /// Rejecting the agreement-decision worker handoff fails the mandatory gate before persistence.
    @Test
    void agreementDecisionWorkerRejectionFailsGateBeforePersistence() {
        TestSchedulers schedulers = new TestSchedulers();
        RecordingGateway gateway = new RecordingGateway(schedulers, fullyEligibleSnapshot());
        RecordingPresenter presenter = defaultPresenter(schedulers);
        RecordingEffects effects = new RecordingEffects(schedulers);
        IllegalStateException rejection = new IllegalStateException("worker rejected decision");
        AtomicInteger submissions = new AtomicInteger();
        Executor rejectSecondSubmission = operation -> {
            if (submissions.incrementAndGet() > 1) {
                throw rejection;
            }
            schedulers.workerExecutor.execute(operation);
        };
        StartupPromptCoordinator coordinator = new StartupPromptCoordinator(
                fullyEligibleEnvironment(StartupPlatformPrompt.NONE),
                strings(),
                gateway,
                presenter,
                effects,
                schedulers.uiDispatcher,
                rejectSecondSubmission);

        CompletableFuture<@Nullable Void> completion = coordinator.start().toCompletableFuture();
        CompletableFuture<Boolean> agreementGate =
                coordinator.agreementGate().toCompletableFuture();
        schedulers.drain();

        CompletionException completionFailure =
                assertThrows(CompletionException.class, completion::join);
        CompletionException gateFailure =
                assertThrows(CompletionException.class, agreementGate::join);
        assertSame(rejection, completionFailure.getCause());
        assertSame(rejection, gateFailure.getCause());
        assertEquals(List.of("read"), gateway.events);
        assertEquals(List.of(), effects.events);
        assertTrue(coordinator.isClosed());
    }

    /// Confirms that optional suppression state changes only for explicit suppression decisions.
    @Test
    void suppressionDecisionsDoNotInventPersistedDefaults() {
        TestSchedulers schedulers = new TestSchedulers();
        StartupPromptSnapshot snapshot = new StartupPromptSnapshot(
                1,
                false,
                1,
                OptionalInt.of(17),
                false,
                false,
                OptionalInt.of(2026));
        RecordingGateway gateway = new RecordingGateway(schedulers, snapshot);
        RecordingPresenter presenter = new RecordingPresenter(
                schedulers,
                StartupPromptDecision.Agreement.ACCEPT,
                StartupPromptDecision.Suppression.CONTINUE,
                StartupPromptDecision.Suppression.DO_NOT_SHOW_AGAIN,
                StartupPromptDecision.AprilFools.KEEP_LANGUAGE);
        StartupPromptCoordinator coordinator = coordinator(
                schedulers,
                fullyEligibleEnvironment(StartupPlatformPrompt.NONE),
                gateway,
                presenter,
                new RecordingEffects(schedulers));

        coordinator.start();
        schedulers.drain();

        assertEquals(List.of(
                StartupPromptKind.INTERPRETED_JAVA,
                StartupPromptKind.SOFTWARE_RENDERING), presenter.presentedKinds);
        assertEquals(List.of("read", "software:suppress"), gateway.events);
    }

    /// A target-language failure cannot suppress the invitation or start terminal effects.
    @Test
    void aprilFoolsLanguageFailureDoesNotMarkOrRestart() {
        AprilFixture fixture = aprilFixture();
        RuntimeException languageFailure = new IllegalStateException("language failed");
        fixture.gateway().failures.put("language:lzh", languageFailure);

        CompletableFuture<@Nullable Void> completion =
                fixture.coordinator().start().toCompletableFuture();
        fixture.schedulers().drain();

        CompletionException thrown = assertThrows(CompletionException.class, completion::join);
        assertSame(languageFailure, thrown.getCause());
        assertEquals(List.of("read", "language:lzh"), fixture.gateway().events);
        assertEquals(List.of(), fixture.effects().events);
        assertEquals(List.of(StartupPromptKind.APRIL_FOOLS), fixture.effects().reportedKinds);
    }

    /// A save failure stops before waiting, restart, and close while preserving the exact failure.
    @Test
    void aprilFoolsSaveFailureStopsBeforeRestartAndClose() {
        AprilFixture fixture = aprilFixture();
        Exception saveFailure = new Exception("save failed");
        fixture.effects().failures.put("save", saveFailure);

        CompletableFuture<@Nullable Void> completion =
                fixture.coordinator().start().toCompletableFuture();
        fixture.schedulers().drain();

        CompletionException thrown = assertThrows(CompletionException.class, completion::join);
        assertSame(saveFailure, thrown.getCause());
        assertEquals(
                List.of("read", "language:lzh", "april:2026"),
                fixture.gateway().events);
        assertEquals(List.of("save"), fixture.effects().events);
        assertEquals(List.of(StartupPromptKind.APRIL_FOOLS), fixture.effects().reportedKinds);
    }

    /// A restart failure leaves the current application open instead of closing both processes.
    @Test
    void aprilFoolsRestartFailureLeavesCurrentApplicationOpen() {
        AprilFixture fixture = aprilFixture();
        Exception restartFailure = new Exception("restart failed");
        fixture.effects().failures.put("restart", restartFailure);

        CompletableFuture<@Nullable Void> completion =
                fixture.coordinator().start().toCompletableFuture();
        fixture.schedulers().drain();

        CompletionException thrown = assertThrows(CompletionException.class, completion::join);
        assertSame(restartFailure, thrown.getCause());
        assertEquals(List.of("save", "wait", "restart"), fixture.effects().events);
        assertEquals(List.of(StartupPromptKind.APRIL_FOOLS), fixture.effects().reportedKinds);
    }

    /// Successful acceptance persists state, restarts, and closes in strict order.
    @Test
    void aprilFoolsAcceptancePersistsRestartsAndClosesInOrder() {
        AprilFixture fixture = aprilFixture();

        CompletableFuture<@Nullable Void> completion =
                fixture.coordinator().start().toCompletableFuture();
        fixture.schedulers().drain();

        assertTrue(completion.isDone());
        assertFalse(completion.isCompletedExceptionally());
        assertEquals(
                List.of("read", "language:lzh", "april:2026"),
                fixture.gateway().events);
        assertEquals(List.of("save", "wait", "restart", "close"), fixture.effects().events);
        assertEquals(List.of(), fixture.effects().reportedKinds);
    }

    /// Confirms idempotent start and close before and after asynchronous processing.
    @Test
    void startAndCloseAreIdempotent() {
        TestSchedulers schedulers = new TestSchedulers();
        RecordingGateway gateway = new RecordingGateway(schedulers, satisfiedSnapshot());
        StartupPromptCoordinator coordinator = coordinator(
                schedulers,
                inactiveEnvironment(),
                gateway,
                defaultPresenter(schedulers),
                new RecordingEffects(schedulers));

        CompletionStage<@Nullable Void> first = coordinator.start();
        CompletionStage<@Nullable Void> second = coordinator.start();
        assertSame(first, second);
        schedulers.drain();
        coordinator.close();
        coordinator.close();
        coordinator.start();

        assertEquals(List.of("read"), gateway.events);
        assertTrue(coordinator.isStarted());
        assertTrue(coordinator.isClosed());

        RecordingGateway closedGateway = new RecordingGateway(schedulers, satisfiedSnapshot());
        StartupPromptCoordinator closedBeforeStart = coordinator(
                schedulers,
                inactiveEnvironment(),
                closedGateway,
                defaultPresenter(schedulers),
                new RecordingEffects(schedulers));
        closedBeforeStart.close();
        closedBeforeStart.close();
        closedBeforeStart.start();
        schedulers.drain();
        assertEquals(List.of(), closedGateway.events);
    }

    /// Confirms that worker and UI queues advance asynchronously without either waiting on the other.
    @Test
    void dispatchesStateAndEffectsOffUiWhilePresentingOnlyOnUi() {
        TestSchedulers schedulers = new TestSchedulers();
        RecordingGateway gateway = new RecordingGateway(schedulers, fullyEligibleSnapshot());
        RecordingPresenter presenter = defaultPresenter(schedulers);
        StartupPromptCoordinator coordinator = coordinator(
                schedulers,
                fullyEligibleEnvironment(StartupPlatformPrompt.WINDOWS_ARM64),
                gateway,
                presenter,
                new RecordingEffects(schedulers));

        coordinator.start();
        assertEquals(1, schedulers.workerQueueSize());
        assertEquals(0, schedulers.uiQueueSize());
        assertEquals(List.of(), gateway.events);

        schedulers.runNextWorker();
        assertEquals(List.of("read"), gateway.events);
        assertEquals(1, schedulers.uiQueueSize());
        assertEquals(List.of(), presenter.presentedKinds);

        schedulers.runNextUi();
        assertEquals(List.of(StartupPromptKind.AGREEMENT), presenter.presentedKinds);
        assertEquals(1, schedulers.workerQueueSize());
        assertEquals(List.of("read"), gateway.events);

        schedulers.drain();
        assertTrue(coordinator.isClosed());
    }

    /// Confirms that one non-gating presenter failure is reported and later prompts still run.
    @Test
    void isolatesNonGatingPresentationFailure() {
        TestSchedulers schedulers = new TestSchedulers();
        StartupPromptSnapshot snapshot = new StartupPromptSnapshot(
                1,
                false,
                0,
                OptionalInt.empty(),
                false,
                false,
                OptionalInt.empty());
        RecordingGateway gateway = new RecordingGateway(schedulers, snapshot);
        RecordingPresenter presenter = defaultPresenter(schedulers);
        RuntimeException platformFailure = new RuntimeException("platform failure");
        presenter.failures.put(StartupPromptKind.PLATFORM, platformFailure);
        RecordingEffects effects = new RecordingEffects(schedulers);
        StartupPromptCoordinator coordinator = coordinator(
                schedulers,
                fullyEligibleEnvironment(StartupPlatformPrompt.LOONGARCH),
                gateway,
                presenter,
                effects);

        coordinator.start();
        schedulers.drain();

        assertEquals(List.of(
                StartupPromptKind.PLATFORM,
                StartupPromptKind.DEPRECATED_JAVA,
                StartupPromptKind.INTERPRETED_JAVA,
                StartupPromptKind.SOFTWARE_RENDERING,
                StartupPromptKind.APRIL_FOOLS), presenter.presentedKinds);
        assertEquals(List.of(StartupPromptKind.PLATFORM), effects.reportedKinds);
        assertTrue(coordinator.isClosed());
    }

    /// Creates one coordinator bound to deterministic test schedulers.
    ///
    /// @param schedulers deterministic worker and UI queues
    /// @param environment explicit runtime signals
    /// @param gateway recording state gateway
    /// @param presenter recording presenter
    /// @param effects recording effects
    /// @return idle coordinator
    private static StartupPromptCoordinator coordinator(
            TestSchedulers schedulers,
            StartupPromptEnvironment environment,
            RecordingGateway gateway,
            RecordingPresenter presenter,
            RecordingEffects effects) {
        return new StartupPromptCoordinator(
                environment,
                strings(),
                gateway,
                presenter,
                effects,
                schedulers.uiDispatcher,
                schedulers.workerExecutor);
    }

    /// Returns a presenter with explicit non-mutating decisions.
    ///
    /// @param schedulers deterministic schedulers
    /// @return recording presenter
    private static RecordingPresenter defaultPresenter(TestSchedulers schedulers) {
        return new RecordingPresenter(
                schedulers,
                StartupPromptDecision.Agreement.ACCEPT,
                StartupPromptDecision.Suppression.CONTINUE,
                StartupPromptDecision.Suppression.CONTINUE,
                StartupPromptDecision.AprilFools.KEEP_LANGUAGE);
    }

    /// Creates a coordinator whose only eligible prompt accepts the April Fools language switch.
    ///
    /// @return deterministic restart-flow fixture
    private static AprilFixture aprilFixture() {
        TestSchedulers schedulers = new TestSchedulers();
        StartupPromptSnapshot snapshot = new StartupPromptSnapshot(
                1,
                false,
                1,
                OptionalInt.of(17),
                true,
                true,
                OptionalInt.empty());
        RecordingGateway gateway = new RecordingGateway(schedulers, snapshot);
        RecordingPresenter presenter = new RecordingPresenter(
                schedulers,
                StartupPromptDecision.Agreement.ACCEPT,
                StartupPromptDecision.Suppression.CONTINUE,
                StartupPromptDecision.Suppression.CONTINUE,
                StartupPromptDecision.AprilFools.SWITCH_LANGUAGE);
        RecordingEffects effects = new RecordingEffects(schedulers);
        StartupPromptCoordinator coordinator = coordinator(
                schedulers,
                fullyEligibleEnvironment(StartupPlatformPrompt.NONE),
                gateway,
                presenter,
                effects);
        return new AprilFixture(schedulers, gateway, effects, coordinator);
    }

    /// Returns state that activates every prompt condition.
    ///
    /// @return fully eligible state
    private static StartupPromptSnapshot fullyEligibleSnapshot() {
        return new StartupPromptSnapshot(
                0,
                true,
                0,
                OptionalInt.empty(),
                false,
                false,
                OptionalInt.empty());
    }

    /// Returns state that satisfies every persisted prompt condition.
    ///
    /// @return satisfied state
    private static StartupPromptSnapshot satisfiedSnapshot() {
        return new StartupPromptSnapshot(
                1,
                false,
                1,
                OptionalInt.of(17),
                true,
                true,
                OptionalInt.of(2026));
    }

    /// Returns explicit environment signals that activate all runtime-dependent prompts.
    ///
    /// @param platformPrompt platform classification under test
    /// @return fully eligible environment
    private static StartupPromptEnvironment fullyEligibleEnvironment(
            StartupPlatformPrompt platformPrompt) {
        return new StartupPromptEnvironment(
                1,
                1,
                platformPrompt,
                17,
                11,
                true,
                true,
                true,
                2026,
                true,
                Optional.of("lzh"));
    }

    /// Returns explicit inactive runtime signals without relying on coordinator defaults.
    ///
    /// @return inactive environment
    private static StartupPromptEnvironment inactiveEnvironment() {
        return new StartupPromptEnvironment(
                1,
                1,
                StartupPlatformPrompt.NONE,
                17,
                17,
                false,
                false,
                false,
                2026,
                false,
                Optional.empty());
    }

    /// Returns complete deterministic localized content for presenter tests.
    ///
    /// @return startup prompt strings
    private static StartupPromptStrings strings() {
        StartupPromptCopy agreementCopy = new StartupPromptCopy("Agreement", "Agreement body");
        StartupPromptStrings.Link agreementLink = new StartupPromptStrings.Link(
                "Agreement link",
                URI.create("https://example.invalid/agreement"));
        return new StartupPromptStrings(
                new StartupPromptStrings.Agreement(
                        agreementCopy,
                        agreementLink,
                        "Accept",
                        "Decline"),
                new StartupPromptCopy("Invalid cache", "Cache reset"),
                new StartupPromptStrings.Platform(
                        new StartupPromptCopy("Windows ARM64", "Windows ARM64 body"),
                        new StartupPromptCopy("LoongArch", "LoongArch body"),
                        new StartupPromptCopy("Unsupported", "Unsupported body")),
                new StartupPromptStrings.DeprecatedJava(
                        new StartupPromptCopy("Deprecated Java", "Deprecated Java body"),
                        Optional.empty()),
                new StartupPromptStrings.Suppression(
                        new StartupPromptCopy("Interpreted", "Interpreted body"),
                        new StartupPromptCopy("Software", "Software body"),
                        "Continue",
                        "Do not show again"),
                new StartupPromptStrings.AprilFools(
                        new StartupPromptCopy("April Fools", "April Fools invitation"),
                        new StartupPromptCopy("Confirm switch", "April Fools confirmation"),
                        10,
                        "Switch (%d)",
                        "Start switch",
                        "Keep initially",
                        "Confirm switch",
                        "Keep finally"),
                "OK");
    }

    /// Deterministic collaborators for one accepted April Fools language-switch flow.
    ///
    /// @param schedulers deterministic worker and UI queues
    /// @param gateway recording state gateway
    /// @param effects recording process effects
    /// @param coordinator coordinator under test
    @NotNullByDefault
    private record AprilFixture(
            TestSchedulers schedulers,
            RecordingGateway gateway,
            RecordingEffects effects,
            StartupPromptCoordinator coordinator) {
        /// Rejects incomplete fixtures before a test can start them.
        private AprilFixture {
            Objects.requireNonNull(schedulers, "schedulers");
            Objects.requireNonNull(gateway, "gateway");
            Objects.requireNonNull(effects, "effects");
            Objects.requireNonNull(coordinator, "coordinator");
        }
    }

    /// Deterministic two-queue scheduler used to prove non-blocking handoffs.
    @NotNullByDefault
    private static final class TestSchedulers {
        /// Worker execution queue.
        private final Queue<Runnable> workerQueue = new ArrayDeque<>();

        /// UI execution queue.
        private final Queue<Runnable> uiQueue = new ArrayDeque<>();

        /// Whether the current test action is executing on the worker queue.
        private boolean onWorker;

        /// Whether the current test action is executing on the UI queue.
        private boolean onUi;

        /// Worker executor exposed to the coordinator.
        private final Executor workerExecutor = new QueuedWorkerExecutor(this);

        /// UI dispatcher exposed to the coordinator.
        private final UiDispatcher uiDispatcher = new QueuedUiDispatcher(this);

        /// Runs queued work until both queues are empty.
        private void drain() {
            int remainingBudget = 1_000;
            while ((!workerQueue.isEmpty() || !uiQueue.isEmpty()) && remainingBudget-- > 0) {
                if (!workerQueue.isEmpty()) {
                    runNextWorker();
                } else {
                    runNextUi();
                }
            }
            if (remainingBudget <= 0) {
                throw new IllegalStateException("Prompt test scheduler did not become idle");
            }
        }

        /// Runs exactly one queued worker action.
        private void runNextWorker() {
            Runnable action = Objects.requireNonNull(workerQueue.poll(), "no worker action queued");
            assertFalse(onUi, "worker must not run inside UI dispatch");
            onWorker = true;
            try {
                action.run();
            } finally {
                onWorker = false;
            }
        }

        /// Runs exactly one queued UI action.
        private void runNextUi() {
            Runnable action = Objects.requireNonNull(uiQueue.poll(), "no UI action queued");
            assertFalse(onWorker, "UI must not run inside worker dispatch");
            onUi = true;
            try {
                action.run();
            } finally {
                onUi = false;
            }
        }

        /// Returns the number of queued worker actions.
        ///
        /// @return worker queue size
        private int workerQueueSize() {
            return workerQueue.size();
        }

        /// Returns the number of queued UI actions.
        ///
        /// @return UI queue size
        private int uiQueueSize() {
            return uiQueue.size();
        }

        /// Verifies that a state or effect operation runs only on the worker queue.
        private void requireWorker() {
            assertTrue(onWorker, "operation must run on worker queue");
            assertFalse(onUi, "worker operation must not run on UI queue");
        }

        /// Verifies that a presentation operation runs only on the UI queue.
        private void requireUi() {
            assertTrue(onUi, "presentation must run on UI queue");
            assertFalse(onWorker, "UI operation must not run on worker queue");
        }
    }

    /// Queues worker submissions without executing them inline.
    @NotNullByDefault
    private static final class QueuedWorkerExecutor implements Executor {
        /// Owning test scheduler.
        private final TestSchedulers schedulers;

        /// Creates a worker executor for one scheduler.
        ///
        /// @param schedulers owning scheduler
        private QueuedWorkerExecutor(TestSchedulers schedulers) {
            this.schedulers = schedulers;
        }

        /// Queues one worker action.
        ///
        /// @param command non-null action
        @Override
        public void execute(Runnable command) {
            schedulers.workerQueue.add(Objects.requireNonNull(command, "command"));
        }
    }

    /// Queues UI submissions without executing them inline.
    @NotNullByDefault
    private static final class QueuedUiDispatcher implements UiDispatcher {
        /// Owning test scheduler.
        private final TestSchedulers schedulers;

        /// Creates a UI dispatcher for one scheduler.
        ///
        /// @param schedulers owning scheduler
        private QueuedUiDispatcher(TestSchedulers schedulers) {
            this.schedulers = schedulers;
        }

        /// Returns whether the test is currently running a UI action.
        ///
        /// @return UI execution state
        @Override
        public boolean isDispatchThread() {
            return schedulers.onUi;
        }

        /// Queues one UI action.
        ///
        /// @param operation non-null action
        @Override
        public void dispatch(Runnable operation) {
            schedulers.uiQueue.add(Objects.requireNonNull(operation, "operation"));
        }
    }

    /// Records state access and verifies worker-thread ownership.
    @NotNullByDefault
    private static final class RecordingGateway implements StartupPromptStateGateway {
        /// Deterministic scheduler assertions.
        private final TestSchedulers schedulers;

        /// Immutable snapshot returned by every read.
        private final StartupPromptSnapshot snapshot;

        /// Ordered state operations.
        private final List<String> events = new ArrayList<>();

        /// Named state failures for focused tests.
        private final Map<String, RuntimeException> failures = new HashMap<>();

        /// Creates one recording gateway.
        ///
        /// @param schedulers scheduler assertions
        /// @param snapshot immutable state to return
        private RecordingGateway(TestSchedulers schedulers, StartupPromptSnapshot snapshot) {
            this.schedulers = schedulers;
            this.snapshot = snapshot;
        }

        /// Records and returns the configured snapshot.
        ///
        /// @return configured snapshot
        @Override
        public StartupPromptSnapshot readSnapshot() {
            record("read");
            return snapshot;
        }

        /// Records agreement acceptance.
        ///
        /// @param agreementVersion accepted version
        @Override
        public void acceptAgreement(int agreementVersion) {
            record("agreement:" + agreementVersion);
        }

        /// Records cache restoration.
        @Override
        public void restoreDefaultCacheDirectory() {
            record("cache:default");
        }

        /// Records platform acknowledgement.
        ///
        /// @param promptVersion acknowledged version
        @Override
        public void markPlatformPromptShown(int promptVersion) {
            record("platform:" + promptVersion);
        }

        /// Records deprecated-Java acknowledgement.
        ///
        /// @param minimumJavaVersion acknowledged Java version
        @Override
        public void markDeprecatedJavaPromptShown(int minimumJavaVersion) {
            record("java:" + minimumJavaVersion);
        }

        /// Records interpreted-warning suppression.
        @Override
        public void suppressInterpretedJavaWarning() {
            record("interpreted:suppress");
        }

        /// Records software-warning suppression.
        @Override
        public void suppressSoftwareRenderingWarning() {
            record("software:suppress");
        }

        /// Records yearly April Fools resolution.
        ///
        /// @param year resolved year
        @Override
        public void markAprilFoolsShown(int year) {
            record("april:" + year);
        }

        /// Records language selection.
        ///
        /// @param languageId target language identifier
        @Override
        public void selectLanguage(String languageId) {
            record("language:" + languageId);
        }

        /// Records one worker operation and raises its configured failure after observation.
        ///
        /// @param event stable event name
        private void record(String event) {
            schedulers.requireWorker();
            events.add(event);
            @Nullable RuntimeException failure = failures.get(event);
            if (failure != null) {
                throw failure;
            }
        }
    }

    /// Records typed presenter invocations and supplies explicit decisions.
    @NotNullByDefault
    private static final class RecordingPresenter implements StartupPromptPresenter {
        /// Deterministic scheduler assertions.
        private final TestSchedulers schedulers;

        /// Agreement decision supplied by the test.
        private final StartupPromptDecision.Agreement agreementDecision;

        /// Interpreted-mode decision supplied by the test.
        private final StartupPromptDecision.Suppression interpretedDecision;

        /// Software-rendering decision supplied by the test.
        private final StartupPromptDecision.Suppression softwareDecision;

        /// April Fools decision supplied by the test.
        private final StartupPromptDecision.AprilFools aprilFoolsDecision;

        /// Ordered prompt presentations.
        private final List<StartupPromptKind> presentedKinds = new ArrayList<>();

        /// Prompt-specific presentation failures.
        private final Map<StartupPromptKind, RuntimeException> failures =
                new EnumMap<>(StartupPromptKind.class);

        /// Creates a presenter with an explicit decision for every branching prompt.
        ///
        /// @param schedulers scheduler assertions
        /// @param agreementDecision agreement decision
        /// @param interpretedDecision interpreted-mode decision
        /// @param softwareDecision software-rendering decision
        /// @param aprilFoolsDecision April Fools decision
        private RecordingPresenter(
                TestSchedulers schedulers,
                StartupPromptDecision.Agreement agreementDecision,
                StartupPromptDecision.Suppression interpretedDecision,
                StartupPromptDecision.Suppression softwareDecision,
                StartupPromptDecision.AprilFools aprilFoolsDecision) {
            this.schedulers = schedulers;
            this.agreementDecision = agreementDecision;
            this.interpretedDecision = interpretedDecision;
            this.softwareDecision = softwareDecision;
            this.aprilFoolsDecision = aprilFoolsDecision;
        }

        /// Records agreement presentation.
        ///
        /// @param strings localized agreement strings
        /// @return configured decision or failure
        @Override
        public CompletionStage<StartupPromptDecision.Agreement> presentAgreement(
                StartupPromptStrings.Agreement strings) {
            return result(StartupPromptKind.AGREEMENT, agreementDecision);
        }

        /// Records invalid-cache presentation.
        ///
        /// @param copy localized copy
        /// @param acknowledgeLabel localized acknowledgement label
        /// @return acknowledgement or failure
        @Override
        public CompletionStage<StartupPromptDecision.Acknowledgement> presentInvalidCacheDirectory(
                StartupPromptCopy copy,
                String acknowledgeLabel) {
            return result(
                    StartupPromptKind.INVALID_CACHE_DIRECTORY,
                    StartupPromptDecision.Acknowledgement.ACKNOWLEDGE);
        }

        /// Records platform presentation.
        ///
        /// @param platformPrompt platform classification
        /// @param copy localized copy
        /// @param acknowledgeLabel localized acknowledgement label
        /// @return acknowledgement or failure
        @Override
        public CompletionStage<StartupPromptDecision.Acknowledgement> presentPlatform(
                StartupPlatformPrompt platformPrompt,
                StartupPromptCopy copy,
                String acknowledgeLabel) {
            return result(
                    StartupPromptKind.PLATFORM,
                    StartupPromptDecision.Acknowledgement.ACKNOWLEDGE);
        }

        /// Records deprecated-Java presentation.
        ///
        /// @param currentJavaVersion current Java version
        /// @param minimumJavaVersion minimum Java version
        /// @param strings localized warning strings
        /// @param acknowledgeLabel localized acknowledgement label
        /// @return acknowledgement or failure
        @Override
        public CompletionStage<StartupPromptDecision.Acknowledgement> presentDeprecatedJava(
                int currentJavaVersion,
                int minimumJavaVersion,
                StartupPromptStrings.DeprecatedJava strings,
                String acknowledgeLabel) {
            return result(
                    StartupPromptKind.DEPRECATED_JAVA,
                    StartupPromptDecision.Acknowledgement.ACKNOWLEDGE);
        }

        /// Records interpreted-mode presentation.
        ///
        /// @param strings localized warning strings
        /// @return configured decision or failure
        @Override
        public CompletionStage<StartupPromptDecision.Suppression> presentInterpretedJava(
                StartupPromptStrings.Suppression strings) {
            return result(StartupPromptKind.INTERPRETED_JAVA, interpretedDecision);
        }

        /// Records software-rendering presentation.
        ///
        /// @param strings localized warning strings
        /// @return configured decision or failure
        @Override
        public CompletionStage<StartupPromptDecision.Suppression> presentSoftwareRendering(
                StartupPromptStrings.Suppression strings) {
            return result(StartupPromptKind.SOFTWARE_RENDERING, softwareDecision);
        }

        /// Records April Fools presentation.
        ///
        /// @param targetLanguageId target language identifier
        /// @param strings localized invitation strings
        /// @return configured decision or failure
        @Override
        public CompletionStage<StartupPromptDecision.AprilFools> presentAprilFools(
                String targetLanguageId,
                StartupPromptStrings.AprilFools strings) {
            return result(StartupPromptKind.APRIL_FOOLS, aprilFoolsDecision);
        }

        /// Records one UI presentation and returns its decision or configured failure.
        ///
        /// @param promptKind presented prompt
        /// @param decision configured typed decision
        /// @param <D> decision type
        /// @return completed or failed stage
        private <D extends StartupPromptDecision> CompletionStage<D> result(
                StartupPromptKind promptKind,
                D decision) {
            schedulers.requireUi();
            presentedKinds.add(promptKind);
            @Nullable RuntimeException failure = failures.get(promptKind);
            if (failure != null) {
                return CompletableFuture.failedFuture(failure);
            }
            return CompletableFuture.completedFuture(decision);
        }
    }

    /// Records process effects and verifies worker-thread ownership.
    @NotNullByDefault
    private static final class RecordingEffects implements StartupPromptEffects {
        /// Deterministic scheduler assertions.
        private final TestSchedulers schedulers;

        /// Ordered process effects.
        private final List<String> events = new ArrayList<>();

        /// Prompt kinds reported as failed.
        private final List<StartupPromptKind> reportedKinds = new ArrayList<>();

        /// Named checked failures for terminal operations.
        private final Map<String, Exception> failures = new HashMap<>();

        /// Optional close callback used to simulate reentrant owner cleanup.
        private @Nullable Runnable closeCallback;

        /// Creates effects bound to one scheduler.
        ///
        /// @param schedulers scheduler assertions
        private RecordingEffects(TestSchedulers schedulers) {
            this.schedulers = schedulers;
        }

        /// Records save dispatch.
        ///
        /// @throws Exception configured save failure
        @Override
        public void saveBeforeRestart() throws Exception {
            perform("save");
        }

        /// Records waiting for saves.
        ///
        /// @throws Exception configured wait failure
        @Override
        public void waitForPendingSaves() throws Exception {
            perform("wait");
        }

        /// Records restart dispatch.
        ///
        /// @throws Exception configured restart failure
        @Override
        public void restartApplication() throws Exception {
            perform("restart");
        }

        /// Records application close dispatch.
        ///
        /// @throws Exception configured close failure
        @Override
        public void closeApplication() throws Exception {
            perform("close");
        }

        /// Records a failure report.
        ///
        /// @param promptKind owning prompt
        /// @param failure exact operation failure
        @Override
        public void reportFailure(StartupPromptKind promptKind, Throwable failure) {
            schedulers.requireWorker();
            reportedKinds.add(promptKind);
        }

        /// Records one terminal action and raises its configured checked failure.
        ///
        /// @param event stable effect name
        /// @throws Exception configured failure
        private void perform(String event) throws Exception {
            schedulers.requireWorker();
            events.add(event);
            if ("close".equals(event) && closeCallback != null) {
                closeCallback.run();
            }
            @Nullable Exception failure = failures.get(event);
            if (failure != null) {
                throw failure;
            }
        }
    }
}
