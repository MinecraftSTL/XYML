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
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import space.minecraftstl.xyml.java.JavaInfo;
import space.minecraftstl.xyml.java.JavaManager;
import space.minecraftstl.xyml.java.JavaRuntime;
import space.minecraftstl.xyml.setting.GameSettings;
import space.minecraftstl.xyml.setting.JavaVersionType;
import space.minecraftstl.xyml.task.Task;
import space.minecraftstl.xyml.task.TaskResource;
import space.minecraftstl.xyml.util.FileSaver;
import space.minecraftstl.xyml.util.platform.Platform;
import space.minecraftstl.xyml.util.versioning.GameVersionNumber;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies the two-decision Java repair flow without touching the process-wide Java registry.
@NotNullByDefault
final class JavaRuntimeRepairTaskFactoryTest {
    /// Reuses a compatible registered runtime without entering the download path, then selects from a fresh snapshot.
    ///
    /// @throws Exception when the composed task unexpectedly fails
    @Test
    void skipsDownloadWhenCompatibleRuntimeIsAlreadyRegistered() throws Exception {
        JavaRuntime initialSelection = runtime("initial-selection");
        JavaRuntime finalSelection = runtime("final-selection");
        AtomicInteger selections = new AtomicInteger();
        AtomicInteger downloads = new AtomicInteger();

        Task<JavaRuntime> repair = JavaRuntimeRepairTaskFactory.resolveCompatibleJava(
                manifest(),
                GameVersionNumber.asGameVersion("1.20.1"),
                ignoredTarget -> Task.supplyAsync(() -> {
                    return selections.incrementAndGet() == 1 ? initialSelection : finalSelection;
                }),
                ignoredTarget -> {
                    downloads.incrementAndGet();
                    throw new AssertionError("Download factory must not be called for a compatible runtime");
                });

        assertEquals(TaskResource.Kind.ORCHESTRATION, repair.getResources().iterator().next().getKind());
        assertSame(finalSelection, repair.run());
        assertEquals(2, selections.get());
        assertEquals(0, downloads.get());
    }

    /// Runs a precisely resourced Java selection before acquiring the unrelated instance persistence resources.
    @Test
    void persistenceStageHandsOffSelectionAndDeclaresExactResources(@TempDir Path temporaryDirectory) {
        JavaRuntime selected = runtime("resource-handoff");
        Path javaDirectory = temporaryDirectory.resolve("java-runtime");
        Path instanceDirectory = temporaryDirectory.resolve("instances").resolve("example");
        Path settingsFile = temporaryDirectory.resolve("external-config").resolve("settings.json");
        TaskResource javaResource = TaskResource.javaRuntime(javaDirectory);
        Task<JavaRuntime> selection = Task.supplyAsync(() -> selected).setResources(javaResource);
        AtomicReference<@Nullable JavaRuntime> persisted = new AtomicReference<>();

        Task<@Nullable Void> persistence = JavaRuntimeRepairTaskFactory.createPersistenceTask(
                selection,
                instanceDirectory,
                settingsFile,
                persisted::set);

        assertEquals(
                List.of(TaskResource.Kind.ORCHESTRATION),
                persistence.getResources().stream().map(TaskResource::getKind).toList());
        assertEquals(Set.of(javaResource), selection.getResources());
        assertTrue(persistence.test(), () -> "Persistence task failed: " + persistence.getException());
        assertSame(selected, persisted.get());
    }

    /// Keeps a final source validator and settings persistence in one uninterrupted resource lease.
    ///
    /// @throws Exception when bounded task execution fails
    @Test
    void finalValidatorAndPersistenceAreAtomicAgainstConflictingTasks(@TempDir Path temporaryDirectory)
            throws Exception {
        Path instanceDirectory = temporaryDirectory.resolve("instances").resolve("atomic");
        Path settingsFile = instanceDirectory.resolve("settings.json");
        TaskResource instanceResource = TaskResource.gameInstance(instanceDirectory);
        CountDownLatch validatorEntered = new CountDownLatch(1);
        CountDownLatch releaseValidator = new CountDownLatch(1);
        List<String> executionOrder = new CopyOnWriteArrayList<>();
        Task<?> validator = Task.runAsync(() -> {
            executionOrder.add("validator");
            validatorEntered.countDown();
            if (!releaseValidator.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out while holding the final validation lease");
            }
        }).setResources(instanceResource);
        Task<@Nullable Void> persistence = JavaRuntimeRepairTaskFactory.createPersistenceTask(
                Task.completed(runtime("atomic-selection")),
                instanceDirectory,
                settingsFile,
                validator,
                ignoredJava -> executionOrder.add("persistence"));
        Task<@Nullable Void> contender = Task.runAsync(() -> executionOrder.add("contender"))
                .setResources(instanceResource);

        CompletableFuture<Boolean> persistenceResult = CompletableFuture.supplyAsync(persistence::test);
        assertTrue(validatorEntered.await(5, TimeUnit.SECONDS));
        CompletableFuture<Boolean> contenderResult = CompletableFuture.supplyAsync(contender::test);
        try {
            releaseValidator.countDown();
            assertTrue(persistenceResult.get(5, TimeUnit.SECONDS));
            assertTrue(contenderResult.get(5, TimeUnit.SECONDS));
        } finally {
            releaseValidator.countDown();
        }

        assertEquals(List.of("validator", "persistence", "contender"), executionOrder);
    }

