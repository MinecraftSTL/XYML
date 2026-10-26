// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.accounts;

import org.jetbrains.annotations.NotNullByDefault;

/// Exposes optional authlib-injector server management beside an account store.
///
/// The small capability interface keeps generic account-model tests and alternate stores independent
/// from launcher-specific authentication-server persistence.
@NotNullByDefault
interface AuthlibServerStoreProvider {
    /// Returns the authlib-injector server store owned by this account source.
    ///
    /// @return configured server management bridge
    AuthlibServerStore authlibServerStore();
}
