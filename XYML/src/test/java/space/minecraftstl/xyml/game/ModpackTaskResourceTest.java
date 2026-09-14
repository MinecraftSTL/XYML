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
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import space.minecraftstl.xyml.download.DefaultDependencyManager;
import space.minecraftstl.xyml.modpack.Modpack;
import space.minecraftstl.xyml.modpack.ModpackManifest;
import space.minecraftstl.xyml.modpack.mcbbs.McbbsModpackManifest;
import space.minecraftstl.xyml.setting.GameDirectory;
import space.minecraftstl.xyml.setting.GameDirectoryID;
import space.minecraftstl.xyml.setting.LauncherSettings;
import space.minecraftstl.xyml.setting.SettingsManager;
import space.minecraftstl.xyml.task.Task;
import space.minecraftstl.xyml.task.TaskResource;
import space.minecraftstl.xyml.util.PortablePath;
import space.minecraftstl.xyml.util.i18n.LocalizedText;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies resource declarations on native and launcher-level modpack installation chains.
@NotNullByDefault
public final class ModpackTaskResourceTest {
    /// Native XYML installation owns only its repository operation, selected instance, run directory, and archive.
    ///
    /// @param temporaryDirectory isolated repository and archive root
    @Test
    public void nativeInstallUsesPreciseInstanceResources(@TempDir Path temporaryDirectory) {
        XYMLGameRepository repository = newRepository(temporaryDirectory.resolve("repository"));
        GameInstanceID instanceId = new GameInstanceID("native-pack");
        Path archive = temporaryDirectory.resolve("native-pack.zip");
        repository.markInstanceAsModpack(instanceId);
        Modpack modpack = new TestModpack(XYMLModpackManifest.INSTANCE, Task.runAsync(() -> {
        }));

        Task<?> installation = new XYMLModpackInstallTask(repository, archive, modpack, instanceId);

        assertEquals(Set.of(
                TaskResource.repositoryOperation(repository.getBaseDirectory()),
                TaskResource.gameInstance(repository.getInstanceRoot(instanceId)),
                TaskResource.gameDirectory(repository.getRunDirectory(instanceId)),
                TaskResource.archive(archive)), installation.getResources());
        assertFalse(installation.getResources().contains(TaskResource.gameDirectory(repository.getBaseDirectory())));
    }

    /// Different instance bodies in one repository overlap before their short repository finalizers run.
    ///
    /// @param temporaryDirectory isolated repository and archive root
    /// @throws Exception if task execution or bounded coordination fails
    @Test
    @Timeout(15)
    public void helperAllowsDifferentInstanceBodiesToRunInParallel(@TempDir Path temporaryDirectory) throws Exception {
        XYMLGameRepository repository = newRepository(temporaryDirectory.resolve("repository"));
        CountDownLatch bothStarted = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        Task<?> first = failingInstall(
                repository,
                new GameInstanceID("first-pack"),
                temporaryDirectory.resolve("first.zip"),
                bothStarted,
                release);
        Task<?> second = failingInstall(
                repository,
                new GameInstanceID("second-pack"),
                temporaryDirectory.resolve("second.zip"),
                bothStarted,
                release);

        assertOrchestration(first);
        assertOrchestration(second);
        CompletableFuture<Boolean> firstResult = CompletableFuture.supplyAsync(() -> first.executor().test());
        CompletableFuture<Boolean> secondResult = CompletableFuture.supplyAsync(() -> second.executor().test());

        boolean overlapped = bothStarted.await(5, TimeUnit.SECONDS);
        release.countDown();

        assertFalse(firstResult.get(10, TimeUnit.SECONDS));
        assertFalse(secondResult.get(10, TimeUnit.SECONDS));
        assertTrue(overlapped, "Different instance installation bodies did not overlap");
    }

    /// MCBBS post-install settings mutation exposes the instance tree that subsumes its settings file.
    ///
    /// @param temporaryDirectory isolated repository root
    /// @throws ReflectiveOperationException if the private task factory cannot be inspected
    @Test
    public void postInstallSettingsUseInstanceAndConfigurationResources(@TempDir Path temporaryDirectory)
            throws ReflectiveOperationException {
        XYMLGameRepository repository = newRepository(temporaryDirectory.resolve("repository"));
        GameInstanceID instanceId = new GameInstanceID("settings-pack");
        Method factory = ModpackHelper.class.getDeclaredMethod(
                "createMcbbsPostInstallTask",
                XYMLGameRepository.class,
                McbbsModpackManifest.class,
                GameInstanceID.class);
        factory.setAccessible(true);

        Task<?> postInstall = (Task<?>) factory.invoke(null, repository, new McbbsModpackManifest(), instanceId);

        assertEquals(Set.of(
                TaskResource.gameInstance(repository.getInstanceRoot(instanceId))), postInstall.getResources());
    }

