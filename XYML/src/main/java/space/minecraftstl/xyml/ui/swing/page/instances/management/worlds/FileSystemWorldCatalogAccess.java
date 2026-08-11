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

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import space.minecraftstl.xyml.game.GameInstanceID;
import space.minecraftstl.xyml.game.GameRepository;
import space.minecraftstl.xyml.game.World;
import space.minecraftstl.xyml.game.WorldArchiveImporter;
import space.minecraftstl.xyml.game.WorldLockedException;
import space.minecraftstl.xyml.ui.swing.choice.LoadCancellation;
import space.minecraftstl.xyml.util.io.FileUtils;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/// Real repository and Core World API adapter for one managed instance's `saves` directory.
///
/// The shallow index intentionally observes only child directory entries. It never constructs
/// `World` values, opens `level.dat`, or decodes icons until a range is explicitly requested.
@NotNullByDefault
final class FileSystemWorldCatalogAccess implements WorldCatalogAccess {
    /// Repository used to resolve the effective game directory on demand.
    private final GameRepository repository;

    /// Stable managed instance identifier.
    private final GameInstanceID instanceId;

    /// Deterministic case-insensitive order for raw directory labels.
    private static final Comparator<Path> DIRECTORY_ORDER = Comparator
            .comparing(FileSystemWorldCatalogAccess::directoryName, String.CASE_INSENSITIVE_ORDER)
            .thenComparing(Path::toString);

