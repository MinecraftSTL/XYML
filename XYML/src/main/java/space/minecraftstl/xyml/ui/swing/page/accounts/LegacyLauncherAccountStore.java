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

import javafx.beans.InvalidationListener;
import javafx.collections.ListChangeListener;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import space.minecraftstl.xyml.auth.Account;
import space.minecraftstl.xyml.auth.ClassicAccount;
import space.minecraftstl.xyml.auth.authlibinjector.AuthlibInjectorAccount;
import space.minecraftstl.xyml.auth.authlibinjector.AuthlibInjectorServer;
import space.minecraftstl.xyml.observable.Subscription;
import space.minecraftstl.xyml.observable.ValueChangeListener;
import space.minecraftstl.xyml.observable.ValueChangeSupport;
import space.minecraftstl.xyml.setting.Accounts;
import space.minecraftstl.xyml.util.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static space.minecraftstl.xyml.ui.swing.legacy.LegacyJavaFxDispatcher.execute;
import static space.minecraftstl.xyml.ui.swing.legacy.LegacyJavaFxDispatcher.requireEventThread;
import static space.minecraftstl.xyml.util.i18n.I18n.i18n;
import static space.minecraftstl.xyml.util.logging.Logger.LOG;

/// Transitional adapter that projects legacy JavaFX account state into immutable presentation-safe values.
///
/// Construction must occur on the JavaFX application thread after [Accounts#init()]. The adapter never publishes
/// account objects, server objects, authentication tokens, passwords, or private serialized account data.
@NotNullByDefault
public final class LegacyLauncherAccountStore implements AccountStore, AutoCloseable {
    /// Serializes listener registration with the transition to the closed lifecycle state.
    private final Object lifecycleLock = new Object();

    /// Plain account-state transition publisher.
    private final ValueChangeSupport<AccountStoreState> changes = new ValueChangeSupport<>(this);

    /// Listener for account additions, removals, reordering, and extractor-driven account updates.
    private final ListChangeListener<Account> accountsListener = change -> accountListChanged();

    /// Listener for the selected legacy account property.
    private final InvalidationListener selectionListener = observable -> execute(this::refreshSnapshot);

    /// Listener for display metadata changes on authlib-injector servers referenced by current accounts.
    private final InvalidationListener serverMetadataListener = observable -> refreshSnapshot();

    /// Server instances currently observed by identity to balance each listener registration exactly once.
    private final Set<AuthlibInjectorServer> observedServers =
            Collections.newSetFromMap(new IdentityHashMap<>());

    /// Whether closure has been requested from any thread.
    private final AtomicBoolean closed = new AtomicBoolean();

    /// Latest immutable cross-thread-safe account projection.
    private volatile AccountStoreState currentSnapshot;

    /// Creates the bridge and attaches legacy listeners on the JavaFX application thread.
    public LegacyLauncherAccountStore() {
        requireEventThread();
        synchronizeServerListeners();
        currentSnapshot = readSnapshot();
        Accounts.getAccounts().addListener(accountsListener);
        Accounts.selectedAccountProperty().addListener(selectionListener);
    }

    /// Returns the latest immutable presentation-safe account state.
    @Override
    public AccountStoreState snapshot() {
        return currentSnapshot;
    }

    /// Registers a listener for future account-state transitions.
    ///
    /// @param listener account-state transition listener
    /// @return independently cancellable listener registration
    @Override
    public Subscription subscribe(ValueChangeListener<AccountStoreState> listener) {
        Objects.requireNonNull(listener, "listener");
        synchronized (lifecycleLock) {
            if (closed.get()) {
                throw new IllegalStateException("Legacy launcher account store is closed");
            }
            return changes.subscribe(listener);
        }
    }

    /// Selects an account by stable identifier without synchronously waiting across UI toolkit threads.
    ///
    /// A selection that became stale before the JavaFX operation runs is ignored. The current list projection will
    /// subsequently reconcile the Swing model instead of retaining a legacy account object outside this adapter.
    ///
    /// @param accountId stable persisted account identifier
    @Override
    public void selectAccount(String accountId) {
        Objects.requireNonNull(accountId, "accountId");
        if (closed.get()) {
            throw new IllegalStateException("Legacy launcher account store is closed");
        }
        execute(() -> selectAccountOnEventThread(accountId));
    }

    /// Requests idempotent listener removal without blocking a Swing EDT caller on JavaFX.
    @Override
    public void close() {
        synchronized (lifecycleLock) {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
        }
        execute(this::removeLegacyListeners);
    }

