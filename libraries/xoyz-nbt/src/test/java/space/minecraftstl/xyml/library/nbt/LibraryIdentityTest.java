/*
 * Copyright 2026 MinecraftSTL
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
// Added by MinecraftSTL in 2026 for the XYML namespace and monorepo build.
package space.minecraftstl.xyml.library.nbt;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import space.minecraftstl.xyml.library.nbt.io.NBTCodec;
import space.minecraftstl.xyml.library.nbt.tag.CompoundTag;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.module.ModuleDescriptor;
import java.lang.module.ModuleFinder;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies the public identity and basic serialization contract of the namespaced XoyzNBT artifact.
@NotNullByDefault
public final class LibraryIdentityTest {
    /// Runs one command against the executable library JAR.
    ///
    /// @param artifact executable library JAR
    /// @param argument command-line argument
    /// @return trimmed combined process output
    /// @throws IOException when the process cannot be started or read
    /// @throws InterruptedException when the process wait is interrupted
    private static String runJar(Path artifact, String argument) throws IOException, InterruptedException {
        String executableName = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        Path javaExecutable = Path.of(System.getProperty("java.home"), "bin", executableName);
        Process process = new ProcessBuilder(javaExecutable.toString(), "-jar", artifact.toString(), argument)
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
        assertEquals(0, process.waitFor(), output);
        return output;
    }

    /// Verifies the renamed module and public package exposed to downstream consumers.
    @Test
    void moduleAndPublicPackageUseXymlNamespace() {
        Path artifact = resolveArtifact(
                "xyml.xoyzNbt.jar", "xoyz-nbt", "space.minecraftstl.xyml.library.nbt");
        ModuleDescriptor descriptor = ModuleFinder.of(artifact).findAll().iterator().next().descriptor();
        assertEquals("space.minecraftstl.xyml.library.nbt", descriptor.name());
        assertTrue(descriptor.exports().stream().anyMatch(export ->
                export.source().equals("space.minecraftstl.xyml.library.nbt")));
        assertEquals("space.minecraftstl.xyml.library.nbt", NBTElement.class.getPackageName());
    }

    /// Verifies the executable JAR exposes only the XoyzNBT CLI identity and version metadata.
    ///
    /// @throws IOException when the executable JAR cannot be started or read
    /// @throws InterruptedException when the process wait is interrupted
    @Test
    void executableJarUsesXoyzNbtIdentity() throws IOException, InterruptedException {
        Path artifact = resolveArtifact(
                "xyml.xoyzNbt.jar", "xoyz-nbt", "space.minecraftstl.xyml.library.nbt");
        ModuleDescriptor descriptor = ModuleFinder.of(artifact).findAll().iterator().next().descriptor();

        assertTrue(runJar(artifact, "--help").startsWith("Usage: xoyz-nbt [options]"));
        assertEquals(descriptor.rawVersion().orElseThrow(), runJar(artifact, "--version"));
    }

    /// Resolves a Gradle-injected artifact path or the artifact matching the compiled module
    /// for IntelliJ's JUnit runner.
    ///
    /// @param propertyName Gradle system property containing the artifact path
    /// @param artifactName archive base name
    /// @param moduleName compiled module name
    /// @return executable library artifact
    /// @throws IllegalStateException when no local artifact can be found
    private static Path resolveArtifact(String propertyName, String artifactName, String moduleName) {
        @Nullable String configuredPath = System.getProperty(propertyName);
        if (configuredPath != null && !configuredPath.isBlank()) {
            return Path.of(configuredPath);
        }

        Path libraryDirectory = moduleDirectory();
        Path mainClasses = libraryDirectory.resolve("build/classes/java/main");
        if (!Files.isDirectory(mainClasses)) {
            throw new IllegalStateException("Compiled module classes were not found: " + mainClasses);
        }

        ModuleDescriptor descriptor = ModuleFinder.of(mainClasses).find(moduleName)
                .orElseThrow(() -> new IllegalStateException(
                        "Compiled module descriptor was not found: " + moduleName))
                .descriptor();
        String version = descriptor.rawVersion()
                .orElseThrow(() -> new IllegalStateException(
                        "Compiled module has no version: " + moduleName));
        Path artifact = libraryDirectory.resolve("build/libs").resolve(artifactName + "-" + version + ".jar");
        if (!Files.isRegularFile(artifact)) {
            throw new IllegalStateException("Artifact for compiled version was not found: " + artifact);
        }
        return artifact;
    }

    /// Locates the module containing the compiled test class.
    ///
    /// @return module directory
    /// @throws IllegalStateException when the test output location cannot be mapped to a module
    private static Path moduleDirectory() {
        try {
            @Nullable CodeSource codeSource = LibraryIdentityTest.class.getProtectionDomain().getCodeSource();
            if (codeSource != null) {
                @Nullable URL location = codeSource.getLocation();
                if (location != null) {
                    Path outputLocation = Path.of(location.toURI()).toAbsolutePath().normalize();
                    for (@Nullable Path current = outputLocation;
                         current != null;
                         current = current.getParent()) {
                        @Nullable Path fileName = current.getFileName();
                        if (fileName != null && "build".equals(fileName.toString()) && Files.isDirectory(current)) {
                            @Nullable Path moduleDirectory = current.getParent();
                            if (moduleDirectory != null) {
                                return moduleDirectory;
                            }
                        }
                    }
                }
            }
        } catch (URISyntaxException
                 | FileSystemNotFoundException
                 | IllegalArgumentException
                 | SecurityException exception) {
            throw new IllegalStateException("Unable to locate test output directory", exception);
        }

        Path workingDirectory = Path.of(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();
        Path modulePath = workingDirectory.resolve("libraries/xoyz-nbt");
        if (Files.isDirectory(modulePath.resolve("build"))) {
            return modulePath;
        }
        if (Files.isDirectory(workingDirectory.resolve("build"))) {
            return workingDirectory;
        }
        throw new IllegalStateException("Unable to locate xoyz-nbt module directory from the test classpath");
    }

    /// Verifies that a representative compound tag survives binary NBT serialization.
    ///
    /// @throws IOException when the in-memory codec unexpectedly fails
    @Test
    void compoundTagRoundTripsThroughBinaryCodec() throws IOException {
        CompoundTag original = new CompoundTag()
                .addString("name", "XYML")
                .addInt("format", 1);
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        NBTCodec.of().writeTag(output, original);
        CompoundTag decoded = NBTCodec.of().readTag(output.toByteArray(), CompoundTag.class);

        assertEquals(original, decoded);
    }
}
