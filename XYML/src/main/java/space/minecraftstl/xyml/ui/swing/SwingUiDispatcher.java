// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing;

import org.jetbrains.annotations.NotNullByDefault;
import space.minecraftstl.xyml.ui.UiDispatcher;

import javax.swing.SwingUtilities;
import java.util.Objects;

/// Adapts the toolkit-neutral [UiDispatcher] contract to the Swing event dispatch thread.
@NotNullByDefault
public enum SwingUiDispatcher implements UiDispatcher {
    /// Shared stateless dispatcher used by Swing-facing services.
    INSTANCE;

    /// Returns whether the caller is currently running on the Swing event dispatch thread.
    @Override
    public boolean isDispatchThread() {
        return SwingUtilities.isEventDispatchThread();
    }

    /// Queues an operation on the Swing event dispatch thread.
    ///
    /// @param operation the non-null operation to queue
    @Override
    public void dispatch(Runnable operation) {
        SwingUtilities.invokeLater(Objects.requireNonNull(operation, "operation"));
    }
}
