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
import space.minecraftstl.xyml.ui.swing.choice.ViewportChoiceDataSource;

import java.util.concurrent.CompletionStage;

/// Supplies exact account-list state, viewport rows, selection, and the add-account command.
///
/// Implementations must keep [AccountsSnapshot#itemCount()] and [#exactItemCount()] equal. Indexed
/// content must remain stable for one [AccountsSnapshot#contentRevision()] value.
@NotNullByDefault
public interface AccountsModel extends ViewportChoiceDataSource<AccountListItem> {
    /// Returns the latest minimal account-list state.
    ///
    /// @return current account snapshot
    AccountsSnapshot snapshot();

    /// Registers for future account-list transitions on the publishing thread.
    ///
    /// @param listener account snapshot transition listener
    /// @return independently cancellable listener registration
    Subscription subscribe(ValueChangeListener<AccountsSnapshot> listener);

    /// Selects a loaded account by its stable identifier.
    ///
    /// @param accountId stable account identifier
    void selectAccount(String accountId);

    /// Opens the add-account workflow.
    void addAccount();

    /// Permanently removes one currently loaded account by stable identifier.
    ///
    /// @param accountId stable account identifier
    /// @param allowReadOnlyOverwrite whether confirmed backup-and-overwrite may make newer storage writable
    /// @throws AccountStorageOverwriteRequiredException when storage is read-only and overwrite is not allowed
    void removeAccount(String accountId, boolean allowReadOnlyOverwrite);

    /// Starts asynchronous authentication and persistence for one currently loaded account.
    ///
    /// @param accountId stable account identifier
    /// @return completion after refreshed authentication data has been persisted and released
    CompletionStage<Void> refreshAccount(String accountId);
}
