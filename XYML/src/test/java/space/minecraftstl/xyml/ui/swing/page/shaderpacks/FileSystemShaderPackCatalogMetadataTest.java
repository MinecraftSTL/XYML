// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

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
