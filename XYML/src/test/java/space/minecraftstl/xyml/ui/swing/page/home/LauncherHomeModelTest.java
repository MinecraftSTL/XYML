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
package space.minecraftstl.xyml.ui.swing.page.home;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import space.minecraftstl.xyml.game.launch.DefaultGameLaunchService;
import space.minecraftstl.xyml.game.launch.LaunchRequest;
import space.minecraftstl.xyml.game.launch.LaunchSession;
import space.minecraftstl.xyml.game.launch.LaunchStatus;
import space.minecraftstl.xyml.observable.Subscription;
import space.minecraftstl.xyml.observable.ValueChangeListener;
import space.minecraftstl.xyml.observable.ValueChangeSupport;
import space.minecraftstl.xyml.observable.property.ReadOnlyProperty;
import space.minecraftstl.xyml.task.presentation.TaskSnapshot;
import space.minecraftstl.xyml.util.platform.ManagedProcess;

import java.util.ArrayList;
import java.util.List;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests launch readiness mapping, command gating, and selection subscription ownership.
@NotNullByDefault
public final class LauncherHomeModelTest {
    /// Localized readiness strings used by every focused model test.
    private static final HomeStatusStrings STATUS_STRINGS =
            new HomeStatusStrings(
                    "Ready",
                    "Choose an account",
                    "Choose an instance",
                    "Exporting launch script");

    /// Missing selections map deterministically, with the account requirement taking precedence.
    @Test
    public void mapsMissingSelectionsAndReadyTransition() {
        FakeSelectionStore store = new FakeSelectionStore(
                new HomeSelectionState("", "directory-a", "", "", "", "", ""));
        AtomicReference<HomeSnapshot> published = new AtomicReference<>();
        LauncherHomeModel model = createModel(store, new AtomicInteger(), new AtomicInteger(),
                new AtomicInteger(), new AtomicInteger());
        Subscription subscription = model.subscribe(change -> published.set(change.currentValue()));

        HomeSnapshot missingAccount = model.snapshot();
        store.publish(new HomeSelectionState(
                "account-a", "directory-a", "", "Alex", "microsoft", "", ""));
        HomeSnapshot missingInstance = model.snapshot();
        store.publish(new HomeSelectionState(
                "account-a", "directory-a", "instance-a",
                "Alex", "microsoft", "1.21.1", "Default directory"));
        HomeSnapshot ready = model.snapshot();

        assertAll(
                () -> assertEquals("Choose an account", missingAccount.statusText()),
                () -> assertFalse(missingAccount.launchEnabled()),
                () -> assertEquals("Choose an instance", missingInstance.statusText()),
                () -> assertFalse(missingInstance.launchEnabled()),
                () -> assertEquals("Ready", ready.statusText()),
                () -> assertTrue(ready.launchEnabled()),
                () -> assertEquals("Alex", ready.accountName()),
                () -> assertEquals("1.21.1", ready.instanceName()),
                () -> assertEquals(ready, published.get()));

        subscription.unsubscribe();
        model.close();
    }

    /// Stable IDs enable launch even when optional display names are unavailable.
    @Test
    public void basesReadinessOnStableIdentityInsteadOfDisplayText() {
        FakeSelectionStore store = new FakeSelectionStore(
                new HomeSelectionState("", "", "", "Alex", "microsoft", "1.21.1", "Games"));
        AtomicInteger launches = new AtomicInteger();
        LauncherHomeModel model = createModel(
                store, new AtomicInteger(), new AtomicInteger(), new AtomicInteger(), launches);

        HomeSnapshot presentationOnly = model.snapshot();
        model.launch();
        store.publish(new HomeSelectionState(
                "account-a", "directory-a", "instance-a", "", "microsoft", "", "Games"));
        HomeSnapshot identityReady = model.snapshot();
        model.launch();

        assertAll(
                () -> assertEquals("Choose an account", presentationOnly.statusText()),
                () -> assertFalse(presentationOnly.launchEnabled()),
                () -> assertEquals("Ready", identityReady.statusText()),
                () -> assertTrue(identityReady.launchEnabled()),
                () -> assertEquals("", identityReady.accountName()),
                () -> assertEquals("", identityReady.instanceName()),
                () -> assertEquals(1, launches.get()));
        model.close();
    }

