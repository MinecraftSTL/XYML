// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.downloads;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import space.minecraftstl.xyml.addon.RemoteAddon;
import space.minecraftstl.xyml.addon.mod.ModLoaderType;
import space.minecraftstl.xyml.game.GameInstanceID;
import space.minecraftstl.xyml.download.DownloadProvider;
import space.minecraftstl.xyml.task.Task;
import space.minecraftstl.xyml.task.TaskResource;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/// Verifies the short directory reservation used before add-on downloads hand off to exact files.
@NotNullByDefault
final class DefaultRemoteAddonInstallLauncherResourceTest {
    /// Temporary managed add-on directory.
    @TempDir
    private @Nullable Path temporaryDirectory;

    /// Declares only the selected directory while deferred preparation chooses its private temporary file.
    @Test
    void declaresSelectedDirectoryForDeferredPreparation() {
        Path directory = Objects.requireNonNull(temporaryDirectory, "temporaryDirectory").resolve("mods");
        RemoteAddon.Version version = new RemoteAddon.Version(
                () -> RemoteAddon.Source.MODRINTH,
                "version",
                "project",
                "Fixture",
                "1.0",
                Instant.EPOCH,
                RemoteAddon.VersionType.Release,
                new RemoteAddon.File(Map.of(), "https://example.invalid/fixture.jar", "fixture.jar"),
                List.of(),
                List.of("1.21"),
                List.<ModLoaderType>of());
        RemoteAddonInstallRequest request = new RemoteAddonInstallRequest(
                new RemoteAddonCatalogItem(
                        RemoteAddon.BROKEN,
                        RemoteAddonCatalogKind.MOD,
                        RemoteAddonCatalogSource.MODRINTH),
                version,
                new RemoteAddonInstallTarget(
                        RemoteAddonCatalogKind.MOD,
                        new GameInstanceID("fixture"),
                        directory));

        Task<?> task = new DefaultRemoteAddonInstallLauncher(new DownloadProvider())
                .createInstallTask(request);

        assertEquals(Set.of(TaskResource.gameDirectory(directory)), task.getResources());
    }
}
