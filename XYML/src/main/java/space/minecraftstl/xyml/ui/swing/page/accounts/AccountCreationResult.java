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

/// Plain successful result published after the account is committed and selected.
///
/// @param accountId stable account identifier
/// @param displayName authenticated profile name
/// @param method authentication method
/// @param portable whether the account was stored in workspace-local files
@NotNullByDefault
public record AccountCreationResult(
        String accountId,
        String displayName,
        AccountCreationMethod method,
        boolean portable) {
    /// Validates one immutable successful result.
    public AccountCreationResult {
        accountId = Objects.requireNonNull(accountId, "accountId");
        displayName = Objects.requireNonNull(displayName, "displayName");
        method = Objects.requireNonNull(method, "method");
    }
}
