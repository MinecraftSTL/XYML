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
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import space.minecraftstl.xyml.game.GameRepository;
import space.minecraftstl.xyml.game.World;
import space.minecraftstl.xyml.game.WorldLockedException;
import space.minecraftstl.xyml.util.io.FileUtils;

import javax.swing.SwingUtilities;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.stream.Stream;

import static space.minecraftstl.xyml.util.logging.Logger.LOG;

/// Filesystem-backed asynchronous catalog for one instance's local world backups.
///
/// The class performs only a direct-child directory and ZIP listing during `load`. It does not build
/// `World` instances until a backup or restore is explicitly requested, ensuring that NBT and archive
/// parsing never burden initial Swing page activation.
@NotNullByDefault
public final class FileSystemWorldBackupCatalog implements WorldBackupCatalog {
    /// Timestamp portion retained from the historical world-backup archive convention.
    private static final DateTimeFormatter BACKUP_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");

    /// Highest number of same-second filename alternatives before reporting a collision failure.
    private static final int MAXIMUM_BACKUP_NAME_ATTEMPTS = 256;

    /// Managed direct-child saves directory.
    private final Path savesDirectory;

    /// Managed local backup ZIP directory.
    private final Path backupsDirectory;

    /// Caller-owned worker receiving every filesystem, compression, and archive operation.
    private final Executor executor;

    /// Creates a production catalog for a repository instance without touching its filesystem.
    ///
    /// @param repository repository containing the managed instance
    /// @param instanceId stable managed instance identifier
    /// @param executor caller-owned background executor
    public FileSystemWorldBackupCatalog(GameRepository repository, String instanceId, Executor executor) {
        this(
                Objects.requireNonNull(repository, "repository")
                        .getRunDirectory(Objects.requireNonNull(instanceId, "instanceId")),
                executor);
    }

