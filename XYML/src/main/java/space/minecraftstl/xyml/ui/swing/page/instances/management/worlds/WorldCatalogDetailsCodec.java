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

import space.minecraftstl.xyml.library.nbt.tag.ByteTag;
import space.minecraftstl.xyml.library.nbt.tag.CompoundTag;
import space.minecraftstl.xyml.library.nbt.tag.DoubleTag;
import space.minecraftstl.xyml.library.nbt.tag.FloatTag;
import space.minecraftstl.xyml.library.nbt.tag.IntArrayTag;
import space.minecraftstl.xyml.library.nbt.tag.IntTag;
import space.minecraftstl.xyml.library.nbt.tag.ListTag;
import space.minecraftstl.xyml.library.nbt.tag.LongTag;
import space.minecraftstl.xyml.library.nbt.tag.StringTag;
import space.minecraftstl.xyml.library.nbt.tag.Tag;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import space.minecraftstl.xyml.game.World;
import space.minecraftstl.xyml.game.WorldLockedException;
import space.minecraftstl.xyml.util.io.FileUtils;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Iterator;
import java.util.Locale;
import java.util.Objects;

import static space.minecraftstl.xyml.util.i18n.I18n.i18n;

/// Converts Core world NBT into immutable Swing snapshots and applies validated form mutations.
///
/// Every method in this class performs blocking work and is called only by the catalog's background
/// access layer. Snapshot extraction never touches worlds outside the requested viewport range.
@NotNullByDefault
final class WorldCatalogDetailsCodec {
    /// Minecraft's required world-icon edge length.
    private static final int ICON_SIZE = 64;

    /// Sentinel label for an Overworld position that needs no dimension prefix.
    private static final String OVERWORLD_LABEL = "";

    /// Prevents construction of this blocking utility class.
    private WorldCatalogDetailsCodec() {
    }

    /// Reads all legacy world-information fields from one already loaded Core world.
    ///
    /// @param world loaded readable world
    /// @return immutable detail snapshot
    /// @throws IOException when a decoded icon cannot be encoded for the UI boundary
    static WorldCatalogDetails read(World world) throws IOException {
        World loadedWorld = Objects.requireNonNull(world, "world");
        CompoundTag data = requireData(loadedWorld);
        return new WorldCatalogDetails(
                loadedWorld.getLevelDataPath(),
                encodeIcon(loadedWorld.getIcon()),
                loadedWorld.getSeed(),
                worldSpawn(data),
                data.get("Time") instanceof LongTag timeTag ? timeTag.getValue() : null,
                readWorldSettings(loadedWorld, data),
                readPlayer(loadedWorld, data));
    }

    /// Applies every supported submitted value and writes world, generator, and player data once.
    ///
    /// @param world freshly reopened unlocked world
    /// @param update validated detail update
    /// @throws IOException when a requested field disappeared or persistence fails
    static void apply(World world, WorldDetailsUpdate update) throws IOException {
        World loadedWorld = Objects.requireNonNull(world, "world");
        WorldDetailsUpdate requested = Objects.requireNonNull(update, "update");
        rejectLocked(loadedWorld);
        CompoundTag data = requireData(loadedWorld);
        if (!(data.get("LevelName") instanceof StringTag worldNameTag)) {
            throw new IOException("World name is unavailable");
        }
        worldNameTag.setValue(requested.worldName());
        applyWorldSettings(loadedWorld, data, requested.settings());
        if (requested.player() != null) {
            applyPlayer(data, loadedWorld.getPlayerData(), Objects.requireNonNull(requested.player()));
        }
        loadedWorld.writeWorldData();
    }

