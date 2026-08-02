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
package space.minecraftstl.xyml.upgrade;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Verifies canonical channel identifiers and their version depths.
@NotNullByDefault
final class UpdateChannelTest {
    /// Maps every supported identifier without historical aliases.
    @Test
    void resolvesCanonicalIdentifiers() {
        assertEquals(UpdateChannel.STABLE, UpdateChannel.fromName("stable"));
        assertEquals(UpdateChannel.BETA, UpdateChannel.fromName("beta"));
        assertEquals(UpdateChannel.ALPHA, UpdateChannel.fromName("alpha"));
        assertEquals(UpdateChannel.DEV, UpdateChannel.fromName("dev"));
        assertThrows(IllegalArgumentException.class, () -> UpdateChannel.fromName("nightly"));
        assertThrows(IllegalArgumentException.class, () -> UpdateChannel.fromName("DEVELOPMENT"));
    }

    /// Assigns exactly one additional decimal component at each less-stable channel.
    @Test
    void exposesRequiredVersionDepth() {
        assertEquals(3, UpdateChannel.STABLE.versionComponentCount());
        assertEquals(4, UpdateChannel.BETA.versionComponentCount());
        assertEquals(5, UpdateChannel.ALPHA.versionComponentCount());
        assertEquals(6, UpdateChannel.DEV.versionComponentCount());
    }
}
