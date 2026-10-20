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
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import space.minecraftstl.xyml.game.GameInstanceID;
import space.minecraftstl.xyml.mcp.McpDeletionConfirmation.DeletionKind;

import javax.swing.SwingUtilities;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies launcher-owned confirmation behavior without opening a native dialog.
@NotNullByDefault
final class SwingMcpDeletionConfirmationTest {
    /// Ensures each deletion category independently controls whether its dialog is shown.
    @Test
    void routesEachCategoryToItsOwnPreference() {
        AtomicInteger dialogCalls = new AtomicInteger();
        SwingMcpDeletionConfirmation instanceConfirmation = new SwingMcpDeletionConfirmation(
                kind -> {
                    assertTrue(SwingUtilities.isEventDispatchThread());
                    return kind == McpDeletionConfirmation.DeletionKind.INSTANCE;
                },
                () -> true,
                kind -> {
                    throw new AssertionError("Unexpected confirmation preference change for " + kind);
                },
                (owner, message, disableConfirmationMessage, title, confirmationWritable) -> {
                    dialogCalls.incrementAndGet();
                    return new SwingMcpDeletionConfirmation.ConfirmationDecision(false, false);
                });
        McpDeletionConfirmation.DeletionRequest instance = McpDeletionConfirmation.DeletionRequest.instance(
                new GameInstanceID("demo"));
        McpDeletionConfirmation.DeletionRequest mods = McpDeletionConfirmation.DeletionRequest.mods(
                new GameInstanceID("demo"), 2);

        assertFalse(instanceConfirmation.confirm(instance));
        assertTrue(instanceConfirmation.confirm(mods));
        assertEquals(1, dialogCalls.get());

        SwingMcpDeletionConfirmation modConfirmation = new SwingMcpDeletionConfirmation(
                kind -> {
                    assertTrue(SwingUtilities.isEventDispatchThread());
                    return kind == McpDeletionConfirmation.DeletionKind.MODS;
                },
                () -> true,
                kind -> {
                    throw new AssertionError("Unexpected confirmation preference change for " + kind);
                },
                (owner, message, disableConfirmationMessage, title, confirmationWritable) -> {
                    dialogCalls.incrementAndGet();
                    return new SwingMcpDeletionConfirmation.ConfirmationDecision(false, false);
                });

        assertTrue(modConfirmation.confirm(instance));
        assertFalse(modConfirmation.confirm(mods));
        assertEquals(2, dialogCalls.get());
    }

    /// Ensures enabled confirmation runs on the EDT and returns the user's current decision.
    @Test
    void followsDialogDecisionWhenConfirmationIsEnabled() {
        AtomicBoolean decision = new AtomicBoolean();
        AtomicInteger dialogCalls = new AtomicInteger();
        SwingMcpDeletionConfirmation confirmation = new SwingMcpDeletionConfirmation(
                kind -> true,
                () -> {
                    assertTrue(SwingUtilities.isEventDispatchThread());
                    return true;
                },
                kind -> {
                    throw new AssertionError("Unexpected confirmation preference change for " + kind);
                },
                (owner, message, disableConfirmationMessage, title, confirmationWritable) -> {
                    assertTrue(SwingUtilities.isEventDispatchThread());
                    assertTrue(message.contains("demo"));
                    assertFalse(disableConfirmationMessage.isBlank());
                    assertFalse(title.isBlank());
                    assertTrue(confirmationWritable);
                    dialogCalls.incrementAndGet();
                    return new SwingMcpDeletionConfirmation.ConfirmationDecision(decision.get(), false);
                });

        McpDeletionConfirmation.DeletionRequest request = McpDeletionConfirmation.DeletionRequest.mods(
                new GameInstanceID("demo"), 2);
        assertFalse(confirmation.confirm(request));
        decision.set(true);
        assertTrue(confirmation.confirm(request));
        assertEquals(2, dialogCalls.get());
    }

