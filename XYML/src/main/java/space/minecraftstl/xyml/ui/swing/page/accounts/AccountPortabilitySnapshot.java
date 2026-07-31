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

/// Immutable storage-location state for one launcher account.
///
/// @param accountId stable launcher account identifier
/// @param portable whether metadata and credentials are stored beside the launcher
/// @param writable whether both source and destination account stores currently accept writes
@NotNullByDefault
public record AccountPortabilitySnapshot(
        String accountId,
        boolean portable,
        boolean writable) {
    /// Validates one account storage-location snapshot.
    public AccountPortabilitySnapshot {
        Objects.requireNonNull(accountId, "accountId");
        if (accountId.isBlank()) {
            throw new IllegalArgumentException("accountId must not be blank");
        }
    }
}
