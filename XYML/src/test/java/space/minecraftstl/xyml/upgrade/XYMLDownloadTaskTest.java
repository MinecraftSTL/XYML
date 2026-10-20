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
import org.junit.jupiter.api.io.TempDir;
import space.minecraftstl.xyml.task.FileDownloadTask;
import space.minecraftstl.xyml.task.TaskResource;

import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/// Verifies the normalized public resource view of launcher-upgrade downloads.
@NotNullByDefault
final class XYMLDownloadTaskTest {
    /// Per-test upgrade destination root.
    @TempDir
    private Path temporaryDirectory;

    /// One upgrade download exposes the surrounding directory that subsumes its exact target.
    @Test
    void declaresUpgradeDestinationResources() {
        Path upgradeDirectory = temporaryDirectory.resolve("upgrade");
        Path target = upgradeDirectory.resolve("XYML.jar");
        RemoteVersion version = new RemoteVersion(
                UpdateChannel.STABLE,
                "1.0.0",
                "https://example.invalid/XYML.jar",
                RemoteVersion.Type.JAR,
                new FileDownloadTask.IntegrityCheck("SHA-1", "0".repeat(40)),
                false,
                false);

        XYMLDownloadTask task = new XYMLDownloadTask(version, target);

        assertEquals(
                Set.of(
                        TaskResource.launcherUpgrade(upgradeDirectory)),
                task.getResources());
    }
}
