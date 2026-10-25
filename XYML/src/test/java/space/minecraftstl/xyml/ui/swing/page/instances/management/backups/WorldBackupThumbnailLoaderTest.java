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
package space.minecraftstl.xyml.ui.swing.page.instances.management.backups;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Exercises actual ZIP and PNG boundaries without parsing NBT, extracting worlds, or changing source archives.
@NotNullByDefault
public final class WorldBackupThumbnailLoaderTest {
    /// Isolated generated archives, never user backups.
    @TempDir
    private Path directory;

    /// Root and exported single-world layouts, including Unicode, normalize the world's own image.
    @Test
    public void readsRootAndWrappedWorldIconsWithoutReadingInvalidNbt() throws Exception {
        for (String root : new String[] {"", "中文世界/"}) {
            byte[] png = png(0xff2367ab, 64, 64);
            Path path = zip(root.isEmpty() ? "root.zip" : "wrapped.zip", Map.of(
                    root + "level.dat", new byte[] {1, 2, 3}, root + "icon.png", png,
                    root + "region/r.0.0.mca", new byte[] {9, 8, 7}));
            byte[] original = Files.readAllBytes(path);
            BufferedImage icon = Objects.requireNonNull(WorldBackupThumbnailLoader.load(metadata(path)));
            assertEquals(40, icon.getWidth());
            assertEquals(40, icon.getHeight());
            assertEquals(0xff2367ab, icon.getRGB(20, 20));
            icon.flush();
            assertArrayEquals(original, Files.readAllBytes(path));
            Path moved = directory.resolve(path.getFileName() + ".moved");
            Files.move(path, moved);
            Files.delete(moved);
        }
    }

    /// Missing icons and ambiguous or unrelated folders do not cause recursive image searches.
    @Test
    public void rejectsMissingAmbiguousAndUnrelatedLayouts() throws Exception {
        byte[] png = png(0xff447744, 64, 64);
        assertNull(WorldBackupThumbnailLoader.load(metadata(zip("missing.zip", Map.of("world/level.dat", new byte[0])))));
        assertNull(WorldBackupThumbnailLoader.load(metadata(zip("unrelated.zip", Map.of(
                "level.dat", new byte[0], "images/icon.png", png)))));
        assertNull(WorldBackupThumbnailLoader.load(metadata(zip("ambiguous.zip", Map.of(
                "one/level.dat", new byte[0], "one/icon.png", png, "two/level.dat", new byte[0])))));
        assertNull(WorldBackupThumbnailLoader.load(metadata(zip("traversal.zip", Map.of(
                "world/level.dat", new byte[0], "world/icon.png", png, "world/../other.txt", new byte[0])))));
        assertNull(WorldBackupThumbnailLoader.load(metadata(zip("not-world.zip", Map.of("icon.png", png)))));
    }

    /// Special-level worlds remain supported and large valid images are subsampled before normalization.
    @Test
    public void supportsSpecialLevelAndSubsamplesLargerImages() throws Exception {
        Path path = zip("large-image.zip", Map.of("special_level.dat", new byte[0],
                "icon.png", png(0xffaabb33, 1024, 768)));
        BufferedImage image = Objects.requireNonNull(WorldBackupThumbnailLoader.load(metadata(path)));
        assertEquals(40, image.getWidth());
        assertEquals(40, image.getHeight());
        assertEquals(0xffaabb33, image.getRGB(20, 20));
        image.flush();
    }

    /// Declared entry bytes and image dimensions are rejected before unsafe pixel allocation.
    @Test
    public void boundsEncodedBytesDimensionsAndPixels() throws Exception {
        assertNull(WorldBackupThumbnailLoader.load(metadata(zip("oversized.zip", Map.of(
                "level.dat", new byte[0], "icon.png", new byte[WorldBackupThumbnailLoader.MAX_ICON_BYTES + 1])))));
        assertNull(WorldBackupThumbnailLoader.load(metadata(zip("wide.zip", Map.of(
                "level.dat", new byte[0], "icon.png", headerPng(8193, 1))))));
        assertNull(WorldBackupThumbnailLoader.load(metadata(zip("pixels.zip", Map.of(
                "level.dat", new byte[0], "icon.png", headerPng(8192, 4096))))));
        assertNull(WorldBackupThumbnailLoader.load(metadata(zip("not-png.zip", Map.of(
                "level.dat", new byte[0], "icon.png", "not a PNG".getBytes(StandardCharsets.UTF_8))))));
    }