    /// Navigation commands always delegate, while launch delegates only after both selections exist.
    @Test
    public void delegatesCommandsAndGatesLaunch() {
        FakeSelectionStore store = new FakeSelectionStore(
                new HomeSelectionState("", "directory-a", "", "", "", "", ""));
        AtomicInteger accountSelections = new AtomicInteger();
        AtomicInteger instanceSelections = new AtomicInteger();
        AtomicInteger instanceAdditions = new AtomicInteger();
        AtomicInteger launches = new AtomicInteger();
        LauncherHomeModel model = createModel(
                store, accountSelections, instanceSelections, instanceAdditions, launches);

        model.selectAccount();
        model.selectInstance();
        model.addInstance();
        model.launch();
        store.publish(new HomeSelectionState(
                "account-a", "directory-a", "instance-a", "Steve", "offline", "1.20.1", "Games"));
        model.launch();

        assertAll(
                () -> assertEquals(1, accountSelections.get()),
                () -> assertEquals(1, instanceSelections.get()),
                () -> assertEquals(1, instanceAdditions.get()),
                () -> assertEquals(1, launches.get()));
        model.close();
    }

    /// Script export captures the same immutable selection as launch and gates later launch preparation until terminal.
    @Test
    public void exportsScriptWithoutRacingLaunchPreparation() {
        FakeSelectionStore store = new FakeSelectionStore(new HomeSelectionState(
                "account-a", "directory-a", "instance-a", "Alex", "microsoft", "1.21.1", "Games"));
        HomeStatusStrings strings = new HomeStatusStrings(
                "Ready", "Choose an account", "Choose an instance", "Exporting launch script");
        AtomicReference<@Nullable LaunchRequest> exportedRequest = new AtomicReference<>();
        AtomicReference<@Nullable Path> exportedPath = new AtomicReference<>();
        AtomicInteger launchCalls = new AtomicInteger();
        CompletableFuture<Path> exportCompletion = new CompletableFuture<>();
        LauncherHomeModel model = new LauncherHomeModel(
                store,
                strings,
                () -> { },
                () -> { },
                () -> { },
                request -> {
                    launchCalls.incrementAndGet();
                    throw new AssertionError("launch must remain gated while script export is pending");
                },
                (request, scriptFile) -> {
                    exportedRequest.set(request);
                    exportedPath.set(scriptFile);
                    return exportCompletion;
                });
        Path target = Path.of("build", "launcher-home-test.bat").toAbsolutePath().normalize();
        try {
            CompletionStage<Path> stage = model.exportLaunchScript(target);
            HomeSnapshot exporting = model.snapshot();
            model.launch();

            assertAll(
                    () -> assertEquals(
                            new LaunchRequest("account-a", "directory-a", "instance-a"),
                            exportedRequest.get()),
                    () -> assertEquals(target, exportedPath.get()),
                    () -> assertEquals("Exporting launch script", exporting.statusText()),
                    () -> assertFalse(exporting.launching()),
                    () -> assertFalse(exporting.launchEnabled()),
                    () -> assertFalse(exporting.selectionCommandsEnabled()),
                    () -> assertEquals(0, launchCalls.get()));

            exportCompletion.complete(target);
            assertEquals(target, stage.toCompletableFuture().join());
            assertAll(
                    () -> assertEquals("Ready", model.snapshot().statusText()),
                    () -> assertTrue(model.snapshot().launchEnabled()),
                    () -> assertTrue(model.snapshot().selectionCommandsEnabled()));
        } finally {
            model.close();
        }
    }

