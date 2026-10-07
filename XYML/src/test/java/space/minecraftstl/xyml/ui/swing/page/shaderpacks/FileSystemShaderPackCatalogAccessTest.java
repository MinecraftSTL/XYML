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
import org.junit.jupiter.api.io.TempDir;
import space.minecraftstl.xyml.util.io.DeletionMode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies filesystem indexing, configuration adapters, and safe mutations.
@NotNullByDefault
final class FileSystemShaderPackCatalogAccessTest {
    /// Verifies backend detection and per-backend enabled state.
    @Test
    void detectsAndReadsBothBackends(@TempDir Path root) throws IOException {
        createPack(root.resolve("shaderpacks/A"));
        write(root.resolve("config/iris.properties"), "shaderPack=A\n");
        write(root.resolve("optionsof.txt"), "ofShaders:true\nofShaderPack:A\n");
        FileSystemShaderPackCatalogAccess access = new FileSystemShaderPackCatalogAccess(
                root,
                root.resolve("mods"),
                () -> true);

        ShaderPackCatalogItem item = access.loadItems(access.loadIndex()).get(0);

        assertEquals(Set.of(ShaderPackBackend.IRIS_OCULUS, ShaderPackBackend.OPTIFINE),
                access.detectAvailableBackends());
        assertEquals(Set.of(ShaderPackBackend.IRIS_OCULUS, ShaderPackBackend.OPTIFINE),
                item.enabledBackends());
    }

    /// Verifies that enabling one pack replaces the previous selection without touching unknown text.
    @Test
    void replacesSingleSelectionAndPreservesConfiguration(@TempDir Path root) throws IOException {
        createPack(root.resolve("shaderpacks/A"));
        createPack(root.resolve("shaderpacks/B"));
        Path iris = root.resolve("config/iris.properties");
        Path optifine = root.resolve("optionsof.txt");
        write(iris, "# keep me\r\nunknown=value\r\nshaderPack=A\r\n");
        write(optifine, "unknown:value\r\nofShaders:true\r\nofShaderPack:A\r\n");
        FileSystemShaderPackCatalogAccess access = new FileSystemShaderPackCatalogAccess(
                root,
                root.resolve("mods"),
                () -> true);

        access.setEnabled(root.resolve("shaderpacks/B"), Set.of(ShaderPackBackend.IRIS_OCULUS), true);
        access.setEnabled(root.resolve("shaderpacks/B"), Set.of(ShaderPackBackend.OPTIFINE), true);

        List<ShaderPackCatalogItem> items = access.loadItems(access.loadIndex());
        ShaderPackCatalogItem a = items.stream().filter(item -> item.fileName().equals("A")).findFirst().orElseThrow();
        ShaderPackCatalogItem b = items.stream().filter(item -> item.fileName().equals("B")).findFirst().orElseThrow();
        assertFalse(a.enabled());
        assertEquals(Set.of(ShaderPackBackend.IRIS_OCULUS, ShaderPackBackend.OPTIFINE), b.enabledBackends());
        assertTrue(Files.readString(iris).contains("# keep me\r\nunknown=value\r\nshaderPack=B"));
        assertTrue(Files.readString(optifine).contains("unknown:value\r\nofShaders:true\r\nofShaderPack:B"));
    }

    /// Verifies import validation and duplicate-target protection.
    @Test
    void importsValidPackAndRejectsDuplicate(@TempDir Path root) throws IOException {
        Path source = root.resolve("source");
        createPack(source);
        Path run = root.resolve("run");
        FileSystemShaderPackCatalogAccess access = new FileSystemShaderPackCatalogAccess(
                run,
                run.resolve("mods"),
                () -> false);

        access.importShaderPacks(List.of(source));

        Path installed = run.resolve("shaderpacks/source");
        assertTrue(Files.isDirectory(installed));
        assertThrows(IOException.class, () -> access.importShaderPacks(List.of(source)));
        assertThrows(IllegalArgumentException.class, () -> access.importShaderPacks(List.of(run.resolve("missing"))));
    }

