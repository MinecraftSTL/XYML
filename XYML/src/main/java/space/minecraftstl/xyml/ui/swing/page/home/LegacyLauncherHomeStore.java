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

import javafx.beans.value.ChangeListener;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import space.minecraftstl.xyml.auth.Account;
import space.minecraftstl.xyml.game.XYMLGameRepository;
import space.minecraftstl.xyml.observable.Subscription;
import space.minecraftstl.xyml.observable.ValueChangeListener;
import space.minecraftstl.xyml.observable.ValueChangeSupport;
import space.minecraftstl.xyml.setting.Accounts;
import space.minecraftstl.xyml.setting.GameDirectoryManager;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

import static space.minecraftstl.xyml.ui.swing.legacy.LegacyJavaFxDispatcher.execute;
import static space.minecraftstl.xyml.ui.swing.legacy.LegacyJavaFxDispatcher.requireEventThread;

/// Transitional store projecting legacy JavaFX account and repository selections into plain strings.
@NotNullByDefault
public final class LegacyLauncherHomeStore implements HomeSelectionStore, AutoCloseable {
    /// Serializes listener registration with the transition to the closed lifecycle state.
    private final Object lifecycleLock = new Object();

    /// Home selection transition publisher.
    private final ValueChangeSupport<HomeSelectionState> changes = new ValueChangeSupport<>(this);

    /// Shared listener for selected account, repository, and instance properties.
    private final ChangeListener<Object> selectionListener =
            (observable, previous, current) -> execute(this::refreshSnapshot);

    /// Latest cross-thread-safe plain selection state.
    private volatile HomeSelectionState currentSnapshot;

    /// Whether closure has been requested from any thread.
    private final AtomicBoolean closed = new AtomicBoolean();

    /// Creates a store after Accounts and GameDirectoryManager initialization on the JavaFX thread.
    public LegacyLauncherHomeStore() {
        requireEventThread();
        currentSnapshot = readSnapshot();
        Accounts.selectedAccountProperty().addListener(selectionListener);
        GameDirectoryManager.selectedRepositoryProperty().addListener(selectionListener);
        GameDirectoryManager.selectedInstanceProperty().addListener(selectionListener);
    }

    /// Returns the latest plain home selection state.
    @Override
    public HomeSelectionState snapshot() {
        return currentSnapshot;
    }

    /// Registers a plain selection-state listener.
    @Override
    public Subscription subscribe(ValueChangeListener<HomeSelectionState> listener) {
        Objects.requireNonNull(listener, "listener");
        synchronized (lifecycleLock) {
            if (closed.get()) {
                throw new IllegalStateException("Legacy launcher home store is closed");
            }
            return changes.subscribe(listener);
        }
    }

    /// Requests idempotent JavaFX listener removal without blocking a Swing EDT caller.
    @Override
    public void close() {
        synchronized (lifecycleLock) {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
        }
        execute(this::removeLegacyListeners);
    }

    /// Rebuilds and publishes plain strings after a legacy selection changes.
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

    /// Removes every JavaFX selection listener on the JavaFX application thread.
    private void removeLegacyListeners() {
        requireEventThread();
        Accounts.selectedAccountProperty().removeListener(selectionListener);
        GameDirectoryManager.selectedRepositoryProperty().removeListener(selectionListener);
        GameDirectoryManager.selectedInstanceProperty().removeListener(selectionListener);
    }

    /// Reads current legacy selections without performing expensive version resolution.
    ///
    /// @return immutable plain selection state
    private static HomeSelectionState readSnapshot() {
        requireEventThread();
        @Nullable Account account = Accounts.getSelectedAccount();
        String accountName = account == null ? "" : account.getProfileName();
        String accountDetail = account == null ? "" : accountType(account);

        XYMLGameRepository repository = GameDirectoryManager.getSelectedRepository();
        @Nullable String instanceId = repository.getSelectedInstance();
        String instanceDetail = instanceId == null
                ? ""
                : GameDirectoryManager.getGameDirectoryDisplayName(repository.getGameDirectory());
        return new HomeSelectionState(
                accountName,
                accountDetail,
                Objects.requireNonNullElse(instanceId, ""),
                instanceDetail);
    }

    /// Returns a stable raw provider identifier for one legacy account.
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