    /// Ensures opting out affects only an approved deletion category.
    @Test
    void disablesMatchingCategoryOnlyAfterApproval() {
        AtomicReference<@Nullable DeletionKind> disabledKind = new AtomicReference<>();
        McpDeletionConfirmation.DeletionRequest instance = McpDeletionConfirmation.DeletionRequest.instance(
                new GameInstanceID("demo"));

        SwingMcpDeletionConfirmation cancelled = new SwingMcpDeletionConfirmation(
                kind -> true,
                () -> true,
                disabledKind::set,
                (owner, message, disableConfirmationMessage, title, confirmationWritable) ->
                        new SwingMcpDeletionConfirmation.ConfirmationDecision(false, true));
        assertFalse(cancelled.confirm(instance));
        assertNull(disabledKind.get());

        SwingMcpDeletionConfirmation unchecked = new SwingMcpDeletionConfirmation(
                kind -> true,
                () -> true,
                disabledKind::set,
                (owner, message, disableConfirmationMessage, title, confirmationWritable) ->
                        new SwingMcpDeletionConfirmation.ConfirmationDecision(true, false));
        assertTrue(unchecked.confirm(instance));
        assertNull(disabledKind.get());

        McpDeletionConfirmation.DeletionRequest mods = McpDeletionConfirmation.DeletionRequest.mods(
                new GameInstanceID("demo"), 2);
        SwingMcpDeletionConfirmation approved = new SwingMcpDeletionConfirmation(
                kind -> true,
                () -> true,
                kind -> {
                    assertTrue(SwingUtilities.isEventDispatchThread());
                    disabledKind.set(kind);
                },
                (owner, message, disableConfirmationMessage, title, confirmationWritable) ->
                        new SwingMcpDeletionConfirmation.ConfirmationDecision(true, true));
        assertTrue(approved.confirm(mods));
        assertEquals(McpDeletionConfirmation.DeletionKind.MODS, disabledKind.get());
    }

    /// Ensures read-only settings never offer or apply the opt-out request.
    @Test
    void rejectsOptOutWhenSettingsAreReadOnly() {
        AtomicBoolean disableCalled = new AtomicBoolean();
        SwingMcpDeletionConfirmation confirmation = new SwingMcpDeletionConfirmation(
                kind -> true,
                () -> {
                    assertTrue(SwingUtilities.isEventDispatchThread());
                    return false;
                },
                kind -> disableCalled.set(true),
                (owner, message, disableConfirmationMessage, title, confirmationWritable) -> {
                    assertFalse(confirmationWritable);
                    return new SwingMcpDeletionConfirmation.ConfirmationDecision(true, true);
                });

        assertTrue(confirmation.confirm(McpDeletionConfirmation.DeletionRequest.instance(
                new GameInstanceID("demo"))));
        assertFalse(disableCalled.get());
    }

    /// Ensures an approved opt-out immediately bypasses only later requests of the same category.
    @Test
    void appliesEachOptOutImmediatelyAndIndependently() {
        AtomicBoolean instanceRequired = new AtomicBoolean(true);
        AtomicBoolean modsRequired = new AtomicBoolean(true);
        AtomicInteger dialogCalls = new AtomicInteger();
        SwingMcpDeletionConfirmation confirmation = new SwingMcpDeletionConfirmation(
                kind -> switch (kind) {
                    case INSTANCE -> instanceRequired.get();
                    case MODS -> modsRequired.get();
                },
                () -> true,
                kind -> {
                    switch (kind) {
                        case INSTANCE -> instanceRequired.set(false);
                        case MODS -> modsRequired.set(false);
                    }
                },
                (owner, message, disableConfirmationMessage, title, confirmationWritable) -> {
                    dialogCalls.incrementAndGet();
                    return new SwingMcpDeletionConfirmation.ConfirmationDecision(true, true);
                });
        McpDeletionConfirmation.DeletionRequest instance = McpDeletionConfirmation.DeletionRequest.instance(
                new GameInstanceID("demo"));
        McpDeletionConfirmation.DeletionRequest mods = McpDeletionConfirmation.DeletionRequest.mods(
                new GameInstanceID("demo"), 2);

        assertTrue(confirmation.confirm(instance));
        assertTrue(confirmation.confirm(instance));
        assertEquals(1, dialogCalls.get());
        assertTrue(modsRequired.get());

        assertTrue(confirmation.confirm(mods));
        assertTrue(confirmation.confirm(mods));
        assertEquals(2, dialogCalls.get());
    }
}
