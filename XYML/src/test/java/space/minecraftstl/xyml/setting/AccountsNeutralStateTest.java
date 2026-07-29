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
package space.minecraftstl.xyml.setting;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import space.minecraftstl.xyml.auth.Account;
import space.minecraftstl.xyml.auth.AccountID;
import space.minecraftstl.xyml.auth.AuthInfo;
import space.minecraftstl.xyml.auth.AuthenticationException;
import space.minecraftstl.xyml.observable.Subscription;
import space.minecraftstl.xyml.observable.collection.ListChange;
import space.minecraftstl.xyml.observable.collection.ObservableList;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/// Verifies the toolkit-neutral account state boundary.
@NotNullByDefault
public final class AccountsNeutralStateTest {
    /// Keeps the authlib provider construction in [Accounts] independent of packaged resources.
    private static final String AUTHLIB_LOCATION_PROPERTY = "xyml.authlibinjector.location";

    /// Verifies that account field changes are exposed as neutral list updates without JavaFX adapters.
    @Test
    public void accountChangesPublishNeutralUpdateEvents() {
        withAccounts(() -> {
            ObservableList<Account> accounts = Accounts.getAccounts();
            TestAccount account = new TestAccount("neutral-update");
            List<ListChange.Kind> changes = new ArrayList<>();
            Subscription subscription = accounts.subscribe(change -> changes.add(change.kind()));
            try {
                accounts.add(account);
                account.invalidateForTest();
                assertEquals(List.of(ListChange.Kind.ADD, ListChange.Kind.UPDATE), changes);
            } finally {
                subscription.unsubscribe();
                accounts.clear();
                Accounts.setSelectedAccount(null);
            }
        });
    }

    /// Verifies that selected-account accessors share one neutral property.
    @Test
    public void selectedAccountAccessorsStaySynchronized() {
        withAccounts(() -> {
            TestAccount account = new TestAccount("neutral-selection");
            try {
                Accounts.setSelectedAccount(account);
                assertSame(account, Accounts.selectedAccountProperty().get());

                Accounts.selectedAccountProperty().set(null);
                assertNull(Accounts.getSelectedAccount());
            } finally {
                Accounts.setSelectedAccount(null);
            }
        });
    }

    /// Runs an account test with a deterministic authlib provider configuration.
    ///
    /// @param executable test body
    private static void withAccounts(Executable executable) {
        @Nullable String previous = System.getProperty(AUTHLIB_LOCATION_PROPERTY);
        System.setProperty(AUTHLIB_LOCATION_PROPERTY, Path.of("missing-authlib-injector.jar").toString());
        try {
            executable.execute();
        } catch (Throwable failure) {
            throw new AssertionError("Account state test failed", failure);
        } finally {
            if (previous == null) {
                System.clearProperty(AUTHLIB_LOCATION_PROPERTY);
            } else {
                System.setProperty(AUTHLIB_LOCATION_PROPERTY, previous);
            }
        }
    }

    /// Minimal account implementation that exposes the protected neutral invalidation hook for tests.
    @NotNullByDefault
    private static final class TestAccount extends Account {
        /// Stable profile ID used by this test account.
        private final UUID profileID = UUID.nameUUIDFromBytes(getAccountID().toString().getBytes());

        /// Creates a deterministic offline-like test account.
        ///
        /// @param value account ID suffix
        private TestAccount(String value) {
            super(new AccountID(UUID.nameUUIDFromBytes(value.getBytes())));
        }

        /// Returns the stable display name.
        @Override
        public String getProfileName() {
            return "Neutral Test";
        }

        /// Returns the stable profile ID.
        @Override
        public UUID getProfileID() {
            return profileID;
        }

        /// Returns unsupported authentication because tests never contact a provider.
        @Override
        public AuthInfo logIn() throws AuthenticationException {
            throw new UnsupportedOperationException("Test account cannot log in");
        }

        /// Returns unsupported offline launch information because tests only inspect state changes.
        @Override
        public AuthInfo playOffline() throws AuthenticationException {
            throw new UnsupportedOperationException("Test account cannot play offline");
        }

        /// Publishes one neutral account update for the list extractor test.
        private void invalidateForTest() {
            invalidate();
        }
    }
}
