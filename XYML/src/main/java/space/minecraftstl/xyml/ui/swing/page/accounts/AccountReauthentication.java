// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.accounts;

import org.jetbrains.annotations.NotNullByDefault;
import space.minecraftstl.xyml.auth.AuthInfo;

import java.util.concurrent.CompletionStage;

/// Asynchronously reauthenticates one persisted account after credential expiry.
@NotNullByDefault
public interface AccountReauthentication extends AutoCloseable {
    /// Starts reauthentication by stable account ID and returns without blocking the caller.
    ///
    /// The returned stage may be cancelled. Implementations allow only one active operation.
    ///
    /// @param accountId stable persisted account identifier
    /// @return cancellable authentication completion
    CompletionStage<AuthInfo> reauthenticate(String accountId);

    /// Cancels active authentication and releases every owned prompt and OAuth subscription.
    @Override
    void close();
}
