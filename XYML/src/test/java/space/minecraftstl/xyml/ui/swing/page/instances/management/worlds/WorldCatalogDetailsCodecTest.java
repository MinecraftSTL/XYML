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
package space.minecraftstl.xyml.ui.swing.page.instances.management.worlds;

import org.glavo.nbt.io.NBTCodec;
import org.glavo.nbt.tag.CompoundTag;
import org.glavo.nbt.tag.DoubleTag;
import org.glavo.nbt.tag.IntArrayTag;
import org.glavo.nbt.tag.ListTag;
import org.glavo.nbt.tag.TagType;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import space.minecraftstl.xyml.game.World;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies complete world-detail extraction, one-write mutation, and strict icon replacement.
@NotNullByDefault
final class WorldCatalogDetailsCodecTest {
    /// Temporary root containing complete directory-world fixtures.
    @TempDir
    private Path temporaryDirectory;

    /// Reads every supported legacy field and writes editable world and player values together.
    @Test
    void readsAndWritesCompleteLegacyDetails() throws IOException {
        Path directory = createWorld("details-world");
        WorldCatalogItem loaded = WorldCatalogItem.loaded(new World(directory));
        WorldCatalogDetails details = Objects.requireNonNull(loaded.details());

        assertEquals(directory.resolve("level.dat").toAbsolutePath().normalize(), details.levelDataPath());
        assertEquals(99887766L, details.seed());
        assertEquals("(11, 72, -4)", details.worldSpawn());
        assertEquals(48_000L, details.playedTimeTicks());
        assertEquals(Boolean.FALSE, details.settings().allowCheats());
        assertEquals(Boolean.TRUE, details.settings().generateStructures());
        assertEquals(WorldCatalogDetails.Difficulty.NORMAL, details.settings().difficulty());
        assertEquals(Boolean.FALSE, details.settings().difficultyLocked());
        assertNotNull(details.iconPngBase64());

        WorldCatalogDetails.PlayerSummary player = Objects.requireNonNull(details.player());
        assertTrue(Objects.requireNonNull(player.location()).endsWith("(1.25, 65.00, -8.50)"));
        assertTrue(Objects.requireNonNull(player.lastDeathLocation()).endsWith("(2, 50, 3)"));
        assertEquals("(4, 70, 5)", player.spawn());
        assertEquals(WorldCatalogDetails.GameMode.CREATIVE, player.gameMode());
        assertEquals(18.5F, player.health());
        assertEquals(17, player.foodLevel());
        assertEquals(3.5F, player.foodSaturation());
        assertEquals(12, player.xpLevel());

        WorldDetailsUpdate update = new WorldDetailsUpdate(
                "Updated Name",
                new WorldCatalogDetails.WorldSettings(
                        true,
                        false,
                        WorldCatalogDetails.Difficulty.HARD,
                        true),
                new WorldDetailsUpdate.PlayerUpdate(
                        WorldCatalogDetails.GameMode.HARDCORE,
                        9.5F,
                        8,
                        1.25F,
                        27));
        WorldCatalogDetailsCodec.apply(new World(directory), update);

        World updatedWorld = new World(directory);
        WorldCatalogDetails updated = WorldCatalogDetailsCodec.read(updatedWorld);
        assertEquals("Updated Name", updatedWorld.getWorldName());
        assertEquals(Boolean.TRUE, updated.settings().allowCheats());
        assertEquals(Boolean.FALSE, updated.settings().generateStructures());
        assertEquals(WorldCatalogDetails.Difficulty.HARD, updated.settings().difficulty());
        assertEquals(Boolean.TRUE, updated.settings().difficultyLocked());
        WorldCatalogDetails.PlayerSummary updatedPlayer = Objects.requireNonNull(updated.player());
        assertEquals(WorldCatalogDetails.GameMode.HARDCORE, updatedPlayer.gameMode());
        assertEquals(9.5F, updatedPlayer.health());
        assertEquals(8, updatedPlayer.foodLevel());
        assertEquals(1.25F, updatedPlayer.foodSaturation());
        assertEquals(27, updatedPlayer.xpLevel());
    }

