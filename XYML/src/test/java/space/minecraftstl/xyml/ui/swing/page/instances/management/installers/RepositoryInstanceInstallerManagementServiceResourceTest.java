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
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import space.minecraftstl.xyml.game.GameInstanceID;
import space.minecraftstl.xyml.game.XYMLGameRepository;
import space.minecraftstl.xyml.setting.GameDirectory;
import space.minecraftstl.xyml.setting.GameDirectoryID;
import space.minecraftstl.xyml.setting.LauncherSettings;
import space.minecraftstl.xyml.setting.SettingsManager;
import space.minecraftstl.xyml.task.Task;
import space.minecraftstl.xyml.task.TaskResource;
import space.minecraftstl.xyml.util.PortablePath;
import space.minecraftstl.xyml.util.i18n.LocalizedText;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/// Verifies that installer mutations reserve only their short repository-resolution phase at the root.
@NotNullByDefault
final class RepositoryInstanceInstallerManagementServiceResourceTest {
    /// Temporary repository root.
    @TempDir
    private @Nullable Path repositoryRoot;

    /// Reflected launcher-settings field restored after every test.
    private @Nullable Field launcherSettingsField;

    /// Launcher settings installed before the test, or null when configuration was initially unloaded.
    private @Nullable Object previousLauncherSettings;

    /// Installs the minimum isolated settings state required to construct an XYML repository.
    @BeforeEach
    void installSettings() throws ReflectiveOperationException {
        Field field = SettingsManager.class.getDeclaredField("launcherSettings");
        field.setAccessible(true);
        launcherSettingsField = field;
        previousLauncherSettings = field.get(null);
        field.set(null, new LauncherSettings());
    }

    /// Restores the process-wide settings reference after the repository fixture is discarded.
    @AfterEach
    void restoreSettings() throws IllegalAccessException {
        Field field = Objects.requireNonNull(launcherSettingsField, "launcherSettingsField");
        field.set(null, previousLauncherSettings);
    }

    /// All installer entry points resolve one fixed instance under repository metadata ownership before handoff.
    @Test
    void declaresMetadataAndInstanceResolutionResources() {
        XYMLGameRepository repository = newRepository();
        GameInstanceID instanceId = new GameInstanceID("fixture");
        RepositoryInstanceInstallerManagementService service =
                new RepositoryInstanceInstallerManagementService(repository, Runnable::run);
        Set<TaskResource> expected = Set.of(
                TaskResource.repositoryMetadata(repository.getBaseDirectory()),
                TaskResource.gameInstance(repository.getInstanceRoot(instanceId)));

        assertEquals(expected, service.installRemoteVersions(instanceId, List.of()).getResources());
        assertEquals(expected, service.removeLibrary(instanceId, "fabric").getResources());
        assertEquals(expected, service.installOffline(instanceId, repository.getBaseDirectory().resolve("forge.jar"))
                .getResources());
    }

    /// A failed offline validation crosses both resource handoffs and preserves its domain failure classification.
    @Test
    void preservesOfflineValidationFailureAcrossResourceHandoffs() {
        XYMLGameRepository repository = newRepository();
        RepositoryInstanceInstallerManagementService service =
                new RepositoryInstanceInstallerManagementService(repository, Runnable::run);
        Task<InstanceInstallerSnapshot> task = service.installOffline(
                new GameInstanceID("fixture"),
                repository.getBaseDirectory().resolve("missing-installer.jar"));

        assertFalse(task.test());
        assertInstanceOf(InstanceInstallerValidationException.class, task.getException());
    }

    /// Creates one repository without loading or mutating an instance catalog.
    ///
    /// @return repository rooted in the test directory
    private XYMLGameRepository newRepository() {
        Path root = Objects.requireNonNull(repositoryRoot, "repositoryRoot");
        return new XYMLGameRepository(new GameDirectory(
                GameDirectoryID.generate(),
                LocalizedText.plain("Installer resource test"),
                PortablePath.of(root.toString())));
    }
}
