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
import space.minecraftstl.xyml.game.GameInstanceID;
import space.minecraftstl.xyml.game.GameInstanceManifest;
import space.minecraftstl.xyml.game.XYMLGameRepository;
import space.minecraftstl.xyml.setting.GameDirectory;
import space.minecraftstl.xyml.setting.GameDirectoryID;
import space.minecraftstl.xyml.setting.GameSettings;
import space.minecraftstl.xyml.setting.LauncherSettings;
import space.minecraftstl.xyml.setting.SettingsManager;
import space.minecraftstl.xyml.util.PortablePath;
import space.minecraftstl.xyml.util.gson.JsonUtils;
import space.minecraftstl.xyml.util.i18n.LocalizedText;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies explicit installed-instance target resolution against a selected repository.
@NotNullByDefault
final class LauncherRemoteAddonInstallTargetResolverTest {
    /// Temporary game-directory root containing deterministic installed instances.
    @TempDir
    private Path repositoryRoot;

    /// Keeps an explicit target independent from launcher-global selection and rejects unsupported input.
    @Test
    void resolvesOnlyTheExplicitInstalledInstance() throws ReflectiveOperationException, IOException {
        Field launcherSettingsField = SettingsManager.class.getDeclaredField("launcherSettings");
        launcherSettingsField.setAccessible(true);
        @Nullable Object previousLauncherSettings = launcherSettingsField.get(null);
        launcherSettingsField.set(null, new LauncherSettings());
        try {
            XYMLGameRepository repository = createRepository();
            GameInstanceID globallySelectedId = new GameInstanceID("globally-selected");
            GameInstanceID explicitTargetId = new GameInstanceID("explicit-target");
            writeManifest(repository, globallySelectedId);
            writeManifest(repository, explicitTargetId);
            repository.refresh();
            repository.setSelectedInstance(globallySelectedId);
            assertEquals(2, repository.getInstanceCount());
            configureRunningDirectory(repository, globallySelectedId);
            configureRunningDirectory(repository, explicitTargetId);

            LauncherRemoteAddonInstallTargetResolver resolver =
                    new LauncherRemoteAddonInstallTargetResolver(() -> repository);
            RemoteAddonInstallTarget modTarget = resolver.resolve(
                    RemoteAddonCatalogKind.MOD,
                    explicitTargetId).orElseThrow();
            RemoteAddonInstallTarget resourcePackTarget = resolver.resolve(
                    RemoteAddonCatalogKind.RESOURCE_PACK,
                    explicitTargetId).orElseThrow();
            RemoteAddonInstallTarget shaderPackTarget = resolver.resolve(
                    RemoteAddonCatalogKind.SHADER_PACK,
                    explicitTargetId).orElseThrow();
            RemoteAddonInstallTarget legacyTarget = resolver.resolve(RemoteAddonCatalogKind.MOD).orElseThrow();

            assertAll(
                    () -> assertEquals(explicitTargetId, modTarget.instanceId()),
                    () -> assertEquals(repositoryRoot.resolve("mods"), modTarget.directory()),
                    () -> assertEquals(explicitTargetId, resourcePackTarget.instanceId()),
                    () -> assertEquals(repositoryRoot.resolve("resourcepacks"), resourcePackTarget.directory()),
                    () -> assertEquals(explicitTargetId, shaderPackTarget.instanceId()),
                    () -> assertEquals(repositoryRoot.resolve("shaderpacks"), shaderPackTarget.directory()),
                    () -> assertEquals(globallySelectedId, legacyTarget.instanceId()),
                    () -> assertTrue(resolver.resolve(RemoteAddonCatalogKind.MOD, null).isEmpty()),
                    () -> assertTrue(resolver.resolve(
                            RemoteAddonCatalogKind.MOD,
                            new GameInstanceID("missing")).isEmpty()),
                    () -> assertTrue(resolver.resolve(
                            RemoteAddonCatalogKind.WORLD,
                            explicitTargetId).isEmpty()));
        } finally {
            launcherSettingsField.set(null, previousLauncherSettings);
        }
    }

    /// Creates a repository rooted at the temporary game directory.
    ///
    /// @return repository used by the target-resolution test
    private XYMLGameRepository createRepository() {
        GameDirectory gameDirectory = new GameDirectory(
                GameDirectoryID.generate(),
                LocalizedText.plain("Target resolver test"),
                PortablePath.of(repositoryRoot.toString()));
        return new XYMLGameRepository(gameDirectory);
    }

    /// Writes one minimal installed-instance manifest for repository discovery.
    ///
    /// @param repository repository receiving the manifest
    /// @param instanceId installed instance identifier
    /// @throws IOException if the fixture manifest cannot be written
    private static void writeManifest(
            XYMLGameRepository repository,
            GameInstanceID instanceId) throws IOException {
        Path manifestFile = repository.getInstanceJson(instanceId);
        Files.createDirectories(manifestFile.getParent());
        JsonUtils.writeToJsonFile(
                manifestFile,
                new GameInstanceManifest(instanceId).withMainClass("net.minecraft.client.main.Main"));
    }

    /// Gives each fixture an explicit running directory without depending on persisted global presets.
    ///
    /// @param repository repository receiving the setting
    /// @param instanceId instance whose run directory is configured
    private void configureRunningDirectory(XYMLGameRepository repository, GameInstanceID instanceId) {
        GameSettings.Instance setting =
                Objects.requireNonNull(repository.createInstanceGameSettings(instanceId));
        setting.getOverrideProperties().add(
                GameSettings.PROPERTY_RUNNING_DIRECTORY);
        setting.runningDirectoryProperty().setValue(repositoryRoot.toString());
    }
}