    /// Keeps an external symbolic-link run directory in the final cross-module resource union.
    ///
    /// @throws Exception when bounded task execution or symbolic-link setup fails
    @Test
    void finalValidatorRetainsSymbolicLinkDescendantDeclaration(@TempDir Path temporaryDirectory) throws Exception {
        Path instanceDirectory = Files.createDirectories(temporaryDirectory.resolve("instance"));
        Path externalRunDirectory = Files.createDirectories(temporaryDirectory.resolve("external-run"));
        Path linkedRunDirectory = instanceDirectory.resolve("run");
        try {
            Files.createSymbolicLink(linkedRunDirectory, externalRunDirectory);
        } catch (IOException | UnsupportedOperationException | SecurityException unavailable) {
            Assumptions.assumeTrue(false, "Symbolic links are unavailable: " + unavailable.getMessage());
            return;
        }

        TaskResource instanceResource = TaskResource.gameInstance(instanceDirectory);
        TaskResource runResource = TaskResource.gameDirectory(linkedRunDirectory);
        Task<?> validator = Task.completed(null).setResources(instanceResource, runResource);
        assertEquals(Set.of(instanceResource), validator.getResources());
        assertEquals(Set.of(instanceResource, runResource), validator.getResourceDeclarations());

        AtomicBoolean persisted = new AtomicBoolean();
        Task<@Nullable Void> persistence = JavaRuntimeRepairTaskFactory.createPersistenceTask(
                Task.completed(runtime("symbolic-link-selection")),
                instanceDirectory,
                instanceDirectory.resolve("settings.json"),
                validator,
                ignoredJava -> persisted.set(true));

        assertTrue(persistence.test(), () -> "Symbolic-link persistence failed: " + persistence.getException());
        assertTrue(persisted.get());
    }

    /// Downloads only after an empty first decision and uses the post-download decision instead of the download result.
    ///
    /// @throws Exception when the composed task unexpectedly fails
    @Test
    void reselectsFromRegistryAfterDownload() throws Exception {
        JavaRuntime downloaded = runtime("download-result");
        JavaRuntime selected = runtime("post-download-selection");
        AtomicReference<@Nullable JavaRuntime> registered = new AtomicReference<>();
        AtomicInteger selections = new AtomicInteger();
        AtomicInteger downloads = new AtomicInteger();

        Task<JavaRuntime> repair = JavaRuntimeRepairTaskFactory.resolveCompatibleJava(
                manifest(),
                GameVersionNumber.asGameVersion("1.20.1"),
                ignoredTarget -> Task.supplyAsync(() -> {
                    selections.incrementAndGet();
                    return registered.get();
                }),
                target -> {
                    assertSame(GameJavaVersion.JAVA_17, target);
                    downloads.incrementAndGet();
                    return Task.supplyAsync(() -> {
                        registered.set(selected);
                        return downloaded;
                    });
                });

        assertSame(selected, repair.run());
        assertEquals(2, selections.get());
        assertEquals(1, downloads.get());
    }

    /// Accepts the same runtime from both decisions without requiring a comparison with the failed launch runtime.
    ///
    /// @throws Exception when the composed task unexpectedly fails
    @Test
    void acceptsUnchangedFinalSelection() throws Exception {
        JavaRuntime selected = runtime("same-selection");
        AtomicInteger selections = new AtomicInteger();

        Task<JavaRuntime> repair = JavaRuntimeRepairTaskFactory.resolveCompatibleJava(
                manifest(),
                GameVersionNumber.asGameVersion("1.20.1"),
                ignoredTarget -> Task.supplyAsync(() -> {
                    selections.incrementAndGet();
                    return selected;
                }),
                ignoredTarget -> {
                    throw new AssertionError("Download factory must not be called for a compatible runtime");
                });

        assertSame(selected, repair.run());
        assertEquals(2, selections.get());
    }

