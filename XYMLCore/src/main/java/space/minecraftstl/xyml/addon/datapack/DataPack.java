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
package space.minecraftstl.xyml.addon.datapack;

import com.google.gson.JsonParseException;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import space.minecraftstl.xyml.addon.LocalAddonFile;
import space.minecraftstl.xyml.addon.meta.PackMcMeta;
import space.minecraftstl.xyml.observable.Subscription;
import space.minecraftstl.xyml.observable.ValueChangeListener;
import space.minecraftstl.xyml.observable.ValueChangeSupport;
import space.minecraftstl.xyml.util.StringUtils;
import space.minecraftstl.xyml.util.gson.JsonUtils;
import space.minecraftstl.xyml.util.io.CompressingUtils;
import space.minecraftstl.xyml.util.io.FileUtils;
import space.minecraftstl.xyml.util.io.Unzipper;
import space.minecraftstl.xyml.util.versioning.GameVersionNumber;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static space.minecraftstl.xyml.util.logging.Logger.LOG;

/// Discovers and mutates the data packs belonging to one world directory without depending on a UI toolkit.
///
/// Pack-list reads return immutable snapshots. Loading, installing, and deleting are serialized so the file-system
/// mutation and corresponding snapshot publication form one ordered operation. Change listeners run synchronously on
/// the calling thread; UI consumers must dispatch toolkit work themselves.
@NotNullByDefault
public class DataPack {
    /// Extension used for disabled archives and metadata files.
    private static final String DISABLED_EXT = "disabled";

    /// Extension used for data-pack archives.
    private static final String ZIP_EXT = "zip";

    /// Directory containing this world's data packs.
    private final Path path;

    /// Serializes file-system mutations with immutable snapshot replacement and ordered notification.
    private final Object mutationLock = new Object();

    /// Publishes complete immutable list transitions without imposing a UI dispatch policy.
    private final ValueChangeSupport<@Unmodifiable List<Pack>> packsChangeSupport =
            new ValueChangeSupport<>(this);

    /// Latest immutable pack snapshot, safely published to readers on any thread.
    private volatile @Unmodifiable List<Pack> packs = List.of();

    /// Creates a data-pack manager for one directory.
    ///
    /// @param path data-pack directory
    public DataPack(Path path) {
        this.path = Objects.requireNonNull(path, "path");
    }

    /// Returns the managed data-pack directory.
    ///
    /// @return data-pack directory
    public Path getPath() {
        return path;
    }

    /// Returns the latest immutable pack snapshot.
    ///
    /// The returned list remains stable when a later load, install, or delete publishes a replacement.
    ///
    /// @return immutable pack snapshot
    public @Unmodifiable List<Pack> getPacks() {
        return packs;
    }

    /// Subscribes to complete immutable pack-snapshot transitions.
    ///
    /// Notifications are synchronous on the thread performing the successful load, install, or delete operation.
    /// The subscription does not emit the current value immediately; callers should read [#getPacks()] when attaching.
    ///
    /// @param listener snapshot listener
    /// @return independently cancellable subscription
    public Subscription subscribePacks(
            ValueChangeListener<@Unmodifiable List<Pack>> listener) {
        return packsChangeSupport.subscribe(listener);
    }

