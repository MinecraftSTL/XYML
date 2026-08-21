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
package space.minecraftstl.xyml.download.forge;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import space.minecraftstl.xyml.download.UnsupportedInstallationException;
import space.minecraftstl.xyml.game.GameInstanceID;
import space.minecraftstl.xyml.game.GameInstanceManifest;
import space.minecraftstl.xyml.game.GameInstancePatch;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Verifies Forge installation compatibility checks.
@NotNullByDefault
public final class ForgeInstallTaskTest {

    /// Existing Cleanroom patches reject Forge with the stable compatibility reason.
    @Test
    public void rejectsCleanroomInstances() {
        UnsupportedInstallationException exception = assertThrows(
                UnsupportedInstallationException.class,
                () -> ForgeInstallTask.checkCleanroomCompatibility(resolvedWithPatch("cleanroom"), "1.12.2"));

        assertEquals(
                UnsupportedInstallationException.CLEANROOM_NOT_COMPATIBLE_WITH_FORGE,
                exception.getReason());
    }

    /// Unrelated loader patches do not block Forge installation.
    @Test
    public void acceptsInstancesWithoutCleanroom() {
        assertDoesNotThrow(
                () -> ForgeInstallTask.checkCleanroomCompatibility(resolvedWithPatch("fabric"), "1.12.2"));
    }

    /// Creates resolved manifest views containing one loader patch.
    ///
    /// @param patchId loader patch identifier
    /// @return resolved fixture
    private static GameInstanceManifest.Resolved resolvedWithPatch(String patchId) {
        GameInstanceManifest launchManifest = new GameInstanceManifest(new GameInstanceID("test"));
        GameInstanceManifest standaloneManifest = launchManifest.withPatches(List.of(new GameInstancePatch(patchId)));
        return new GameInstanceManifest.Resolved(standaloneManifest, launchManifest, standaloneManifest);
    }
}
