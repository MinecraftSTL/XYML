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
package space.minecraftstl.xyml.ui.swing.page.shaderpacks;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies wrapped shader ZIPs through the production filesystem catalog and configuration boundary.
@NotNullByDefault
final class ShaderPackCatalogZipIntegrationTest {
    /// Recognizes root, one-wrapper, and deeper-wrapped shader payloads without extracting the archive.
    ///
    /// @param run temporary run directory
    /// @throws IOException when fixtures or configuration cannot be read
    @Test
    void recognizesWrappedArchivesAndPreservesOriginalBytes(@TempDir Path run) throws IOException {
        FileSystemShaderPackCatalogAccess access = access(run);
        int index = 0;
        for (String entry : List.of("shaders/final.fsh", "photon-voxy-support/shaders/final.fsh",
                "outer/photon-voxy-support/shaders/world0/final.fsh")) {
            Path source = run.resolve("sources/pack-" + index++ + ".zip");
            createArchive(source, List.of(entry));
            byte[] original = Files.readAllBytes(source);
            access.importShaderPacks(List.of(source));
            Path installed = access.directory().resolve(source.getFileName());
            assertTrue(access.loadItems(List.of(installed)).get(0).valid());
            org.junit.jupiter.api.Assertions.assertArrayEquals(original, Files.readAllBytes(installed));
            access.setEnabled(installed, Set.of(ShaderPackBackend.IRIS_OCULUS), true);
            assertTrue(access.loadItems(List.of(installed)).get(0).enabled());
            access.setEnabled(installed, Set.of(ShaderPackBackend.IRIS_OCULUS), false);
            assertFalse(access.loadItems(List.of(installed)).get(0).enabled());
        }
    }

    /// Wrapper support does not accept traversal, absolute paths, misleading names, or directory-only archives.
    ///
    /// @param run temporary run directory
    /// @throws IOException when fixture archives cannot be written
    @Test
    void rejectsUnsafeAndNonShaderArchives(@TempDir Path run) throws IOException {
        FileSystemShaderPackCatalogAccess access = access(run);
        int index = 0;
        for (List<String> entries : List.of(List.of("wrap/shaders/final.fsh", "../outside"),
                List.of("wrap/shaders/final.fsh", "/outside"),
                List.of("wrap/not-shaders/final.fsh"), List.of("wrap/shaders/"),
                List.of("wrap/shaders/final.fsh", "wrap/../outside"))) {
            Path zip = access.directory().resolve("invalid-" + index++ + ".zip");
            createArchive(zip, entries);
            assertFalse(access.loadItems(List.of(zip)).get(0).valid());
            assertThrows(IllegalArgumentException.class, () -> access.setEnabled(
                    zip, Set.of(ShaderPackBackend.IRIS_OCULUS), true));
        }
        assertFalse(Files.exists(run.resolve("config/iris.properties")));
    }

    /// Entry count limits remain in force even after a nested shader file has already been found.
    ///
    /// @param run temporary run directory
    /// @throws IOException when fixture archives cannot be written
    @Test
    void retainsArchiveEntryLimit(@TempDir Path run) throws IOException {
        FileSystemShaderPackCatalogAccess access = access(run);
        java.util.ArrayList<String> entries = new java.util.ArrayList<>();
        entries.add("photon-voxy-support/shaders/final.fsh");
        for (int index = 1; index < 4096; index++) entries.add("photon-voxy-support/docs/" + index);
        Path bounded = access.directory().resolve("bounded.zip");
        createArchive(bounded, entries);
        assertTrue(access.loadItems(List.of(bounded)).get(0).valid());
        entries.add("photon-voxy-support/docs/4096");
        Path oversized = access.directory().resolve("oversized.zip");
        createArchive(oversized, entries);
        assertFalse(access.loadItems(List.of(oversized)).get(0).valid());
    }

    /// An opt-in local sample is copied to test storage; only the copy and temporary configuration are modified.
    ///
    /// @param run temporary run directory
    /// @throws IOException when the supplied sample or temporary files cannot be accessed
    @Test
    @EnabledIfEnvironmentVariable(named = "XYML_SHADERPACK_SAMPLE", matches = ".+")
    void recognizesUserSampleWithoutModifyingSource(@TempDir Path run) throws IOException {
        Path sample = Path.of(Objects.requireNonNull(System.getenv("XYML_SHADERPACK_SAMPLE")));
        byte[] before = Files.readAllBytes(sample);
        FileSystemShaderPackCatalogAccess access = access(run);
        access.importShaderPacks(List.of(sample));
        Path installed = access.directory().resolve(sample.getFileName());
        ShaderPackCatalogItem item = access.loadItems(access.loadIndex()).get(0);
        assertEquals(installed.toAbsolutePath().normalize(), item.path());
        assertTrue(item.valid());
        access.setEnabled(installed, Set.of(ShaderPackBackend.IRIS_OCULUS), true);
        assertTrue(access.loadItems(access.loadIndex()).get(0).enabled());
        access.setEnabled(installed, Set.of(ShaderPackBackend.IRIS_OCULUS), false);
        assertFalse(access.loadItems(access.loadIndex()).get(0).enabled());
        org.junit.jupiter.api.Assertions.assertArrayEquals(before, Files.readAllBytes(sample));
    }

    /// Creates access exclusively inside test-owned storage.
    ///
    /// @param run temporary run directory
    /// @return production catalog boundary
    private static FileSystemShaderPackCatalogAccess access(Path run) {
        return new FileSystemShaderPackCatalogAccess(run, run.resolve("mods"), () -> false);
    }

    /// Writes bounded synthetic entry contents without executing any shader or archive script.
    ///
    /// @param path ZIP fixture path
    /// @param names exact archive entry names
    /// @throws IOException when ZIP writing fails
    private static void createArchive(Path path, List<String> names) throws IOException {
        Files.createDirectories(path.getParent());
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(path))) {
            for (String name : names) {
                output.putNextEntry(new ZipEntry(name));
                if (!name.endsWith("/")) output.write("fixture".getBytes(StandardCharsets.UTF_8));
                output.closeEntry();
            }
        }
    }
}
