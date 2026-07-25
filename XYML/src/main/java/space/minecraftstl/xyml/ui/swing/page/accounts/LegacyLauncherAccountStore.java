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
import space.minecraftstl.xyml.auth.Account;
import space.minecraftstl.xyml.auth.ClassicAccount;
import space.minecraftstl.xyml.auth.authlibinjector.AuthlibInjectorAccount;
import space.minecraftstl.xyml.auth.authlibinjector.AuthlibInjectorServer;
import space.minecraftstl.xyml.auth.offline.OfflineAccount;
import space.minecraftstl.xyml.auth.offline.Skin;
import space.minecraftstl.xyml.observable.Subscription;
import space.minecraftstl.xyml.observable.ValueChangeListener;
import space.minecraftstl.xyml.observable.ValueChangeSupport;
import space.minecraftstl.xyml.setting.Accounts;
import space.minecraftstl.xyml.util.StringUtils;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static space.minecraftstl.xyml.ui.swing.legacy.LegacyStateDispatcher.execute;
import static space.minecraftstl.xyml.ui.swing.legacy.LegacyStateDispatcher.requireEventThread;
import static space.minecraftstl.xyml.util.i18n.I18n.i18n;
import static space.minecraftstl.xyml.util.logging.Logger.LOG;

/// Adapter that projects the account state model into immutable presentation-safe values.
///
/// Construction must occur on the state event thread after [Accounts#init()]. The adapter never publishes
/// account objects, server objects, authentication tokens, passwords, or private serialized account data.
@NotNullByDefault
public final class LegacyLauncherAccountStore
        implements AccountStore, AuthlibServerStoreProvider, OfflineSkinStoreProvider, OfflineSkinStore, AutoCloseable {
    /// Serializes listener registration with the transition to the closed lifecycle state.
    private final Object lifecycleLock = new Object();

    /// Plain account-state transition publisher.
    private final ValueChangeSupport<AccountStoreState> changes = new ValueChangeSupport<>(this);

    /// Subscription for account additions, removals, reordering, and extractor-driven account updates.
    private final Subscription accountsSubscription;

    /// Subscription for the selected legacy account property.
    private final Subscription selectionSubscription;

    /// Persists and projects configured authlib-injector servers for the accounts page.
    private final LegacyAuthlibServerStore authlibServerStore;

    /// Neutral server metadata subscriptions indexed by server identity.
    private final Map<AuthlibInjectorServer, Subscription> observedServers = new IdentityHashMap<>();

    /// Whether closure has been requested from any thread.
    private final AtomicBoolean closed = new AtomicBoolean();

    /// Latest immutable cross-thread-safe account projection.
    private volatile AccountStoreState currentSnapshot;

    /// Creates the bridge and attaches neutral state subscriptions on the legacy state event thread.
    public LegacyLauncherAccountStore() {
        requireEventThread();
        authlibServerStore = new LegacyAuthlibServerStore();
        try {
            synchronizeServerListeners();
            currentSnapshot = readSnapshot();
            accountsSubscription = Accounts.getAccountsValue().subscribe(change -> execute(this::accountListChanged));
            selectionSubscription = Accounts.selectedAccountValueProperty()
                    .subscribe(change -> execute(this::refreshSnapshot));
        } catch (RuntimeException | Error failure) {
            authlibServerStore.close();
            throw failure;
        }
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

    /// Returns the owned persistent authlib-injector server management bridge.
    ///
    /// @return configured server store associated with this account source
    @Override
    public AuthlibServerStore authlibServerStore() {
        return authlibServerStore;
    }

    /// Returns this legacy bridge as the owner of offline-account skin persistence.
    ///
    /// @return persistent offline-skin bridge
    @Override
    public OfflineSkinStore offlineSkinStore() {
        return this;
    }

    /// Reads a presentation-safe skin snapshot for one exact current offline account.
    ///
    /// @param accountId stable launcher account identifier
    /// @return skin state, or empty when the account is absent or not offline
    @Override
    public Optional<OfflineSkinSnapshot> snapshot(String accountId) {
        Objects.requireNonNull(accountId, "accountId");
        requireEventThread();
        if (closed.get()) {
            throw new IllegalStateException("Legacy launcher account store is closed");
        }
        for (Account account : Accounts.getAccountsValue()) {
            if (account.getAccountID().toString().equals(accountId)
                    && account instanceof OfflineAccount offlineAccount) {
                return Optional.of(new OfflineSkinSnapshot(
                        accountId,
                        offlineAccount.getProfileName(),
                        offlineAccount.getSkin(),
                        !Accounts.isAccountFilesReadOnly(offlineAccount)));
            }
        }
        return Optional.empty();
    }

    /// Replaces an offline account skin only when its backing metadata can be persisted.
    ///
    /// [OfflineAccount#setSkin(Skin)] publishes the change signal consumed by [Accounts], which in
    /// turn rewrites account metadata through its established persistence flow.
    ///
    /// @param accountId stable launcher account identifier
    /// @param skin replacement skin, or null to restore the launcher default
    /// @throws AccountStorageOverwriteRequiredException when newer account files are read-only
    @Override
    public void setSkin(String accountId, @Nullable Skin skin) {
        Objects.requireNonNull(accountId, "accountId");
        requireEventThread();
        if (closed.get()) {
            throw new IllegalStateException("Legacy launcher account store is closed");
        }
        OfflineAccount offlineAccount = findOfflineAccount(accountId);
        if (Accounts.isAccountFilesReadOnly(offlineAccount)) {
            throw new AccountStorageOverwriteRequiredException(
                    accountId,
                    i18n("account.storage.read_only"));
        }
        offlineAccount.setSkin(skin);
    }

    /// Selects an account by stable identifier without synchronously waiting across UI toolkit threads.
    ///
    /// A selection that became stale before the state operation runs is ignored. The current list projection will
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

    /// Removes an account by stable identifier while keeping the legacy selection valid.
    ///
    /// Read-only account files require prior user consent before backup-and-overwrite recovery.
    ///
    /// @param accountId stable persisted account identifier
    /// @param allowReadOnlyOverwrite whether confirmed backup-and-overwrite may make storage writable
    @Override
    public void removeAccount(String accountId, boolean allowReadOnlyOverwrite) {
        Objects.requireNonNull(accountId, "accountId");
        if (closed.get()) {
            throw new IllegalStateException("Legacy launcher account store is closed");
        }
        execute(() -> removeAccountOnEventThread(accountId, allowReadOnlyOverwrite));
    }

    /// Requests idempotent subscription removal without blocking a Swing EDT caller on the state event thread.
    @Override
    public void close() {
        synchronized (lifecycleLock) {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
        }
        authlibServerStore.close();
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

    /// Applies a stable selection command on the state event thread when the account still exists.
    ///
    /// @param accountId stable account identifier captured as plain text
    private void selectAccountOnEventThread(String accountId) {
        requireEventThread();
        if (closed.get()) {
            return;
        }
        for (Account account : Accounts.getAccountsValue()) {
            if (account.getAccountID().toString().equals(accountId)) {
                Accounts.setSelectedAccount(account);
                return;
            }
        }
    }

    /// Removes one exact account and selects the first remaining account before structural publication.
    ///
    /// @param accountId stable account identifier
    /// @param allowReadOnlyOverwrite whether the user confirmed destructive storage recovery
    private static void removeAccountOnEventThread(
            String accountId,
            boolean allowReadOnlyOverwrite) {
        requireEventThread();
        Account target = Accounts.getAccountsValue().stream()
                .filter(account -> account.getAccountID().toString().equals(accountId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown account: " + accountId));
        if (!Accounts.canRemoveAccount(target)) {
            if (!allowReadOnlyOverwrite) {
                throw new AccountStorageOverwriteRequiredException(
                        accountId,
                        i18n("account.storage.read_only"));
            }
            try {
                Accounts.forceOverwriteAccountFiles(target);
            } catch (IOException failure) {
                throw new UncheckedIOException(i18n("message.failed"), failure);
            }
        }

        if (Accounts.getSelectedAccount() == target) {
            @Nullable Account replacement = Accounts.getAccountsValue().stream()
                    .filter(account -> account != target)
                    .findFirst()
                    .orElse(null);
            Accounts.setSelectedAccount(replacement);
        }
        Accounts.getAccountsValue().remove(target);
    }

    /// Locates one existing offline account by its persisted launcher identifier.
    ///
    /// @param accountId stable launcher account identifier
    /// @return matching offline account
    /// @throws IllegalArgumentException when the identifier is missing or belongs to another account type
    private static OfflineAccount findOfflineAccount(String accountId) {
        requireEventThread();
        return Accounts.getAccountsValue().stream()
                .filter(account -> account.getAccountID().toString().equals(accountId))
                .filter(OfflineAccount.class::isInstance)
                .map(OfflineAccount.class::cast)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown offline account: " + accountId));
    }

    /// Attaches and detaches server metadata listeners to match current account references by identity.
    private void synchronizeServerListeners() {
        requireEventThread();
        Set<AuthlibInjectorServer> requiredServers =
                Collections.newSetFromMap(new IdentityHashMap<>());
        for (Account account : Accounts.getAccountsValue()) {
            if (account instanceof AuthlibInjectorAccount authlibAccount) {
                requiredServers.add(authlibAccount.getServer());
            }
        }

        Iterator<Map.Entry<AuthlibInjectorServer, Subscription>> iterator = observedServers.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<AuthlibInjectorServer, Subscription> entry = iterator.next();
            AuthlibInjectorServer server = entry.getKey();
            if (!requiredServers.contains(server)) {
                entry.getValue().unsubscribe();
                iterator.remove();
            }
        }
        for (AuthlibInjectorServer server : requiredServers) {
            if (!observedServers.containsKey(server)) {
                Subscription subscription = server.changes().subscribe(
                        change -> execute(this::refreshSnapshot));
                observedServers.put(server, subscription);
            }
        }
    }

    /// Removes every neutral subscription owned by this adapter on the state event thread.
    private void removeLegacyListeners() {
        requireEventThread();
        accountsSubscription.unsubscribe();
        selectionSubscription.unsubscribe();
        for (Subscription subscription : observedServers.values()) {
            subscription.unsubscribe();
        }
        observedServers.clear();
    }

    /// Captures current accounts, display metadata, and selection as immutable plain values.
    ///
    /// @return presentation-safe account state
    private static AccountStoreState readSnapshot() {
        requireEventThread();
        List<AccountDescriptor> descriptors = new ArrayList<>(Accounts.getAccountsValue().size());
        for (Account account : Accounts.getAccountsValue()) {
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
    /// @param account legacy account read only during this state-event-thread call
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
    /// @param account legacy account read only during this state-event-thread call
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