    /// Verifies deletion clears active backend pointers before removing the file.
    @Test
    void deletesEnabledPackAndClearsPointers(@TempDir Path root) throws IOException {
        Path pack = root.resolve("shaderpacks/A");
        createPack(pack);
        Path iris = root.resolve("config/iris.properties");
        Path optifine = root.resolve("optionsof.txt");
        write(iris, "shaderPack=A\n");
        write(optifine, "ofShaders:true\nofShaderPack=A\n");
        FileSystemShaderPackCatalogAccess access = new FileSystemShaderPackCatalogAccess(
                root,
                root.resolve("mods"),
                () -> true);

        access.delete(pack, DeletionMode.PERMANENT);

        assertFalse(Files.exists(pack));
        assertTrue(Files.readString(iris).contains("shaderPack=OFF"));
        assertTrue(Files.readString(optifine).contains("ofShaders:false"));
    }

    /// Requires a valid shader-pack payload when an API caller enables a pack directly.
    @Test
    void rejectsDirectActivationOfInvalidPack(@TempDir Path root) throws IOException {
        Path invalid = root.resolve("shaderpacks/invalid");
        Files.createDirectories(invalid);
        FileSystemShaderPackCatalogAccess access = new FileSystemShaderPackCatalogAccess(
                root,
                root.resolve("mods"),
                () -> false);

        assertThrows(IllegalArgumentException.class, () -> access.setEnabled(
                invalid, Set.of(ShaderPackBackend.IRIS_OCULUS), true));
    }

    /// Rejects ZIP entries that escape the shader-pack archive's logical root.
    @Test
    void rejectsUnsafeZipShaderPack(@TempDir Path root) throws IOException {
        Path archive = root.resolve("shaderpacks/unsafe.zip");
        Files.createDirectories(archive.getParent());
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(archive))) {
            output.putNextEntry(new ZipEntry("../outside.txt"));
            output.write("escape".getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
        }
        FileSystemShaderPackCatalogAccess access = new FileSystemShaderPackCatalogAccess(
                root,
                root.resolve("mods"),
                () -> false);

        assertThrows(IllegalArgumentException.class, () -> access.setEnabled(
                archive, Set.of(ShaderPackBackend.IRIS_OCULUS), true));
    }

    /// Uses supported metadata rather than a misleading archive file name for Iris detection.
    @Test
    void detectsSupportedModMetadataAndIgnoresUnknownJarNames(@TempDir Path root) throws IOException {
        Path mods = root.resolve("mods");
        Files.createDirectories(mods);
        writeModJar(mods.resolve("random-name.jar"), "{\"id\":\"iris\"}");
        FileSystemShaderPackCatalogAccess access = new FileSystemShaderPackCatalogAccess(
                root,
                mods,
                () -> false);

        assertEquals(Set.of(ShaderPackBackend.IRIS_OCULUS), access.detectAvailableBackends());

        Files.delete(mods.resolve("random-name.jar"));
        writeModJar(mods.resolve("iris-looking.jar"), "{\"name\":\"iris\"}");
        assertEquals(Set.of(), access.detectAvailableBackends());
    }

    /// Creates a valid directory-style shader pack.
    ///
    /// @param path target directory
    /// @throws IOException when the fixture cannot be created
    private static void createPack(Path path) throws IOException {
        Files.createDirectories(path.resolve("shaders"));
        write(path.resolve("shaders/shader.fsh"), "void main() { }\n");
    }

    /// Writes a minimal mod archive with one metadata entry.
    ///
    /// @param path archive path
    /// @param metadata JSON metadata text
    /// @throws IOException when the archive cannot be written
    private static void writeModJar(Path path, String metadata) throws IOException {
        Files.createDirectories(path.getParent());
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(path))) {
            output.putNextEntry(new ZipEntry("fabric.mod.json"));
            output.write(metadata.getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
        }
    }

    /// Writes one UTF-8 fixture file.
    ///
    /// @param path target path
    /// @param content file content
    /// @throws IOException when writing fails
    private static void write(Path path, String content) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, content, StandardCharsets.UTF_8);
    }
}
