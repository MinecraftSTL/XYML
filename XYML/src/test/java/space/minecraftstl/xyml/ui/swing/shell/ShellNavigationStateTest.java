// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.shell;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests toolkit-neutral destination selection behavior.
@NotNullByDefault
public final class ShellNavigationStateTest {
    /// Re-selecting the current destination is a no-op while another destination changes state.
    @Test
    public void reportsOnlyRealDestinationChanges() {
        ShellNavigationState state = new ShellNavigationState(ShellPageId.INSTANCES);

        assertFalse(state.select(ShellPageId.INSTANCES));
        assertTrue(state.select(ShellPageId.DOWNLOADS));
        assertEquals(ShellPageId.DOWNLOADS, state.selectedPage());
        assertTrue(state.select(ShellPageId.INSTANCES));
        assertEquals(ShellPageId.INSTANCES, state.selectedPage());
    }

    /// Clearing a side destination exposes the persistent main page and is idempotent.
    @Test
    public void representsPersistentMainPageWithoutSelectingASideDestination() {
        ShellNavigationState state = new ShellNavigationState();

        assertNull(state.selectedPage());
        assertFalse(state.clear());
        assertTrue(state.select(ShellPageId.ACCOUNTS));
        assertTrue(state.clear());
        assertNull(state.selectedPage());
        assertFalse(state.clear());
    }
}
