// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.accounts;

import org.jetbrains.annotations.NotNullByDefault;

/// Tracks preference loading and the native dialog for one production add-account workflow.
@NotNullByDefault
public interface AccountCreationWorkflowHandle extends AutoCloseable {
    /// Returns whether the workflow has closed or has been cancelled.
    ///
    /// @return true after terminal closure
    boolean isClosed();

    /// Cancels pending preference loading and closes any created dialog.
    @Override
    void close();
}
