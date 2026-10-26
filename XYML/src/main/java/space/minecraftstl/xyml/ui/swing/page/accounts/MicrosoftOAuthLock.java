// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.accounts;

import org.jetbrains.annotations.NotNullByDefault;

/// Owns the process-wide monitor for subscriptions to the launcher shared Microsoft OAuth callback.
@NotNullByDefault
final class MicrosoftOAuthLock {
    /// Shared monitor excluding overlapping native account creation and reauthentication flows.
    private static final Object MONITOR = new Object();

    /// Prevents utility instantiation.
    private MicrosoftOAuthLock() {
    }

    /// Returns the shared synchronization monitor.
    ///
    /// @return process-wide OAuth monitor
    static Object monitor() {
        return MONITOR;
    }
}