    /// A duplicate icon name is ambiguous even when the ZIP API can return one of its entries.
    @Test
    public void rejectsDuplicateIconEntries() throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("level.dat", new byte[0]);
        entries.put("icon.png", png(0xffff0000, 64, 64));
        entries.put("icon.pn_", png(0xff0000ff, 64, 64));
        Path path = zip("duplicates.zip", entries);
        byte[] bytes = Files.readAllBytes(path);
        byte[] before = "icon.pn_".getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i <= bytes.length - before.length; i++) {
            boolean matches = true;
            for (int j = 0; j < before.length; j++) matches &= bytes[i + j] == before[j];
            if (matches) bytes[i + before.length - 1] = 'g';
        }
        Files.write(path, bytes);
        assertNull(WorldBackupThumbnailLoader.load(metadata(path)));
    }

    /// Changed metadata cannot publish an image under an old cache identity, and the EDT is rejected before I/O.
    @Test
    public void rejectsStaleMetadataAndEdtReads() throws Exception {
        Path path = zip("stale.zip", Map.of("level.dat", new byte[0], "icon.png", png(0xff123456, 64, 64)));
        WorldBackupArchive actual = metadata(path);
        assertNull(WorldBackupThumbnailLoader.load(new WorldBackupArchive(path, actual.fileName(),
                actual.sizeBytes() + 1, actual.lastModified())));
        assertNull(WorldBackupThumbnailLoader.load(new WorldBackupArchive(path, actual.fileName(),
                actual.sizeBytes(), Instant.EPOCH)));
        Files.delete(path);
        EdtDispatcher.executeAndWait(() -> assertThrows(IllegalStateException.class,
                () -> WorldBackupThumbnailLoader.load(actual)));
    }

    /// Corrupt ZIP data fails only the optional loader rather than creating an image from unrelated bytes.
    @Test
    public void reportsUnreadableZipAsOptionalIoFailure() throws Exception {
        Path path = Files.writeString(directory.resolve("broken.zip"), "not a ZIP archive");
        assertThrows(IOException.class, () -> WorldBackupThumbnailLoader.load(metadata(path)));
    }

    /// Writes one isolated ZIP fixture; byte arrays are owned only by the test and are not mutated.
    ///
    /// @param name generated archive filename
    /// @param entries read-only entry names and bytes
    /// @return created fixture archive
    /// @throws IOException when fixture creation fails
    private Path zip(String name, Map<String, byte[]> entries) throws IOException {
        Path path = directory.resolve(name);
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(path))) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                output.putNextEntry(new ZipEntry(entry.getKey()));
                output.write(entry.getValue());
                output.closeEntry();
            }
        }
        return path;
    }

    /// Captures the same lightweight identity used by the production index.
    ///
    /// @param path generated fixture ZIP
    /// @return immutable file metadata
    /// @throws IOException when fixture metadata cannot be read
    private static WorldBackupArchive metadata(Path path) throws IOException {
        return new WorldBackupArchive(path, path.getFileName().toString(), Files.size(path),
                Files.getLastModifiedTime(path).toInstant());
    }

    /// Encodes a constant-color PNG without interacting with desktop windows.
    ///
    /// @param color ARGB source color
    /// @param width source width
    /// @param height source height
    /// @return owned encoded PNG bytes
    /// @throws IOException when PNG encoding fails
    private static byte @Unmodifiable [] png(int color, int width, int height) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(new java.awt.Color(color, true));
            graphics.fillRect(0, 0, width, height);
        } finally {
            graphics.dispose();
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(image, "PNG", bytes);
        image.flush();
        return bytes.toByteArray();
    }

    /// Constructs a PNG header with valid chunk checksums without allocating its claimed raster.
    ///
    /// @param width declared source width
    /// @param height declared source height
    /// @return owned encoded header bytes
    /// @throws IOException when in-memory fixture encoding fails
    private static byte @Unmodifiable [] headerPng(int width, int height) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.write(new byte[] {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10});
            ByteArrayOutputStream header = new ByteArrayOutputStream();
            try (DataOutputStream data = new DataOutputStream(header)) {
                data.writeInt(width);
                data.writeInt(height);
                data.write(new byte[] {8, 6, 0, 0, 0});
            }
            chunk(output, "IHDR", header.toByteArray());
            chunk(output, "IEND", new byte[0]);
        }
        return bytes.toByteArray();
    }

    /// Writes a PNG chunk and its CRC in standard big-endian order.
    ///
    /// @param output in-memory encoded image
    /// @param type four-byte chunk type
    /// @param data read-only chunk payload
    /// @throws IOException when in-memory output fails
    private static void chunk(DataOutputStream output, String type, byte[] data) throws IOException {
        byte[] name = type.getBytes(StandardCharsets.US_ASCII);
        output.writeInt(data.length);
        output.write(name);
        output.write(data);
        CRC32 crc = new CRC32();
        crc.update(name);
        crc.update(data);
        output.writeInt((int) crc.getValue());
    }
}
