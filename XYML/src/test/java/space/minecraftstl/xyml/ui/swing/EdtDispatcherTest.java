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