    /// Creates a catalog from one already-resolved instance run directory for focused tests.
    ///
    /// @param runDirectory managed instance run directory
    /// @param executor caller-owned background executor
    FileSystemWorldBackupCatalog(Path runDirectory, Executor executor) {
        Path normalizedRunDirectory = Objects.requireNonNull(runDirectory, "runDirectory").toAbsolutePath().normalize();
        this.savesDirectory = normalizedRunDirectory.resolve("saves");
        this.backupsDirectory = normalizedRunDirectory.resolve("backups");
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    /// Returns the saves directory without performing an I/O operation.
    ///
    /// @return normalised saves directory
    @Override
    public Path savesDirectory() {
        return savesDirectory;
    }

    /// Returns the backups directory without performing an I/O operation.
    ///
    /// @return normalised backups directory
    @Override
    public Path backupsDirectory() {
        return backupsDirectory;
    }

    /// Schedules an inexpensive direct-child source and archive index.
    ///
    /// @return terminal shallow snapshot
    @Override
    public CompletionStage<WorldBackupSnapshot> load() {
        return submit(this::indexDirectories);
    }

    /// Schedules a safe locked-world archive export followed by a fresh shallow index.
    ///
    /// @param source selected shallow source directory
    /// @return terminal shallow snapshot after archive creation
    @Override
    public CompletionStage<WorldBackupSnapshot> createBackup(WorldBackupSource source) {
        WorldBackupSource requestedSource = Objects.requireNonNull(source, "source");
        return submit(() -> {
            createBackupOnWorker(requestedSource);
            return indexDirectories();
        });
    }

    /// Schedules permanent deletion of one direct-child backup archive and a fresh index.
    ///
    /// @param archive selected backup archive
    /// @return terminal shallow snapshot after archive deletion
    @Override
    public CompletionStage<WorldBackupSnapshot> deleteBackup(WorldBackupArchive archive) {
        WorldBackupArchive requestedArchive = Objects.requireNonNull(archive, "archive");
        return submit(() -> {
            Path archivePath = requireDirectBackupArchive(requestedArchive);
            FileUtils.forceDelete(archivePath);
            return indexDirectories();
        });
    }

    /// Schedules Core archive installation into a newly named direct child of `saves`.
    ///
    /// @param archive selected backup archive
    /// @param destinationName user-confirmed new save name
    /// @return terminal shallow snapshot after archive installation
    @Override
    public CompletionStage<WorldBackupSnapshot> restoreBackup(WorldBackupArchive archive, String destinationName) {
        WorldBackupArchive requestedArchive = Objects.requireNonNull(archive, "archive");
        String requestedDestinationName = Objects.requireNonNull(destinationName, "destinationName");
        return submit(() -> {
            Path archivePath = requireDirectBackupArchive(requestedArchive);
            String safeDestinationName = requireSingleDirectoryName(requestedDestinationName);
            Files.createDirectories(savesDirectory);
            new World(archivePath).install(savesDirectory, safeDestinationName);
            return indexDirectories();
        });
    }

    /// Submits a checked-I/O operation while enforcing the non-EDT I/O contract.
    ///
    /// @param operation background operation
    /// @return future containing the terminal operation result
    private <T> CompletableFuture<T> submit(CheckedSupplier<T> operation) {
        return CompletableFuture.supplyAsync(() -> {
            requireBackgroundThread();
            try {
                return Objects.requireNonNull(operation, "operation").get();
            } catch (IOException exception) {
                throw new CompletionException(exception);
            }
        }, executor);
    }

    /// Performs a shallow direct-child index without parsing world NBT or archive contents.
    ///
    /// @return immutable current source and backup metadata
    private WorldBackupSnapshot indexDirectories() throws IOException {
        requireBackgroundThread();
        return new WorldBackupSnapshot(indexSources(), indexArchives());
    }

    /// Lists direct child directories in `saves` with no `World` construction.
    ///
    /// @return immutable ordered source directory metadata
    private @Unmodifiable List<WorldBackupSource> indexSources() throws IOException {
        if (!Files.isDirectory(savesDirectory)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.list(savesDirectory)) {
            List<WorldBackupSource> sources = new ArrayList<>();
            stream.filter(Files::isDirectory).forEach(path -> {
                Path normalizedPath = path.toAbsolutePath().normalize();
                @Nullable Path fileName = normalizedPath.getFileName();
                if (fileName != null) {
                    sources.add(new WorldBackupSource(normalizedPath, fileName.toString()));
                }
            });
            sources.sort(Comparator.comparing(WorldBackupSource::directoryName, String.CASE_INSENSITIVE_ORDER)
                    .thenComparing(WorldBackupSource::directoryName));
            return List.copyOf(sources);
        }
    }

    /// Lists direct child ZIP archives in `backups` without opening their compressed contents.
    ///
    /// @return immutable newest-first backup archive metadata
    private @Unmodifiable List<WorldBackupArchive> indexArchives() throws IOException {
        if (!Files.isDirectory(backupsDirectory)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.list(backupsDirectory)) {
            List<WorldBackupArchive> archives = new ArrayList<>();
            stream.filter(Files::isRegularFile)
                    .filter(FileSystemWorldBackupCatalog::isZipArchive)
                    .forEach(path -> indexArchive(path, archives));
            archives.sort(Comparator.comparing(WorldBackupArchive::lastModified).reversed()
                    .thenComparing(WorldBackupArchive::fileName, String.CASE_INSENSITIVE_ORDER));
            return List.copyOf(archives);
        }
    }

    /// Adds readable lightweight metadata for one archive or logs and skips an unreadable entry.
    ///
    /// @param path candidate ZIP file
    /// @param archives mutable worker-local result list
    private static void indexArchive(Path path, List<WorldBackupArchive> archives) {
        try {
            Path normalizedPath = path.toAbsolutePath().normalize();
            @Nullable Path fileName = normalizedPath.getFileName();
            if (fileName == null) {
                return;
            }
            archives.add(new WorldBackupArchive(
                    normalizedPath,
                    fileName.toString(),
                    Files.size(normalizedPath),
                    Files.getLastModifiedTime(normalizedPath).toInstant()));
        } catch (IOException exception) {
            LOG.warning("Failed to index world backup " + path, exception);
        }
    }

    /// Tests an archive suffix without opening file contents.
    ///
    /// @param path candidate local file
    /// @return whether the filename ends in `.zip`, ignoring ASCII case
    private static boolean isZipArchive(Path path) {
        @Nullable Path fileName = Objects.requireNonNull(path, "path").getFileName();
        return fileName != null && fileName.toString().toLowerCase(java.util.Locale.ROOT).endsWith(".zip");
    }

    /// Exports one validated unlocked source directory into a collision-free archive.
    ///
    /// @param source shallow selected source
    /// @throws IOException if validation, locking, or archive export fails
    private void createBackupOnWorker(WorldBackupSource source) throws IOException {
        Path sourceDirectory = requireDirectSourceDirectory(source);
        if (!Files.isDirectory(sourceDirectory)) {
            throw new NoSuchFileException(sourceDirectory.toString());
        }
        World world = new World(sourceDirectory);
        if (world.isLocked()) {
            throw new WorldLockedException("The world " + world.getFile() + " has been locked");
        }
        Files.createDirectories(backupsDirectory);
        Path destination = nextBackupPath(world.getFileName());
        Path temporary = Files.createTempFile(backupsDirectory, ".xyml-world-backup-", ".zip.part");
        boolean moved = false;
        try {
            world.export(temporary, world.getFileName());
            Files.move(temporary, destination);
            moved = true;
        } finally {
            if (!moved) {
                Files.deleteIfExists(temporary);
            }
        }
    }

    /// Chooses a non-existing backup filename using the historical timestamp and counter scheme.
    ///
    /// @param worldDirectoryName original selected world directory name
    /// @return currently unused backup archive path
    /// @throws IOException when every bounded filename candidate already exists
    private Path nextBackupPath(String worldDirectoryName) throws IOException {
        String sourceName = Objects.requireNonNull(worldDirectoryName, "worldDirectoryName");
        String baseName = BACKUP_TIME_FORMATTER.format(LocalDateTime.now(ZoneId.systemDefault())) + "_" + sourceName;
        for (int counter = 0; counter < MAXIMUM_BACKUP_NAME_ATTEMPTS; counter++) {
            String suffix = counter == 0 ? "" : " " + counter;
            Path candidate = backupsDirectory.resolve(baseName + suffix + ".zip").toAbsolutePath().normalize();
            if (!candidate.startsWith(backupsDirectory) || candidate.getParent() == null
                    || !candidate.getParent().equals(backupsDirectory)) {
                throw new IOException("Backup filename escaped its managed directory");
            }
            if (!Files.exists(candidate)) {
                return candidate;
            }
        }
        throw new IOException("Could not find a free world backup filename");
    }

    /// Validates that a selected shallow source still belongs directly to this instance's saves folder.
    ///
    /// @param source selected shallow source
    /// @return safe direct-child directory path
    /// @throws IOException when the supplied source was stale or outside the managed folder
    private Path requireDirectSourceDirectory(WorldBackupSource source) throws IOException {
        Path candidate = Objects.requireNonNull(source, "source").directory().toAbsolutePath().normalize();
        if (candidate.getParent() == null || !candidate.getParent().equals(savesDirectory)) {
            throw new IOException("Selected world is outside this instance saves directory");
        }
        return candidate;
    }

    /// Validates that a selected archive still belongs directly to this instance's backups folder.
    ///
    /// @param archive selected backup archive
    /// @return safe direct-child archive path
    /// @throws IOException when the supplied archive was stale, outside the folder, or no longer a file
    private Path requireDirectBackupArchive(WorldBackupArchive archive) throws IOException {
        Path candidate = Objects.requireNonNull(archive, "archive").archive().toAbsolutePath().normalize();
        if (candidate.getParent() == null || !candidate.getParent().equals(backupsDirectory)) {
            throw new IOException("Selected backup is outside this instance backups directory");
        }
        if (!Files.isRegularFile(candidate)) {
            throw new NoSuchFileException(candidate.toString());
        }
        return candidate;
    }

    /// Validates that a restore target cannot escape the managed saves directory or overwrite a path segment.
    ///
    /// @param rawName user-confirmed restore target
    /// @return normalized single directory name
    /// @throws IOException if the input is blank, invalid, absolute, or contains multiple path segments
    private static String requireSingleDirectoryName(String rawName) throws IOException {
        String candidate = Objects.requireNonNull(rawName, "rawName").trim();
        if (candidate.isBlank()) {
            throw new IOException("Restore destination name must not be blank");
        }
        try {
            Path path = Path.of(candidate);
            if (path.isAbsolute() || path.getNameCount() != 1 || ".".equals(candidate) || "..".equals(candidate)) {
                throw new IOException("Restore destination must be one directory name");
            }
            return path.getFileName().toString();
        } catch (InvalidPathException exception) {
            throw new IOException("Restore destination name is invalid", exception);
        }
    }

    /// Fails fast if a caller-provided executor would run filesystem work on the Swing EDT.
    private static void requireBackgroundThread() {
        if (SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException("World backup filesystem work must not run on the Swing EDT");
        }
    }

    /// Defines a checked worker operation used by the generic async submission bridge.
    ///
    /// @param <T> terminal result type
    @FunctionalInterface
    private interface CheckedSupplier<T> {
        /// Produces one worker result.
        ///
        /// @return terminal worker result
        /// @throws IOException when filesystem work fails
        T get() throws IOException;
    }
}
