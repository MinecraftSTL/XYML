// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import javax.swing.SwingUtilities;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests the Core dispatcher adapter against the real Swing event queue without opening a window.
@NotNullByDefault
public final class SwingUiDispatcherTest {
    /// Verifies that dispatching from a worker thread moves work onto the Swing event dispatch thread.
    @Test
    public void dispatchesToSwingEventThread() throws Exception {
        assertFalse(SwingUiDispatcher.INSTANCE.isDispatchThread());

        CompletableFuture<Boolean> result = new CompletableFuture<>();
        SwingUiDispatcher.INSTANCE.dispatch(() -> result.complete(
                SwingUiDispatcher.INSTANCE.isDispatchThread() && SwingUtilities.isEventDispatchThread()));

        assertTrue(result.get(5, TimeUnit.SECONDS));
    }
}
