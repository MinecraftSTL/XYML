// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.instances.management.installers;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import space.minecraftstl.xyml.download.TestComponentRemoteVersion;
import space.minecraftstl.xyml.download.ComponentRemoteVersion;
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
        ComponentRemoteVersion fabric = remoteVersion("fabric", "0.16.0");
        ComponentRemoteVersion optifine = remoteVersion("optifine", "HD_U_I6");

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
    private static ComponentRemoteVersion remoteVersion(String libraryId, String version) {
        return new TestComponentRemoteVersion(libraryId, "1.20.1", version, Instant.EPOCH, List.of());
    }
}
