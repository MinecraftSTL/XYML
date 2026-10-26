// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.accounts;

import org.jetbrains.annotations.NotNullByDefault;
import space.minecraftstl.xyml.auth.AuthInfo;

/// Opaque successfully reauthenticated account awaiting launcher-state persistence.
@NotNullByDefault
public interface PreparedReauthentication {
    /// Returns the stable account identifier.
    ///
    /// @return account identifier
    String accountId();

    /// Returns launch authentication data.
    ///
    /// @return authenticated launch data
    AuthInfo authInfo();
}
