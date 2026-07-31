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
package space.minecraftstl.xyml.game;

import org.glavo.nbt.io.NBTCodec;
import org.glavo.nbt.tag.CompoundTag;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies toolkit-neutral world-icon decoding, normalization, and failure isolation.
@NotNullByDefault
final class WorldIconTest {
    /// Temporary root containing complete world fixtures.
    @TempDir
    private Path temporaryDirectory;

    /// Arbitrary aspect ratios are centered in a 64-by-64 canvas with nearest-neighbor pixels.
    @Test
    void normalizesWorldIconWithoutSmoothing() throws IOException {
        Path worldDirectory = createWorldDirectory("scaled-world");
        BufferedImage source = new BufferedImage(2, 1, BufferedImage.TYPE_INT_ARGB);
        source.setRGB(0, 0, 0xFFFF0000);
        source.setRGB(1, 0, 0xFF0000FF);
        assertTrue(ImageIO.write(source, "PNG", worldDirectory.resolve("icon.png").toFile()));

        World world = new World(worldDirectory);
        BufferedImage icon = Objects.requireNonNull(world.getIcon());

        assertEquals(64, icon.getWidth());
        assertEquals(64, icon.getHeight());
        assertEquals(0x00000000, icon.getRGB(10, 15));
        assertEquals(0xFFFF0000, icon.getRGB(0, 16));
        assertEquals(0xFFFF0000, icon.getRGB(31, 47));
        assertEquals(0xFF0000FF, icon.getRGB(32, 16));
        assertEquals(0xFF0000FF, icon.getRGB(63, 47));
        assertEquals(0x00000000, icon.getRGB(50, 48));
    }

    /// An empty icon does not prevent loading an otherwise valid world and yields no icon.
    @Test
    void ignoresEmptyWorldIcon() throws IOException {
        Path worldDirectory = createWorldDirectory("empty-icon-world");
        Files.createFile(worldDirectory.resolve("icon.png"));

        World world = new World(worldDirectory);

        @Nullable BufferedImage icon = world.getIcon();
        assertNull(icon);
    }

    /// Archive-backed worlds use the same decoder before their temporary ZIP file system closes.
    @Test
    void loadsWorldIconFromArchive() throws IOException {
        Path sourceDirectory = createWorldDirectory("archive-source");
        BufferedImage source = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        source.setRGB(12, 34, 0xFF12AB34);
        assertTrue(ImageIO.write(source, "PNG", sourceDirectory.resolve("icon.png").toFile()));
        Path archive = temporaryDirectory.resolve("archive-world.zip");
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(archive))) {
            writeArchiveEntry(output, "level.dat", sourceDirectory.resolve("level.dat"));
            writeArchiveEntry(output, "icon.png", sourceDirectory.resolve("icon.png"));
        }

        World world = new World(archive);
        BufferedImage icon = Objects.requireNonNull(world.getIcon());

        assertEquals(64, icon.getWidth());
        assertEquals(64, icon.getHeight());
        assertEquals(0xFF12AB34, icon.getRGB(12, 34));
    }

    /// Creates a directory with the minimum valid level-data fields.
    ///
    /// @param name fixture directory and stored world name
    /// @return complete fixture directory
    private Path createWorldDirectory(String name) throws IOException {
        Path directory = Files.createDirectory(temporaryDirectory.resolve(name));
        CompoundTag data = new CompoundTag()
                .addString("LevelName", name)
                .addLong("LastPlayed", 1L);
        CompoundTag root = new CompoundTag().addTag("Data", data);
        try (OutputStream output = new GZIPOutputStream(Files.newOutputStream(directory.resolve("level.dat")))) {
            NBTCodec.of().writeTag(output, root);
        }
        return directory;
    }

    /// Copies one fixture file into an open world archive.
    ///
    /// @param output open ZIP output
    /// @param name archive entry name
    /// @param source fixture file
    private static void writeArchiveEntry(ZipOutputStream output, String name, Path source) throws IOException {
        output.putNextEntry(new ZipEntry(name));
        Files.copy(source, output);
        output.closeEntry();
    }
}
