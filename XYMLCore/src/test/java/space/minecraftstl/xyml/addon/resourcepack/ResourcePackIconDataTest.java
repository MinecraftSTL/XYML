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
package space.minecraftstl.xyml.addon.resourcepack;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import space.minecraftstl.xyml.game.DefaultGameRepository;
import space.minecraftstl.xyml.image.EncodedImage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/// Verifies real folder and ZIP scans retain encoded icons without using the JavaFX adapter.
@NotNullByDefault
public final class ResourcePackIconDataTest {
    /// Temporary classic repository root.
    @TempDir
    private Path temporaryDirectory;

    /// Folder and ZIP resource packs expose their exact encoded icon bytes after manager refresh.
    @Test
    public void scansToolkitNeutralFolderAndZipIcons() throws IOException {
        Path resourcePacks = Files.createDirectories(temporaryDirectory.resolve("resourcepacks"));
        byte[] folderIcon = {1, 2, 3, 4};
        byte[] replacementFolderIcon = {9, 10, 11, 12};
        byte[] zipIcon = {5, 6, 7, 8};
        Path folderPack = resourcePacks.resolve("folder-pack");
        createFolderPack(folderPack, folderIcon);
        createZipPack(resourcePacks.resolve("zip-pack.zip"), zipIcon);

        ResourcePackManager manager = new ResourcePackManager(
                new DefaultGameRepository(temporaryDirectory), "instance");
        manager.refresh();
        Files.write(folderPack.resolve("pack.png"), replacementFolderIcon);
        @Unmodifiable List<ResourcePackFile> packs = manager.getLocalFiles();

        assertEquals(List.of("folder-pack", "zip-pack.zip"), packs.stream()
                .map(ResourcePackFile::getFileNameWithExtension)
                .toList());
        assertInstanceOf(ResourcePackFolder.class, packs.get(0));
        assertInstanceOf(ResourcePackZipFile.class, packs.get(1));
        assertEncodedBytes(replacementFolderIcon, packs.get(0).loadIconData());
        assertEncodedBytes(zipIcon, packs.get(1).loadIconData());
    }

    /// Creates one direct resource-pack directory fixture.
    ///
    /// @param directory fixture directory
    /// @param icon encoded icon bytes
    private static void createFolderPack(Path directory, byte[] icon) throws IOException {
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("pack.mcmeta"), packMetadata(), StandardCharsets.UTF_8);
        Files.write(directory.resolve("pack.png"), icon);
    }

    /// Creates one ZIP resource-pack fixture.
    ///
    /// @param archive fixture archive
    /// @param icon encoded icon bytes
    private static void createZipPack(Path archive, byte[] icon) throws IOException {
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(archive))) {
            writeEntry(output, "pack.mcmeta", packMetadata().getBytes(StandardCharsets.UTF_8));
            writeEntry(output, "pack.png", icon);
        }
    }

    /// Writes one complete ZIP fixture entry.
    ///
    /// @param output open fixture archive
    /// @param name entry name
    /// @param content complete entry bytes
    private static void writeEntry(ZipOutputStream output, String name, byte[] content) throws IOException {
        output.putNextEntry(new ZipEntry(name));
        output.write(content);
        output.closeEntry();
    }

    /// Returns minimal valid resource-pack metadata.
    ///
    /// @return metadata JSON
    private static String packMetadata() {
        return "{\"pack\":{\"pack_format\":15,\"description\":\"fixture\"}}";
    }

    /// Verifies one nullable scan result has exact expected bytes.
    ///
    /// @param expected expected encoded bytes
    /// @param encoded nullable scan result
    private static void assertEncodedBytes(byte[] expected, @Nullable EncodedImage encoded) throws IOException {
        assertNotNull(encoded);
        try (var input = Objects.requireNonNull(encoded).openStream()) {
            assertArrayEquals(expected, input.readAllBytes());
        }
    }
}