    /// Installs one single-pack or multi-pack archive into a target world data-pack directory.
    ///
    /// Existing entries with matching logical names are removed first. Multi-pack archives may also contribute a
    /// resource-pack archive in the location selected by the target game version.
    ///
    /// @param sourceDataPackPath source archive
    /// @param targetDataPackDirectory target data-pack directory
    /// @param gameVersionNumber target version, or null when the old resource-pack location must be used
    /// @throws IOException when the source cannot be read or the destination cannot be updated
    public static void installPack(
            Path sourceDataPackPath,
            Path targetDataPackDirectory,
            @Nullable GameVersionNumber gameVersionNumber) throws IOException {
        boolean containsMultiplePacks;
        Set<String> packNames = new HashSet<>();
        try (FileSystem fs = CompressingUtils.readonly(sourceDataPackPath)
                .setAutoDetectEncoding(true)
                .build()) {
            Path dataPacks = fs.getPath("datapacks");
            Path mcmeta = fs.getPath("pack.mcmeta");

            if (Files.exists(dataPacks)) {
                containsMultiplePacks = true;
            } else if (Files.exists(mcmeta)) {
                containsMultiplePacks = false;
            } else {
                throw new IOException("Malformed datapack zip");
            }

            if (containsMultiplePacks) {
                try (Stream<Path> stream = Files.list(dataPacks)) {
                    packNames = stream
                            .map(FileUtils::getNameWithoutExtension)
                            .collect(Collectors.toSet());
                }
            } else {
                packNames.add(FileUtils.getNameWithoutExtension(sourceDataPackPath));
            }

            try (DirectoryStream<Path> stream = Files.newDirectoryStream(targetDataPackDirectory)) {
                for (Path entry : stream) {
                    String packName = FileUtils.getName(entry);
                    if (FileUtils.getExtension(entry).equals(DISABLED_EXT)) {
                        packName = StringUtils.removeSuffix(packName, "." + DISABLED_EXT);
                    }
                    packName = FileUtils.getNameWithoutExtension(packName);
                    if (packNames.contains(packName)) {
                        if (Files.isDirectory(entry)) {
                            FileUtils.deleteDirectory(entry);
                        } else if (Files.isRegularFile(entry)) {
                            Files.delete(entry);
                        }
                    }
                }
            }
        }

        if (!containsMultiplePacks) {
            FileUtils.copyFile(
                    sourceDataPackPath,
                    targetDataPackDirectory.resolve(FileUtils.getName(sourceDataPackPath)));
            return;
        }

        new Unzipper(sourceDataPackPath, targetDataPackDirectory)
                .setReplaceExistentFile(true)
                .setSubDirectory("/datapacks/")
                .unzip();

        Path worldDirectory = Objects.requireNonNull(
                targetDataPackDirectory.getParent(),
                "targetDataPackDirectory parent");
        Path targetResourceZipPath;
        // When the version cannot be obtained, retain the old-version resource-pack location.
        boolean useNewResourcePath = gameVersionNumber != null
                && gameVersionNumber.compareTo("26.1-snapshot-6") >= 0;

        if (useNewResourcePath) {
            Path resourcePackDirectory = worldDirectory.resolve("resourcepacks");
            Files.createDirectories(resourcePackDirectory);
            targetResourceZipPath = resourcePackDirectory.resolve("resources.zip");
        } else {
            targetResourceZipPath = worldDirectory.resolve("resources.zip");
        }

        try (FileSystem outputResourcesZipFileSystem =
                     CompressingUtils.createWritableZipFileSystem(targetResourceZipPath);
             FileSystem inputPackZipFileSystem =
                     CompressingUtils.createReadOnlyZipFileSystem(sourceDataPackPath)) {
            Path resourcesZip = inputPackZipFileSystem.getPath("resources.zip");
            if (Files.isRegularFile(resourcesZip)) {
                copyEmbeddedResources(resourcesZip, outputResourcesZipFileSystem);
            }
            writeGeneratedResourceMetadata(outputResourcesZipFileSystem);
        }
    }

    /// Copies an embedded resource archive into the generated world resource archive.
    ///
    /// @param resourcesZip embedded resource archive
    /// @param outputResourcesZipFileSystem writable destination archive file system
    /// @throws IOException when either archive cannot be copied
    private static void copyEmbeddedResources(
            Path resourcesZip,
            FileSystem outputResourcesZipFileSystem) throws IOException {
        Path temporaryResourcesFile = Files.createTempFile("xyml", ".zip");
        try {
            Files.copy(resourcesZip, temporaryResourcesFile, StandardCopyOption.REPLACE_EXISTING);
            try (FileSystem resources =
                         CompressingUtils.createReadOnlyZipFileSystem(temporaryResourcesFile)) {
                FileUtils.copyDirectory(
                        resources.getPath("/"),
                        outputResourcesZipFileSystem.getPath("/"));
            }
        } finally {
            Files.deleteIfExists(temporaryResourcesFile);
        }
    }

