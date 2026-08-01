/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2020  huangyuhui <huanghongxun2008@126.com> and contributors
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
import org.glavo.nbt.tag.*;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import space.minecraftstl.xyml.util.io.*;
import space.minecraftstl.xyml.util.versioning.GameVersionNumber;

import javax.imageio.ImageIO;
import java.awt.AlphaComposite;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.GZIPOutputStream;

import static space.minecraftstl.xyml.util.logging.Logger.LOG;

/// Represents a Minecraft world directory or importable archive and its editable NBT metadata.
///
/// Directory instances can mutate, copy, export, lock, and delete world data. Archive instances
/// are read-only sources until installed. World icons are exposed as toolkit-neutral 64-by-64
/// buffered images so Core does not depend on a desktop UI framework.
@NotNullByDefault
public final class World {
    /// Fixed edge length used for normalized world icons.
    private static final int ICON_SIZE = 64;

    /// Original world directory or archive path.
    private final Path file;

    /// Display and installation file name derived from the source layout.
    private String fileName;

    /// Normalized world icon, or `null` when absent or unreadable.
    private @Nullable BufferedImage icon;

    /// Complete root level-data tag.
    private CompoundTag levelData;

    /// Mutable `Data` child within `levelData`.
    private CompoundTag dataTag;

    /// Path from which `levelData` was loaded and to which it is written.
    private Path levelDataPath;

    /// Optional backing tag written to a separate world-generation settings file.
    private @Nullable CompoundTag worldGenSettingsDataBackingTag;

    /// Optional normalized world-generation settings used for reading and modification.
    private @Nullable CompoundTag normalizedWorldGenSettingsData;

    /// Optional separate world-generation settings file path.
    private @Nullable Path worldGenSettingsDataPath;

    /// Optional player data used for both modification and write-back.
    private @Nullable CompoundTag playerData;

    /// Optional separate player-data file path.
    private @Nullable Path playerDataPath;

    /// Loads and validates a world directory or archive.
    ///
    /// Icon decoding failures are logged and do not invalidate an otherwise valid world.
    ///
    /// @param file world directory or archive
    /// @throws IOException if the source is not a valid readable world
    public World(Path file) throws IOException {
        this.file = file;

        if (Files.isDirectory(file)) {
            fileName = FileUtils.getName(this.file);
            Path levelDatPath = this.file.resolve("level.dat");
            if (!Files.exists(levelDatPath)) { // version 20w14infinite
                levelDatPath = this.file.resolve("special_level.dat");
            }
            if (!Files.exists(levelDatPath)) {
                throw new IOException("Not a valid world directory since level.dat or special_level.dat cannot be found.");
            }
            this.levelDataPath = levelDatPath;
            loadAndCheckWorldData();

            Path iconFile = this.file.resolve("icon.png");
            if (Files.isRegularFile(iconFile)) {
                icon = loadIcon(iconFile);
            }
        } else if (Files.isRegularFile(file))
            try (FileSystem fs = CompressingUtils.readonly(this.file).setAutoDetectEncoding(true).build()) {
                Path root;
                if (Files.isRegularFile(fs.getPath("/level.dat"))) {
                    root = fs.getPath("/");
                    fileName = FileUtils.getName(this.file);
                } else {
                    try (Stream<Path> filesStream = Files.list(fs.getPath("/"))) {
                        @Unmodifiable List<Path> files = filesStream.toList();
                        if (files.size() != 1 || !Files.isDirectory(files.get(0))) {
                            throw new IOException("Not a valid world zip file");
                        }

                        root = files.get(0);
                        fileName = FileUtils.getName(root);
                    }
                }

                Path levelDat = root.resolve("level.dat");
                if (!Files.exists(levelDat)) { //version 20w14infinite
                    levelDat = root.resolve("special_level.dat");
                }
                if (!Files.exists(levelDat)) {
                    throw new IOException("Not a valid world zip file since level.dat or special_level.dat cannot be found.");
                }
                loadAndCheckLevelData(levelDat);

                Path iconFile = root.resolve("icon.png");
                if (Files.isRegularFile(iconFile)) {
                    icon = loadIcon(iconFile);
                }
            }
        else
            throw new IOException("Path " + file + " cannot be recognized as a Minecraft world");
    }

