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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/// Verifies bounded local shader-pack metadata extraction.
@NotNullByDefault
final class FileSystemShaderPackCatalogMetadataTest {
    /// Reads pack.mcmeta without reading or representing package icons.
    @Test
    void readsDirectoryPresentationMetadata(@TempDir Path temporaryDirectory) throws Exception {
        Path shaderPack = temporaryDirectory.resolve("shaderpacks").resolve("example");
        Files.createDirectories(shaderPack.resolve("shaders"));
        Files.writeString(shaderPack.resolve("pack.mcmeta"), "{\"pack\":{\"description\":\"Example shader\"}}");
        Files.write(shaderPack.resolve("icon.png"), new byte[] {1, 2, 3});

        FileSystemShaderPackCatalogAccess access = new FileSystemShaderPackCatalogAccess(
                temporaryDirectory,
                temporaryDirectory.resolve("mods"),
                () -> false);
        ShaderPackCatalogItem item = access.loadItems(List.of(shaderPack)).get(0);

        assertEquals("Example shader", item.description());
        assertEquals(true, item.valid());
    }

    /// Missing metadata is represented as an empty description.
    @Test
    void missingPresentationMetadataUsesFallback(@TempDir Path temporaryDirectory) throws Exception {
        Path shaderPack = temporaryDirectory.resolve("shaderpacks").resolve("empty");
        Files.createDirectories(shaderPack.resolve("shaders"));

        FileSystemShaderPackCatalogAccess access = new FileSystemShaderPackCatalogAccess(
                temporaryDirectory,
                temporaryDirectory.resolve("mods"),
                () -> false);
        ShaderPackCatalogItem item = access.loadItems(List.of(shaderPack)).get(0);

        assertEquals("", item.description());
    }
}