    /// Writes deterministic metadata and removes a stale icon from a generated world resource archive.
    ///
    /// @param outputResourcesZipFileSystem writable destination archive file system
    /// @throws IOException when metadata or icon entries cannot be updated
    private static void writeGeneratedResourceMetadata(
            FileSystem outputResourcesZipFileSystem) throws IOException {
        Path packMcMeta = outputResourcesZipFileSystem.getPath("pack.mcmeta");
        String metaContent = """
                {
                    "pack": {
                        "pack_format": 4,
                        "description": "Modified by XYML."
                    }
                }
                """;
        Files.writeString(
                packMcMeta,
                metaContent,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING);

        Path packPng = outputResourcesZipFileSystem.getPath("pack.png");
        if (Files.isRegularFile(packPng)) {
            Files.delete(packPng);
        }
    }

    /// Installs an archive into this manager and immediately publishes a refreshed snapshot.
    ///
    /// @param sourcePackPath source archive
    /// @param gameVersionNumber target version, or null when the old resource-pack location must be used
    /// @throws IOException when the archive cannot be installed
    public void installPack(
            Path sourcePackPath,
            @Nullable GameVersionNumber gameVersionNumber) throws IOException {
        synchronized (mutationLock) {
            installPack(sourcePackPath, path, gameVersionNumber);
            loadFromDirLocked();
        }
    }

    /// Deletes one pack from disk and removes every snapshot entry with the same stable identifier.
    ///
    /// @param packToDelete pack to delete
    /// @throws IOException when the pack cannot be deleted
    public void deletePack(Pack packToDelete) throws IOException {
        Objects.requireNonNull(packToDelete, "packToDelete");
        synchronized (mutationLock) {
            Path pathToDelete = packToDelete.getPath();
            if (Files.isDirectory(pathToDelete)) {
                FileUtils.deleteDirectory(pathToDelete);
            } else if (Files.isRegularFile(pathToDelete)) {
                Files.delete(pathToDelete);
            }

            @Unmodifiable List<Pack> retained = packs.stream()
                    .filter(pack -> !pack.getId().equals(packToDelete.getId()))
                    .toList();
            publishPacksLocked(retained);
        }
    }

    /// Discovers the managed directory and publishes a complete immutable snapshot on the calling thread.
    ///
    /// Failures are logged and leave the previous snapshot unchanged.
    public void loadFromDir() {
        synchronized (mutationLock) {
            loadFromDirLocked();
        }
    }

    /// Discovers and publishes the managed directory while [#mutationLock] is held.
    private void loadFromDirLocked() {
        final @Unmodifiable List<Pack> discoveredPacks;
        try {
            discoveredPacks = discoverPacks(path);
        } catch (Exception e) {
            LOG.warning("Failed to read datapacks " + path, e);
            return;
        }
        publishPacksLocked(discoveredPacks);
    }

    /// Discovers supported packs beneath one directory and returns a sorted immutable snapshot.
    ///
    /// @param directory directory to scan
    /// @return immutable snapshot sorted by stable identifier
    /// @throws IOException when the directory cannot be listed
    private @Unmodifiable List<Pack> discoverPacks(Path directory) throws IOException {
        try (Stream<Path> stream = Files.list(directory)) {
            return stream
                    .parallel()
                    .map(this::loadSinglePackFromPath)
                    .flatMap(Optional::stream)
                    .sorted(Comparator.comparing(Pack::getId, String.CASE_INSENSITIVE_ORDER))
                    .toList();
        }
    }

    /// Atomically replaces and synchronously publishes the current immutable snapshot.
    ///
    /// This method must be called while [#mutationLock] is held so concurrent operations cannot reorder
    /// publications.
    ///
    /// @param discoveredPacks newly discovered or retained packs
    private void publishPacksLocked(List<? extends Pack> discoveredPacks) {
        @Unmodifiable List<Pack> previous = packs;
        @Unmodifiable List<Pack> current = List.copyOf(discoveredPacks);
        packs = current;
        packsChangeSupport.fireChange(previous, current);
    }

    /// Loads one path according to whether it is a directory or regular archive.
    ///
    /// @param candidate candidate path
    /// @return parsed pack, or empty when the path is not a supported pack
    private Optional<Pack> loadSinglePackFromPath(Path candidate) {
        if (Files.isDirectory(candidate)) {
            return loadSinglePackFromDirectory(candidate);
        }
        if (Files.isRegularFile(candidate)) {
            return loadSinglePackFromZipFile(candidate);
        }
        return Optional.empty();
    }

