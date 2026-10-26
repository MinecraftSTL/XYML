// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.accounts;

import org.jetbrains.annotations.NotNullByDefault;

import java.util.concurrent.CompletionStage;

/// Cancellable handle for one in-flight add-account operation.
@NotNullByDefault
public interface AccountCreationOperation {
    /// Requests interruption and closes any pending native prompt.
    ///
    /// @return true when this call initiated cancellation
    boolean cancel();

    /// Returns the terminal successful or exceptional completion.
    ///
    /// Cancellation completes the stage exceptionally with a cancellation exception.
    ///
    /// @return operation completion
    CompletionStage<AccountCreationResult> completion();
}
