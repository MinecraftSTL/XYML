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
package space.minecraftstl.xyml.mcp;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import space.minecraftstl.xyml.java.JavaInfo;
import space.minecraftstl.xyml.java.JavaRuntime;
import space.minecraftstl.xyml.task.TaskResource;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies MCP launch resource derivation covers every mutable or inheritance-sensitive filesystem range.
@NotNullByDefault
public final class XYMLMcpServiceLaunchResourceTest {
    /// Shared assets and the effective inherited JAR instance remain protected until process creation.
    ///
    /// @param temporaryDirectory isolated resource paths
    @Test
    public void launchResourcesIncludeAssetsAndInheritedJarInstance(@TempDir Path temporaryDirectory) {
        Path instance = temporaryDirectory.resolve("versions/child");
        Path jarInstance = temporaryDirectory.resolve("versions/base");
        Path run = temporaryDirectory.resolve("runs/child");
        Path libraries = temporaryDirectory.resolve("libraries");
        Path assets = temporaryDirectory.resolve("assets");
        Path natives = instance.resolve("natives");
        JavaRuntime java = testJava(temporaryDirectory);

        List<TaskResource> resources = XYMLMcpService.launchResources(
                temporaryDirectory, instance, jarInstance, run, libraries, assets, java, natives);

        assertTrue(resources.contains(TaskResource.repositoryOperation(temporaryDirectory)));
        assertTrue(resources.contains(TaskResource.gameInstance(instance)));
        assertTrue(resources.contains(TaskResource.gameInstance(jarInstance)));
        assertTrue(resources.contains(TaskResource.gameDirectory(run)));
        assertTrue(resources.contains(TaskResource.gameDirectory(libraries)));
        assertTrue(resources.contains(TaskResource.gameDirectory(assets)));
        assertTrue(resources.contains(TaskResource.gameDirectory(natives)));
    }

    /// Wrapper, pre-launch, and post-exit commands are rejected because descendants may outlive the Task lease.
    @Test
    public void rejectsArbitraryCommandsOutsideTaskLifecycle() {
        assertThrows(IllegalStateException.class,
                () -> XYMLMcpService.requireSupportedLaunchCommands("wrapper", "", ""));
        assertThrows(IllegalStateException.class,
                () -> XYMLMcpService.requireSupportedLaunchCommands("", "prepare", ""));
        assertThrows(IllegalStateException.class,
                () -> XYMLMcpService.requireSupportedLaunchCommands("", "", "cleanup"));
    }

    /// Creates a deterministic Java runtime without depending on the test worker's executable discovery.
    ///
    /// @param temporaryDirectory isolated runtime root
    /// @return synthetic runtime with the worker's platform metadata
    private static JavaRuntime testJava(Path temporaryDirectory) {
        return new JavaRuntime(
                temporaryDirectory.resolve("java/bin/java"),
                JavaInfo.CURRENT_ENVIRONMENT,
                false,
                false);
    }
}