    /// Launch captures all stable IDs together, gates concurrent commands, and restores readiness after cancellation.
    @Test
    public void capturesStableRequestAndTracksPreparingSession() {
        HomeSelectionState firstSelection = new HomeSelectionState(
                "account-a", "directory-a", "instance-a", "Alex", "microsoft", "1.21.1", "Games");
        HomeSelectionState secondSelection = new HomeSelectionState(
                "account-b", "directory-b", "instance-b", "Steve", "offline", "1.20.1", "Other");
        FakeSelectionStore store = new FakeSelectionStore(firstSelection);
        AtomicReference<@Nullable LaunchRequest> capturedRequest = new AtomicReference<>();
        AtomicInteger launchCalls = new AtomicInteger();
        AtomicInteger accountSelections = new AtomicInteger();
        AtomicInteger instanceSelections = new AtomicInteger();
        AtomicInteger instanceAdditions = new AtomicInteger();
        DefaultGameLaunchService launchService = pendingLaunchService();
        LauncherHomeModel model = new LauncherHomeModel(
                store,
                STATUS_STRINGS,
                accountSelections::incrementAndGet,
                instanceSelections::incrementAndGet,
                instanceAdditions::incrementAndGet,
                request -> {
                    capturedRequest.set(request);
                    launchCalls.incrementAndGet();
                    return launchService.launch(request);
                },
                HomeLaunchScriptExportCommand.unavailable());
        try {
            model.launch();
            LaunchSession firstSession = model.launchSessionProperty().getValue().orElseThrow();
            HomeSnapshot preparing = model.snapshot();
            store.publish(secondSelection);
            model.selectAccount();
            model.selectInstance();
            model.addInstance();
            model.launch();

            assertAll(
                    () -> assertEquals(
                            new LaunchRequest("account-a", "directory-a", "instance-a"),
                            capturedRequest.get()),
                    () -> assertEquals(1, launchCalls.get()),
                    () -> assertTrue(preparing.launching()),
                    () -> assertFalse(preparing.launchEnabled()),
                    () -> assertFalse(preparing.selectionCommandsEnabled()),
                    () -> assertEquals(0, accountSelections.get()),
                    () -> assertEquals(0, instanceSelections.get()),
                    () -> assertEquals(0, instanceAdditions.get()));

            assertTrue(firstSession.cancel());
            assertAll(
                    () -> assertEquals(LaunchStatus.CANCELLED, firstSession.status()),
                    () -> assertFalse(model.snapshot().launching()),
                    () -> assertTrue(model.snapshot().launchEnabled()),
                    () -> assertTrue(model.snapshot().selectionCommandsEnabled()));

            model.launch();
            assertAll(
                    () -> assertEquals(
                            new LaunchRequest("account-b", "directory-b", "instance-b"),
                            capturedRequest.get()),
                    () -> assertEquals(2, launchCalls.get()));
        } finally {
            model.close();
            launchService.close();
        }
    }

    /// Terminal state is still published when status-listener cleanup throws its contracted runtime failure.
    @Test
    public void publishesTerminalSnapshotWhenStatusUnsubscribeFails() {
        FakeSelectionStore store = new FakeSelectionStore(new HomeSelectionState(
                "account-a", "directory-a", "instance-a", "Alex", "microsoft", "1.21.1", "Games"));
        DefaultGameLaunchService launchService = pendingLaunchService();
        IllegalStateException unsubscribeFailure = new IllegalStateException("status unsubscribe failed");
        LauncherHomeModel model = new LauncherHomeModel(
                store,
                STATUS_STRINGS,
                () -> { },
                () -> { },
                () -> { },
                request -> new ThrowingUnsubscribeLaunchSession(
                        launchService.launch(request),
                        unsubscribeFailure),
                HomeLaunchScriptExportCommand.unavailable());
        AtomicReference<HomeSnapshot> latestPublished = new AtomicReference<>(model.snapshot());
        Subscription homeSubscription = model.subscribe(
                change -> latestPublished.set(change.currentValue()));
        try {
            model.launch();
            LaunchSession session = model.launchSessionProperty().getValue().orElseThrow();

            assertTrue(session.cancel());

            assertAll(
                    () -> assertEquals(LaunchStatus.CANCELLED, session.status()),
                    () -> assertFalse(model.snapshot().launching()),
                    () -> assertTrue(model.snapshot().launchEnabled()),
                    () -> assertTrue(model.snapshot().selectionCommandsEnabled()),
                    () -> assertEquals(model.snapshot(), latestPublished.get()));
        } finally {
            homeSubscription.unsubscribe();
            model.close();
            launchService.close();
        }
    }

    /// A session that fails before status subscription is reconciled immediately instead of leaving launch disabled.
    @Test
    public void reconcilesSessionAlreadyTerminalWhenCommandReturns() {
        FakeSelectionStore store = new FakeSelectionStore(new HomeSelectionState(
                "account-a", "directory-a", "instance-a", "Alex", "microsoft", "1.21.1", "Games"));
        RejectedExecutionException rejection = new RejectedExecutionException("test launch rejection");
        DefaultGameLaunchService launchService = new DefaultGameLaunchService(
                request -> {
                    throw new AssertionError("rejected scheduling must not invoke the task factory");
                },
                command -> {
                    throw rejection;
                },
                "Test launch",
                "Waiting for test launch");
        LauncherHomeModel model = new LauncherHomeModel(
                store,
                STATUS_STRINGS,
                () -> { },
                () -> { },
                () -> { },
                launchService::launch,
                HomeLaunchScriptExportCommand.unavailable());
        try {
            model.launch();
            LaunchSession session = model.launchSessionProperty().getValue().orElseThrow();

            assertAll(
                    () -> assertEquals(LaunchStatus.FAILED, session.status()),
                    () -> assertSame(rejection, session.failure().orElseThrow()),
                    () -> assertFalse(model.snapshot().launching()),
                    () -> assertTrue(model.snapshot().launchEnabled()),
                    () -> assertTrue(model.snapshot().selectionCommandsEnabled()),
                    () -> assertTrue(launchService.activePreparation().isEmpty()));
        } finally {
            model.close();
            launchService.close();
        }
    }