    /// Creates one real filesystem adapter without resolving or enumerating `saves`.
    ///
    /// @param repository managed game repository
    /// @param instanceId stable non-blank instance identifier
    FileSystemWorldCatalogAccess(GameRepository repository, GameInstanceID instanceId) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.instanceId = Objects.requireNonNull(instanceId, "instanceId");
    }

    /// Resolves the instance run directory and appends the conventional `saves` segment.
    ///
    /// @return normalized saves directory without listing it
    @Override
    public Path savesDirectory() {
        return Objects.requireNonNull(repository.getRunDirectory(instanceId), "run directory")
                .resolve("saves")
                .toAbsolutePath()
                .normalize();
    }

    /// Builds a direct-child directory index without parsing NBT or images.
    ///
    /// @param cancellation cooperative cancellation signal
    /// @return immutable sorted direct-child directory paths
    /// @throws IOException when the existing saves directory cannot be listed
    @Override
    public @Unmodifiable List<Path> indexWorldDirectories(LoadCancellation cancellation) throws IOException {
        LoadCancellation signal = Objects.requireNonNull(cancellation, "cancellation");
        signal.throwIfCancelled();
        Path savesDirectory = savesDirectory();
        if (!Files.isDirectory(savesDirectory)) {
            return List.of();
        }
        List<Path> directories = new ArrayList<>();
        try (Stream<Path> children = Files.list(savesDirectory)) {
            children.forEach(child -> {
                signal.throwIfCancelled();
                if (Files.isDirectory(child)) {
                    directories.add(child.toAbsolutePath().normalize());
                }
            });
        }
        signal.throwIfCancelled();
        directories.sort(DIRECTORY_ORDER);
        return List.copyOf(directories);
    }

    /// Parses exactly one visible world directory through the Core World API.
    ///
    /// @param directory exact directory from the shallow index
    /// @param cancellation cooperative cancellation signal
    /// @return loaded metadata or an unreadable placeholder for the same directory
    @Override
    public WorldCatalogItem loadItem(Path directory, LoadCancellation cancellation) {
        Path normalizedDirectory = Objects.requireNonNull(directory, "directory").toAbsolutePath().normalize();
        LoadCancellation signal = Objects.requireNonNull(cancellation, "cancellation");
        signal.throwIfCancelled();
        try {
            World world = new World(normalizedDirectory);
            signal.throwIfCancelled();
            return WorldCatalogItem.loaded(world);
        } catch (IOException | RuntimeException failure) {
            signal.throwIfCancelled();
            return WorldCatalogItem.unreadable(normalizedDirectory, failure);
        }
    }

    /// Reads a selected archive through Core before asking the user for its target name.
    ///
    /// The later mutation reruns strict central-directory validation through [WorldArchiveImporter]
    /// immediately before any bytes are extracted. This read-only preview deliberately performs no
    /// filesystem mutation, so a changed source archive cannot bypass the import policy.
    ///
    /// @param archive selected local archive
    /// @param cancellation cooperative cancellation signal
    /// @return Core-derived source and suggested target name
    /// @throws IOException when the archive is not a world
    @Override
    public WorldCatalogImport inspectImport(Path archive, LoadCancellation cancellation) throws IOException {
        Path normalizedArchive = Objects.requireNonNull(archive, "archive").toAbsolutePath().normalize();
        LoadCancellation signal = Objects.requireNonNull(cancellation, "cancellation");
        signal.throwIfCancelled();
        World world = new World(normalizedArchive);
        signal.throwIfCancelled();
        String suggestedName = world.getWorldName();
        if (suggestedName.isBlank()) {
            suggestedName = world.getFileName();
        }
        return new WorldCatalogImport(normalizedArchive, suggestedName);
    }

    /// Validates, stages, and atomically publishes one archive through Core's strict importer.
    ///
    /// @param world validated source archive
    /// @param targetName non-blank destination name
    /// @param cancellation cooperative cancellation signal
    /// @throws IOException when validation, staging, or atomic publication fails
    @Override
    public void install(
            WorldCatalogImport world,
            String targetName,
            LoadCancellation cancellation) throws IOException {
        WorldCatalogImport importWorld = Objects.requireNonNull(world, "world");
        String normalizedTargetName = requireNonBlank(targetName, "targetName");
        LoadCancellation signal = Objects.requireNonNull(cancellation, "cancellation");
        signal.throwIfCancelled();
        Path savesDirectory = savesDirectory();
        // Revalidate the source immediately before extraction so the preview cannot authorize
        // a later replaced archive with different paths or entry data.
        new WorldArchiveImporter().importArchive(
                importWorld.source(),
                savesDirectory,
                normalizedTargetName);
        signal.throwIfCancelled();
    }

    /// Reopens a validated current world and delegates lock-aware deletion to Core.
    ///
    /// @param world selected current row
    /// @param cancellation cooperative cancellation signal
    /// @throws IOException when the world is unreadable, locked, or cannot be removed
    @Override
    public void delete(WorldCatalogItem world, LoadCancellation cancellation) throws IOException {
        WorldCatalogItem selectedWorld = Objects.requireNonNull(world, "world");
        LoadCancellation signal = Objects.requireNonNull(cancellation, "cancellation");
        if (!selectedWorld.readable()) {
            throw new IOException("Unreadable worlds cannot be deleted through the World API");
        }
        signal.throwIfCancelled();
        World sourceWorld = new World(selectedWorld.path());
        signal.throwIfCancelled();
        sourceWorld.delete();
        signal.throwIfCancelled();
    }

    /// Reopens and copies one current readable world after enforcing a direct, absent sibling target.
    ///
    /// @param world selected current row
    /// @param targetName requested sibling directory and stored level name
    /// @param cancellation cooperative cancellation signal
    /// @throws IOException when the source is locked or the target is invalid or already present
    @Override
    public void copy(
            WorldCatalogItem world,
            String targetName,
            LoadCancellation cancellation) throws IOException {
        WorldCatalogItem selectedWorld = requireReadableWorld(world);
        String normalizedTargetName = requireNonBlank(targetName, "targetName");
        if (!FileUtils.isNameValid(normalizedTargetName)) {
            throw new IOException("Invalid world copy name: " + normalizedTargetName);
        }
        LoadCancellation signal = Objects.requireNonNull(cancellation, "cancellation");
        signal.throwIfCancelled();
        Path parent = Objects.requireNonNull(selectedWorld.path().getParent(), "world parent");
        Path target = parent.resolve(normalizedTargetName).toAbsolutePath().normalize();
        if (!parent.equals(target.getParent())) {
            throw new IOException("World copy target must remain inside the saves directory");
        }
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new FileAlreadyExistsException(target.toString());
        }
        World sourceWorld = new World(selectedWorld.path());
        rejectLocked(sourceWorld);
        signal.throwIfCancelled();
        sourceWorld.copy(normalizedTargetName);
        signal.throwIfCancelled();
    }

    /// Exports one current readable world through a sibling temporary archive and atomic publication.
    ///
    /// @param world selected current row
    /// @param archive requested final ZIP destination
    /// @param cancellation cooperative cancellation signal
    /// @throws IOException when the source is locked or the destination cannot be published
    @Override
    public void export(
            WorldCatalogItem world,
            Path archive,
            LoadCancellation cancellation) throws IOException {
        WorldCatalogItem selectedWorld = requireReadableWorld(world);
        Path destination = Objects.requireNonNull(archive, "archive").toAbsolutePath().normalize();
        LoadCancellation signal = Objects.requireNonNull(cancellation, "cancellation");
        @Nullable Path fileName = destination.getFileName();
        if (fileName == null || !fileName.toString().toLowerCase(java.util.Locale.ROOT).endsWith(".zip")) {
            throw new IOException("World export destination must use the .zip extension");
        }
        if (destination.startsWith(selectedWorld.path())) {
            throw new IOException("World export destination cannot be inside the source world");
        }
        @Nullable Path parent = destination.getParent();
        if (parent == null) {
            throw new IOException("World export destination has no parent directory");
        }
        signal.throwIfCancelled();
        Files.createDirectories(parent);
        World sourceWorld = new World(selectedWorld.path());
        rejectLocked(sourceWorld);
        Path staging = Files.createTempFile(parent, ".xyml-world-export-", ".zip.tmp")
                .toAbsolutePath()
                .normalize();
        boolean published = false;
        @Nullable Throwable failure = null;
        try {
            Files.delete(staging);
            signal.throwIfCancelled();
            sourceWorld.export(staging, selectedWorld.directoryName());
            signal.throwIfCancelled();
            publishExport(staging, destination);
            published = true;
        } catch (IOException | RuntimeException | Error thrown) {
            failure = thrown;
            throw thrown;
        } finally {
            if (!published) {
                try {
                    Files.deleteIfExists(staging);
                } catch (IOException cleanupFailure) {
                    if (failure != null) {
                        failure.addSuppressed(cleanupFailure);
                    } else {
                        throw cleanupFailure;
                    }
                }
            }
        }
    }

    /// Publishes a complete temporary archive without exposing partial destination bytes.
    ///
    /// @param staging complete sibling temporary archive
    /// @param destination final archive path
    /// @throws IOException when neither atomic nor ordinary replacement succeeds
    private static void publishExport(Path staging, Path destination) throws IOException {
        try {
            Files.move(
                    staging,
                    destination,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(staging, destination, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /// Rejects a row that Core could not decode before any mutation or export starts.
    ///
    /// @param world selected row
    /// @return validated readable row
    /// @throws IOException when the row is unreadable
    private static WorldCatalogItem requireReadableWorld(WorldCatalogItem world) throws IOException {
        WorldCatalogItem selectedWorld = Objects.requireNonNull(world, "world");
        if (!selectedWorld.readable()) {
            throw new IOException("Unreadable worlds cannot be modified through the World API");
        }
        return selectedWorld;
    }

    /// Rejects copying or exporting a world while Minecraft owns its session lock.
    ///
    /// @param world reopened Core world
    /// @throws WorldLockedException when the session lock is held
    private static void rejectLocked(World world) throws WorldLockedException {
        if (world.isLocked()) {
            throw new WorldLockedException("The world " + world.getFile() + " has been locked");
        }
    }

    /// Extracts a directory name used for deterministic shallow-index sorting.
    ///
    /// @param path normalized direct-child directory
    /// @return non-blank final path segment
    private static String directoryName(Path path) {
        @Nullable Path fileName = path.getFileName();
        if (fileName == null || fileName.toString().isBlank()) {
            throw new IllegalArgumentException("World directory must have a name: " + path);
        }
        return fileName.toString();
    }

    /// Rejects blank configuration or command strings.
    ///
    /// @param value candidate value
    /// @param name parameter name for diagnostics
    /// @return validated value
    private static String requireNonBlank(String value, String name) {
        String checkedValue = Objects.requireNonNull(value, name);
        if (checkedValue.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return checkedValue;
    }
}
