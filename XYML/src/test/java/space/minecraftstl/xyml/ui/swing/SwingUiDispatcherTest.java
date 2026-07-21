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