    /// Loads a directory containing enabled or disabled metadata.
    ///
    /// @param candidate candidate directory
    /// @return parsed pack, or empty when no metadata exists
    private Optional<Pack> loadSinglePackFromDirectory(Path candidate) {
        Path mcmeta = candidate.resolve("pack.mcmeta");
        Path disabledMcmeta = candidate.resolve("pack.mcmeta.disabled");

        if (!Files.exists(mcmeta) && !Files.exists(disabledMcmeta)) {
            return Optional.empty();
        }

        Path metadataPath = Files.exists(mcmeta) ? mcmeta : disabledMcmeta;
        return parsePack(
                candidate,
                true,
                FileUtils.getNameWithoutExtension(candidate),
                metadataPath);
    }

    /// Loads a zip archive whose root contains pack metadata.
    ///
    /// @param candidate candidate archive
    /// @return parsed pack, or empty when the archive is unsupported or unreadable
    private Optional<Pack> loadSinglePackFromZipFile(Path candidate) {
        try (FileSystem fs = CompressingUtils.createReadOnlyZipFileSystem(candidate)) {
            Path mcmeta = fs.getPath("pack.mcmeta");

            if (!Files.exists(mcmeta)) {
                return Optional.empty();
            }

            String packName = FileUtils.getName(candidate);
            if (FileUtils.getExtension(candidate).equals(DISABLED_EXT)) {
                packName = FileUtils.getNameWithoutExtension(packName);
            }
            packName = FileUtils.getNameWithoutExtension(packName);

            return parsePack(candidate, false, packName, mcmeta);
        } catch (IOException e) {
            LOG.warning("IO error reading " + candidate, e);
            return Optional.empty();
        }
    }

    /// Parses one metadata file into a pack model.
    ///
    /// @param dataPackPath persistent pack path
    /// @param directory whether the pack is a directory
    /// @param name stable display identifier
    /// @param mcmetaPath metadata path, which may belong to a mounted archive
    /// @return parsed pack, or empty when metadata is invalid or unreadable
    private Optional<Pack> parsePack(
            Path dataPackPath,
            boolean directory,
            String name,
            Path mcmetaPath) {
        try {
            PackMcMeta mcMeta = JsonUtils.fromNonNullJson(
                    Files.readString(mcmetaPath),
                    PackMcMeta.class);
            return Optional.of(new Pack(
                    dataPackPath,
                    directory,
                    name,
                    mcMeta.pack().description(),
                    this));
        } catch (JsonParseException e) {
            LOG.warning("Invalid pack.mcmeta format in " + dataPackPath, e);
        } catch (IOException e) {
            LOG.warning("IO error reading " + dataPackPath, e);
        }
        return Optional.empty();
    }

    /// Toolkit-neutral model for one discovered data pack and its requested active state.
    @NotNullByDefault
    public static class Pack {
        /// Serializes active-state transitions and related file renames.
        private final Object stateLock = new Object();

        /// Current pack path, updated after a successful archive rename.
        private volatile Path path;

        /// Whether the pack is represented by a directory rather than an archive.
        private final boolean directory;

        /// Metadata file for directories or archive file for zip packs; guarded by [#stateLock].
        private Path statusFile;

        /// Requested active state, safely published to readers on any thread.
        private volatile boolean active;

        /// Publishes requested active-state transitions without exposing a toolkit property.
        private final ValueChangeSupport<Boolean> activeChangeSupport = new ValueChangeSupport<>(this);

        /// Stable pack identifier.
        private final String id;

        /// Parsed pack description.
        private final LocalAddonFile.Description description;

        /// Manager that discovered and owns this pack snapshot entry.
        private final DataPack parentDataPack;