    /// Stops after an acquisition failure instead of making a misleading final Java decision.
    @Test
    void downloadFailureStopsFinalSelection() {
        AtomicInteger selections = new AtomicInteger();

        Task<JavaRuntime> repair = JavaRuntimeRepairTaskFactory.resolveCompatibleJava(
                manifest(),
                GameVersionNumber.asGameVersion("1.20.1"),
                ignoredTarget -> Task.supplyAsync(() -> {
                    selections.incrementAndGet();
                    return null;
                }),
                ignoredTarget -> Task.supplyAsync(() -> {
                    throw new IOException("download failed");
                }));

        assertThrows(IOException.class, repair::run);
        assertEquals(1, selections.get());
    }

    /// Reports a failed acquisition when a fresh registry decision still has no compatible runtime.
    @Test
    void rejectsMissingPostAcquisitionSelection() {
        JavaRuntime downloaded = runtime("downloaded-but-unregistered");

        Task<JavaRuntime> repair = JavaRuntimeRepairTaskFactory.resolveCompatibleJava(
                manifest(),
                GameVersionNumber.asGameVersion("1.20.1"),
                ignoredTarget -> Task.completed(null),
                ignoredTarget -> Task.completed(downloaded));

        IllegalStateException failure = assertThrows(IllegalStateException.class, repair::run);
        assertTrue(failure.getMessage().contains("No compatible Java runtime"));
    }

    /// Rejects a generally launchable runtime when its major version differs from the diagnosed repair target.
    @Test
    void targetSelectionRequiresTheDiagnosedJavaMajor() {
        GameVersionNumber gameVersion = GameVersionNumber.asGameVersion("1.16.5");
        GameInstanceManifest java8Manifest = manifest(GameJavaVersion.JAVA_8);
        JavaRuntime java8 = runtime("strict-java-8", "1.8.0_412");
        JavaRuntime java17 = runtime("generic-java-17", "17.0.12");

        assertSame(java17, JavaManager.findSuitableJava(List.of(java17), gameVersion, java8Manifest));
        assertNull(JavaRuntimeRepairTaskFactory.selectTargetJava(
                List.of(java17),
                gameVersion,
                java8Manifest,
                GameJavaVersion.JAVA_8));
        assertSame(java8, JavaRuntimeRepairTaskFactory.selectTargetJava(
                List.of(java17, java8),
                gameVersion,
                java8Manifest,
                GameJavaVersion.JAVA_8));
    }

    /// Gives Cleanroom's mandatory Java rule priority and reuses a higher compatible registered runtime.
    ///
    /// @throws Exception when the composed task unexpectedly fails
    @Test
    void cleanroomReusesHigherCompatibleJavaWithoutDownloading() throws Exception {
        GameVersionNumber gameVersion = GameVersionNumber.asGameVersion("1.12.2");
        GameInstanceManifest cleanroomManifest = manifest(GameJavaVersion.JAVA_8)
                .withPatches(List.of(new GameInstancePatch("cleanroom").withVersion("0.4.0")));
        JavaRuntime java25 = runtime("cleanroom-java-25", "25.0.1");
        AtomicReference<@Nullable GameJavaVersion> requestedTarget = new AtomicReference<>();

        Task<JavaRuntime> repair = JavaRuntimeRepairTaskFactory.resolveCompatibleJava(
                cleanroomManifest,
                gameVersion,
                target -> {
                    assertSame(GameJavaVersion.JAVA_21, target);
                    requestedTarget.set(target);
                    @Nullable JavaRuntime selected = JavaRuntimeRepairTaskFactory.selectTargetJava(
                            List.of(java25),
                            gameVersion,
                            cleanroomManifest,
                            target);
                    assertSame(java25, selected);
                    return Task.supplyAsync(() -> selected);
                },
                ignoredTarget -> {
                    throw new AssertionError("A compatible registered Cleanroom runtime must skip downloading");
                });

        assertSame(java25, repair.run());
        assertSame(GameJavaVersion.JAVA_21, requestedTarget.get());
    }

