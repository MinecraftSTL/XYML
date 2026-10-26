// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

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
