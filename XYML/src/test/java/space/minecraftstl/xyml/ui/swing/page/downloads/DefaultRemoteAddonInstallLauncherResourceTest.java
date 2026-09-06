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
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import space.minecraftstl.xyml.addon.RemoteAddon;
import space.minecraftstl.xyml.addon.mod.ModLoaderType;
import space.minecraftstl.xyml.download.MojangDownloadProvider;
import space.minecraftstl.xyml.game.GameInstanceID;
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

        Task<?> task = new DefaultRemoteAddonInstallLauncher(new MojangDownloadProvider())
                .createInstallTask(request);

        assertEquals(Set.of(TaskResource.gameDirectory(directory)), task.getResources());
    }
}
