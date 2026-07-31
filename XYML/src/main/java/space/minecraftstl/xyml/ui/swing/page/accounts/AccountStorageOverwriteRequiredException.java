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

import java.util.Objects;

/// Signals that deleting one account requires explicit backup-and-overwrite consent.
@NotNullByDefault
public final class AccountStorageOverwriteRequiredException extends IllegalStateException {
    /// Stable identifier of the account whose storage is read-only.
    private final String accountId;

    /// Creates a read-only storage signal for one exact account.
    ///
    /// @param accountId stable account identifier
    /// @param message localized explanation of the incompatible storage version
    AccountStorageOverwriteRequiredException(String accountId, String message) {
        super(Objects.requireNonNull(message, "message"));
        this.accountId = Objects.requireNonNull(accountId, "accountId");
    }

    /// Returns the stable account identifier associated with this signal.
    ///
    /// @return stable account identifier
    public String accountId() {
        return accountId;
    }
}
