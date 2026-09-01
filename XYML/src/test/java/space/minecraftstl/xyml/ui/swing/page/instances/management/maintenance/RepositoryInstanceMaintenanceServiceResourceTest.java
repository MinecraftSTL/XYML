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
package space.minecraftstl.xyml.ui.swing.page.instances.management.maintenance;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import space.minecraftstl.xyml.game.GameInstanceID;
import space.minecraftstl.xyml.game.XYMLGameRepository;
import space.minecraftstl.xyml.setting.GameDirectory;
import space.minecraftstl.xyml.setting.GameDirectoryID;
import space.minecraftstl.xyml.setting.GameSettings;
import space.minecraftstl.xyml.setting.GameSettingsPresetID;
import space.minecraftstl.xyml.setting.GameSettingsPresets;
import space.minecraftstl.xyml.setting.LauncherSettings;
import space.minecraftstl.xyml.setting.SettingsManager;
import space.minecraftstl.xyml.task.Task;
import space.minecraftstl.xyml.task.TaskResource;
import space.minecraftstl.xyml.util.PortablePath;
import space.minecraftstl.xyml.util.i18n.LocalizedText;

import java.lang.reflect.Field;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies explicit repository and instance resource roots for maintenance operations.
@NotNullByDefault
final class RepositoryInstanceMaintenanceServiceResourceTest {
    /// Temporary repository root.
    @TempDir
    private @Nullable Path repositoryRoot;

    /// Reflected launcher-settings field restored after every test.
    private @Nullable Field launcherSettingsField;

    /// Reflected game-settings-presets field restored after every test.
    private @Nullable Field gameSettingsPresetsField;

    /// Launcher settings installed before the test, or null when configuration was initially unloaded.
    private @Nullable Object previousLauncherSettings;

    /// Game settings presets installed before the test, or null when configuration was initially unloaded.
    private @Nullable Object previousGameSettingsPresets;

    /// Installs isolated launcher and game-setting defaults required by effective run-directory resolution.
    @BeforeEach
    void installSettings() throws ReflectiveOperationException {
        Field launcherField = SettingsManager.class.getDeclaredField("launcherSettings");
        Field presetsField = SettingsManager.class.getDeclaredField("gameSettingsPresets");
        launcherField.setAccessible(true);
        presetsField.setAccessible(true);
        launcherSettingsField = launcherField;
        gameSettingsPresetsField = presetsField;
        previousLauncherSettings = launcherField.get(null);
        previousGameSettingsPresets = presetsField.get(null);

        LauncherSettings settings = new LauncherSettings();
        GameSettingsPresetID presetId = GameSettingsPresetID.generate();
        GameSettingsPresets presets = new GameSettingsPresets();
        presets.getPresets().add(new GameSettings.Preset(presetId));
        settings.defaultGameSettingsPresetProperty().set(presetId);
        launcherField.set(null, settings);
        presetsField.set(null, presets);
    }

    /// Restores both process-wide settings references after each repository fixture.
    @AfterEach
    void restoreSettings() throws IllegalAccessException {
        Field launcherField = Objects.requireNonNull(launcherSettingsField, "launcherSettingsField");
        Field presetsField = Objects.requireNonNull(gameSettingsPresetsField, "gameSettingsPresetsField");
        launcherField.set(null, previousLauncherSettings);
        presetsField.set(null, previousGameSettingsPresets);
    }

    /// Local and fixed-path maintenance operations no longer fall back to the process-wide conservative lock.
    @Test
    void declaresExplicitLocalMaintenanceResources() {
        XYMLGameRepository repository = newRepository();
        GameInstanceID instanceId = new GameInstanceID("fixture");
        RepositoryInstanceMaintenanceService service =
                new RepositoryInstanceMaintenanceService(repository, instanceId, Runnable::run);
        Path archive = repository.getBaseDirectory().resolve("update.zip");

        assertEquals(Set.of(
                        TaskResource.gameDirectory(repository.getBaseDirectory()),
                        TaskResource.archive(archive)),
                service.updateModpack(archive, StandardCharsets.UTF_8).getResources());
        assertExplicit(service.redownloadAssets());
        assertExplicit(service.removeAssets());
        assertExplicit(service.removeLibraries());
        assertExplicit(service.cleanGeneratedFiles());
    }

    /// Remote updates reserve only catalog and instance resolution before handing off to their dynamic child branch.
    @Test
    void declaresRemoteUpdateResolutionResources() {
        XYMLGameRepository repository = newRepository();
        GameInstanceID instanceId = new GameInstanceID("fixture");
        RepositoryInstanceMaintenanceService service = new RepositoryInstanceMaintenanceService(
                repository,
                instanceId,
                Runnable::run);

        assertEquals(Set.of(
                        TaskResource.repositoryMetadata(repository.getBaseDirectory()),
                        TaskResource.gameInstance(repository.getInstanceRoot(instanceId))),
                service.updateModpack(URI.create("https://example.invalid/update.zip")).getResources());
    }

    /// Asserts that one task has a non-empty declaration without the unresolved conservative marker.
    ///
    /// @param task stopped maintenance task
    private static void assertExplicit(Task<?> task) {
        assertFalse(task.getResources().isEmpty());
        assertFalse(task.getResources().contains(TaskResource.conservative()));
        assertTrue(task.getResources().stream().allMatch(Objects::nonNull));
    }

    /// Creates one repository without loading or mutating an instance catalog.
    ///
    /// @return repository rooted in the test directory
    private XYMLGameRepository newRepository() {
        Path root = Objects.requireNonNull(repositoryRoot, "repositoryRoot");
        return new XYMLGameRepository(new GameDirectory(
                GameDirectoryID.generate(),
                LocalizedText.plain("Maintenance resource test"),
                PortablePath.of(root.toString())));
    }
}
