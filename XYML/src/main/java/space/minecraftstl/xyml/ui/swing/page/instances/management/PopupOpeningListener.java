// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.instances.management;

import org.jetbrains.annotations.NotNullByDefault;

import javax.swing.event.PopupMenuEvent;
import javax.swing.event.PopupMenuListener;
import java.util.Objects;

/// Runs one callback only when a combo-box popup opens.
@NotNullByDefault
final class PopupOpeningListener implements PopupMenuListener {
    /// Operation invoked immediately before popup choices become visible.
    private final Runnable operation;

    /// Creates a popup-opening listener.
    ///
    /// @param operation operation invoked when the popup opens
    PopupOpeningListener(Runnable operation) {
        this.operation = Objects.requireNonNull(operation, "operation");
    }

    /// Runs the configured popup-opening operation.
    @Override
    public void popupMenuWillBecomeVisible(PopupMenuEvent event) {
        operation.run();
    }

    /// Performs no work when the popup closes.
    @Override
    public void popupMenuWillBecomeInvisible(PopupMenuEvent event) {
    }

    /// Performs no work when popup display is canceled.
    @Override
    public void popupMenuCanceled(PopupMenuEvent event) {
    }
}