    /// Restores direct values and override ownership when durable settings persistence fails.
    @Test
    void persistenceFailureRollsBackTheInMemorySelection() {
        GameSettings.Instance setting = new GameSettings.Instance();
        GameSettings.DetectedJava previousJava = new GameSettings.DetectedJava("8.0.412", "previous-path");
        setting.getOverrideProperties().add(GameSettings.PROPERTY_JAVA_TYPE);
        setting.javaTypeProperty().setValue(JavaVersionType.CUSTOM);
        setting.detectedJavaProperty().setValue(previousJava);

        IOException failure = assertThrows(IOException.class, () ->
                JavaRuntimeRepairTaskFactory.persistJavaSelection(
                        () -> setting,
                        runtime("new-selection"),
                        () -> {
                            throw new IOException("settings write failed");
                        }));

        assertEquals("settings write failed", failure.getMessage());
        assertSame(JavaVersionType.CUSTOM, setting.javaTypeProperty().getValue());
        assertSame(previousJava, setting.detectedJavaProperty().getValue());
        assertTrue(setting.getOverrideProperties().contains(GameSettings.PROPERTY_JAVA_TYPE));
        assertFalse(setting.getOverrideProperties().contains(GameSettings.PROPERTY_DETECTED_JAVA));
    }

    /// Waits for a real FileSaver write before the persistence boundary returns.
    ///
    /// @throws Exception when the selection or save barrier unexpectedly fails
    @Test
    void persistenceBoundaryDrainsQueuedFileSaverWrites(@TempDir Path temporaryDirectory) throws Exception {
        Path savedFile = temporaryDirectory.resolve("instance-settings.json");
        GameSettings.Instance setting = new GameSettings.Instance();

        JavaRuntimeRepairTaskFactory.persistJavaSelectionAndDrain(
                () -> setting,
                runtime("drained-selection"),
                () -> FileSaver.save(savedFile, "saved"),
                FileSaver::waitForAllSaves);

        assertEquals("saved", Files.readString(savedFile));
    }

    /// Preserves an Error as the primary failure and suppresses a later save-barrier failure on it.
    @Test
    void saveBarrierFailureDoesNotReplacePersistenceError() {
        GameSettings.Instance setting = new GameSettings.Instance();
        AssertionError persistenceFailure = new AssertionError("persistence failed");
        IOException barrierFailure = new IOException("barrier failed");

        AssertionError thrown = assertThrows(AssertionError.class, () ->
                JavaRuntimeRepairTaskFactory.persistJavaSelectionAndDrain(
                        () -> setting,
                        runtime("error-selection"),
                        () -> {
                            throw persistenceFailure;
                        },
                        () -> {
                            throw barrierFailure;
                        }));

        assertSame(persistenceFailure, thrown);
        assertEquals(1, thrown.getSuppressed().length);
        assertSame(barrierFailure, thrown.getSuppressed()[0]);
    }

    /// Retries an interrupted save barrier to completion and restores the caller's interrupted state.
    @Test
    void interruptedSaveBarrierCompletesBeforeReportingInterruption() {
        GameSettings.Instance setting = new GameSettings.Instance();
        InterruptedException interruption = new InterruptedException("barrier interrupted");
        AtomicInteger attempts = new AtomicInteger();

        try {
            InterruptedException thrown = assertThrows(InterruptedException.class, () ->
                    JavaRuntimeRepairTaskFactory.persistJavaSelectionAndDrain(
                            () -> setting,
                            runtime("interrupted-selection"),
                            () -> {
                            },
                            () -> {
                                if (attempts.incrementAndGet() == 1) {
                                    throw interruption;
                                }
                            }));

            assertSame(interruption, thrown);
            assertEquals(2, attempts.get());
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    /// Creates a manifest that explicitly requests Java 17.
    private static GameInstanceManifest manifest() {
        return manifest(GameJavaVersion.JAVA_17);
    }

    /// Creates a manifest that explicitly requests the supplied Java family.
    ///
    /// @param javaVersion requested Java family
    /// @return synthetic manifest
    private static GameInstanceManifest manifest(GameJavaVersion javaVersion) {
        return new GameInstanceManifest(new GameInstanceID("test"))
                .withJavaVersion(javaVersion);
    }

    /// Creates a synthetic managed Java runtime.
    private static JavaRuntime runtime(String directory) {
        return runtime(directory, "17.0.12");
    }

    /// Creates a synthetic managed Java runtime with explicit version metadata.
    ///
    /// @param directory synthetic runtime directory
    /// @param version Java version text
    /// @return synthetic managed runtime
    private static JavaRuntime runtime(String directory, String version) {
        return new JavaRuntime(
                Path.of("C:/Java", directory, "bin", "java.exe"),
                new JavaInfo(Platform.SYSTEM_PLATFORM, version, "Test Vendor"),
                true,
                false);
    }
}
