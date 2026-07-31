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
import org.junit.jupiter.api.Test;
import space.minecraftstl.xyml.auth.AuthInfo;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/// Tests the credential-bearing result boundary used by account-page refresh actions.
@NotNullByDefault
public final class AccountRefreshCommandTest {
    /// A refresh delegates one stable ID without blocking and closes the eventual result exactly once.
    @Test
    public void delegatesAsynchronouslyAndClosesSuccessfulResult() {
        FakeReauthentication reauthentication = new FakeReauthentication();
        CloseTrackingAuthInfo authInfo = new CloseTrackingAuthInfo();
        AccountRefreshCommand command = AccountRefreshCommand.from(reauthentication);

        CompletionStage<Void> completion = command.refresh("account-alpha");

        assertAll(
                () -> assertEquals("account-alpha", reauthentication.requestedAccountId()),
                () -> assertFalse(completion.toCompletableFuture().isDone()),
                () -> assertEquals(0, authInfo.closeCount()));
        reauthentication.complete(authInfo);
        completion.toCompletableFuture().join();

        assertEquals(1, authInfo.closeCount());
        reauthentication.close();
    }

    /// Controllable reauthentication service used to prove asynchronous command behavior.
    @NotNullByDefault
    private static final class FakeReauthentication implements AccountReauthentication {
        /// Authentication completion controlled by the test.
        private final CompletableFuture<AuthInfo> completion = new CompletableFuture<>();

        /// Last requested stable account ID, or null before the first request.
        private volatile @Nullable String requestedAccountId;

        /// Records the stable ID and returns an incomplete authentication stage.
        @Override
        public CompletionStage<AuthInfo> reauthenticate(String accountId) {
            requestedAccountId = accountId;
            return completion;
        }

        /// Cancels the controllable completion when it remains active.
        @Override
        public void close() {
            completion.cancel(false);
        }

        /// Completes authentication with one close-tracking result.
        ///
        /// @param authInfo authentication result
        private void complete(AuthInfo authInfo) {
            completion.complete(authInfo);
        }

        /// Returns the last requested account ID.
        ///
        /// @return requested stable account ID, or null before invocation
        private @Nullable String requestedAccountId() {
            return requestedAccountId;
        }
    }

    /// Authentication result whose close calls are observable without exposing credentials.
    @NotNullByDefault
    private static final class CloseTrackingAuthInfo extends AuthInfo {
        /// Number of resource-release calls.
        private final AtomicInteger closeCount = new AtomicInteger();

        /// Creates a deterministic credential-shaped test result.
        private CloseTrackingAuthInfo() {
            super(
                    "Player",
                    UUID.fromString("00000000-0000-0000-0000-000000000001"),
                    "test-token",
                    AuthInfo.USER_TYPE_MSA,
                    "{}");
        }

        /// Records one release call.
        @Override
        public void close() {
            closeCount.incrementAndGet();
        }

        /// Returns how many times the result was released.
        ///
        /// @return close-call count
        private int closeCount() {
            return closeCount.get();
        }
    }
}
