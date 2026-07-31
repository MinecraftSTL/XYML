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
import space.minecraftstl.xyml.auth.AuthInfo;
import space.minecraftstl.xyml.game.GameInstanceID;
import space.minecraftstl.xyml.game.launch.DefaultGameLaunchService;
import space.minecraftstl.xyml.game.launch.GameLaunchService;
import space.minecraftstl.xyml.game.launch.LaunchRequest;
import space.minecraftstl.xyml.game.launch.LaunchSession;
import space.minecraftstl.xyml.game.launch.LaunchStatus;
import space.minecraftstl.xyml.setting.LauncherVisibility;
import space.minecraftstl.xyml.task.Task;
import space.minecraftstl.xyml.ui.launch.LaunchInteraction;
import space.minecraftstl.xyml.ui.swing.application.SwingApplicationCommands;
import space.minecraftstl.xyml.ui.swing.application.SwingApplicationPresentation;
import space.minecraftstl.xyml.ui.swing.application.SwingApplicationPresentationFactory;
import space.minecraftstl.xyml.ui.swing.page.accounts.AccountReauthentication;
import space.minecraftstl.xyml.util.platform.ManagedProcess;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies exact workflow delegation and resource ownership of the Swing command owner.
@NotNullByDefault
public final class SwingApplicationCommandOwnerTest {
    /// Commands preserve the exact launch request and return the exact service-created session.
    @Test
    public void commandsDelegateExactRequestAndSession() {
        RecordingGameLaunchService service = new RecordingGameLaunchService();
        SwingApplicationCommandOwner owner =
                new SwingApplicationCommandOwner(service, () -> { });
        try {
            LaunchRequest request = new LaunchRequest(
                    "account-id", "directory-id", new GameInstanceID("instance-id"));

            LaunchSession returned = owner.commands().launchCommand().launch(request);

            assertSame(request, service.lastRequest());
            assertSame(service.lastSession(), returned);
            assertEquals(1, service.launchCalls());
        } finally {
            owner.close();
        }
    }

    /// The supplied account command runs exactly once for one exposed command invocation.
    @Test
    public void addAccountCommandRunsExactlyOnce() {
        RecordingGameLaunchService service = new RecordingGameLaunchService();
        AtomicInteger accountCalls = new AtomicInteger();
        SwingApplicationCommandOwner owner =
                new SwingApplicationCommandOwner(service, accountCalls::incrementAndGet);
        try {
            owner.commands().addAccountCommand().run();

            assertEquals(1, accountCalls.get());
        } finally {
            owner.close();
        }
    }

    /// Repeated owner closure reaches the service once and retained launch commands use its rejection.
    @Test
    public void closeIsIdempotentAndRetainedLaunchCommandUsesServiceRejection() {
        RecordingGameLaunchService service = new RecordingGameLaunchService();
        SwingApplicationCommandOwner owner =
                new SwingApplicationCommandOwner(service, () -> { });
        SwingApplicationCommands commands = owner.commands();

        owner.close();
        owner.close();
        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> commands.launchCommand().launch(
                        new LaunchRequest(
                                "account-id",
                                "directory-id",
                                new GameInstanceID("closed-instance"))));

