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
import org.jetbrains.annotations.Unmodifiable;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/// Immutable account-store state containing only presentation-safe text and stable identifiers.
///
/// A selected identifier that is absent from `accounts` is retained so a temporarily inconsistent
/// backing store can converge without inventing a selected row. The model maps it to no selection.
///
/// @param accounts immutable account descriptors in display order
/// @param selectedAccountId selected stable identifier, or null for no selected account
@NotNullByDefault
public record AccountStoreState(
        @Unmodifiable List<AccountDescriptor> accounts,
        @Nullable String selectedAccountId) {
    /// Defensively copies descriptors and rejects duplicate stable identifiers.
    public AccountStoreState {
        accounts = List.copyOf(accounts);
        Set<String> accountIds = new HashSet<>();
        for (AccountDescriptor account : accounts) {
            if (!accountIds.add(account.id())) {
                throw new IllegalArgumentException("Duplicate account id: " + account.id());
            }
        }
        if (selectedAccountId != null && selectedAccountId.isBlank()) {
            throw new IllegalArgumentException("Selected account id cannot be blank");
        }
    }
}
