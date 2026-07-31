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
import space.minecraftstl.xyml.observable.Subscription;
import space.minecraftstl.xyml.observable.ValueChangeListener;

/// Supplies account descriptors and persists account selection without exposing a UI toolkit.
@NotNullByDefault
public interface AccountStore {
    /// Returns the latest immutable account state.
    ///
    /// @return current account descriptors and selection
    AccountStoreState snapshot();

    /// Registers for future account-store transitions on the publishing thread.
    ///
    /// @param listener account-state transition listener
    /// @return independently cancellable listener registration
    Subscription subscribe(ValueChangeListener<AccountStoreState> listener);

    /// Persists selection of one stable account identifier.
    ///
    /// @param accountId stable account identifier
    void selectAccount(String accountId);

    /// Permanently removes one account by stable identifier.
    ///
    /// Implementations must leave selection empty or pointing to a remaining account before publishing the
    /// resulting structural transition.
    ///
    /// @param accountId stable account identifier
    /// @param allowReadOnlyOverwrite whether confirmed backup-and-overwrite may make newer storage writable
    /// @throws AccountStorageOverwriteRequiredException when storage is read-only and overwrite is not allowed
    void removeAccount(String accountId, boolean allowReadOnlyOverwrite);
}
