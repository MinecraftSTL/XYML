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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import space.minecraftstl.xyml.auth.AuthInfo;
import space.minecraftstl.xyml.auth.AuthenticationException;
import space.minecraftstl.xyml.ui.UiDispatcher;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests reauthentication retry, cancellation, progress, persistence, and AuthInfo ownership without Swing.
@NotNullByDefault
public final class DefaultAccountReauthenticationTest {
    /// Test-owned caller executor.
    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "reauthentication-test");
        thread.setDaemon(true);
        return thread;
    });

    /// Stops the test-owned executor after every scenario.
    @AfterEach
    public void stopExecutor() {
        executor.shutdownNow();
    }

    /// A failed classic password attempt returns to the prompt with localized error and then persists success.
    @Test
    public void retriesClassicPasswordAfterLocalizedFailure() throws Exception {
        FakeGateway gateway = new FakeGateway(AccountReauthenticationKind.CLASSIC_PASSWORD, false);
        gateway.classicFailuresRemaining.set(1);
        FakeInteraction interaction = new FakeInteraction();
        interaction.passwords.add("wrong".toCharArray());
        interaction.passwords.add("correct".toCharArray());
        DefaultAccountReauthentication service = service(gateway, interaction);

        AuthInfo result = service.reauthenticate("account-id")
                .toCompletableFuture().get(5, TimeUnit.SECONDS);

        assertAll(
                () -> assertEquals("Player", result.getUsername()),
                () -> assertEquals(List.of("wrong", "correct"), gateway.passwords),
                () -> assertEquals(2, interaction.passwordErrors.size()),
                () -> assertNull(interaction.passwordErrors.get(0)),
                () -> assertEquals("localized: bad password", interaction.passwordErrors.get(1)),
                () -> assertEquals(List.of("describe", "classic", "classic", "persist"), gateway.actions),
                () -> assertFalse(gateway.persistAllowedOverwrite.get()));
        service.close();
        result.close();
    }

    /// OAuth retry forwards device authorization notices and retries only after explicit confirmation.
    @Test
    public void forwardsOAuthProgressAndRetries() throws Exception {
        FakeGateway gateway = new FakeGateway(AccountReauthenticationKind.OAUTH_DEVICE_CODE, false);
        gateway.oauthFailuresRemaining.set(1);
        FakeInteraction interaction = new FakeInteraction();
        interaction.retryOAuth = true;
        DefaultAccountReauthentication service = service(gateway, interaction);

        AuthInfo result = service.reauthenticate("account-id")
                .toCompletableFuture().get(5, TimeUnit.SECONDS);

        assertAll(
                () -> assertEquals(2, gateway.oauthAttempts.get()),
                () -> assertEquals(List.of("localized: OAuth failed"), interaction.oauthErrors),
                () -> assertEquals(2, interaction.notices.stream()
                        .filter(notice -> notice.kind()
                                == AccountReauthenticationNotice.Kind.DEVICE_AUTHORIZATION)
                        .count()),
                () -> assertTrue(interaction.notices.stream()
                        .anyMatch(notice -> notice.kind()
                                == AccountReauthenticationNotice.Kind.PERSISTING)),
                () -> assertEquals(1, gateway.persistCalls.get()));
        service.close();
        result.close();
    }

    /// Declining read-only storage cancels before network authentication or overwrite.
    @Test
    public void cancelsBeforeAuthenticationWhenReadOnlyOverwriteDeclined() {
        FakeGateway gateway = new FakeGateway(AccountReauthenticationKind.CLASSIC_PASSWORD, true);
        FakeInteraction interaction = new FakeInteraction();
        interaction.confirmReadOnly = false;
        DefaultAccountReauthentication service = service(gateway, interaction);

        java.util.concurrent.CompletableFuture<AuthInfo> completion = service
                .reauthenticate("account-id").toCompletableFuture();

        assertThrows(CancellationException.class, completion::join);
        assertAll(
                () -> assertEquals(1, interaction.readOnlyConfirmations.get()),
                () -> assertEquals(List.of("describe"), gateway.actions),
                () -> assertEquals(0, gateway.persistCalls.get()));
        service.close();
    }

    /// Confirmed read-only storage is passed to persistence only after authentication succeeds.
    @Test
    public void persistsWithConfirmedReadOnlyOverwrite() throws Exception {
        FakeGateway gateway = new FakeGateway(AccountReauthenticationKind.DIRECT, true);
        FakeInteraction interaction = new FakeInteraction();
        DefaultAccountReauthentication service = service(gateway, interaction);

        AuthInfo result = service.reauthenticate("account-id")
                .toCompletableFuture().get(5, TimeUnit.SECONDS);

        assertAll(
                () -> assertEquals(List.of("describe", "direct", "persist"), gateway.actions),
                () -> assertTrue(gateway.persistAllowedOverwrite.get()));
        service.close();
        result.close();
    }

    /// Cancelling the returned future interrupts blocking OAuth work and closes native interaction ownership.
    @Test
    public void callerCancellationInterruptsOAuthAndClosesInteraction() throws Exception {
        FakeGateway gateway = new FakeGateway(AccountReauthenticationKind.OAUTH_DEVICE_CODE, false);
        gateway.blockOAuth = true;
        FakeInteraction interaction = new FakeInteraction();
        DefaultAccountReauthentication service = service(gateway, interaction);
        java.util.concurrent.CompletableFuture<AuthInfo> completion = service
                .reauthenticate("account-id").toCompletableFuture();
        assertTrue(gateway.oauthStarted.await(5, TimeUnit.SECONDS));

        boolean cancelled = completion.cancel(true);

        assertAll(
                () -> assertTrue(cancelled),
                () -> assertTrue(gateway.oauthInterrupted.await(5, TimeUnit.SECONDS)),
                () -> assertTrue(gateway.authenticationCancellations.get() > 0),
                () -> assertTrue(interaction.closeCalls.get() > 0),
                () -> assertEquals(0, gateway.persistCalls.get()));
        service.close();
    }

    /// Persistence failure is localized, shown, and closes AuthInfo that was never delivered.
    @Test
    public void localizesPersistenceFailureAndClosesAbandonedAuthInfo() {
        FakeGateway gateway = new FakeGateway(AccountReauthenticationKind.DIRECT, false);
        gateway.persistFailure = new IllegalStateException("disk failed");
        FakeInteraction interaction = new FakeInteraction();
        DefaultAccountReauthentication service = service(gateway, interaction);

        ExecutionException execution = assertThrows(
                ExecutionException.class,
                () -> service.reauthenticate("account-id")
                        .toCompletableFuture().get(5, TimeUnit.SECONDS));

        AccountReauthenticationException failure = assertInstanceOf(
                AccountReauthenticationException.class,
                execution.getCause());
        assertAll(
                () -> assertEquals("localized: disk failed", failure.getMessage()),
                () -> assertEquals(List.of("localized: disk failed"), interaction.terminalErrors),
                () -> assertTrue(gateway.authInfo.closed.get()));
        service.close();
    }

    /// Closing the service cancels active work and permanently rejects new operations.
    @Test
    public void closeCancelsAndRejectsNewWork() throws Exception {
        FakeGateway gateway = new FakeGateway(AccountReauthenticationKind.OAUTH_DEVICE_CODE, false);
        gateway.blockOAuth = true;
        DefaultAccountReauthentication service = service(gateway, new FakeInteraction());
        java.util.concurrent.CompletableFuture<AuthInfo> completion = service
                .reauthenticate("account-id").toCompletableFuture();
        assertTrue(gateway.oauthStarted.await(5, TimeUnit.SECONDS));

        service.close();

        assertAll(
                () -> assertTrue(completion.isCancelled()),
                () -> assertThrows(IllegalStateException.class,
                        () -> service.reauthenticate("account-id")));
    }

    /// Creates a service using immediate deterministic UI dispatch.
    ///
    /// @param gateway fake gateway
    /// @param interaction fake interaction
    /// @return reauthentication service
    private DefaultAccountReauthentication service(
            FakeGateway gateway,
            FakeInteraction interaction) {
        return new DefaultAccountReauthentication(
                gateway,
                interaction,
                executor,
                ImmediateUiDispatcher.INSTANCE);
    }

    /// Deterministic UI dispatcher for headless tests.
    @NotNullByDefault
    private enum ImmediateUiDispatcher implements UiDispatcher {
        /// Shared dispatcher.
        INSTANCE;

        /// Treats every caller as the test UI thread.
        @Override
        public boolean isDispatchThread() {
            return true;
        }

        /// Runs dispatched work immediately.
        @Override
        public void dispatch(Runnable operation) {
            Objects.requireNonNull(operation, "operation").run();
        }
    }

    /// Headless prompt and progress boundary.
    @NotNullByDefault
    private static final class FakeInteraction implements AccountReauthenticationInteraction {
        /// Password attempts returned in order.
        private final Queue<char[]> passwords = new ArrayDeque<>();

        /// Localized errors shown beside password attempts.
        private final List<@Nullable String> passwordErrors = new ArrayList<>();

        /// Localized OAuth retry errors.
        private final List<String> oauthErrors = new ArrayList<>();

        /// Delivered progress notices.
        private final List<AccountReauthenticationNotice> notices = new ArrayList<>();

        /// Terminal localized errors.
        private final List<String> terminalErrors = new ArrayList<>();

        /// Number of read-only confirmations.
        private final AtomicInteger readOnlyConfirmations = new AtomicInteger();

        /// Number of close requests.
        private final AtomicInteger closeCalls = new AtomicInteger();

        /// Read-only confirmation result.
        private boolean confirmReadOnly = true;

        /// OAuth retry result.
        private boolean retryOAuth;

        /// Records and answers read-only confirmation.
        @Override
        public boolean confirmReadOnlyStorage(AccountReauthenticationTarget target) {
            readOnlyConfirmations.incrementAndGet();
            return confirmReadOnly;
        }

        /// Returns the next password and captures the previous error.
        @Override
        public char[] requestPassword(
                AccountReauthenticationTarget target,
                @Nullable String localizedError) {
            passwordErrors.add(localizedError);
            char @Nullable [] password = passwords.poll();
            if (password == null) {
                throw new AccountReauthenticationCancelledException();
            }
            return password;
        }

        /// Records and answers OAuth retry confirmation.
        @Override
        public boolean confirmOAuthRetry(
                AccountReauthenticationTarget target,
                String localizedError) {
            oauthErrors.add(localizedError);
            return retryOAuth;
        }

        /// Captures progress.
        @Override
        public synchronized void onProgress(
                AccountReauthenticationTarget target,
                AccountReauthenticationNotice notice) {
            notices.add(notice);
        }

        /// Captures terminal failure presentation.
        @Override
        public void showFailure(
                AccountReauthenticationTarget target,
                String localizedError) {
            terminalErrors.add(localizedError);
        }

        /// Records interaction closure.
        @Override
        public void closeCurrentInteraction() {
            closeCalls.incrementAndGet();
        }
    }

    /// Fake gateway with controllable retry, blocking, and persistence outcomes.
    @NotNullByDefault
    private static final class FakeGateway implements AccountReauthenticationGateway {
        /// Recovery kind returned by describe.
        private final AccountReauthenticationKind kind;

        /// Read-only state returned by describe.
        private final boolean readOnly;

        /// Ordered gateway action history.
        private final List<String> actions = new ArrayList<>();

        /// Captured classic password strings.
        private final List<String> passwords = new ArrayList<>();

        /// Remaining classic failures.
        private final AtomicInteger classicFailuresRemaining = new AtomicInteger();

        /// Remaining OAuth failures.
        private final AtomicInteger oauthFailuresRemaining = new AtomicInteger();

        /// OAuth attempt count.
        private final AtomicInteger oauthAttempts = new AtomicInteger();

        /// Persistence call count.
        private final AtomicInteger persistCalls = new AtomicInteger();

        /// Whether persistence received overwrite permission.
        private final AtomicBoolean persistAllowedOverwrite = new AtomicBoolean();

        /// Number of explicit active-authentication cancellation requests.
        private final AtomicInteger authenticationCancellations = new AtomicInteger();

        /// Signals OAuth entry.
        private final CountDownLatch oauthStarted = new CountDownLatch(1);

        /// Signals blocking OAuth interruption.
        private final CountDownLatch oauthInterrupted = new CountDownLatch(1);

        /// Authentication data reused by successful fake operations.
        private final TrackingAuthInfo authInfo = new TrackingAuthInfo();

        /// Whether OAuth waits for interruption.
        private boolean blockOAuth;

        /// Injected persistence failure.
        private @Nullable RuntimeException persistFailure;

        /// Creates a gateway for one target shape.
        ///
        /// @param kind recovery kind
        /// @param readOnly read-only state
        private FakeGateway(AccountReauthenticationKind kind, boolean readOnly) {
            this.kind = kind;
            this.readOnly = readOnly;
        }

        /// Returns immutable target metadata.
        @Override
        public synchronized AccountReauthenticationTarget describe(String accountId) {
            actions.add("describe");
            return new AccountReauthenticationTarget(accountId, "Player", kind, false, readOnly);
        }

        /// Captures password and optionally throws a localized authentication candidate.
        @Override
        public synchronized PreparedReauthentication authenticateClassic(
                AccountReauthenticationTarget target,
                String password) throws Exception {
            actions.add("classic");
            passwords.add(password);
            if (classicFailuresRemaining.getAndDecrement() > 0) {
                throw new AuthenticationException("bad password");
            }
            return prepared();
        }

        /// Emits device progress, optionally blocks, and optionally fails.
        @Override
        public synchronized PreparedReauthentication authenticateOAuth(
                AccountReauthenticationTarget target,
                Consumer<AccountReauthenticationNotice> progress) throws Exception {
            actions.add("oauth");
            oauthAttempts.incrementAndGet();
            oauthStarted.countDown();
            progress.accept(AccountReauthenticationNotice.deviceAuthorization(
                    "https://example.test/device",
                    "ABCD-EFGH"));
            if (blockOAuth) {
                try {
                    new CountDownLatch(1).await();
                } catch (InterruptedException failure) {
                    oauthInterrupted.countDown();
                    throw failure;
                }
            }
            if (oauthFailuresRemaining.getAndDecrement() > 0) {
                throw new AuthenticationException("OAuth failed");
            }
            progress.accept(AccountReauthenticationNotice.authorizationCompleted());
            return prepared();
        }

        /// Records direct authentication.
        @Override
        public synchronized PreparedReauthentication authenticateDirect(
                AccountReauthenticationTarget target) {
            actions.add("direct");
            return prepared();
        }

        /// Records explicit cancellation of gateway callback ownership.
        @Override
        public void cancelActiveAuthentication() {
            authenticationCancellations.incrementAndGet();
        }

        /// Records persistence or throws the injected failure.
        @Override
        public synchronized void persist(
                PreparedReauthentication prepared,
                boolean allowReadOnlyOverwrite) {
            actions.add("persist");
            persistCalls.incrementAndGet();
            persistAllowedOverwrite.set(allowReadOnlyOverwrite);
            if (persistFailure != null) {
                throw persistFailure;
            }
        }

        /// Produces deterministic localized text.
        @Override
        public String localizeFailure(Throwable failure) {
            return "localized: " + failure.getMessage();
        }

        /// Creates one opaque prepared result around the fake AuthInfo.
        ///
        /// @return prepared result
        private PreparedReauthentication prepared() {
            return new PreparedReauthentication() {
                /// Returns the fake stable ID.
                @Override
                public String accountId() {
                    return "account-id";
                }

                /// Returns fake authentication data.
                @Override
                public AuthInfo authInfo() {
                    return authInfo;
                }
            };
        }
    }

    /// AuthInfo that records abandoned-result closure.
    @NotNullByDefault
    private static final class TrackingAuthInfo extends AuthInfo {
        /// Whether close was invoked.
        private final AtomicBoolean closed = new AtomicBoolean();

        /// Creates stable fake launch credentials.
        private TrackingAuthInfo() {
            super(
                    "Player",
                    UUID.fromString("00000000-0000-0000-0000-000000000001"),
                    "token",
                    USER_TYPE_MSA,
                    "{}");
        }

        /// Records closure.
        @Override
        public void close() {
            closed.set(true);
        }
    }
}
