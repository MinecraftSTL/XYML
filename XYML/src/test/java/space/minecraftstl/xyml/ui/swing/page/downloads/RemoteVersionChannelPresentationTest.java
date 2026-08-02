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
package space.minecraftstl.xyml.ui.swing.page.downloads;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import space.minecraftstl.xyml.addon.RemoteAddon;

import java.awt.Color;

import static org.junit.jupiter.api.Assertions.assertEquals;

/// Verifies the light and dark release-channel palette inherited from the upstream download page.
@NotNullByDefault
class RemoteVersionChannelPresentationTest {
    /// Verifies every release channel retains its exact light and dark color.
    @Test
    void resolvesUpstreamChannelColors() {
        assertEquals(new Color(0xCB2245),
                RemoteVersionChannelPresentation.color(RemoteAddon.VersionType.Alpha, false));
        assertEquals(new Color(0xFF496E),
                RemoteVersionChannelPresentation.color(RemoteAddon.VersionType.Alpha, true));
        assertEquals(new Color(0xE08325),
                RemoteVersionChannelPresentation.color(RemoteAddon.VersionType.Beta, false));
        assertEquals(new Color(0xFFA347),
                RemoteVersionChannelPresentation.color(RemoteAddon.VersionType.Beta, true));
        assertEquals(new Color(0x00AF5C),
                RemoteVersionChannelPresentation.color(RemoteAddon.VersionType.Release, false));
        assertEquals(new Color(0x1BD96A),
                RemoteVersionChannelPresentation.color(RemoteAddon.VersionType.Release, true));
    }
}
