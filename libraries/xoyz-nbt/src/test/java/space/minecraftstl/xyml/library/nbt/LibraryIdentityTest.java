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
import org.junit.jupiter.api.Test;
import space.minecraftstl.xyml.library.nbt.io.NBTCodec;
import space.minecraftstl.xyml.library.nbt.tag.CompoundTag;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.module.ModuleDescriptor;
import java.lang.module.ModuleFinder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

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
        Path artifact = Path.of(System.getProperty("xyml.xoyzNbt.jar"));
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
        Path artifact = Path.of(System.getProperty("xyml.xoyzNbt.jar"));
        ModuleDescriptor descriptor = ModuleFinder.of(artifact).findAll().iterator().next().descriptor();

        assertTrue(runJar(artifact, "--help").startsWith("Usage: xoyz-nbt [options]"));
        assertEquals(descriptor.rawVersion().orElseThrow(), runJar(artifact, "--version"));
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
