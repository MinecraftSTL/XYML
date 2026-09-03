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
import org.junit.jupiter.api.Test;
import space.minecraftstl.xyml.java.JavaInfo;
import space.minecraftstl.xyml.java.JavaManager;
import space.minecraftstl.xyml.java.JavaRuntime;
import space.minecraftstl.xyml.setting.GameSettings;
import space.minecraftstl.xyml.setting.JavaVersionType;
import space.minecraftstl.xyml.task.Task;
import space.minecraftstl.xyml.util.platform.Platform;
import space.minecraftstl.xyml.util.versioning.GameVersionNumber;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
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

        assertSame(finalSelection, repair.run());
        assertEquals(2, selections.get());
        assertEquals(0, downloads.get());
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
