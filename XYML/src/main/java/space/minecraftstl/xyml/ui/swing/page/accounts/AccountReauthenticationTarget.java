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

/// Plain account metadata needed before credential-expiry recovery starts.
///
/// @param accountId stable account identifier
/// @param displayName profile or login display name
/// @param kind recovery mechanism
/// @param portable whether credentials belong to workspace-local files
/// @param storageReadOnly whether successful credentials require confirmed backup-and-overwrite
@NotNullByDefault
public record AccountReauthenticationTarget(
        String accountId,
        String displayName,
        AccountReauthenticationKind kind,
        boolean portable,
        boolean storageReadOnly) {
    /// Validates one immutable target snapshot.
    public AccountReauthenticationTarget {
        accountId = Objects.requireNonNull(accountId, "accountId");
        displayName = Objects.requireNonNull(displayName, "displayName");
        kind = Objects.requireNonNull(kind, "kind");
    }
}
