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
package space.minecraftstl.xyml.ui.swing.shell;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
}