    /// A launch-command exception clears the pending gate and preserves the original failure identity.
    @Test
    public void rollsBackPendingStateWhenLaunchCommandFails() {
        FakeSelectionStore store = new FakeSelectionStore(new HomeSelectionState(
                "account-a", "directory-a", "instance-a", "Alex", "microsoft", "1.21.1", "Games"));
        IllegalStateException failure = new IllegalStateException("test launch command failure");
        AtomicInteger launchCalls = new AtomicInteger();
        LauncherHomeModel model = new LauncherHomeModel(
                store,
                STATUS_STRINGS,
                () -> { },
                () -> { },
                () -> { },
                request -> {
                    launchCalls.incrementAndGet();
                    throw failure;
                },
                HomeLaunchScriptExportCommand.unavailable());
        try {
            assertSame(failure, assertThrows(IllegalStateException.class, model::launch));
            assertAll(
                    () -> assertFalse(model.snapshot().launching()),
                    () -> assertTrue(model.snapshot().launchEnabled()),
                    () -> assertTrue(model.snapshot().selectionCommandsEnabled()));

            assertSame(failure, assertThrows(IllegalStateException.class, model::launch));
            assertEquals(2, launchCalls.get());
        } finally {
            model.close();
        }
    }

    /// Runtime failures from earlier observers do not block later home or launch-session observers.
    @Test
    public void isolatesRuntimeFailuresAcrossObservableRegistrations() {
        FakeSelectionStore store = new FakeSelectionStore(new HomeSelectionState(
                "account-a", "directory-a", "instance-a", "Alex", "microsoft", "1.21.1", "Games"));
        DefaultGameLaunchService launchService = pendingLaunchService();
        LauncherHomeModel model = new LauncherHomeModel(
                store,
                STATUS_STRINGS,
                () -> { },
                () -> { },
                () -> { },
                launchService::launch,
                HomeLaunchScriptExportCommand.unavailable());
        AtomicInteger homeDeliveries = new AtomicInteger();
        AtomicReference<@Nullable LaunchSession> observedSession = new AtomicReference<>();
        AtomicInteger reportedFailures = new AtomicInteger();
        Thread currentThread = Thread.currentThread();
        @Nullable Thread.UncaughtExceptionHandler previousHandler = currentThread.getUncaughtExceptionHandler();
        currentThread.setUncaughtExceptionHandler((thread, failure) -> reportedFailures.incrementAndGet());
        Subscription failingHomeSubscription = model.subscribe(change -> {
            throw new IllegalStateException("test home observer failure");
        });
        Subscription recordingHomeSubscription = model.subscribe(change -> homeDeliveries.incrementAndGet());
        Subscription failingSessionSubscription = model.launchSessionProperty().subscribe(change -> {
            throw new IllegalStateException("test launch-session observer failure");
        });
        Subscription recordingSessionSubscription = model.launchSessionProperty().subscribe(
                change -> observedSession.set(change.currentValue().orElse(null)));
        try {
            model.launch();

            assertAll(
                    () -> assertEquals(1, homeDeliveries.get()),
                    () -> assertSame(
                            model.launchSessionProperty().getValue().orElseThrow(),
                            observedSession.get()),
                    () -> assertEquals(2, reportedFailures.get()));
        } finally {
            recordingSessionSubscription.unsubscribe();
            failingSessionSubscription.unsubscribe();
            recordingHomeSubscription.unsubscribe();
            failingHomeSubscription.unsubscribe();
            model.close();
            launchService.close();
            currentThread.setUncaughtExceptionHandler(previousHandler);
        }
    }

