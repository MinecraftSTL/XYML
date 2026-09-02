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
package space.minecraftstl.xyml.ui.swing.page.instances.management.installers;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import space.minecraftstl.xyml.download.RemoteVersion;
import space.minecraftstl.xyml.task.Task;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/// Verifies progress metadata attached to existing-instance installer tasks.
@NotNullByDefault
final class RepositoryInstanceInstallerManagementServiceTest {
    /// Every component stage follows its dependency-download category, including non-game loaders.
    @Test
    void groupsDependencyDownloadsForEverySelectedLoader() {
        RemoteVersion fabric = remoteVersion("fabric", "0.16.0");
        RemoteVersion optifine = remoteVersion("optifine", "HD_U_I6");

        List<String> stages = RepositoryInstanceInstallerManagementService
                .remoteInstallationStages(List.of(fabric, optifine))
                .stream()
                .map(Task.StagesHint::stage)
                .toList();

        assertEquals(List.of(
                "xyml.install.libraries",
                "xyml.install.fabric:0.16.0",
                "xyml.install.libraries",
                "xyml.install.optifine:HD_U_I6"), stages);
    }

    /// Creates one deterministic remote-version fixture.
    ///
    /// @param libraryId component library identifier
    /// @param version component version
    /// @return immutable remote-version fixture
    private static RemoteVersion remoteVersion(String libraryId, String version) {
        return new RemoteVersion(libraryId, "1.20.1", version, Instant.EPOCH, List.of());
    }
}
