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
package space.minecraftstl.xyml.mcp;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import space.minecraftstl.xyml.game.GameInstanceID;

import javax.swing.SwingUtilities;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies launcher-owned confirmation behavior without opening a native dialog.
@NotNullByDefault
final class SwingMcpDeletionConfirmationTest {
    /// Ensures disabling confirmation authorizes deletion without invoking the dialog boundary.
    @Test
    void bypassesDialogWhenConfirmationIsDisabled() {
        AtomicInteger dialogCalls = new AtomicInteger();
        SwingMcpDeletionConfirmation confirmation = new SwingMcpDeletionConfirmation(
                () -> false,
                (owner, message, title) -> {
                    dialogCalls.incrementAndGet();
                    return false;
                });

        assertTrue(confirmation.confirm(McpDeletionConfirmation.DeletionRequest.instance(
                new GameInstanceID("demo"))));
        assertEquals(0, dialogCalls.get());
    }

    /// Ensures enabled confirmation runs on the EDT and returns the user's current decision.
    @Test
    void followsDialogDecisionWhenConfirmationIsEnabled() {
        AtomicBoolean decision = new AtomicBoolean();
        AtomicInteger dialogCalls = new AtomicInteger();
        SwingMcpDeletionConfirmation confirmation = new SwingMcpDeletionConfirmation(
                () -> true,
                (owner, message, title) -> {
                    assertTrue(SwingUtilities.isEventDispatchThread());
                    assertTrue(message.contains("demo"));
                    assertFalse(title.isBlank());
                    dialogCalls.incrementAndGet();
                    return decision.get();
                });

        McpDeletionConfirmation.DeletionRequest request = McpDeletionConfirmation.DeletionRequest.mods(
                new GameInstanceID("demo"), 2);
        assertFalse(confirmation.confirm(request));
        decision.set(true);
        assertTrue(confirmation.confirm(request));
        assertEquals(2, dialogCalls.get());
    }
}