    /// Distinct sessions replace the task property even when their value equality deliberately reports true.
    @Test
    public void publishesDistinctSessionsByIdentityInsteadOfValueEquality() {
        FakeSelectionStore store = new FakeSelectionStore(new HomeSelectionState(
                "account-a", "directory-a", "instance-a", "Alex", "microsoft", "1.21.1", "Games"));
        DefaultGameLaunchService firstService = pendingLaunchService();
        DefaultGameLaunchService secondService = pendingLaunchService();
        AtomicInteger launchCalls = new AtomicInteger();
        LauncherHomeModel model = new LauncherHomeModel(
                store,
                STATUS_STRINGS,
                () -> { },
                () -> { },
                () -> { },
                request -> {
                    DefaultGameLaunchService service = launchCalls.getAndIncrement() == 0
                            ? firstService
                            : secondService;
                    return new EqualLaunchSession(service.launch(request));
                },
                HomeLaunchScriptExportCommand.unavailable());
        List<LaunchSession> observedSessions = new ArrayList<>();
        Subscription subscription = model.launchSessionProperty().subscribe(
                change -> observedSessions.add(change.currentValue().orElseThrow()));
        try {
            model.launch();
            LaunchSession firstSession = model.launchSessionProperty().getValue().orElseThrow();
            assertTrue(firstSession.cancel());
            model.launch();
            LaunchSession secondSession = model.launchSessionProperty().getValue().orElseThrow();

            assertAll(
                    () -> assertNotSame(firstSession, secondSession),
                    () -> assertEquals(firstSession, secondSession),
                    () -> assertSame(secondSession, model.launchSessionProperty().getValue().orElseThrow()),
                    () -> assertEquals(2, observedSessions.size()),
                    () -> assertSame(firstSession, observedSessions.get(0)),
                    () -> assertSame(secondSession, observedSessions.get(1)));
        } finally {
            subscription.unsubscribe();
            model.close();
            firstService.close();
            secondService.close();
        }
    }

    /// Closing removes the owned store registration and rejects every future command or subscription.
    @Test
    public void closesSubscriptionAndRejectsFurtherUse() {
        FakeSelectionStore store = new FakeSelectionStore(
                new HomeSelectionState(
                        "account-a", "directory-a", "instance-a",
                        "Alex", "microsoft", "1.21.1", "Games"));
        AtomicInteger launches = new AtomicInteger();
        LauncherHomeModel model = createModel(
                store, new AtomicInteger(), new AtomicInteger(), new AtomicInteger(), launches);
        HomeSnapshot beforeClose = model.snapshot();

        model.close();
        model.close();
        store.publish(new HomeSelectionState(
                "account-b", "directory-b", "instance-b", "Steve", "offline", "1.20.1", "Other"));

        assertAll(
                () -> assertFalse(store.hasSubscribers()),
                () -> assertEquals(beforeClose, model.snapshot()),
                () -> assertThrows(IllegalStateException.class, model::selectAccount),
                () -> assertThrows(IllegalStateException.class, model::selectInstance),
                () -> assertThrows(IllegalStateException.class, model::addInstance),
                () -> assertThrows(IllegalStateException.class, model::launch),
                () -> assertThrows(IllegalStateException.class, () -> model.subscribe(change -> { })),
                () -> assertEquals(0, launches.get()));
    }

    /// A transition occurring between the initial snapshot and registration is recovered by post-subscribe reconciliation.
    @Test
    public void reconcilesTransitionDuringSubscription() {
        FakeSelectionStore store = new FakeSelectionStore(
                new HomeSelectionState("", "directory-a", "", "", "", "", ""));
        HomeSelectionState readySelection =
                new HomeSelectionState(
                        "account-a", "directory-a", "instance-a",
                        "Alex", "microsoft", "1.21.1", "Games");
        store.transitionBeforeNextSubscription(readySelection);

        LauncherHomeModel model = createModel(store, new AtomicInteger(), new AtomicInteger(),
                new AtomicInteger(), new AtomicInteger());

        assertAll(
                () -> assertEquals("Alex", model.snapshot().accountName()),
                () -> assertEquals("1.21.1", model.snapshot().instanceName()),
                () -> assertTrue(model.snapshot().launchEnabled()));
        model.close();
    }

