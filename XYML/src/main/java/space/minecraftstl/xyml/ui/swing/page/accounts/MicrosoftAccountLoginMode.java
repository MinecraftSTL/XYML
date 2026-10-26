// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.accounts;

import org.jetbrains.annotations.NotNullByDefault;

/// Microsoft OAuth grant modes exposed by the Swing account dialog.
@NotNullByDefault
public enum MicrosoftAccountLoginMode {
    /// Uses a local callback server and authorization page in the system browser.
    BROWSER,

    /// Uses a verification URI and short device code.
    DEVICE_CODE
}
