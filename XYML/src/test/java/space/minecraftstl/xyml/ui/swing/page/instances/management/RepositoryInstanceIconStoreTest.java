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
package space.minecraftstl.xyml.ui.swing.page.instances.management;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import space.minecraftstl.xyml.game.GameInstanceID;
import space.minecraftstl.xyml.game.XYMLGameRepository;
import space.minecraftstl.xyml.setting.GameDirectory;
import space.minecraftstl.xyml.setting.GameDirectoryID;
import space.minecraftstl.xyml.setting.GameSettings;
import space.minecraftstl.xyml.setting.GameInstanceIconType;
import space.minecraftstl.xyml.setting.LauncherSettings;
import space.minecraftstl.xyml.setting.SettingsManager;
import space.minecraftstl.xyml.util.FileSaver;
import space.minecraftstl.xyml.util.PortablePath;
import space.minecraftstl.xyml.util.i18n.LocalizedText;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;

/// Verifies repository-backed management icon resolution and explicit override priority.
@NotNullByDefault
final class RepositoryInstanceIconStoreTest {
    /// Temporary game directory root.
    @TempDir
    private @Nullable Path repositoryRoot;

    /// Uses the detected Fabric icon for `DEFAULT` while retaining explicit icon choices.
    @Test
    void resolvesDefaultFromFabricPatchAndKeepsExplicitOverride()
            throws IOException, InterruptedException, ReflectiveOperationException {
        Field launcherSettingsField = SettingsManager.class.getDeclaredField("launcherSettings");
        launcherSettingsField.setAccessible(true);
        @Nullable Object previousLauncherSettings = launcherSettingsField.get(null);
        launcherSettingsField.set(null, new LauncherSettings());
        try {
            Path root = Objects.requireNonNull(repositoryRoot, "repositoryRoot");
            GameDirectory gameDirectory = new GameDirectory(
                    GameDirectoryID.generate(),
                    LocalizedText.plain("Icon test"),
                    PortablePath.of(root.toString()));
            XYMLGameRepository repository = new XYMLGameRepository(gameDirectory);
            GameInstanceID instanceId = new GameInstanceID("fabric-instance");
            writeFabricPatchManifest(repository.getInstanceJson(instanceId));
            repository.refresh();

            GameSettings.Instance settings = Objects.requireNonNull(
                    repository.getInstanceGameSettingsOrCreate(instanceId),
                    "instance settings");
            assertEquals(GameInstanceIconType.DEFAULT, settings.iconProperty().getValue());

            RepositoryInstanceIconStore store = new RepositoryInstanceIconStore(repository, instanceId);
            assertEquals(GameInstanceIconType.FABRIC, store.load().builtInType());

            settings.iconProperty().setValue(GameInstanceIconType.GRASS);
            assertEquals(GameInstanceIconType.GRASS, store.load().builtInType());
        } finally {
            try {
                FileSaver.waitForAllSaves();
            } finally {
                launcherSettingsField.set(null, previousLauncherSettings);
            }
        }
    }

    /// Writes one root manifest using the same explicit Fabric patch shape as migrated launcher instances.
    ///
    /// @param manifestFile target instance JSON file
    /// @throws IOException when the fixture cannot be written
    private static void writeFabricPatchManifest(Path manifestFile) throws IOException {
        Path normalizedFile = Objects.requireNonNull(manifestFile, "manifestFile")
                .toAbsolutePath()
                .normalize();
        Files.createDirectories(normalizedFile.getParent());
        Files.writeString(normalizedFile, """
                {
                  "id": "fabric-instance",
                  "root": true,
                  "patches": [
                    {
                      "id": "game",
                      "version": "1.21",
                      "priority": 0,
                      "mainClass": "net.minecraft.client.main.Main",
                      "libraries": []
                    },
                    {
                      "id": "fabric",
                      "version": "0.16.3",
                      "priority": 30000,
                      "mainClass": "net.fabricmc.loader.impl.launch.knot.KnotClient",
                      "libraries": [
                        {
                          "name": "net.fabricmc:fabric-loader:0.16.3",
                          "url": "https://maven.fabricmc.net/"
                        }
                      ]
                    }
                  ]
                }
                """, StandardCharsets.UTF_8);
    }
}
