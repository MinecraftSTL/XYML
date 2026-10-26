// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import javax.swing.SwingUtilities;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests EDT dispatch semantics without creating displayable Swing components.
@NotNullByDefault
public final class EdtDispatcherTest {
    /// Synchronous dispatch moves work to the EDT and waits for completion.
    @Test
    public void executeAndWaitRunsOnEventDispatchThread() {
        AtomicBoolean ranOnEventDispatchThread = new AtomicBoolean();

        EdtDispatcher.executeAndWait(() -> ranOnEventDispatchThread.set(SwingUtilities.isEventDispatchThread()));

        assertTrue(ranOnEventDispatchThread.get());
    }

    /// Dispatch requested from the EDT runs inline and preserves action order.
    @Test
    public void executeRunsInlineOnEventDispatchThread() {
        AtomicInteger sequence = new AtomicInteger();

        EdtDispatcher.executeAndWait(() -> {
            EdtDispatcher.execute(() -> assertEquals(0, sequence.getAndIncrement()));
            assertEquals(1, sequence.getAndIncrement());
        });

        assertEquals(2, sequence.get());
    }
}
