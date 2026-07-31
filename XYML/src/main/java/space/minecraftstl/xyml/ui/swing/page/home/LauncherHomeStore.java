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
import space.minecraftstl.xyml.auth.Account;
import space.minecraftstl.xyml.game.GameInstanceID;
import space.minecraftstl.xyml.game.XYMLGameRepository;
import space.minecraftstl.xyml.observable.Subscription;
import space.minecraftstl.xyml.observable.ValueChangeListener;
import space.minecraftstl.xyml.observable.ValueChangeSupport;
import space.minecraftstl.xyml.setting.Accounts;
import space.minecraftstl.xyml.setting.GameDirectoryManager;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

import static space.minecraftstl.xyml.ui.swing.runtime.LauncherStateDispatcher.execute;
import static space.minecraftstl.xyml.ui.swing.runtime.LauncherStateDispatcher.requireEventThread;

/// Store projecting launcher account and repository selections into immutable Swing home-page state.
@NotNullByDefault
public final class LauncherHomeStore implements HomeSelectionStore, AutoCloseable {
    /// Serializes listener registration with the transition to the closed lifecycle state.
    private final Object lifecycleLock = new Object();

    /// Home selection transition publisher.
    private final ValueChangeSupport<HomeSelectionState> changes = new ValueChangeSupport<>(this);

    /// Subscription to selected-account changes.
    private final Subscription accountSelectionSubscription;

    /// Subscription to selected-repository changes.
    private final Subscription repositorySelectionSubscription;

    /// Subscription to selected-instance changes.
    private final Subscription instanceSelectionSubscription;

    /// Latest cross-thread-safe selection state.
    private volatile HomeSelectionState currentSnapshot;

    /// Whether closure has been requested from any thread.
    private final AtomicBoolean closed = new AtomicBoolean();

    /// Creates a store after Accounts and GameDirectoryManager initialization on the Swing event thread.
    public LauncherHomeStore() {
        requireEventThread();
        currentSnapshot = readSnapshot();
        accountSelectionSubscription = Accounts.selectedAccountProperty()
                .subscribe(change -> execute(this::refreshSnapshot));
        repositorySelectionSubscription = GameDirectoryManager.selectedRepositoryProperty()
                .subscribe(change -> execute(this::refreshSnapshot));
        instanceSelectionSubscription = GameDirectoryManager.selectedInstanceProperty()
                .subscribe(change -> execute(this::refreshSnapshot));
    }

    /// Returns the latest home selection state.
    @Override
    public HomeSelectionState snapshot() {
        return currentSnapshot;
    }

    /// Registers a selection-state listener.
    @Override
    public Subscription subscribe(ValueChangeListener<HomeSelectionState> listener) {
        Objects.requireNonNull(listener, "listener");
        synchronized (lifecycleLock) {
            if (closed.get()) {
                throw new IllegalStateException("Launcher launcher home store is closed");
            }
            return changes.subscribe(listener);
        }
    }

    /// Requests idempotent subscription removal without blocking the caller.
    @Override
    public void close() {
        synchronized (lifecycleLock) {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
        }
        execute(this::removeSubscriptions);
    }

    /// Rebuilds and publishes immutable values after a launcher selection changes.
    private void refreshSnapshot() {
        requireEventThread();
        if (closed.get()) {
            return;
        }
        HomeSelectionState previous = currentSnapshot;
        HomeSelectionState replacement = readSnapshot();
        currentSnapshot = replacement;
        changes.fireChange(previous, replacement);
    }

    /// Removes every selection subscription on the Swing event thread.
    private void removeSubscriptions() {
        requireEventThread();
        accountSelectionSubscription.unsubscribe();
        repositorySelectionSubscription.unsubscribe();
        instanceSelectionSubscription.unsubscribe();
    }

    /// Reads current launcher selections without performing expensive version resolution.
    ///
    /// @return immutable plain selection state
    private static HomeSelectionState readSnapshot() {
        requireEventThread();
        @Nullable Account account = Accounts.getSelectedAccount();
        String accountId = account == null ? "" : account.getAccountID().toString();
        String accountName = account == null ? "" : account.getProfileName();
        String accountDetail = account == null ? "" : accountType(account);

        XYMLGameRepository repository = GameDirectoryManager.getSelectedRepository();
        String gameDirectoryId = repository.getGameDirectory().getId().toString();
        @Nullable GameInstanceID selectedInstanceId = repository.getSelectedInstance();
        String instanceDetail = selectedInstanceId == null
                ? ""
                : GameDirectoryManager.getGameDirectoryDisplayName(repository.getGameDirectory());
        return new HomeSelectionState(
                accountId,
                gameDirectoryId,
                selectedInstanceId,
                accountName,
                accountDetail,
                selectedInstanceId == null ? "" : selectedInstanceId.id(),
                instanceDetail);
    }

    /// Returns a stable raw provider identifier for one launcher account.
    ///
    /// @param account selected account
    /// @return provider identifier, or empty when the account type is unknown
    private static String accountType(Account account) {
        try {
            return Accounts.getLoginType(Accounts.getAccountFactory(account));
        } catch (IllegalArgumentException failure) {
            return "";
        }
    }
}
