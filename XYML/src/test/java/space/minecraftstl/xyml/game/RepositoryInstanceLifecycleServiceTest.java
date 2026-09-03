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
package space.minecraftstl.xyml.game;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import space.minecraftstl.xyml.setting.GameDirectory;
import space.minecraftstl.xyml.setting.GameDirectoryID;
import space.minecraftstl.xyml.setting.GameSettings;
import space.minecraftstl.xyml.setting.LauncherSettings;
import space.minecraftstl.xyml.ui.swing.page.instances.management.RepositoryInstanceLifecycleService;
import space.minecraftstl.xyml.util.FileSaver;
import space.minecraftstl.xyml.util.PortablePath;
import space.minecraftstl.xyml.util.gson.JsonUtils;
import space.minecraftstl.xyml.util.i18n.LocalizedText;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies that repository lifecycle disk phases can be separated from the catalog refresh.
@NotNullByDefault
public final class RepositoryInstanceLifecycleServiceTest {
    /// Temporary repository roots used by lifecycle fixtures.
    @TempDir
    private Path temporaryDirectory;

    /// Duplication remains absent from the loaded catalog until the explicit refresh phase runs.
    ///
    /// @throws IOException when fixture creation or duplication fails
    @Test
    public void duplicateWithoutRefreshDefersCatalogRebuild() throws IOException {
        XYMLGameRepository repository = newRepository(temporaryDirectory.resolve("repository"));
        GameInstanceID source = new GameInstanceID("source");
        GameInstanceID destination = new GameInstanceID("destination");
        installInstance(repository, source);
        createIsolatedSettings(repository, source);
        RepositoryInstanceLifecycleService service = new RepositoryInstanceLifecycleService(repository);

        service.duplicateWithoutRefresh(source, destination, true);

        assertTrue(Files.isDirectory(repository.getInstanceRoot(destination)));
        assertFalse(repository.hasInstance(destination));

        service.refreshRepository();

        assertTrue(repository.hasInstance(destination));
    }

    /// The established duplication method still refreshes the catalog before returning.
    ///
    /// @throws IOException when fixture creation or duplication fails
    @Test
    public void defaultDuplicateStillRefreshesCatalog() throws IOException {
        XYMLGameRepository repository = newRepository(temporaryDirectory.resolve("repository"));
        GameInstanceID source = new GameInstanceID("source");
        GameInstanceID destination = new GameInstanceID("destination");
        installInstance(repository, source);
        createIsolatedSettings(repository, source);
        RepositoryInstanceLifecycleService service = new RepositoryInstanceLifecycleService(repository);

        service.duplicate(source, destination, true);

        assertTrue(repository.hasInstance(destination));
    }

    /// A staged duplicate keeps using the source running directory captured during its short preparation phase.
    ///
    /// @throws IOException when fixture creation or duplication fails
    /// @throws InterruptedException when queued settings writes cannot be drained
    @Test
    public void duplicateFromSnapshotIgnoresLaterRunDirectoryChange() throws IOException, InterruptedException {
        XYMLGameRepository repository = newRepository(temporaryDirectory.resolve("repository"));
        GameInstanceID source = new GameInstanceID("source");
        GameInstanceID destination = new GameInstanceID("destination");
        installInstance(repository, source);
        GameSettings.Instance settings = Objects.requireNonNull(repository.createInstanceGameSettings(source));
        settings.getOverrideProperties().add(GameSettings.PROPERTY_RUNNING_DIRECTORY);
        Path capturedRunDirectory = temporaryDirectory.resolve("captured-run");
        Path laterRunDirectory = temporaryDirectory.resolve("later-run");
        Files.createDirectories(capturedRunDirectory);
        Files.createDirectories(laterRunDirectory);
        Files.writeString(capturedRunDirectory.resolve("captured.txt"), "captured");
        Files.writeString(laterRunDirectory.resolve("later.txt"), "later");
        settings.runningDirectoryProperty().setValue(capturedRunDirectory.toString());
        RepositoryInstanceLifecycleService service = new RepositoryInstanceLifecycleService(repository);

        XYMLGameRepository.InstanceDuplicationSnapshot snapshot = service.prepareDuplicate(source);
        settings.runningDirectoryProperty().setValue(laterRunDirectory.toString());
        FileSaver.waitForAllSaves();
        service.duplicateWithoutRefresh(source, destination, true, snapshot);

        assertTrue(Files.exists(repository.getInstanceRoot(destination).resolve("captured.txt")));
        assertFalse(Files.exists(repository.getInstanceRoot(destination).resolve("later.txt")));
    }

    /// Rename flushes queued settings before moving files and invalidates identifier-keyed settings caches.
    ///
    /// @throws IOException when fixture creation or rename fails
    /// @throws InterruptedException when the final save barrier is interrupted
    @Test
    public void renameFlushesPendingSettingsAndRekeysCache() throws IOException, InterruptedException {
        XYMLGameRepository repository = newRepository(temporaryDirectory.resolve("repository"));
        GameInstanceID source = new GameInstanceID("source");
        GameInstanceID destination = new GameInstanceID("destination");
        installInstance(repository, source);
        createIsolatedSettings(repository, source);
        repository.saveGameSettings(source);
        RepositoryInstanceLifecycleService service = new RepositoryInstanceLifecycleService(repository);

        service.renameWithoutRefresh(source, destination);
        FileSaver.waitForAllSaves();

        assertFalse(Files.exists(repository.getInstanceRoot(source)));
        assertTrue(Files.isDirectory(repository.getInstanceRoot(destination)));
        assertNull(repository.getInstanceGameSettings(source));
        assertNotNull(repository.getInstanceGameSettings(destination));
    }

    /// Creates an isolated repository without depending on process-global launcher settings.
    ///
    /// @param root repository root
    /// @return repository backed by the supplied root
    private static XYMLGameRepository newRepository(Path root) {
        return new XYMLGameRepository(
                new GameDirectory(
                        GameDirectoryID.generate(),
                        LocalizedText.plain("Lifecycle test"),
                        PortablePath.of(root.toString())),
                new LauncherSettings());
    }

    /// Writes and loads one minimal instance manifest.
    ///
    /// @param repository repository receiving the fixture
    /// @param instanceId fixture instance identifier
    /// @throws IOException when the manifest cannot be written
    private static void installInstance(XYMLGameRepository repository, GameInstanceID instanceId) throws IOException {
        Path manifest = repository.getInstanceJson(instanceId);
        Files.createDirectories(manifest.getParent());
        JsonUtils.writeToJsonFile(manifest, new GameInstanceManifest(instanceId));
        repository.refresh();
        assertTrue(repository.hasInstance(instanceId));
    }

    /// Creates source settings that force the duplicate operation to use the instance directory as its run directory.
    ///
    /// @param repository repository owning the instance
    /// @param instanceId source instance identifier
    private static void createIsolatedSettings(XYMLGameRepository repository, GameInstanceID instanceId) {
        GameSettings.Instance settings = Objects.requireNonNull(repository.createInstanceGameSettings(instanceId));
        settings.getOverrideProperties().add(GameSettings.PROPERTY_RUNNING_DIRECTORY);
        settings.runningDirectoryProperty().setValue("");
    }
}
