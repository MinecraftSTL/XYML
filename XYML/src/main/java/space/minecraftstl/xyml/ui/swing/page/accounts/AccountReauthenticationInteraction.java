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

/// Blocking prompt boundary invoked by the reauthentication worker and progress boundary invoked on the UI thread.
@NotNullByDefault
public interface AccountReauthenticationInteraction {
    /// Confirms backup-and-overwrite before any credentials are mutated.
    ///
    /// @param target read-only account target
    /// @return true to permit successful persistence
    boolean confirmReadOnlyStorage(AccountReauthenticationTarget target);

    /// Requests a password, optionally showing the previous localized authentication error.
    ///
    /// The caller clears the returned mutable array immediately after constructing the transient password string.
    ///
    /// @param target classic account target
    /// @param localizedError previous localized error, or null for the first attempt
    /// @return newly allocated password characters
    /// @throws AccountReauthenticationCancelledException when the user cancels
    char[] requestPassword(
            AccountReauthenticationTarget target,
            @Nullable String localizedError) throws AccountReauthenticationCancelledException;

    /// Confirms retry after one localized OAuth failure.
    ///
    /// @param target OAuth target
    /// @param localizedError localized failure message
    /// @return true to retry, false to cancel
    boolean confirmOAuthRetry(AccountReauthenticationTarget target, String localizedError);

    /// Presents one credential-free progress transition on the UI thread.
    ///
    /// @param target active target
    /// @param notice progress notice
    void onProgress(AccountReauthenticationTarget target, AccountReauthenticationNotice notice);

    /// Presents one terminal localized failure.
    ///
    /// @param target active target
    /// @param localizedError localized failure message
    void showFailure(AccountReauthenticationTarget target, String localizedError);

    /// Closes the current password, confirmation, or OAuth progress UI.
    void closeCurrentInteraction();
}
