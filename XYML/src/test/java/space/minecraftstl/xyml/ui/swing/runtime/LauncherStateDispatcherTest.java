// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.runtime;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import javax.swing.SwingUtilities;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies launcher-state serialization on the Swing event dispatch thread.
@NotNullByDefault
final class LauncherStateDispatcherTest {
    /// Asynchronous dispatch reaches the Swing event dispatch thread.
    @Test
    void executeUsesSwingEventDispatchThread() throws InterruptedException {
        CountDownLatch completed = new CountDownLatch(1);
        AtomicBoolean eventThread = new AtomicBoolean();

        LauncherStateDispatcher.execute(() -> {
            eventThread.set(SwingUtilities.isEventDispatchThread());
            completed.countDown();
        });

        assertTrue(completed.await(5, TimeUnit.SECONDS));
        assertTrue(eventThread.get());
    }

    /// Synchronous dispatch completes on the Swing event dispatch thread before returning.
    @Test
    void executeAndWaitUsesSwingEventDispatchThread() {
        AtomicBoolean eventThread = new AtomicBoolean();

        LauncherStateDispatcher.executeAndWait(() ->
                eventThread.set(SwingUtilities.isEventDispatchThread()));

        assertTrue(eventThread.get());
    }

    /// Direct launcher-state access rejects a worker thread.
    @Test
    void requireEventThreadRejectsWorkerThread() {
        assertThrows(IllegalStateException.class, LauncherStateDispatcher::requireEventThread);
    }
}
