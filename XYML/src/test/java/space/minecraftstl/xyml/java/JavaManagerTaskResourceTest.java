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
package space.minecraftstl.xyml.java;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import space.minecraftstl.xyml.download.MojangDownloadProvider;
import space.minecraftstl.xyml.game.GameJavaVersion;
import space.minecraftstl.xyml.setting.SettingsManager;
import space.minecraftstl.xyml.task.Task;
import space.minecraftstl.xyml.task.TaskResource;
import space.minecraftstl.xyml.util.platform.Platform;

import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/// Verifies Java-manager composition roots retain precise child resource declarations.
@NotNullByDefault
public final class JavaManagerTaskResourceTest {
    /// Temporary Java home and archive paths.
    @TempDir
    private Path temporaryDirectory;

    /// Local runtime registration covers its Java home and the persisted user-settings file.
    @Test
    public void localRegistrationUsesRuntimeAndSettingsResources() {
        Path javaHome = temporaryDirectory.resolve("runtime");
        Path executable = javaHome.resolve("bin/java.exe");

        Task<JavaRuntime> task = JavaManager.getAddJavaTask(executable);

        assertEquals(Set.of(
                TaskResource.javaRuntime(javaHome),
                TaskResource.configuration(SettingsManager.USER_SETTINGS_LOCATION)), task.getResources());
    }

    /// Mojang and archive installation continuations preserve every underlying runtime and file resource.
    @Test
    public void managedInstallContinuationsPreserveUnderlyingResources() {
        Platform platform = Platform.SYSTEM_PLATFORM;
        GameJavaVersion version = GameJavaVersion.JAVA_17;
        Path archive = temporaryDirectory.resolve("runtime.zip");
        Task<JavaRuntime> download = JavaManager.getDownloadJavaTask(new MojangDownloadProvider(), platform, version);
        Task<JavaRuntime> install = JavaManager.getInstallJavaTask(platform, "fixture", Map.of(), archive);

        assertEquals(
                JavaManager.REPOSITORY.getDownloadJavaTask(new MojangDownloadProvider(), platform, version)
                        .getResources(),
                download.getResources());
        assertEquals(
                JavaManager.REPOSITORY.getInstallJavaTask(platform, "fixture", Map.of(), archive).getResources(),
                install.getResources());
        assertFalse(download.getResources().contains(TaskResource.conservative()));
        assertFalse(install.getResources().contains(TaskResource.conservative()));
    }

    /// Resource forwarding preserves a lexical descendant for later symlink and junction identity resolution.
    @Test
    public void copiedDeclarationRetainsLexicallyCoveredDescendant() {
        Path runtime = temporaryDirectory.resolve("runtime");
        TaskResource boundary = TaskResource.javaRuntime(runtime);
        TaskResource possibleAlias = TaskResource.configuration(runtime.resolve("linked/settings.json"));
        Task<String> source = Task.supplyAsync(() -> "source").setResources(boundary, possibleAlias);
        Task<String> target = Task.supplyAsync(() -> "target");

        JavaManager.copyResourceDeclaration(target, source);

        assertEquals(Set.of(boundary), source.getResources());
        assertEquals(Set.of(boundary, possibleAlias), source.getResourceDeclarations());
        assertEquals(source.getResourceDeclarations(), target.getResourceDeclarations());
    }
}
