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
package space.minecraftstl.xyml.game.launch;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies ordinary, quick-play, and test-mode launch requests.
@NotNullByDefault
final class LaunchRequestTest {
    /// The three-identifier constructor describes an ordinary launch with no optional mode.
    @Test
    void ordinaryConstructorLeavesOptionalModesDisabled() {
        LaunchRequest request = new LaunchRequest("account", "directory", "instance");

        assertNull(request.quickPlaySingleplayer());
        assertFalse(request.testMode());
    }

    /// A valid single-player folder is retained exactly without display-name normalization.
    @Test
    void capturesExactSingleplayerWorldFolder() {
        LaunchRequest request = new LaunchRequest(
                "account",
                "directory",
                "instance",
                "World Folder");

        assertEquals("World Folder", request.quickPlaySingleplayer());
        assertFalse(request.testMode());
    }

    /// Blank quick-play targets are rejected before any background preparation is scheduled.
    @Test
    void rejectsBlankSingleplayerWorldFolder() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new LaunchRequest("account", "directory", "instance", "  "));
    }

    /// The test factory enables only test-game policy and leaves quick play absent.
    @Test
    void createsExplicitTestGameRequest() {
        LaunchRequest request = LaunchRequest.test("account", "directory", "instance");

        assertNull(request.quickPlaySingleplayer());
        assertTrue(request.testMode());
    }

    /// Mutually exclusive launch modes cannot be combined through the canonical constructor.
    @Test
    void rejectsCombinedQuickPlayAndTestMode() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new LaunchRequest("account", "directory", "instance", "World Folder", true));
    }
}