        /// Creates one discovered pack model and derives its initial active state from its status path.
        ///
        /// @param path persistent pack path
        /// @param directory whether the pack is represented by a directory
        /// @param id stable pack identifier
        /// @param description parsed pack description
        /// @param parentDataPack owning manager
        public Pack(
                Path path,
                boolean directory,
                String id,
                LocalAddonFile.Description description,
                DataPack parentDataPack) {
            this.path = Objects.requireNonNull(path, "path");
            this.directory = directory;
            this.id = Objects.requireNonNull(id, "id");
            this.description = Objects.requireNonNull(description, "description");
            this.parentDataPack = Objects.requireNonNull(parentDataPack, "parentDataPack");

            statusFile = initializeStatusFile(path, directory);
            active = !FileUtils.getExtension(statusFile).equals(DISABLED_EXT);
        }

        /// Chooses the enabled or disabled status path that currently exists.
        ///
        /// @param packPath persistent pack path
        /// @param directory whether the pack is represented by a directory
        /// @return current metadata or archive status path
        private static Path initializeStatusFile(Path packPath, boolean directory) {
            if (directory) {
                Path mcmeta = packPath.resolve("pack.mcmeta");
                return Files.exists(mcmeta) ? mcmeta : packPath.resolve("pack.mcmeta.disabled");
            }
            return packPath;
        }

        /// Renames the status file to reflect a requested active state while [#stateLock] is held.
        ///
        /// Rename failures are logged and leave the last known paths unchanged. The requested active state remains
        /// changed, matching the existing launcher behavior for file-system failures.
        ///
        /// @param nowActive requested active state
        private void handleFileRenameLocked(boolean nowActive) {
            Path newStatusFile = calculateNewStatusFilePathLocked(nowActive);
            if (statusFile.equals(newStatusFile)) {
                return;
            }
            try {
                Files.move(statusFile, newStatusFile);
                statusFile = newStatusFile;
                if (!directory) {
                    path = newStatusFile;
                }
            } catch (IOException e) {
                LOG.warning("Unable to rename file from " + statusFile + " to " + newStatusFile, e);
            }
        }

        /// Calculates the status path for an active-state transition while [#stateLock] is held.
        ///
        /// @param nowActive requested active state
        /// @return current or renamed status path
        private Path calculateNewStatusFilePathLocked(boolean nowActive) {
            boolean fileDisabled = DISABLED_EXT.equals(FileUtils.getExtension(statusFile));
            if (nowActive && fileDisabled) {
                return statusFile.resolveSibling(FileUtils.getNameWithoutExtension(statusFile));
            }
            if (!nowActive && !fileDisabled) {
                return statusFile.resolveSibling(
                        FileUtils.getName(statusFile) + "." + DISABLED_EXT);
            }
            return statusFile;
        }

        /// Returns the stable pack identifier.
        ///
        /// @return stable identifier
        public String getId() {
            return id;
        }

        /// Returns the parsed pack description.
        ///
        /// @return parsed description
        public LocalAddonFile.Description getDescription() {
            return description;
        }

        /// Returns the manager that discovered this pack.
        ///
        /// @return owning manager
        public DataPack getParentDataPack() {
            return parentDataPack;
        }

        /// Subscribes to requested active-state transitions.
        ///
        /// Notifications are synchronous on the thread calling [#setActive(boolean)].
        ///
        /// @param listener active-state listener
        /// @return independently cancellable subscription
        public Subscription subscribeActive(ValueChangeListener<Boolean> listener) {
            return activeChangeSupport.subscribe(listener);
        }

        /// Returns the requested active state.
        ///
        /// A failed file rename can leave this requested state different from the last known path state.
        ///
        /// @return whether the pack is requested to be active
        public boolean isActive() {
            return active;
        }

        /// Changes the requested active state and attempts the corresponding status-file rename.
        ///
        /// Repeated writes of the current requested state have no effect. Successful transitions are published after
        /// the rename attempt, synchronously on the calling thread.
        ///
        /// @param active whether the pack should be active
        public void setActive(boolean active) {
            synchronized (stateLock) {
                boolean previous = this.active;
                if (previous == active) {
                    return;
                }

                this.active = active;
                handleFileRenameLocked(active);
                activeChangeSupport.fireChange(previous, active);
            }
        }

        /// Returns the current directory or archive path.
        ///
        /// @return current persistent path
        public Path getPath() {
            return path;
        }

        /// Returns whether this pack is represented by a directory.
        ///
        /// @return whether this pack is a directory
        public boolean isDirectory() {
            return directory;
        }
    }
}
