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
package space.minecraftstl.xyml.ui.swing.page.accounts;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import space.minecraftstl.xyml.ui.UiDispatcher;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests the headless account workflow state machine, cancellation, and storage ordering.
@NotNullByDefault
public final class SwingAccountCreationCoordinatorTest {
    /// Caller-owned worker used by each test.
    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "account-workflow-test");
        thread.setDaemon(true);
        return thread;
    });

    /// Stops the test-owned executor after every scenario.
    @AfterEach
    public void stopExecutor() {
        executor.shutdownNow();
    }

    /// Offline, Microsoft, and authlib requests all authenticate and commit on the caller executor.
    @Test
    public void supportsAllThreeMethodsOnCallerExecutor() throws Exception {
        FakeGateway gateway = new FakeGateway();
        FakeInteraction interaction = new FakeInteraction();
        SwingAccountCreationCoordinator coordinator = coordinator(gateway, interaction, true);
        @Unmodifiable List<AccountCreationRequest> requests = List.of(
                AccountCreationRequest.offline("Alex", null, false),
                AccountCreationRequest.microsoft(MicrosoftAccountLoginMode.DEVICE_CODE, false),
                AccountCreationRequest.authlibInjector(
                        "https://example.test/",
                        "user@example.test",
                        "secret",
                        true));

        for (AccountCreationRequest request : requests) {
            RecordingListener listener = new RecordingListener();
            AccountCreationResult result = coordinator.start(request, listener)
                    .completion().toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertAll(
                    () -> assertEquals(request.method(), result.method()),
                    () -> assertEquals(request.portable(), result.portable()),
                    () -> assertEquals(result, listener.result.get()),
                    () -> assertTrue(listener.notices.stream()
                            .anyMatch(notice -> notice.kind()
                                    == AccountCreationNotice.Kind.AUTHENTICATING)));
        }

        assertAll(
                () -> assertEquals(3, gateway.requests.size()),
                () -> assertEquals(3, gateway.commits.get()),
                () -> assertEquals(List.of(
                        AccountCreationMethod.OFFLINE,
                        AccountCreationMethod.MICROSOFT,
                        AccountCreationMethod.AUTHLIB_INJECTOR),
                        gateway.requests.stream().map(AccountCreationRequest::method).toList()),
                () -> assertTrue(gateway.workerThreads.stream()
                        .allMatch("account-workflow-test"::equals)),
                () -> assertNotEquals(Thread.currentThread().getName(), gateway.workerThreads.get(0)));
        coordinator.close();
    }

    /// Cancelling the invalid-name acknowledgement returns to the form before authentication starts.
    @Test
    public void invalidOfflineNameRequiresConfirmation() {
        FakeGateway gateway = new FakeGateway();
        FakeInteraction interaction = new FakeInteraction();
        interaction.confirmInvalidName = false;
        SwingAccountCreationCoordinator coordinator = coordinator(gateway, interaction, false);
        RecordingListener listener = new RecordingListener();

        AccountCreationOperation operation = coordinator.start(
                AccountCreationRequest.offline("invalid name", null, false),
                listener);

        assertThrows(CancellationException.class,
                () -> operation.completion().toCompletableFuture().join());
        assertAll(
                () -> assertEquals(List.of("invalid name"), interaction.invalidNames),
                () -> assertTrue(gateway.requests.isEmpty()),
                () -> assertEquals(1, listener.cancellations.get()));
        coordinator.close();
    }

    /// Read-only target confirmation performs backup-and-overwrite before the observable commit.
    @Test
    public void confirmsAndOverwritesReadOnlyTargetBeforeCommit() throws Exception {
        FakeGateway gateway = new FakeGateway();
        gateway.readOnly = true;
        FakeInteraction interaction = new FakeInteraction();
        SwingAccountCreationCoordinator coordinator = coordinator(gateway, interaction, true);

        AccountCreationResult result = coordinator.start(
                AccountCreationRequest.offline("Alex", null, true),
                new RecordingListener()).completion().toCompletableFuture().get(5, TimeUnit.SECONDS);

        assertAll(
                () -> assertTrue(result.portable()),
                () -> assertEquals(List.of(true), interaction.readOnlyTargets),
                () -> assertEquals(List.of("authenticate", "overwrite", "commit"), gateway.actions));
        coordinator.close();
    }

    /// Authlib multi-profile authentication delegates a plain role list and returns the exact selected UUID.
    @Test
    public void delegatesAuthlibRoleSelection() throws Exception {
        FakeGateway gateway = new FakeGateway();
        gateway.requestRoleSelection = true;
        FakeInteraction interaction = new FakeInteraction();
        interaction.selectedRole = FakeGateway.SECOND_ROLE.profileId().toString();
        SwingAccountCreationCoordinator coordinator = coordinator(gateway, interaction, true);

        coordinator.start(
                AccountCreationRequest.authlibInjector(
                        "https://example.test/",
                        "user@example.test",
                        "secret",
                        false),
                new RecordingListener()).completion().toCompletableFuture().get(5, TimeUnit.SECONDS);

        assertAll(
                () -> assertEquals(List.of(
                        FakeGateway.FIRST_ROLE,
                        FakeGateway.SECOND_ROLE), interaction.roles),
                () -> assertEquals(FakeGateway.SECOND_ROLE.profileId().toString(), gateway.selectedRole.get()));
        coordinator.close();
    }

    /// Explicit cancellation closes pending interaction and interrupts a blocking authentication call.
    @Test
    public void cancellationInterruptsWorkerAndClosesPrompt() throws Exception {
        FakeGateway gateway = new FakeGateway();
        gateway.blockAuthentication = true;
        FakeInteraction interaction = new FakeInteraction();
        SwingAccountCreationCoordinator coordinator = coordinator(gateway, interaction, true);
        RecordingListener listener = new RecordingListener();
        AccountCreationOperation operation = coordinator.start(
                AccountCreationRequest.microsoft(MicrosoftAccountLoginMode.BROWSER, false),
                listener);
        assertTrue(gateway.authenticationStarted.await(5, TimeUnit.SECONDS));

        boolean cancelled = operation.cancel();

        assertAll(
                () -> assertTrue(cancelled),
                () -> assertThrows(CancellationException.class,
                        () -> operation.completion().toCompletableFuture().join()),
                () -> assertTrue(gateway.authenticationInterrupted.await(5, TimeUnit.SECONDS)),
                () -> assertEquals(1, interaction.cancelPendingCalls.get()),
                () -> assertEquals(1, listener.cancellations.get()),
                () -> assertEquals(0, gateway.commits.get()));
        coordinator.close();
    }

    /// Gateway failures are localized once and reported without committing partial state.
    ///
    /// @throws InterruptedException when callback delivery waiting is interrupted
    @Test
    public void localizesFailureAndDoesNotCommit() throws InterruptedException {
        FakeGateway gateway = new FakeGateway();
        gateway.authenticationFailure = new IllegalStateException("raw failure");
        FakeInteraction interaction = new FakeInteraction();
        SwingAccountCreationCoordinator coordinator = coordinator(gateway, interaction, true);
        RecordingListener listener = new RecordingListener();

        AccountCreationOperation operation = coordinator.start(
                AccountCreationRequest.microsoft(MicrosoftAccountLoginMode.BROWSER, false),
                listener);

        assertThrows(java.util.concurrent.CompletionException.class,
                () -> operation.completion().toCompletableFuture().join());
        assertTrue(listener.failureDelivered.await(5, TimeUnit.SECONDS));
        assertAll(
                () -> assertEquals("localized: raw failure", listener.failureMessage.get()),
                () -> assertEquals(gateway.authenticationFailure, listener.failure.get()),
                () -> assertEquals(0, gateway.commits.get()));
        coordinator.close();
    }

    /// Concurrent starts are rejected until the active operation reaches a terminal state.
    @Test
    public void rejectsConcurrentOperation() throws Exception {
        FakeGateway gateway = new FakeGateway();
        gateway.blockAuthentication = true;
        SwingAccountCreationCoordinator coordinator = coordinator(
                gateway,
                new FakeInteraction(),
                true);
        AccountCreationOperation first = coordinator.start(
                AccountCreationRequest.microsoft(MicrosoftAccountLoginMode.BROWSER, false),
                new RecordingListener());
        assertTrue(gateway.authenticationStarted.await(5, TimeUnit.SECONDS));

        assertThrows(IllegalStateException.class, () -> coordinator.start(
                AccountCreationRequest.offline("Alex", null, false),
                new RecordingListener()));

        first.cancel();
        coordinator.close();
    }

    /// Invalid-name recognition and typed acknowledgement match legacy Unicode behavior.
    @Test
    public void validatesOfflineNamesAndConfirmationText() {
        String expected = SwingAccountCreationDialog.replacePunctuationWithSpaces(
                "I know, and confirm!");

        assertAll(
                () -> assertFalse(SwingAccountCreationCoordinator.isInvalidOfflineUsername("Alex_123")),
                () -> assertTrue(SwingAccountCreationCoordinator.isInvalidOfflineUsername("invalid name")),
                () -> assertTrue(SwingAccountCreationCoordinator.isInvalidOfflineUsername("abcdefghijklmnopq")),
                () -> assertEquals("I know  and confirm ", expected),
                () -> assertTrue(SwingAccountCreationDialog.matchesConfirmation(
                        "Iknowandconfirm",
                        expected)),
                () -> assertFalse(SwingAccountCreationDialog.matchesConfirmation(
                        "Iknow",
                        expected)));
    }

    /// Creates a coordinator using immediate deterministic listener dispatch.
    ///
    /// @param gateway fake gateway
    /// @param interaction fake interaction
    /// @param skipCheck invalid-name bypass
    /// @return coordinator
    private SwingAccountCreationCoordinator coordinator(
            FakeGateway gateway,
            FakeInteraction interaction,
            boolean skipCheck) {
        return new SwingAccountCreationCoordinator(
                gateway,
                interaction,
                executor,
                ImmediateUiDispatcher.INSTANCE,
                skipCheck);
    }

    /// Deterministic test UI dispatcher.
    @NotNullByDefault
    private enum ImmediateUiDispatcher implements UiDispatcher {
        /// Shared dispatcher.
        INSTANCE;

        /// Treats every test caller as the UI thread.
        @Override
        public boolean isDispatchThread() {
            return true;
        }

        /// Runs listener work immediately.
        @Override
        public void dispatch(Runnable operation) {
            Objects.requireNonNull(operation, "operation").run();
        }
    }

    /// Headless prompt boundary with controllable answers.
    @NotNullByDefault
    private static final class FakeInteraction implements AccountCreationInteraction {
        /// Whether invalid names are accepted.
        private boolean confirmInvalidName = true;

        /// Whether read-only storage is accepted.
        private boolean confirmReadOnly = true;

        /// Selected role identifier.
        private String selectedRole = FakeGateway.FIRST_ROLE.profileId().toString();

        /// Invalid names presented to the user.
        private final List<String> invalidNames = new ArrayList<>();

        /// Read-only storage targets presented to the user.
        private final List<Boolean> readOnlyTargets = new ArrayList<>();

        /// Last immutable role list.
        private @Unmodifiable List<AccountRoleOption> roles = List.of();

        /// Number of pending-prompt cancellation requests.
        private final AtomicInteger cancelPendingCalls = new AtomicInteger();

        /// Records and answers invalid-name confirmation.
        @Override
        public boolean confirmInvalidOfflineUsername(String username) {
            invalidNames.add(username);
            return confirmInvalidName;
        }

        /// Records and answers read-only-storage confirmation.
        @Override
        public boolean confirmReadOnlyStorage(boolean portable) {
            readOnlyTargets.add(portable);
            return confirmReadOnly;
        }

        /// Records roles and returns the configured selection.
        @Override
        public String selectRole(@Unmodifiable List<AccountRoleOption> availableRoles) {
            roles = List.copyOf(availableRoles);
            return selectedRole;
        }

        /// Records a prompt-cancellation request.
        @Override
        public void cancelPendingInteraction() {
            cancelPendingCalls.incrementAndGet();
        }
    }

    /// Fake gateway covering authentication, role selection, storage ordering, and failure localization.
    @NotNullByDefault
    private static final class FakeGateway implements AccountCreationGateway {
        /// First selectable authlib role.
        private static final AccountRoleOption FIRST_ROLE = new AccountRoleOption(
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                "First");

        /// Second selectable authlib role.
        private static final AccountRoleOption SECOND_ROLE = new AccountRoleOption(
                UUID.fromString("00000000-0000-0000-0000-000000000002"),
                "Second");

        /// Captured requests.
        private final List<AccountCreationRequest> requests = new ArrayList<>();

        /// Worker thread names used by authentication.
        private final List<String> workerThreads = new ArrayList<>();

        /// Ordered gateway actions.
        private final List<String> actions = new ArrayList<>();

        /// Number of commits.
        private final AtomicInteger commits = new AtomicInteger();

        /// Selected role returned by the interaction.
        private final AtomicReference<@Nullable String> selectedRole = new AtomicReference<>();

        /// Signals authentication entry.
        private final CountDownLatch authenticationStarted = new CountDownLatch(1);

        /// Signals interruption while blocked.
        private final CountDownLatch authenticationInterrupted = new CountDownLatch(1);

        /// Whether target storage is read-only.
        private boolean readOnly;

        /// Whether authentication should ask for a role.
        private boolean requestRoleSelection;

        /// Whether authentication should wait until interrupted.
        private boolean blockAuthentication;

        /// Injected authentication failure.
        private @Nullable RuntimeException authenticationFailure;

        /// Returns a deterministic preferred method.
        @Override
        public AccountCreationMethod preferredMethod() {
            return AccountCreationMethod.OFFLINE;
        }

        /// Leaves every method available in headless coordinator tests.
        @Override
        public boolean isMicrosoftOnly() {
            return false;
        }

        /// Accepts preference writes that are outside coordinator behavior under test.
        @Override
        public void storePreferredMethod(AccountCreationMethod method) {
            Objects.requireNonNull(method, "method");
        }

        /// Returns one immutable configured server.
        @Override
        public @Unmodifiable List<AuthlibServerOption> availableAuthlibServers() {
            return List.of(new AuthlibServerOption(
                    "https://example.test/",
                    "Example",
                    true));
        }

        /// Captures a request and optionally blocks, fails, or selects a role.
        @Override
        public synchronized PreparedAccount authenticate(
                AccountCreationRequest request,
                AccountRoleSelector roleSelector,
                Consumer<AccountCreationNotice> progress) throws Exception {
            requests.add(request);
            workerThreads.add(Thread.currentThread().getName());
            actions.add("authenticate");
            authenticationStarted.countDown();
            if (blockAuthentication) {
                try {
                    new CountDownLatch(1).await();
                } catch (InterruptedException failure) {
                    authenticationInterrupted.countDown();
                    throw failure;
                }
            }
            if (authenticationFailure != null) {
                throw authenticationFailure;
            }
            if (requestRoleSelection) {
                selectedRole.set(roleSelector.select(List.of(FIRST_ROLE, SECOND_ROLE)));
            }
            progress.accept(AccountCreationNotice.authorizationCompleted());
            return new FakePreparedAccount(request.method(), request.portable());
        }

        /// Returns configured read-only state.
        @Override
        public boolean isTargetReadOnly(PreparedAccount account) {
            return readOnly;
        }

        /// Records backup-and-overwrite ordering.
        @Override
        public synchronized void forceOverwriteTarget(PreparedAccount account) {
            actions.add("overwrite");
        }

        /// Records commit ordering.
        @Override
        public synchronized void commitAndSelect(PreparedAccount account) {
            actions.add("commit");
            commits.incrementAndGet();
        }

        /// Returns deterministic localized text.
        @Override
        public String localizeFailure(Throwable failure) {
            return "localized: " + failure.getMessage();
        }
    }

    /// Plain fake prepared account.
    ///
    /// @param method authentication method
    /// @param portable target storage
    @NotNullByDefault
    private record FakePreparedAccount(
            AccountCreationMethod method,
            boolean portable) implements PreparedAccount {
        /// Returns a stable fake account ID.
        @Override
        public String accountId() {
            return "account-id";
        }

        /// Returns a stable fake display name.
        @Override
        public String displayName() {
            return "Player";
        }
    }

    /// Captures listener callbacks for assertions.
    @NotNullByDefault
    private static final class RecordingListener implements AccountCreationListener {
        /// Progress notices in delivery order.
        private final List<AccountCreationNotice> notices = new ArrayList<>();

        /// Successful result.
        private final AtomicReference<@Nullable AccountCreationResult> result = new AtomicReference<>();

        /// Cancellation callback count.
        private final AtomicInteger cancellations = new AtomicInteger();

        /// Localized failure message.
        private final AtomicReference<@Nullable String> failureMessage = new AtomicReference<>();

        /// Original failure.
        private final AtomicReference<@Nullable Throwable> failure = new AtomicReference<>();

        /// Signals terminal failure callback delivery independently from completion-future wakeup order.
        private final CountDownLatch failureDelivered = new CountDownLatch(1);

        /// Captures progress.
        @Override
        public synchronized void onProgress(AccountCreationNotice notice) {
            notices.add(notice);
        }

        /// Captures success.
        @Override
        public void onSucceeded(AccountCreationResult successfulResult) {
            result.set(successfulResult);
        }

        /// Captures cancellation.
        @Override
        public void onCancelled() {
            cancellations.incrementAndGet();
        }

        /// Captures localized and original failure.
        @Override
        public void onFailed(String localizedMessage, Throwable originalFailure) {
            failureMessage.set(localizedMessage);
            failure.set(originalFailure);
            failureDelivered.countDown();
        }
    }
}