        assertSame(service.closedFailure(), failure);
        assertEquals(1, service.closeCalls());
        assertEquals(1, service.launchCalls());
    }

    /// Production closure leaves the caller-owned preparation executor running.
    @Test
    public void productionOwnerDoesNotClosePreparationExecutor() {
        ExecutorService preparationExecutor = Executors.newSingleThreadExecutor();
        SwingApplicationPresentation presentation = SwingApplicationPresentationFactory.create(
                "Command owner test",
                Duration.ZERO,
                Duration.ZERO);
        RecordingAccountReauthentication reauthentication = new RecordingAccountReauthentication();
        try (SwingApplicationCommandOwner owner = new SwingApplicationCommandOwner(
                presentation,
                preparationExecutor,
                () -> { },
                inertVisibilityActions(),
                inertLaunchInteraction(),
                reauthentication)) {
            owner.close();

            assertFalse(preparationExecutor.isShutdown());
            assertEquals(1, reauthentication.closeCalls());
        } finally {
            preparationExecutor.shutdownNow();
        }
    }

    /// Production commands expose the shared stable-ID reauthentication service to the account page.
    @Test
    public void productionOwnerDelegatesAccountRefresh() {
        ExecutorService preparationExecutor = Executors.newSingleThreadExecutor();
        SwingApplicationPresentation presentation = SwingApplicationPresentationFactory.create(
                "Command owner refresh test",
                Duration.ZERO,
                Duration.ZERO);
        SuccessfulAccountReauthentication reauthentication = new SuccessfulAccountReauthentication();
        try (SwingApplicationCommandOwner owner = new SwingApplicationCommandOwner(
                presentation,
                preparationExecutor,
                () -> { },
                inertVisibilityActions(),
                inertLaunchInteraction(),
                reauthentication)) {
            owner.commands().refreshAccountCommand().refresh("account-alpha")
                    .toCompletableFuture()
                    .join();

            assertEquals("account-alpha", reauthentication.requestedAccountId());
        } finally {
            preparationExecutor.shutdownNow();
        }
    }

    /// Production construction failure releases transferred reauthentication before no owner can be returned.
    @Test
    public void productionConstructionFailureClosesReauthentication() {
        ExecutorService preparationExecutor = Executors.newSingleThreadExecutor();
        RecordingAccountReauthentication reauthentication = new RecordingAccountReauthentication();
        try {
            assertThrows(
                    NullPointerException.class,
                    () -> new SwingApplicationCommandOwner(
                            null,
                            preparationExecutor,
                            () -> { },
                            inertVisibilityActions(),
                            inertLaunchInteraction(),
                            reauthentication));

            assertEquals(1, reauthentication.closeCalls());
            assertFalse(preparationExecutor.isShutdown());
        } finally {
            preparationExecutor.shutdownNow();
        }
    }

    /// `CLOSE` runs only after the session exposes a durable `PROCESS_CREATED` outcome.
    @Test
    public void closeVisibilityCannotCancelSuccessfullyCreatedProcess() throws Exception {
        ManagedProcess process = new ManagedProcess(null, List.of("java", "test.Main"));
        AtomicReference<@Nullable LauncherLaunchTaskFactory> factoryReference = new AtomicReference<>();
        AtomicReference<@Nullable SwingApplicationCommandOwner> ownerReference = new AtomicReference<>();
        CountDownLatch closeCompleted = new CountDownLatch(1);
        LaunchVisibilityActions actions = new LaunchVisibilityActions(
                () -> {
                    try {
                        Objects.requireNonNull(ownerReference.get(), "command owner").close();
                    } finally {
                        closeCompleted.countDown();
                    }
                },
                () -> { },
                () -> { });
        LauncherLaunchTaskFactory factory = new LauncherLaunchTaskFactory(
                Runnable::run,
                ignored -> Task.supplyAsync(Runnable::run, () -> {
                    Objects.requireNonNull(factoryReference.get(), "task factory")
                            .registerVisibility(process, LauncherVisibility.CLOSE);
                    return process;
                }),
                actions);
        factoryReference.set(factory);
        DefaultGameLaunchService service = new DefaultGameLaunchService(
                factory,
                Runnable::run,
                "Test launch",
                "Test waiting");
        SwingApplicationCommandOwner owner = new SwingApplicationCommandOwner(
                service,
                () -> { },
                factory::observeCompletion,
                factory,
                () -> { });
        ownerReference.set(owner);

        LaunchSession session = owner.commands().launchCommand().launch(
                new LaunchRequest("account", "directory", new GameInstanceID("instance")));

        assertSame(process, session.completion().toCompletableFuture().get(5L, TimeUnit.SECONDS));
        assertEquals(LaunchStatus.PROCESS_CREATED, session.status());
        assertSame(process, session.createdProcess().orElseThrow());
        assertTrue(closeCompleted.await(5L, TimeUnit.SECONDS));
        assertThrows(
                IllegalStateException.class,
                () -> owner.commands().launchCommand().launch(
                        new LaunchRequest(
                                "account",
                                "directory",
                                new GameInstanceID("another-instance"))));
        assertEquals(LaunchStatus.PROCESS_CREATED, session.status());
    }

    /// Returns no-op runtime actions for ownership-only production construction tests.
    ///
    /// @return inert visibility actions
    private static LaunchVisibilityActions inertVisibilityActions() {
        return new LaunchVisibilityActions(() -> { }, () -> { }, () -> { });
    }

    /// Returns a deterministic interaction that always selects the prompt's safe close action.
    ///
    /// @return inert launch interaction
    private static LaunchInteraction inertLaunchInteraction() {
        return prompt -> CompletableFuture.completedFuture(prompt.closeAction());
    }

    /// Successful account-refresh service used to verify production command wiring.
    @NotNullByDefault
    private static final class SuccessfulAccountReauthentication implements AccountReauthentication {
        /// Last stable account ID requested by the production refresh command.
        private volatile @Nullable String requestedAccountId;

        /// Records one request and returns a disposable authentication result.
        ///
        /// @param accountId stable account identifier
        /// @return immediately successful authentication result
        @Override
        public CompletionStage<AuthInfo> reauthenticate(String accountId) {
            requestedAccountId = accountId;
            return CompletableFuture.completedFuture(new AuthInfo(
                    "Player",
                    UUID.fromString("00000000-0000-0000-0000-000000000001"),
                    "test-token",
                    AuthInfo.USER_TYPE_MSA,
                    "{}"));
        }

        /// Releases no additional fixture resources.
        @Override
        public void close() {
        }

        /// Returns the last requested stable account ID.
        ///
        /// @return requested stable account ID, or null before invocation
        private @Nullable String requestedAccountId() {
            return requestedAccountId;
        }
    }

    /// Records production owner cancellation of the stable-ID reauthentication boundary.
    @NotNullByDefault
    private static final class RecordingAccountReauthentication implements AccountReauthentication {
        /// Number of lifecycle close requests.
        private final AtomicInteger closeCalls = new AtomicInteger();

        /// Rejects unexpected authentication work in an ownership-only test.
        ///
        /// @param accountId unexpected stable account identifier
        /// @return failed unexpected-call completion
        @Override
        public CompletionStage<AuthInfo> reauthenticate(String accountId) {
            return CompletableFuture.failedFuture(
                    new AssertionError("Unexpected reauthentication for " + accountId));
        }

        /// Records one idempotency-visible close call from the command owner.
        @Override
        public void close() {
            closeCalls.incrementAndGet();
        }

        /// Returns the number of close calls.
        ///
        /// @return close-call count
        private int closeCalls() {
            return closeCalls.get();
        }
    }

    /// Launch service fixture that records delegation while retaining production close rejection.
    @NotNullByDefault
    private static final class RecordingGameLaunchService implements GameLaunchService {
        /// Delegate supplies a real launch-session identity and canonical closed-state behavior.
        private final DefaultGameLaunchService delegate = new DefaultGameLaunchService(
                request -> {
                    throw new AssertionError("discarding executor must not invoke task factory");
                },
                ignoredPreparation -> { },
                "Test launch",
                "Test waiting");

        /// Last exact request received by this service, or null before the first launch.
        private final AtomicReference<@Nullable LaunchRequest> lastRequest = new AtomicReference<>();

        /// Last exact session returned by the delegate, or null before the first launch.
        private final AtomicReference<@Nullable LaunchSession> lastSession = new AtomicReference<>();

        /// Number of launch calls reaching this service, including closed-state rejections.
        private final AtomicInteger launchCalls = new AtomicInteger();

        /// Number of close calls reaching this service.
        private final AtomicInteger closeCalls = new AtomicInteger();

        /// Stable rejection proving a retained command reached this service after closure.
        private final IllegalStateException closedFailure =
                new IllegalStateException("recording launch service is closed");

        /// Whether this fixture has entered its permanent closed state.
        private boolean closed;

        /// Records and delegates one exact request, or raises the fixture's stable close rejection.
        ///
        /// @param request exact request received through the command boundary
        /// @return exact session created by the production delegate
        @Override
        public LaunchSession launch(LaunchRequest request) {
            launchCalls.incrementAndGet();
            if (closed) {
                throw closedFailure;
            }
            lastRequest.set(Objects.requireNonNull(request, "request"));
            LaunchSession session = delegate.launch(request);
            lastSession.set(session);
            return session;
        }

        /// Returns the delegate's current preparation.
        ///
        /// @return active session, or an empty value
        @Override
        public Optional<LaunchSession> activePreparation() {
            return delegate.activePreparation();
        }

        /// Records one call and closes the delegate on the first call.
        @Override
        public void close() {
            closeCalls.incrementAndGet();
            if (!closed) {
                closed = true;
                delegate.close();
            }
        }

        /// Returns the last exact request received by the fixture.
        ///
        /// @return last request
        private LaunchRequest lastRequest() {
            return Objects.requireNonNull(lastRequest.get(), "last request");
        }

        /// Returns the last exact session created by the delegate.
        ///
        /// @return last session
        private LaunchSession lastSession() {
            return Objects.requireNonNull(lastSession.get(), "last session");
        }

        /// Returns the number of launch calls reaching the fixture.
        ///
        /// @return launch call count
        private int launchCalls() {
            return launchCalls.get();
        }

        /// Returns the number of close calls reaching the fixture.
        ///
        /// @return close call count
        private int closeCalls() {
            return closeCalls.get();
        }

        /// Returns the stable close rejection owned by this fixture.
        ///
        /// @return stable closed-state failure
        private IllegalStateException closedFailure() {
            return closedFailure;
        }
    }
}