    /// A later store event wins when post-subscribe reconciliation is paused after reading an older snapshot.
    @Test
    @Timeout(10)
    public void reconciliationCannotOverwriteLaterPublishedSelection() throws Exception {
        HomeSelectionState earlierSelection = new HomeSelectionState(
                "account-a", "directory-a", "instance-a", "Alex", "microsoft", "1.21.1", "Games");
        HomeSelectionState laterSelection = new HomeSelectionState(
                "account-b", "directory-b", "instance-b", "Steve", "offline", "1.20.1", "Other");
        FakeSelectionStore store = new FakeSelectionStore(earlierSelection);
        CountDownLatch reconciliationRead = new CountDownLatch(1);
        CountDownLatch reconciliationRelease = new CountDownLatch(1);
        CountDownLatch laterSelectionStored = new CountDownLatch(1);
        AtomicReference<@Nullable LaunchRequest> capturedRequest = new AtomicReference<>();
        DefaultGameLaunchService launchService = pendingLaunchService();
        store.blockSnapshotRead(2, reconciliationRead, reconciliationRelease);
        store.signalAfterNextStore(laterSelectionStored);
        ExecutorService constructorExecutor = Executors.newSingleThreadExecutor();
        ExecutorService publisherExecutor = Executors.newSingleThreadExecutor();
        try {
            Future<LauncherHomeModel> modelFuture = constructorExecutor.submit(() -> new LauncherHomeModel(
                    store,
                    STATUS_STRINGS,
                    () -> { },
                    () -> { },
                    () -> { },
                    request -> {
                        capturedRequest.set(request);
                        return launchService.launch(request);
                    },
                    HomeLaunchScriptExportCommand.unavailable()));
            assertTrue(reconciliationRead.await(5, TimeUnit.SECONDS));
            Future<?> publication = publisherExecutor.submit(() -> store.publish(laterSelection));
            assertTrue(laterSelectionStored.await(5, TimeUnit.SECONDS));
            reconciliationRelease.countDown();

            LauncherHomeModel model = modelFuture.get(5, TimeUnit.SECONDS);
            publication.get(5, TimeUnit.SECONDS);
            try {
                model.launch();
                assertAll(
                        () -> assertEquals("Steve", model.snapshot().accountName()),
                        () -> assertEquals("1.20.1", model.snapshot().instanceName()),
                        () -> assertEquals(
                                new LaunchRequest("account-b", "directory-b", "instance-b"),
                                capturedRequest.get()));
            } finally {
                model.close();
            }
        } finally {
            reconciliationRelease.countDown();
            launchService.close();
            constructorExecutor.shutdownNow();
            publisherExecutor.shutdownNow();
        }
    }

    /// Creates a model whose commands increment the supplied counters.
    ///
    /// @param store fake selection source
    /// @param accountSelections account-navigation count
    /// @param instanceSelections instance-navigation count
    /// @param instanceAdditions add-instance count
    /// @param launches launch count
    /// @return configured home model
    private static LauncherHomeModel createModel(
            FakeSelectionStore store,
            AtomicInteger accountSelections,
            AtomicInteger instanceSelections,
            AtomicInteger instanceAdditions,
            AtomicInteger launches) {
        DefaultGameLaunchService launchService = pendingLaunchService();
        return new LauncherHomeModel(
                store,
                STATUS_STRINGS,
                accountSelections::incrementAndGet,
                instanceSelections::incrementAndGet,
                instanceAdditions::incrementAndGet,
                request -> {
                    launches.incrementAndGet();
                    return launchService.launch(request);
                },
                HomeLaunchScriptExportCommand.unavailable());
    }

    /// Creates a single-flight launch service whose accepted startup commands remain queued for model tests.
    ///
    /// @return launch service producing stable preparing sessions without worker threads
    private static DefaultGameLaunchService pendingLaunchService() {
        return new DefaultGameLaunchService(
                request -> {
                    throw new AssertionError("queued test preparation must not invoke the task factory");
                },
                command -> { },
                "Test launch",
                "Waiting for test launch");
    }

