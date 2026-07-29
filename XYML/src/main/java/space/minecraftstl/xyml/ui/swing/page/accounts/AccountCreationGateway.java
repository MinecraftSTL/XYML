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
import org.jetbrains.annotations.Unmodifiable;

import java.util.List;
import java.util.function.Consumer;

/// Injectable authentication and persistence boundary for the Swing account workflow.
///
/// Authentication may perform network I/O and is called only on the caller-owned executor. Launcher
/// implementations must confine observable account-list reads and every storage mutation to their
/// toolkit dispatcher.
@NotNullByDefault
public interface AccountCreationGateway {
    /// Returns the configured initial method, applying any Microsoft-only policy.
    ///
    /// @return explicit initial method
    AccountCreationMethod preferredMethod();

    /// Returns whether policy currently restricts account creation to Microsoft.
    ///
    /// @return true when no method switcher may be shown
    boolean isMicrosoftOnly();

    /// Persists a user-selected method for the next unrestricted workflow.
    ///
    /// @param method selected authentication method
    void storePreferredMethod(AccountCreationMethod method);

    /// Returns an immutable snapshot of configured authlib-injector choices.
    ///
    /// @return configured server choices
    @Unmodifiable List<AuthlibServerOption> availableAuthlibServers();

    /// Authenticates one request without mutating persisted account state.
    ///
    /// @param request validated creation request
    /// @param roleSelector role chooser used when one login owns multiple profiles
    /// @param progress credential-free progress sink
    /// @return opaque prepared account
    /// @throws Exception when authentication or role selection fails
    PreparedAccount authenticate(
            AccountCreationRequest request,
            AccountRoleSelector roleSelector,
            Consumer<AccountCreationNotice> progress) throws Exception;

    /// Returns whether the prepared account's target files require backup-and-overwrite confirmation.
    ///
    /// @param account prepared account created by this gateway
    /// @return true when normal saving is disabled for the target files
    boolean isTargetReadOnly(PreparedAccount account);

    /// Backs up and overwrites the target account files before committing a prepared account.
    ///
    /// @param account prepared account created by this gateway
    /// @throws Exception when backup or overwrite fails
    void forceOverwriteTarget(PreparedAccount account) throws Exception;

    /// Adds or replaces the prepared account and selects it.
    ///
    /// @param account prepared account created by this gateway
    void commitAndSelect(PreparedAccount account);

    /// Converts a gateway failure to localized user-visible text.
    ///
    /// @param failure authentication or persistence failure
    /// @return localized, non-empty error text
    String localizeFailure(Throwable failure);

    /// Selects one plain profile from an immutable role list.
    @FunctionalInterface
    @NotNullByDefault
    interface AccountRoleSelector {
        /// Selects one profile identifier or cancels by throwing an exception.
        ///
        /// @param roles immutable available roles
        /// @return selected profile identifier text
        /// @throws AccountCreationCancelledException when selection is cancelled
        String select(@Unmodifiable List<AccountRoleOption> roles)
                throws AccountCreationCancelledException;
    }
}
