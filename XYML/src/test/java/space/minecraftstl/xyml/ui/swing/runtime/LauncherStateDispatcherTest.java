/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2026 huangyuhui <huanghongxun2008@126.com> and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
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