    /// Decodes and normalizes one world icon.
    ///
    /// @param iconFile readable icon path, including paths inside an archive file system
    /// @return normalized icon, or `null` when decoding or scaling fails
    private static @Nullable BufferedImage loadIcon(Path iconFile) {
        try (InputStream inputStream = Files.newInputStream(iconFile)) {
            @Nullable BufferedImage source = ImageIO.read(inputStream);
            if (source == null) {
                throw new IOException("Unsupported or empty world icon " + iconFile);
            }
            return normalizeIcon(source);
        } catch (Exception e) {
            LOG.warning("Failed to load world icon", e);
            return null;
        }
    }

    /// Scales an image into a transparent 64-by-64 canvas using nearest-neighbor interpolation.
    ///
    /// Content retains its aspect ratio and is centered in the canvas, matching the historical
    /// JavaFX image request with `preserveRatio=true` and `smooth=false`.
    ///
    /// @param source decoded source image
    /// @return normalized 64-by-64 ARGB image
    private static BufferedImage normalizeIcon(BufferedImage source) {
        int sourceWidth = source.getWidth();
        int sourceHeight = source.getHeight();
        if (sourceWidth <= 0 || sourceHeight <= 0) {
            throw new IllegalArgumentException("World icon has invalid dimensions");
        }

        double scale = Math.min((double) ICON_SIZE / sourceWidth, (double) ICON_SIZE / sourceHeight);
        int scaledWidth = Math.max(1, (int) (sourceWidth * scale));
        int scaledHeight = Math.max(1, (int) (sourceHeight * scale));
        int x = (ICON_SIZE - scaledWidth) / 2;
        int y = (ICON_SIZE - scaledHeight) / 2;

        BufferedImage normalized = new BufferedImage(ICON_SIZE, ICON_SIZE, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = normalized.createGraphics();
        try {
            graphics.setComposite(AlphaComposite.Src);
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            graphics.drawImage(source, x, y, scaledWidth, scaledHeight, null);
        } finally {
            graphics.dispose();
        }
        return normalized;
    }

    /// Returns the original world source path.
    ///
    /// @return world directory or archive path
    public Path getFile() {
        return file;
    }

    /// Returns the source-derived world file name.
    ///
    /// @return world file or root-directory name
    public String getFileName() {
        return fileName;
    }

    /// Returns the world name stored in level data.
    ///
    /// @return stored world name, or an empty string when unavailable
    public String getWorldName() {
        if (levelData.get("Data") instanceof CompoundTag data
                && data.get("LevelName") instanceof StringTag levelNameTag)
            return levelNameTag.get();
        else
            return "";
    }

    /// Updates the stored world name and immediately writes level data for a directory world.
    ///
    /// @param worldName new stored world name
    /// @throws IOException if level data cannot be written
    public void setWorldName(String worldName) throws IOException {
        if (levelData.get("Data") instanceof CompoundTag data && data.get("LevelName") instanceof StringTag levelNameTag) {
            levelNameTag.setValue(worldName);
            writeLevelData();
        }
    }

    /// Returns the conventional session-lock path for this source.
    ///
    /// @return session-lock path
    public Path getSessionLockFile() {
        return file.resolve("session.lock");
    }

    /// Returns the mutable complete level-data tag.
    ///
    /// @return root level-data tag
    public CompoundTag getLevelData() {
        return levelData;
    }

    /// Returns the exact level-data source used by this world.
    ///
    /// Directory worlds return either `level.dat` or the supported `special_level.dat` fallback.
    /// Archive-backed paths are valid only while the constructor's temporary file system remains
    /// open, so callers should use this method only for directory worlds that they own.
    ///
    /// @return exact level-data path
    public Path getLevelDataPath() {
        return levelDataPath;
    }

    /// Returns normalized world-generation settings when present.
    ///
    /// @return mutable normalized settings, or `null` when unavailable
    public @Nullable CompoundTag getNormalizedWorldGenSettingsData() {
        return normalizedWorldGenSettingsData;
    }

    /// Returns loaded single-player data when present.
    ///
    /// @return mutable player data, or `null` when unavailable
    public @Nullable CompoundTag getPlayerData() {
        return playerData;
    }

    /// Returns the last-played epoch timestamp stored by Minecraft.
    ///
    /// @return epoch milliseconds, or zero when unavailable
    public long getLastPlayed() {
        if (dataTag.get("LastPlayed") instanceof LongTag lastPlayedTag) {
            return lastPlayedTag.get();
        } else {
            return 0L;
        }
    }

    /// Parses the recorded Minecraft version.
    ///
    /// @return parsed version, or `null` when absent or unrecognized
    public @Nullable GameVersionNumber getGameVersion() {
        if (levelData.get("Data") instanceof CompoundTag data &&
                data.get("Version") instanceof CompoundTag versionTag &&
                versionTag.get("Name") instanceof StringTag nameTag) {
            return GameVersionNumber.asGameVersion(nameTag.getValue());
        }
        return null;
    }

    /// Returns the world seed across legacy and modern NBT layouts.
    ///
    /// @return seed, or `null` when unavailable
    public @Nullable Long getSeed() {
        // Valid after 1.16(20w20a)
        if (normalizedWorldGenSettingsData != null
                && normalizedWorldGenSettingsData.get("seed") instanceof LongTag seedTag) {
            return seedTag.getValue();
        }
        // Valid before 1.16(20w20a)
        if (dataTag.get("RandomSeed") instanceof LongTag seedTag) {
            return seedTag.getValue();
        }
        return null;
    }

    /// Determines whether the world uses the large-biomes generator across supported layouts.
    ///
    /// @return whether large biomes are enabled
    public boolean isLargeBiomes() {
        // Valid before 1.16(20w20a)
        if (dataTag.get("generatorName") instanceof StringTag generatorNameTag) {
            return "largeBiomes".equals(generatorNameTag.getValue());
        }
        // Unified handling of logic after version 1.16
        else if (normalizedWorldGenSettingsData != null
                && normalizedWorldGenSettingsData.get("dimensions") instanceof CompoundTag dimensionsTag) {
            if (dimensionsTag.get("minecraft:overworld") instanceof CompoundTag overworldTag
                    && overworldTag.get("generator") instanceof CompoundTag generatorTag) {
                // Valid between 1.16(20w20a) and 1.18(21w37a)
                if (generatorTag.get("biome_source") instanceof CompoundTag biomeSourceTag
                        && biomeSourceTag.get("large_biomes") instanceof ByteTag largeBiomesTag) {
                    return largeBiomesTag.get() == (byte) 1;
                }
                // Valid after 1.18(21w37a)
                else if (generatorTag.get("settings") instanceof StringTag settingsTag) {
                    return "minecraft:large_biomes".equals(settingsTag.get());
                }
            }
        }
        return false;
    }

    /// Returns the normalized toolkit-neutral world icon.
    ///
    /// @return 64-by-64 ARGB icon, or `null` when absent or unreadable
    public @Nullable BufferedImage getIcon() {
        return icon;
    }

    /// Probes whether the world session lock is currently held.
    ///
    /// @return whether the world appears locked
    public boolean isLocked() {
        return isLocked(getSessionLockFile());
    }

    /// Determines whether the recorded game version supports data packs.
    ///
    /// @return whether data packs are supported
    public boolean supportDataPacks() {
        @Nullable GameVersionNumber gameVersion = getGameVersion();
        return gameVersion != null && gameVersion.isAtLeast("1.13", "17w43a");
    }

    /// Determines whether the recorded game version supports quick play.
    ///
    /// @return whether quick play is supported
    public boolean supportQuickPlay() {
        return supportQuickPlay(getGameVersion());
    }

    /// Determines quick-play support for an optional parsed game version.
    ///
    /// @param gameVersionNumber parsed game version, or `null`
    /// @return whether quick play is supported
    public static boolean supportQuickPlay(@Nullable GameVersionNumber gameVersionNumber) {
        return gameVersionNumber != null && gameVersionNumber.isAtLeast("1.20", "23w14a");
    }

    /// Reloads validated level data and auxiliary directory-only data.
    ///
    /// @throws IOException if required or auxiliary NBT cannot be read
    private void loadAndCheckWorldData() throws IOException {
        loadAndCheckLevelData(levelDataPath);
        loadOtherData();
    }

    /// Loads and validates the required fields from one level-data file.
    ///
    /// @param levelDat level-data path
    /// @throws IOException if the NBT is unreadable or required fields are absent
    private void loadAndCheckLevelData(Path levelDat) throws IOException {
        this.levelData = NBTCodec.of().readTag(levelDat, TagType.COMPOUND);
        if (!(levelData.get("Data") instanceof CompoundTag data))
            throw new IOException("level.dat missing Data");

        if (!(data.get("LevelName") instanceof StringTag))
            throw new IOException("level.dat missing LevelName");

        if (!(data.get("LastPlayed") instanceof LongTag))
            throw new IOException("level.dat missing LastPlayed");
        this.dataTag = data;
    }

    /// Loads optional world-generation and player data for a directory world.
    ///
    /// @throws IOException if a referenced auxiliary NBT file cannot be read
    private void loadOtherData() throws IOException {
        if (!(levelData.get("Data") instanceof CompoundTag data)) return;

        Path worldGenSettingsDatPath = file.resolve("data/minecraft/world_gen_settings.dat");
        if (data.get("WorldGenSettings") instanceof CompoundTag worldGenSettingsTag) {
            setWorldGenSettingsData(null, worldGenSettingsTag, worldGenSettingsTag);
        } else if (Files.isRegularFile(worldGenSettingsDatPath)) {
            CompoundTag raw = NBTCodec.of().readTag(worldGenSettingsDatPath, TagType.COMPOUND);
            if (raw.get("data") instanceof CompoundTag compoundTag) {
                setWorldGenSettingsData(worldGenSettingsDatPath, raw, compoundTag);
            } else {
                setWorldGenSettingsData(null, null, null);
            }
        } else {
            setWorldGenSettingsData(null, null, null);
        }

        if (data.get("Player") instanceof CompoundTag playerTag) {
            setPlayerData(null, playerTag);
        } else if (data.get("singleplayer_uuid") instanceof IntArrayTag uuidTag && uuidTag.isUUID()) {
            String playerUUID = uuidTag.getUUID().toString();
            Path playerDatPath = file.resolve("players/data/" + playerUUID + ".dat");
            if (Files.exists(playerDatPath)) {
                setPlayerData(playerDatPath, NBTCodec.of().readTag(playerDatPath, TagType.COMPOUND));
            } else {
                setPlayerData(null, null);
            }
        } else {
            setPlayerData(null, null);
        }
    }

    /// Replaces all optional world-generation settings references atomically.
    ///
    /// @param worldGenSettingsDataPath separate backing path, or `null`
    /// @param worldGenSettingsDataBackingTag tag written to the backing path, or `null`
    /// @param unifiedWorldGenSettingsData normalized mutable settings, or `null`
    private void setWorldGenSettingsData(
            @Nullable Path worldGenSettingsDataPath,
            @Nullable CompoundTag worldGenSettingsDataBackingTag,
            @Nullable CompoundTag unifiedWorldGenSettingsData) {
        this.worldGenSettingsDataPath = worldGenSettingsDataPath;
        this.worldGenSettingsDataBackingTag = worldGenSettingsDataBackingTag;
        this.normalizedWorldGenSettingsData = unifiedWorldGenSettingsData;
    }

    /// Replaces optional player data and its separate backing path together.
    ///
    /// @param playerDataPath separate player-data path, or `null`
    /// @param playerData loaded player tag, or `null`
    private void setPlayerData(@Nullable Path playerDataPath, @Nullable CompoundTag playerData) {
        this.playerDataPath = playerDataPath;
        this.playerData = playerData;
    }

    /// Reloads mutable NBT state from the directory source.
    ///
    /// @throws IOException if required or auxiliary NBT cannot be read
    public void reloadWorldData() throws IOException {
        loadAndCheckWorldData();
    }

    /// Renames a temporary directory world on disk and updates its stored level name.
    ///
    /// This operation intentionally retains the original `file` field because callers use the
    /// object only as a temporary installation or copy helper.
    ///
    /// @param newName new stored and directory name
    /// @throws IOException if the source is not a directory or either write/move fails
    public void rename(String newName) throws IOException {
        if (!Files.isDirectory(file))
            throw new IOException("Not a valid world directory");

        // Change the name recorded in level.dat
        dataTag.setString("LevelName", newName);
        writeLevelData();

        // then change the folder's name
        Files.move(file, file.resolveSibling(newName));
    }

    /// Installs this directory or archive into a saves directory under a new name.
    ///
    /// @param savesDir target saves directory
    /// @param name target directory and stored world name
    /// @throws IOException if the target name is invalid, already exists, or installation fails
    public void install(Path savesDir, String name) throws IOException {
        Path worldDir;
        try {
            worldDir = savesDir.resolve(name);
        } catch (InvalidPathException e) {
            throw new IOException(e);
        }

        if (Files.isDirectory(worldDir)) {
            throw new FileAlreadyExistsException("World already exists");
        }

        if (Files.isRegularFile(file)) {
            try (FileSystem fs = CompressingUtils.readonly(file).setAutoDetectEncoding(true).build()) {
                Path levelDatPath = fs.getPath("/level.dat");
                if (Files.isRegularFile(levelDatPath)) {
                    fileName = FileUtils.getName(file);

                    new Unzipper(file, worldDir).unzip();
                } else {
                    try (Stream<Path> stream = Files.list(fs.getPath("/"))) {
                        @Unmodifiable List<Path> subDirs = stream.toList();
                        if (subDirs.size() != 1) {
                            throw new IOException("World zip malformed");
                        }
                        String subDirectoryName = FileUtils.getName(subDirs.get(0));
                        new Unzipper(file, worldDir)
                                .setSubDirectory("/" + subDirectoryName + "/")
                                .unzip();
                    }
                }

            }
            new World(worldDir).rename(name);
        } else if (Files.isDirectory(file)) {
            FileUtils.copyDirectory(file, worldDir);
        }
    }

    /// Exports a directory world into a ZIP archive with the supplied root directory name.
    ///
    /// @param zip destination ZIP path
    /// @param worldName archive root directory name
    /// @throws IOException if this source is not a directory or export fails
    public void export(Path zip, String worldName) throws IOException {
        if (!Files.isDirectory(file))
            throw new IOException();

        try (Zipper zipper = new Zipper(zip)) {
            zipper.putDirectory(file, worldName);
        }
    }

    /// Deletes an unlocked world directory recursively.
    ///
    /// @throws IOException if the world is locked or deletion fails
    public void delete() throws IOException {
        if (isLocked()) {
            throw new WorldLockedException("The world " + getFile() + " has been locked");
        }
        FileUtils.forceDelete(file);
    }

    /// Copies an unlocked directory world beside its source and updates the copied level name.
    ///
    /// @param newName destination directory and stored world name
    /// @throws IOException if the source is invalid, locked, or cannot be copied
    public void copy(String newName) throws IOException {
        if (!Files.isDirectory(file)) {
            throw new IOException("Not a valid world directory");
        }

        if (isLocked()) {
            throw new WorldLockedException("The world " + getFile() + " has been locked");
        }

        Path newPath = file.resolveSibling(newName);
        FileUtils.copyDirectory(file, newPath, path -> !path.contains("session.lock"));
        World newWorld = new World(newPath);
        newWorld.rename(newName);
    }

    /// Acquires and retains the world session lock.
    ///
    /// The caller owns and must close the returned channel to release the lock.
    ///
    /// @return open channel holding the session lock
    /// @throws WorldLockedException if the lock cannot be opened or acquired
    public FileChannel lock() throws WorldLockedException {
        Path lockFile = getSessionLockFile();
        @Nullable FileChannel channel = null;
        try {
            channel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            channel.write(ByteBuffer.wrap("\u2603".getBytes(StandardCharsets.UTF_8)));
            channel.force(true);
            @Nullable FileLock fileLock = channel.tryLock();
            if (fileLock != null) {
                return channel;
            } else {
                IOUtils.closeQuietly(channel);
                throw new WorldLockedException("The world " + getFile() + " has been locked");
            }
        } catch (IOException e) {
            IOUtils.closeQuietly(channel);
            throw new WorldLockedException(e);
        }
    }

    /// Writes modified level, world-generation, and player data to a directory world.
    ///
    /// @throws IOException if this source is not a directory or any write fails
    public void writeWorldData() throws IOException {
        if (!Files.isDirectory(file)) throw new IOException("Not a valid world directory");

        writeLevelData();

        if (worldGenSettingsDataPath != null && worldGenSettingsDataBackingTag != null) {
            writeTag(worldGenSettingsDataBackingTag, worldGenSettingsDataPath);
        }

        if (playerDataPath != null && playerData != null) {
            writeTag(playerData, playerDataPath);
        }
    }

    /// Writes only the complete level-data tag using safe replacement.
    ///
    /// @throws IOException if this source is not a directory or the write fails
    public void writeLevelData() throws IOException {
        writeTag(levelData, levelDataPath);
    }

    /// Writes one NBT tag as GZIP-compressed data using safe replacement.
    ///
    /// @param nbt tag to write
    /// @param path target file path
    /// @throws IOException if this source is not a directory or the write fails
    private void writeTag(CompoundTag nbt, Path path) throws IOException {
        if (!Files.isDirectory(file)) throw new IOException("Not a valid world directory");
        FileUtils.saveSafely(path, os -> {
            try (OutputStream gos = new GZIPOutputStream(os)) {
                NBTCodec.of().writeTag(gos, nbt);
            }
        });
    }

    /// Probes one session-lock path without retaining a successfully acquired lock.
    ///
    /// @param sessionLockFile session-lock path
    /// @return whether the path is inaccessible or already locked
    private static boolean isLocked(Path sessionLockFile) {
        try (FileChannel fileChannel = FileChannel.open(sessionLockFile, StandardOpenOption.WRITE)) {
            return fileChannel.tryLock() == null;
        } catch (AccessDeniedException | OverlappingFileLockException accessDeniedException) {
            return true;
        } catch (NoSuchFileException noSuchFileException) {
            return false;
        } catch (IOException e) {
            LOG.warning("Failed to open the lock file " + sessionLockFile, e);
            return false;
        }
    }

    /// Loads every valid direct child world directory in a saves directory.
    ///
    /// Invalid entries and directory-listing failures are logged and omitted.
    ///
    /// @param savesDir saves directory
    /// @return immutable list of readable worlds
    public static @Unmodifiable List<World> getWorlds(Path savesDir) {
        if (Files.exists(savesDir)) {
            try (Stream<Path> stream = Files.list(savesDir)) {
                return stream
                        .filter(Files::isDirectory)
                        .flatMap(world -> {
                            try {
                                return Stream.of(new World(world.toAbsolutePath().normalize()));
                            } catch (IOException e) {
                                LOG.warning("Failed to read world " + world, e);
                                return Stream.empty();
                            }
                        })
                        .toList();
            } catch (IOException e) {
                LOG.warning("Failed to read saves", e);
            }
        }
        return List.of();
    }
}