    /// Accepts only real 64-by-64 PNG data and removes the custom icon on reset.
    @Test
    void replacesOnlyExactPngIcons() throws IOException {
        Path directory = createWorld("icon-world");
        Path wrongSize = temporaryDirectory.resolve("wrong-size.png");
        assertTrue(ImageIO.write(
                new BufferedImage(32, 64, BufferedImage.TYPE_INT_ARGB),
                "PNG",
                wrongSize.toFile()));
        assertThrows(
                IOException.class,
                () -> WorldCatalogDetailsCodec.replaceIcon(new World(directory), wrongSize));

        Path disguisedJpeg = temporaryDirectory.resolve("disguised.png");
        assertTrue(ImageIO.write(
                new BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB),
                "JPEG",
                disguisedJpeg.toFile()));
        assertThrows(
                IOException.class,
                () -> WorldCatalogDetailsCodec.replaceIcon(new World(directory), disguisedJpeg));

        Path replacement = temporaryDirectory.resolve("replacement.png");
        BufferedImage expected = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        expected.setRGB(7, 9, 0xFF12AB34);
        assertTrue(ImageIO.write(expected, "PNG", replacement.toFile()));
        WorldCatalogDetailsCodec.replaceIcon(new World(directory), replacement);

        BufferedImage actual = Objects.requireNonNull(ImageIO.read(directory.resolve("icon.png").toFile()));
        assertEquals(64, actual.getWidth());
        assertEquals(64, actual.getHeight());
        assertEquals(0xFF12AB34, actual.getRGB(7, 9));
        assertNotNull(WorldCatalogDetailsCodec.read(new World(directory)).iconPngBase64());

        WorldCatalogDetailsCodec.resetIcon(new World(directory));
        assertFalse(Files.exists(directory.resolve("icon.png")));
        assertNull(WorldCatalogDetailsCodec.read(new World(directory)).iconPngBase64());
    }

    /// Creates one complete legacy-layout directory world and 64-by-64 icon.
    ///
    /// @param directoryName fixture directory name
    /// @return created world directory
    private Path createWorld(String directoryName) throws IOException {
        Path directory = Files.createDirectory(temporaryDirectory.resolve(directoryName));
        CompoundTag player = new CompoundTag()
                .addString("Dimension", "minecraft:the_nether")
                .addTag("Pos", playerPosition())
                .addTag("LastDeathLocation", new CompoundTag()
                        .addString("dimension", "minecraft:the_end")
                        .addTag("pos", new IntArrayTag(new int[]{2, 50, 3})))
                .addInt("SpawnX", 4)
                .addInt("SpawnY", 70)
                .addInt("SpawnZ", 5)
                .addString("SpawnDimension", "minecraft:overworld")
                .addInt("playerGameType", 1)
                .addFloat("Health", 18.5F)
                .addInt("foodLevel", 17)
                .addFloat("foodSaturationLevel", 3.5F)
                .addInt("XpLevel", 12);
        CompoundTag data = new CompoundTag()
                .addString("LevelName", directoryName)
                .addLong("LastPlayed", 1L)
                .addLong("RandomSeed", 99887766L)
                .addInt("SpawnX", 11)
                .addInt("SpawnY", 72)
                .addInt("SpawnZ", -4)
                .addLong("Time", 48_000L)
                .addByte("allowCommands", (byte) 0)
                .addByte("MapFeatures", (byte) 1)
                .addByte("Difficulty", (byte) 2)
                .addByte("DifficultyLocked", (byte) 0)
                .addByte("hardcore", (byte) 0)
                .addTag("Player", player);
        writeLevelData(directory.resolve("level.dat"), new CompoundTag().addTag("Data", data));
        BufferedImage icon = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        icon.setRGB(1, 1, 0xFF335577);
        assertTrue(ImageIO.write(icon, "PNG", directory.resolve("icon.png").toFile()));
        return directory.toAbsolutePath().normalize();
    }

    /// Creates a three-double current-player position list.
    ///
    /// @return mutable NBT list used only by fixture creation
    private static ListTag<DoubleTag> playerPosition() {
        ListTag<DoubleTag> position = new ListTag<>(TagType.DOUBLE);
        position.addTag(new DoubleTag(1.25D));
        position.addTag(new DoubleTag(65.0D));
        position.addTag(new DoubleTag(-8.5D));
        return position;
    }

    /// Writes one compressed level-data root.
    ///
    /// @param destination target level-data path
    /// @param root complete root tag
    private static void writeLevelData(Path destination, CompoundTag root) throws IOException {
        try (OutputStream output = new GZIPOutputStream(Files.newOutputStream(destination))) {
            NBTCodec.of().writeTag(output, root);
        }
    }
}
