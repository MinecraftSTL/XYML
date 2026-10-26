// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

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
