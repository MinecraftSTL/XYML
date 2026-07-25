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
import space.minecraftstl.xyml.game.GameRepository;
import space.minecraftstl.xyml.game.World;
import space.minecraftstl.xyml.game.WorldArchiveImporter;
import space.minecraftstl.xyml.ui.swing.choice.LoadCancellation;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
    private final String instanceId;

    /// Deterministic case-insensitive order for raw directory labels.
    private static final Comparator<Path> DIRECTORY_ORDER = Comparator
            .comparing(FileSystemWorldCatalogAccess::directoryName, String.CASE_INSENSITIVE_ORDER)
            .thenComparing(Path::toString);

    /// Creates one real filesystem adapter without resolving or enumerating `saves`.
    ///
    /// @param repository managed game repository
    /// @param instanceId stable non-blank instance identifier
    FileSystemWorldCatalogAccess(GameRepository repository, String instanceId) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.instanceId = requireNonBlank(instanceId, "instanceId");
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
