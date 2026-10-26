// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.accounts;

import org.jetbrains.annotations.NotNullByDefault;

/// Authentication methods supported by the native Swing add-account workflow.
@NotNullByDefault
public enum AccountCreationMethod {
    /// Creates a local offline profile without contacting an authentication service.
    OFFLINE,

    /// Authenticates through the Microsoft account service.
    MICROSOFT,

    /// Authenticates through one configured authlib-injector server.
    AUTHLIB_INJECTOR
}
