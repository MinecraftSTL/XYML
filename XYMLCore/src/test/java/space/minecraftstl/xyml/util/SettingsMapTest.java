// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.util;

import space.minecraftstl.xyml.download.ComponentRemoteVersion;
import space.minecraftstl.xyml.download.TestComponentRemoteVersion;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import space.minecraftstl.xyml.game.GameComponentType;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests for installer state stored in [SettingsMap].
@NotNullByDefault
public final class SettingsMapTest {
    /// Tests that vanilla game selection alone is not treated as a modded installation.
    @Test
    public void minecraftSelectionIsNotModdedInstallation() {
        SettingsMap settings = new SettingsMap();
        settings.put(GameComponentType.GAME.getPatchId(), remoteVersion("game"));

        assertFalse(settings.isInstallingModdedVersion());
    }

    /// Tests that selecting a non-vanilla installer is treated as a modded installation.
    @Test
    public void modLoaderSelectionIsModdedInstallation() {
        SettingsMap settings = new SettingsMap();
        settings.put(GameComponentType.GAME.getPatchId(), remoteVersion("game"));
        settings.put(GameComponentType.FABRIC.getPatchId(), remoteVersion("fabric"));

        assertTrue(settings.isInstallingModdedVersion());
    }

    /// Creates a minimal remote version for installer state tests.
    private static ComponentRemoteVersion remoteVersion(String libraryId) {
        return new TestComponentRemoteVersion(GameComponentType.fromPatchId(libraryId), "1.21.11", "test");
    }
}
