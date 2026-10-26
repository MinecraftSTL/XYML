// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.accounts;

import org.jetbrains.annotations.NotNullByDefault;

import java.util.Objects;

/// Localizable visible and accessible text for the account-selection page.
///
/// @param pageTitle page heading
/// @param addAction add-account command label
/// @param refreshAction refresh or reauthentication command label
/// @param copyUuidAction profile UUID copy command label
/// @param removeAction permanent removal command label
/// @param removeConfirmation destructive removal confirmation
/// @param errorTitle account-action error title
/// @param emptyText text shown when no account exists
@NotNullByDefault
public record AccountsStrings(
        String pageTitle,
        String addAction,
        String refreshAction,
        String copyUuidAction,
        String removeAction,
        String removeConfirmation,
        String errorTitle,
        String emptyText) {
    /// Validates localized account-page text.
    public AccountsStrings {
        Objects.requireNonNull(pageTitle, "pageTitle");
        Objects.requireNonNull(addAction, "addAction");
        Objects.requireNonNull(refreshAction, "refreshAction");
        Objects.requireNonNull(copyUuidAction, "copyUuidAction");
        Objects.requireNonNull(removeAction, "removeAction");
        Objects.requireNonNull(removeConfirmation, "removeConfirmation");
        Objects.requireNonNull(errorTitle, "errorTitle");
        Objects.requireNonNull(emptyText, "emptyText");
    }
}