    /// Waits for one latch while preserving an interrupt request after the synchronization point is reached.
    ///
    /// @param latch synchronization point to await
    private static void awaitUninterruptibly(CountDownLatch latch) {
        boolean interrupted = false;
        while (true) {
            try {
                latch.await();
                break;
            } catch (InterruptedException ignored) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /// Mutable toolkit-neutral selection store used to verify model behavior.
    @NotNullByDefault
    private static final class FakeSelectionStore implements HomeSelectionStore {
        /// Selection transition publisher.
        private final ValueChangeSupport<HomeSelectionState> changes = new ValueChangeSupport<>(this);

        /// Latest fake selection state.
        private final AtomicReference<HomeSelectionState> current;

        /// Replacement installed immediately before the next listener registration, or null when absent.
        private final AtomicReference<@Nullable HomeSelectionState> transitionBeforeSubscription =
                new AtomicReference<>();

        /// Number of completed snapshot reads used to target one deterministic reconciliation race.
        private final AtomicInteger snapshotReads = new AtomicInteger();

        /// Optional barrier consumed by its configured snapshot-read ordinal.
        private final AtomicReference<@Nullable SnapshotReadBarrier> snapshotReadBarrier = new AtomicReference<>();

        /// Optional signal emitted after the next replacement is stored but before listeners run.
        private final AtomicReference<@Nullable CountDownLatch> afterNextStoreSignal = new AtomicReference<>();

        /// Creates a fake store with one initial selection state.
        ///
        /// @param initialState initial selection state
        private FakeSelectionStore(HomeSelectionState initialState) {
            current = new AtomicReference<>(initialState);
        }

        /// Returns the latest fake state.
        @Override
        public HomeSelectionState snapshot() {
            HomeSelectionState snapshot = current.get();
            int readOrdinal = snapshotReads.incrementAndGet();
            @Nullable SnapshotReadBarrier barrier = snapshotReadBarrier.get();
            if (barrier != null
                    && barrier.readOrdinal() == readOrdinal
                    && snapshotReadBarrier.compareAndSet(barrier, null)) {
                barrier.snapshotRead().countDown();
                awaitUninterruptibly(barrier.release());
            }
            return snapshot;
        }

        /// Registers a fake selection listener.
        @Override
        public Subscription subscribe(ValueChangeListener<HomeSelectionState> listener) {
            @Nullable HomeSelectionState replacement = transitionBeforeSubscription.getAndSet(null);
            if (replacement != null) {
                current.set(replacement);
            }
            return changes.subscribe(listener);
        }

        /// Publishes one replacement state synchronously.
        ///
        /// @param replacement replacement selection state
        private void publish(HomeSelectionState replacement) {
            HomeSelectionState previous = current.getAndSet(replacement);
            @Nullable CountDownLatch storedSignal = afterNextStoreSignal.getAndSet(null);
            if (storedSignal != null) {
                storedSignal.countDown();
            }
            changes.fireChange(previous, replacement);
        }

        /// Schedules one unannounced state transition at the next subscription boundary.
        ///
        /// @param replacement replacement state
        private void transitionBeforeNextSubscription(HomeSelectionState replacement) {
            transitionBeforeSubscription.set(replacement);
        }

        /// Pauses one numbered snapshot read after capturing its return value.
        ///
        /// @param readOrdinal one-based snapshot-read ordinal to pause
        /// @param snapshotRead signal emitted after the value is captured
        /// @param release signal allowing that captured value to return
        private void blockSnapshotRead(
                int readOrdinal,
                CountDownLatch snapshotRead,
                CountDownLatch release) {
            snapshotReadBarrier.set(new SnapshotReadBarrier(readOrdinal, snapshotRead, release));
        }

        /// Signals after the next replacement enters the store but before its event is delivered.
        ///
        /// @param storedSignal signal to emit at the storage boundary
        private void signalAfterNextStore(CountDownLatch storedSignal) {
            afterNextStoreSignal.set(storedSignal);
        }

        /// Returns whether the model still owns a listener registration.
        ///
        /// @return true when at least one registration remains
        private boolean hasSubscribers() {
            return changes.hasSubscribers();
        }

        /// One deterministic snapshot-read pause used by the constructor reconciliation race test.
        ///
        /// @param readOrdinal one-based snapshot call to pause
        /// @param snapshotRead signal emitted after capturing the snapshot
        /// @param release signal allowing the captured snapshot to return
        @NotNullByDefault
        private record SnapshotReadBarrier(
                int readOrdinal,
                CountDownLatch snapshotRead,
                CountDownLatch release) {
            /// Validates one positive read ordinal and its synchronization signals.
            private SnapshotReadBarrier {
                if (readOrdinal <= 0) {
                    throw new IllegalArgumentException("readOrdinal must be positive");
                }
                Objects.requireNonNull(snapshotRead, "snapshotRead");
                Objects.requireNonNull(release, "release");
            }
        }
    }

    /// Launch-session decorator whose equality intentionally ignores delegate identity.
    @NotNullByDefault
    private static final class EqualLaunchSession implements LaunchSession {
        /// Real session supplying every lifecycle and presentation operation.
        private final LaunchSession delegate;

        /// Creates one equal-by-class wrapper around a distinct real session.
        ///
        /// @param delegate real session to expose
        private EqualLaunchSession(LaunchSession delegate) {
            this.delegate = Objects.requireNonNull(delegate, "delegate");
        }

        /// Returns the captured launch request.
        @Override
        public LaunchRequest request() {
            return delegate.request();
        }

        /// Returns the current launch status.
        @Override
        public LaunchStatus status() {
            return delegate.status();
        }

        /// Returns the delegated launch-status property.
        @Override
        public ReadOnlyProperty<LaunchStatus> statusProperty() {
            return delegate.statusProperty();
        }

        /// Returns the current task-presentation snapshot.
        @Override
        public TaskSnapshot snapshot() {
            return delegate.snapshot();
        }

        /// Registers a delegated task-presentation listener.
        @Override
        public Subscription subscribe(ValueChangeListener<TaskSnapshot> listener) {
            return delegate.subscribe(listener);
        }

        /// Delegates task-presentation cancellation.
        @Override
        public void requestCancellation() {
            delegate.requestCancellation();
        }

        /// Returns the delegated process-completion stage.
        @Override
        public CompletionStage<ManagedProcess> completion() {
            return delegate.completion();
        }

        /// Returns the delegated created process.
        @Override
        public Optional<ManagedProcess> createdProcess() {
            return delegate.createdProcess();
        }

        /// Returns the delegated terminal failure.
        @Override
        public Optional<Throwable> failure() {
            return delegate.failure();
        }

        /// Delegates cooperative cancellation.
        @Override
        public boolean cancel() {
            return delegate.cancel();
        }

        /// Treats every wrapper of this test type as value-equal regardless of delegate identity.
        @Override
        public boolean equals(@Nullable Object other) {
            return other instanceof EqualLaunchSession;
        }

        /// Returns the stable hash corresponding to the deliberate test equality contract.
        @Override
        public int hashCode() {
            return EqualLaunchSession.class.hashCode();
        }
    }

    /// Launch-session decorator whose status subscription throws after releasing its delegate registration.
    @NotNullByDefault
    private static final class ThrowingUnsubscribeLaunchSession implements LaunchSession {
        /// Real session supplying launch and task behavior.
        private final LaunchSession delegate;

        /// Status property that injects the configured unsubscribe failure.
        private final ReadOnlyProperty<LaunchStatus> statusProperty;

        /// Creates a session with one failing status-listener cleanup boundary.
        ///
        /// @param delegate real launch session
        /// @param unsubscribeFailure failure thrown after delegated status cleanup
        private ThrowingUnsubscribeLaunchSession(
                LaunchSession delegate,
                RuntimeException unsubscribeFailure) {
            this.delegate = Objects.requireNonNull(delegate, "delegate");
            statusProperty = new ThrowingUnsubscribeStatusProperty(
                    delegate.statusProperty(),
                    unsubscribeFailure);
        }

        /// Returns the delegated launch request.
        @Override
        public LaunchRequest request() {
            return delegate.request();
        }

        /// Returns the delegated launch status.
        @Override
        public LaunchStatus status() {
            return delegate.status();
        }

        /// Returns the failure-injecting status property.
        @Override
        public ReadOnlyProperty<LaunchStatus> statusProperty() {
            return statusProperty;
        }

        /// Returns the delegated task snapshot.
        @Override
        public TaskSnapshot snapshot() {
            return delegate.snapshot();
        }

        /// Registers a delegated task listener.
        @Override
        public Subscription subscribe(ValueChangeListener<TaskSnapshot> listener) {
            return delegate.subscribe(listener);
        }

        /// Delegates task cancellation.
        @Override
        public void requestCancellation() {
            delegate.requestCancellation();
        }

        /// Returns the delegated process completion.
        @Override
        public CompletionStage<ManagedProcess> completion() {
            return delegate.completion();
        }

        /// Returns the delegated created process.
        @Override
        public Optional<ManagedProcess> createdProcess() {
            return delegate.createdProcess();
        }

        /// Returns the delegated terminal failure.
        @Override
        public Optional<Throwable> failure() {
            return delegate.failure();
        }

        /// Delegates cooperative cancellation.
        @Override
        public boolean cancel() {
            return delegate.cancel();
        }
    }

    /// Read-only status-property decorator that fails after removing each registration.
    @NotNullByDefault
    private static final class ThrowingUnsubscribeStatusProperty implements ReadOnlyProperty<LaunchStatus> {
        /// Real status property.
        private final ReadOnlyProperty<LaunchStatus> delegate;

        /// Failure thrown after a delegated unsubscribe.
        private final RuntimeException unsubscribeFailure;

        /// Creates a status-property cleanup failure boundary.
        ///
        /// @param delegate real status property
        /// @param unsubscribeFailure failure thrown after cleanup
        private ThrowingUnsubscribeStatusProperty(
                ReadOnlyProperty<LaunchStatus> delegate,
                RuntimeException unsubscribeFailure) {
            this.delegate = Objects.requireNonNull(delegate, "delegate");
            this.unsubscribeFailure = Objects.requireNonNull(unsubscribeFailure, "unsubscribeFailure");
        }

        /// Returns the current delegated status.
        @Override
        public @Nullable LaunchStatus getValue() {
            return delegate.getValue();
        }

        /// Registers a listener whose cleanup releases the delegate before failing.
        @Override
        public Subscription subscribe(ValueChangeListener<LaunchStatus> listener) {
            Subscription registration = delegate.subscribe(listener);
            return Subscription.create(() -> {
                registration.unsubscribe();
                throw unsubscribeFailure;
            });
        }

        /// Returns the delegated property owner.
        @Override
        public @Nullable Object getBean() {
            return delegate.getBean();
        }

        /// Returns the delegated property name.
        @Override
        public String getName() {
            return delegate.getName();
        }
    }
}