    /// Replaces `icon.png` only after validating its real format and exact dimensions.
    ///
    /// @param world freshly reopened unlocked world
    /// @param source selected local image
    /// @throws IOException when the source is not a readable 64-by-64 PNG or cannot be copied
    static void replaceIcon(World world, Path source) throws IOException {
        World loadedWorld = Objects.requireNonNull(world, "world");
        Path selectedSource = Objects.requireNonNull(source, "source").toAbsolutePath().normalize();
        rejectLocked(loadedWorld);
        validateIconSource(selectedSource);
        Path iconPath = loadedWorld.getFile().resolve("icon.png").toAbsolutePath().normalize();
        FileUtils.saveSafely(iconPath, output -> Files.copy(selectedSource, output));
    }

    /// Removes one unlocked world's custom `icon.png` when present.
    ///
    /// @param world freshly reopened unlocked world
    /// @throws IOException when lock probing or deletion fails
    static void resetIcon(World world) throws IOException {
        World loadedWorld = Objects.requireNonNull(world, "world");
        rejectLocked(loadedWorld);
        Files.deleteIfExists(loadedWorld.getFile().resolve("icon.png"));
    }

    /// Encodes one normalized Core icon without exposing its mutable image pixels.
    ///
    /// @param icon decoded icon, or `null`
    /// @return immutable Base64 PNG, or `null`
    /// @throws IOException when ImageIO cannot encode the image
    private static @Nullable String encodeIcon(@Nullable BufferedImage icon) throws IOException {
        if (icon == null) {
            return null;
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        if (!ImageIO.write(icon, "PNG", output)) {
            throw new IOException("PNG encoder is unavailable");
        }
        return Base64.getEncoder().encodeToString(output.toByteArray());
    }

    /// Reads optional world-setting values across supported legacy and modern layouts.
    ///
    /// @param world loaded world owning normalized generator settings
    /// @param data mutable level `Data` tag
    /// @return immutable per-field availability snapshot
    private static WorldCatalogDetails.WorldSettings readWorldSettings(World world, CompoundTag data) {
        return new WorldCatalogDetails.WorldSettings(
                readBoolean(data.get("allowCommands")),
                readGenerateStructures(world, data),
                readDifficulty(data),
                readDifficultyLocked(data));
    }

    /// Reads one optional single-player summary.
    ///
    /// @param world loaded world owning optional player data
    /// @param data mutable level `Data` tag
    /// @return immutable player snapshot, or `null` when no player data exists
    private static @Nullable WorldCatalogDetails.PlayerSummary readPlayer(World world, CompoundTag data) {
        @Nullable CompoundTag player = world.getPlayerData();
        if (player == null) {
            return null;
        }
        return new WorldCatalogDetails.PlayerSummary(
                playerLocation(player),
                playerLastDeathLocation(player),
                playerSpawn(player),
                readGameMode(data, player),
                player.get("Health") instanceof FloatTag healthTag ? healthTag.getValue() : null,
                player.get("foodLevel") instanceof IntTag foodTag ? foodTag.getValue() : null,
                player.get("foodSaturationLevel") instanceof FloatTag saturationTag
                        ? saturationTag.getValue()
                        : null,
                player.get("XpLevel") instanceof IntTag xpTag ? xpTag.getValue() : null);
    }

    /// Reads world structure generation across legacy and modern layouts.
    ///
    /// @param world loaded world
    /// @param data level `Data` tag
    /// @return value or `null` when unsupported
    private static @Nullable Boolean readGenerateStructures(World world, CompoundTag data) {
        if (data.get("MapFeatures") instanceof ByteTag mapFeaturesTag) {
            return readBoolean(mapFeaturesTag);
        }
        @Nullable CompoundTag generator = world.getNormalizedWorldGenSettingsData();
        if (generator == null) {
            return null;
        }
        Tag structures = generator.get("generate_features");
        if (structures == null) {
            structures = generator.get("generate_structures");
        }
        return readBoolean(structures);
    }

    /// Reads difficulty across numeric and string NBT layouts.
    ///
    /// @param data level `Data` tag
    /// @return difficulty or `null` when unsupported
    private static @Nullable WorldCatalogDetails.Difficulty readDifficulty(CompoundTag data) {
        if (data.get("Difficulty") instanceof ByteTag difficultyTag) {
            int value = difficultyTag.getValue();
            WorldCatalogDetails.Difficulty[] values = WorldCatalogDetails.Difficulty.values();
            return value >= 0 && value < values.length ? values[value] : null;
        }
        if (data.get("difficulty_settings") instanceof CompoundTag settings
                && settings.get("difficulty") instanceof StringTag difficultyTag) {
            try {
                return WorldCatalogDetails.Difficulty.valueOf(
                        difficultyTag.getValue().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                return null;
            }
        }
        return null;
    }

    /// Reads the difficulty lock across supported layouts.
    ///
    /// @param data level `Data` tag
    /// @return lock value or `null` when unsupported
    private static @Nullable Boolean readDifficultyLocked(CompoundTag data) {
        if (data.get("DifficultyLocked") instanceof ByteTag lockedTag) {
            return readBoolean(lockedTag);
        }
        if (data.get("difficulty_settings") instanceof CompoundTag settings) {
            return readBoolean(settings.get("locked"));
        }
        return null;
    }

    /// Reads one strict NBT byte boolean.
    ///
    /// @param tag candidate tag
    /// @return boolean value or `null` for missing and malformed values
    private static @Nullable Boolean readBoolean(@Nullable Tag tag) {
        if (!(tag instanceof ByteTag byteTag)) {
            return null;
        }
        return switch (byteTag.getValue()) {
            case 0 -> false;
            case 1 -> true;
            default -> null;
        };
    }

    /// Formats the world spawn across modern compound and legacy coordinate layouts.
    ///
    /// @param data level `Data` tag
    /// @return formatted spawn or `null`
    private static @Nullable String worldSpawn(CompoundTag data) {
        if (data.get("spawn") instanceof CompoundTag spawn
                && spawn.get("pos") instanceof IntArrayTag position) {
            String dimension = spawn.get("dimension") == null
                    ? OVERWORLD_LABEL
                    : dimensionLabel(spawn.get("dimension"));
            return dimension == null ? null : formatPosition(dimension, position);
        }
        if (data.get("SpawnX") instanceof IntTag x
                && data.get("SpawnY") instanceof IntTag y
                && data.get("SpawnZ") instanceof IntTag z) {
            return formatPosition(OVERWORLD_LABEL, x.getValue(), y.getValue(), z.getValue());
        }
        return null;
    }

    /// Formats current player position when dimension and three doubles are available.
    ///
    /// @param player player data
    /// @return formatted position or `null`
    private static @Nullable String playerLocation(CompoundTag player) {
        @Nullable String dimension = dimensionLabel(player.get("Dimension"));
        return dimension == null ? null : formatPosition(dimension, player.get("Pos"));
    }

    /// Formats the last recorded player death position.
    ///
    /// @param player player data
    /// @return formatted position or `null`
    private static @Nullable String playerLastDeathLocation(CompoundTag player) {
        if (!(player.get("LastDeathLocation") instanceof CompoundTag death)) {
            return null;
        }
        @Nullable String dimension = dimensionLabel(death.get("dimension"));
        return dimension == null ? null : formatPosition(dimension, death.get("pos"));
    }

    /// Formats the bed or respawn-anchor position across supported layouts.
    ///
    /// @param player player data
    /// @return formatted position or `null`
    private static @Nullable String playerSpawn(CompoundTag player) {
        if (player.get("respawn") instanceof CompoundTag respawn
                && respawn.get("dimension") != null
                && respawn.get("pos") instanceof IntArrayTag position) {
            @Nullable String dimension = dimensionLabel(respawn.get("dimension"));
            return dimension == null ? null : formatPosition(dimension, position);
        }
        if (player.get("SpawnX") instanceof IntTag x
                && player.get("SpawnY") instanceof IntTag y
                && player.get("SpawnZ") instanceof IntTag z) {
            String dimension = player.get("SpawnDimension") == null
                    ? OVERWORLD_LABEL
                    : dimensionLabel(player.get("SpawnDimension"));
            return dimension == null ? null : formatPosition(dimension, x.getValue(), y.getValue(), z.getValue());
        }
        return null;
    }

    /// Reads player game mode together with the world hardcore flag.
    ///
    /// @param data level `Data` tag
    /// @param player player data
    /// @return mode or `null` when either required tag is unavailable
    private static @Nullable WorldCatalogDetails.GameMode readGameMode(
            CompoundTag data,
            CompoundTag player) {
        @Nullable ByteTag hardcoreTag = hardcoreTag(data);
        if (!(player.get("playerGameType") instanceof IntTag gameTypeTag) || hardcoreTag == null) {
            return null;
        }
        int value = gameTypeTag.getValue();
        if (value == 0 && hardcoreTag.getValue() == 1) {
            return WorldCatalogDetails.GameMode.HARDCORE;
        }
        WorldCatalogDetails.GameMode[] values = WorldCatalogDetails.GameMode.values();
        return value >= 0 && value < WorldCatalogDetails.GameMode.HARDCORE.ordinal()
                ? values[value]
                : null;
    }

    /// Locates the legacy or modern hardcore byte tag.
    ///
    /// @param data level `Data` tag
    /// @return mutable hardcore tag or `null`
    private static @Nullable ByteTag hardcoreTag(CompoundTag data) {
        if (data.get("hardcore") instanceof ByteTag hardcoreTag) {
            return hardcoreTag;
        }
        if (data.get("difficulty_settings") instanceof CompoundTag settings
                && settings.get("hardcore") instanceof ByteTag hardcoreTag) {
            return hardcoreTag;
        }
        return null;
    }

    /// Applies all supported world settings to their existing tags.
    ///
    /// @param world loaded mutable world
    /// @param data level `Data` tag
    /// @param settings requested settings
    /// @throws IOException when a requested field is no longer available
    private static void applyWorldSettings(
            World world,
            CompoundTag data,
            WorldCatalogDetails.WorldSettings settings) throws IOException {
        if (settings.allowCheats() != null) {
            setBoolean(data.get("allowCommands"), settings.allowCheats(), "allowCommands");
        }
        if (settings.generateStructures() != null) {
            Tag structures = data.get("MapFeatures");
            if (structures == null && world.getNormalizedWorldGenSettingsData() != null) {
                CompoundTag generator = Objects.requireNonNull(world.getNormalizedWorldGenSettingsData());
                structures = generator.get("generate_features");
                if (structures == null) {
                    structures = generator.get("generate_structures");
                }
            }
            setBoolean(structures, settings.generateStructures(), "generate structures");
        }
        if (settings.difficulty() != null) {
            setDifficulty(data, settings.difficulty());
        }
        if (settings.difficultyLocked() != null) {
            Tag locked = data.get("DifficultyLocked");
            if (locked == null && data.get("difficulty_settings") instanceof CompoundTag difficultySettings) {
                locked = difficultySettings.get("locked");
            }
            setBoolean(locked, settings.difficultyLocked(), "difficulty lock");
        }
    }

    /// Applies difficulty to its existing legacy or modern tag.
    ///
    /// @param data level `Data` tag
    /// @param difficulty requested difficulty
    /// @throws IOException when the difficulty layout disappeared
    private static void setDifficulty(
            CompoundTag data,
            WorldCatalogDetails.Difficulty difficulty) throws IOException {
        if (data.get("Difficulty") instanceof ByteTag difficultyTag) {
            difficultyTag.setValue((byte) difficulty.ordinal());
            return;
        }
        if (data.get("difficulty_settings") instanceof CompoundTag settings
                && settings.get("difficulty") instanceof StringTag difficultyTag) {
            difficultyTag.setValue(difficulty.tagValue());
            return;
        }
        throw new IOException("World difficulty is unavailable");
    }

    /// Applies optional player values to their exact existing numeric tags.
    ///
    /// @param data level `Data` tag owning hardcore state
    /// @param player mutable player data, or `null`
    /// @param update requested player values
    /// @throws IOException when a requested tag disappeared
    private static void applyPlayer(
            CompoundTag data,
            @Nullable CompoundTag player,
            WorldDetailsUpdate.PlayerUpdate update) throws IOException {
        if (player == null) {
            throw new IOException("Player data is unavailable");
        }
        if (update.gameMode() != null) {
            setGameMode(data, player, update.gameMode());
        }
        if (update.health() != null) {
            requireFloatTag(player.get("Health"), "player health").setValue(update.health());
        }
        if (update.foodLevel() != null) {
            requireIntTag(player.get("foodLevel"), "player food level").setValue(update.foodLevel());
        }
        if (update.foodSaturation() != null) {
            requireFloatTag(player.get("foodSaturationLevel"), "player food saturation")
                    .setValue(update.foodSaturation());
        }
        if (update.xpLevel() != null) {
            requireIntTag(player.get("XpLevel"), "player experience level").setValue(update.xpLevel());
        }
    }

    /// Applies game mode and the coupled hardcore byte together.
    ///
    /// @param data level `Data` tag
    /// @param player player data
    /// @param gameMode requested mode
    /// @throws IOException when required tags disappeared
    private static void setGameMode(
            CompoundTag data,
            CompoundTag player,
            WorldCatalogDetails.GameMode gameMode) throws IOException {
        IntTag gameTypeTag = requireIntTag(player.get("playerGameType"), "player game mode");
        @Nullable ByteTag hardcoreTag = hardcoreTag(data);
        if (hardcoreTag == null) {
            throw new IOException("World hardcore state is unavailable");
        }
        if (gameMode == WorldCatalogDetails.GameMode.HARDCORE) {
            gameTypeTag.setValue(0);
            hardcoreTag.setValue((byte) 1);
        } else {
            gameTypeTag.setValue(gameMode.ordinal());
            hardcoreTag.setValue((byte) 0);
        }
    }

    /// Writes one existing strict byte boolean.
    ///
    /// @param tag candidate mutable tag
    /// @param value requested boolean
    /// @param name field name for diagnostics
    /// @throws IOException when the tag is unavailable or malformed
    private static void setBoolean(@Nullable Tag tag, boolean value, String name) throws IOException {
        if (!(tag instanceof ByteTag byteTag) || readBoolean(byteTag) == null) {
            throw new IOException(name + " is unavailable");
        }
        byteTag.setValue((byte) (value ? 1 : 0));
    }

    /// Returns one required mutable integer tag.
    ///
    /// @param tag candidate tag
    /// @param name field name for diagnostics
    /// @return mutable integer tag
    /// @throws IOException when unavailable
    private static IntTag requireIntTag(@Nullable Tag tag, String name) throws IOException {
        if (tag instanceof IntTag intTag) {
            return intTag;
        }
        throw new IOException(name + " is unavailable");
    }

    /// Returns one required mutable float tag.
    ///
    /// @param tag candidate tag
    /// @param name field name for diagnostics
    /// @return mutable float tag
    /// @throws IOException when unavailable
    private static FloatTag requireFloatTag(@Nullable Tag tag, String name) throws IOException {
        if (tag instanceof FloatTag floatTag) {
            return floatTag;
        }
        throw new IOException(name + " is unavailable");
    }

    /// Returns the required level `Data` compound.
    ///
    /// @param world loaded world
    /// @return mutable `Data` tag
    /// @throws IOException when malformed
    private static CompoundTag requireData(World world) throws IOException {
        if (world.getLevelData().get("Data") instanceof CompoundTag data) {
            return data;
        }
        throw new IOException("level.dat missing Data");
    }

    /// Validates the selected image's real format and dimensions without decoding all pixels.
    ///
    /// @param source selected local source
    /// @throws IOException when it is not an exact 64-by-64 PNG
    private static void validateIconSource(Path source) throws IOException {
        if (!Files.isRegularFile(source)) {
            throw new IOException(i18n("world.icon.change.fail.load.text"));
        }
        try (@Nullable ImageInputStream input = ImageIO.createImageInputStream(source.toFile())) {
            if (input == null) {
                throw new IOException(i18n("world.icon.change.fail.load.text"));
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                throw new IOException(i18n("world.icon.change.fail.load.text"));
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (!"PNG".equalsIgnoreCase(reader.getFormatName())) {
                    throw new IOException(i18n("world.icon.change.fail.load.text"));
                }
                if (width != ICON_SIZE || height != ICON_SIZE) {
                    throw new IOException(i18n("world.icon.change.fail.not_64x64.text", width, height));
                }
            } finally {
                reader.dispose();
            }
        }
    }

    /// Rejects metadata and icon writes while Minecraft owns the session lock.
    ///
    /// @param world freshly reopened world
    /// @throws WorldLockedException when locked
    private static void rejectLocked(World world) throws WorldLockedException {
        if (world.isLocked()) {
            throw new WorldLockedException("The world " + world.getFile() + " has been locked");
        }
    }

    /// Maps legacy numeric and modern string dimension tags to a display prefix.
    ///
    /// @param tag dimension tag
    /// @return empty Overworld prefix, localized or custom dimension name, or `null`
    private static @Nullable String dimensionLabel(@Nullable Tag tag) {
        if (tag instanceof IntTag dimensionTag) {
            return switch (dimensionTag.getValue()) {
                case 0 -> OVERWORLD_LABEL;
                case -1 -> i18n("world.info.dimension.the_nether");
                case 1 -> i18n("world.info.dimension.the_end");
                default -> null;
            };
        }
        if (tag instanceof StringTag dimensionTag) {
            return switch (dimensionTag.getValue()) {
                case "overworld", "minecraft:overworld" -> OVERWORLD_LABEL;
                case "the_nether", "minecraft:the_nether" -> i18n("world.info.dimension.the_nether");
                case "the_end", "minecraft:the_end" -> i18n("world.info.dimension.the_end");
                default -> dimensionTag.getValue();
            };
        }
        return null;
    }

    /// Formats an integer-array position with at least three coordinates.
    ///
    /// @param dimension display dimension prefix
    /// @param position coordinate array
    /// @return formatted position or `null` when malformed
    private static @Nullable String formatPosition(String dimension, IntArrayTag position) {
        if (position.size() < 3) {
            return null;
        }
        return formatPosition(
                dimension,
                position.get(0),
                position.get(1),
                position.get(2));
    }

    /// Formats a three-coordinate integer array or double list position.
    ///
    /// @param dimension display dimension prefix
    /// @param position candidate list tag
    /// @return formatted position or `null` when malformed
    private static @Nullable String formatPosition(String dimension, @Nullable Tag position) {
        if (position instanceof IntArrayTag integerPosition) {
            return formatPosition(dimension, integerPosition);
        }
        if (!(position instanceof ListTag<?> values)
                || values.size() != 3
                || !(values.getTag(0) instanceof DoubleTag x)
                || !(values.getTag(1) instanceof DoubleTag y)
                || !(values.getTag(2) instanceof DoubleTag z)) {
            return null;
        }
        String coordinates = "(%.2f, %.2f, %.2f)".formatted(x.getValue(), y.getValue(), z.getValue());
        return dimension.isEmpty() ? coordinates : dimension + " " + coordinates;
    }

    /// Formats integer coordinates with an optional dimension prefix.
    ///
    /// @param dimension display dimension prefix
    /// @param x x coordinate
    /// @param y y coordinate
    /// @param z z coordinate
    /// @return formatted position
    private static String formatPosition(String dimension, int x, int y, int z) {
        String coordinates = "(%d, %d, %d)".formatted(x, y, z);
        return dimension.isEmpty() ? coordinates : dimension + " " + coordinates;
    }
}
