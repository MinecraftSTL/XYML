// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

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