    /// Handles any structural or extractor-driven account-list transition.
    private void accountListChanged() {
        requireEventThread();
        if (closed.get()) {
            return;
        }
        synchronizeServerListeners();
        refreshSnapshot();
    }

    /// Rebuilds and publishes plain values after one relevant legacy value changes.
    private void refreshSnapshot() {
        requireEventThread();
        if (closed.get()) {
            return;
        }
        AccountStoreState previous = currentSnapshot;
        AccountStoreState replacement = readSnapshot();
        currentSnapshot = replacement;
        try {
            changes.fireChange(previous, replacement);
        } catch (RuntimeException failure) {
            LOG.warning("Failed to publish legacy account state", failure);
        }
    }

    /// Applies a stable selection command on the JavaFX application thread when the account still exists.
    ///
    /// @param accountId stable account identifier captured as plain text
    private void selectAccountOnEventThread(String accountId) {
        requireEventThread();
        if (closed.get()) {
            return;
        }
        for (Account account : Accounts.getAccounts()) {
            if (account.getAccountID().toString().equals(accountId)) {
                Accounts.setSelectedAccount(account);
                return;
            }
        }
    }

    /// Attaches and detaches server metadata listeners to match current account references by identity.
    private void synchronizeServerListeners() {
        requireEventThread();
        Set<AuthlibInjectorServer> requiredServers =
                Collections.newSetFromMap(new IdentityHashMap<>());
        for (Account account : Accounts.getAccounts()) {
            if (account instanceof AuthlibInjectorAccount authlibAccount) {
                requiredServers.add(authlibAccount.getServer());
            }
        }

        Iterator<AuthlibInjectorServer> iterator = observedServers.iterator();
        while (iterator.hasNext()) {
            AuthlibInjectorServer server = iterator.next();
            if (!requiredServers.contains(server)) {
                server.removeListener(serverMetadataListener);
                iterator.remove();
            }
        }
        for (AuthlibInjectorServer server : requiredServers) {
            if (observedServers.add(server)) {
                server.addListener(serverMetadataListener);
            }
        }
    }

    /// Removes every JavaFX listener owned by this adapter on the JavaFX application thread.
    private void removeLegacyListeners() {
        requireEventThread();
        Accounts.getAccounts().removeListener(accountsListener);
        Accounts.selectedAccountProperty().removeListener(selectionListener);
        for (AuthlibInjectorServer server : observedServers) {
            server.removeListener(serverMetadataListener);
        }
        observedServers.clear();
    }

    /// Captures current accounts, display metadata, and selection as immutable plain values.
    ///
    /// @return presentation-safe account state
    private static AccountStoreState readSnapshot() {
        requireEventThread();
        List<AccountDescriptor> descriptors = new ArrayList<>(Accounts.getAccounts().size());
        for (Account account : Accounts.getAccounts()) {
            descriptors.add(toDescriptor(account));
        }

        @Nullable Account selectedAccount = Accounts.getSelectedAccount();
        @Nullable String selectedAccountId = selectedAccount == null
                ? null
                : selectedAccount.getAccountID().toString();
        return new AccountStoreState(List.copyOf(descriptors), selectedAccountId);
    }

    /// Projects one authentication object without retaining it or reading credential-bearing fields.
    ///
    /// @param account legacy account read only during this JavaFX-thread call
    /// @return immutable presentation-safe descriptor
    private static AccountDescriptor toDescriptor(Account account) {
        String profileName = account.getProfileName();
        String displayName = StringUtils.isBlank(profileName)
                ? account.getProfileID().toString()
                : profileName;
        String title = account instanceof ClassicAccount classicAccount
                ? displayName + " - " + classicAccount.getLoginName()
                : displayName;
        return new AccountDescriptor(
                account.getAccountID().toString(),
                title,
                accountDetail(account),
                account.getProfileID().toString());
    }

    /// Builds localized provider, authlib-injector server, and portable-storage detail text.
    ///
    /// @param account legacy account read only during this JavaFX-thread call
    /// @return comma-separated user-visible detail text
    private static String accountDetail(Account account) {
        List<String> details = new ArrayList<>(3);
        details.add(Accounts.getLocalizedLoginTypeName(Accounts.getAccountFactory(account)));
        if (account instanceof AuthlibInjectorAccount authlibAccount) {
            details.add(i18n("account.injector.server") + ": " + authlibAccount.getServer().getName());
        }
        if (account.isPortable()) {
            details.add(i18n("account.portable"));
        }
        return String.join(", ", details);
    }
}