    /// Manual archive extraction hands off to a short settings task instead of retaining shared configuration locks.
    ///
    /// @param temporaryDirectory isolated archive and destination root
    /// @throws ReflectiveOperationException if the private post-install factory cannot be inspected
    @Test
    public void manualInstallUsesPreciseExtractionAndSettingsResources(@TempDir Path temporaryDirectory)
            throws ReflectiveOperationException {
        Path archive = temporaryDirectory.resolve("manual-pack.zip");
        String name = "manual-" + temporaryDirectory.getFileName();
        Path destination = Path.of("externalgames").resolve(name).toAbsolutePath().normalize();

        Task<?> root = ModpackHelper.getInstallManuallyCreatedModpackTask(
                archive,
                name,
                StandardCharsets.UTF_8);
        assertOrchestration(root);
        Task<?> extraction = root.getDependents().iterator().next();
        assertEquals(Set.of(
                TaskResource.gameDirectory(destination),
                TaskResource.archive(archive)), extraction.getResources());

        Method factory = ModpackHelper.class.getDeclaredMethod(
                "createManualPostInstallTask",
                Path.class,
                String.class);
        factory.setAccessible(true);
        Task<?> publication = (Task<?>) factory.invoke(null, destination, name);
        assertEquals(Set.of(
                TaskResource.gameDirectory(destination),
                TaskResource.configuration(SettingsManager.gameDirectoriesLocation()),
                TaskResource.configuration(SettingsManager.settingsLocation())), publication.getResources());
    }

    /// Creates one launcher-level install whose controlled body fails after the concurrency observation.
    ///
    /// @param repository destination repository
    /// @param instanceId destination instance identifier
    /// @param archive distinct input archive resource
    /// @param started shared execution-start counter
    /// @param release shared completion gate
    /// @return complete ModpackHelper chain
    private static Task<?> failingInstall(
            XYMLGameRepository repository,
            GameInstanceID instanceId,
            Path archive,
            CountDownLatch started,
            CountDownLatch release) {
        Task<?> body = Task.runAsync(() -> {
            started.countDown();
            if (!release.await(10, TimeUnit.SECONDS)) {
                throw new IOException("Timed out waiting to release fixture installation");
            }
            throw new IOException("Expected fixture failure");
        }).setResources(
                TaskResource.repositoryOperation(repository.getBaseDirectory()),
                TaskResource.gameInstance(repository.getInstanceRoot(instanceId)),
                TaskResource.gameDirectory(repository.getInstanceRoot(instanceId)),
                TaskResource.archive(archive));
        return ModpackHelper.getInstallTask(
                repository,
                archive,
                instanceId,
                new TestModpack(XYMLModpackManifest.INSTANCE, body),
                "");
    }

    /// Creates one repository without loading or mutating an instance catalog.
    ///
    /// @param root repository root
    /// @return repository backed by the supplied root
    private static XYMLGameRepository newRepository(Path root) {
        return new XYMLGameRepository(
                new GameDirectory(
                        GameDirectoryID.generate(),
                        LocalizedText.plain("Modpack resource test"),
                        PortablePath.of(root.toString())),
                new LauncherSettings());
    }

    /// Verifies that one audited composition node owns only the non-filesystem orchestration marker.
    ///
    /// @param task composition task to inspect
    private static void assertOrchestration(Task<?> task) {
        assertEquals(1, task.getResources().size());
        assertEquals(TaskResource.Kind.ORCHESTRATION, task.getResources().iterator().next().getKind());
    }

    /// Minimal modpack returning a caller-supplied installation body.
    @NotNullByDefault
    private static final class TestModpack extends Modpack {
        /// Installation body returned to ModpackHelper.
        private final Task<?> installation;

        /// Creates a fixture modpack with valid metadata and a controlled installation task.
        ///
        /// @param manifest manifest selecting the ModpackHelper route
        /// @param installation controlled installation body
        private TestModpack(ModpackManifest manifest, Task<?> installation) {
            this.installation = Objects.requireNonNull(installation, "installation");
            setName("Fixture pack");
            setVersion("1.0");
            setGameVersion("1.20.1");
            setEncoding(StandardCharsets.UTF_8);
            setManifest(Objects.requireNonNull(manifest, "manifest"));
        }

        /// Returns the controlled installation body for the requested destination.
        @Override
        public Task<?> getInstallTask(
                DefaultDependencyManager dependencyManager,
                Path zipFile,
                GameInstanceID instanceId,
                String iconUrl,
                @Nullable Set<String> excludedFiles) {
            return installation;
        }
    }
}
