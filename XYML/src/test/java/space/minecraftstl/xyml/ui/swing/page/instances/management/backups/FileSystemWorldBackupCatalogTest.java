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

import space.minecraftstl.xyml.library.nbt.io.NBTCodec;
import space.minecraftstl.xyml.library.nbt.tag.CompoundTag;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import space.minecraftstl.xyml.game.World;
import space.minecraftstl.xyml.util.io.DeletionMode;
import space.minecraftstl.xyml.util.io.TrashMoveException;
import space.minecraftstl.xyml.util.io.TrashOperations;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Executor;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies real local world archive lifecycle behavior without parsing worlds during shallow index.
@NotNullByDefault
final class FileSystemWorldBackupCatalogTest {
    /// Temporary root containing an isolated instance run directory.
    @TempDir
    private Path temporaryDirectory;

    /// Direct worker used only from the JUnit thread, which is not the Swing EDT.
    private static final Executor DIRECT_WORKER = Runnable::run;

    /// Exports a valid selected world, indexes its archive, restores a new save, and deletes the archive.
    @Test
    void createsRestoresAndDeletesLocalBackupWithoutEagerWorldValidation() throws Exception {
        Path runDirectory = Files.createDirectories(temporaryDirectory.resolve("run"));
        Path savesDirectory = Files.createDirectories(runDirectory.resolve("saves"));
        Path sourceDirectory = createWorldDirectory(savesDirectory, "source-world");
        Files.createDirectory(savesDirectory.resolve("not-a-world"));
        FileSystemWorldBackupCatalog catalog = new FileSystemWorldBackupCatalog(runDirectory, DIRECT_WORKER);

        WorldBackupSnapshot initial = catalog.load().toCompletableFuture().join();

        assertEquals(2, initial.sources().size());
        WorldBackupSource source = initial.sources().stream()
                .filter(candidate -> candidate.directory().equals(sourceDirectory.toAbsolutePath().normalize()))
                .findFirst()
                .orElseThrow();

        WorldBackupSnapshot afterCreate = catalog.createBackup(source).toCompletableFuture().join();

        assertEquals(1, afterCreate.archives().size());
        WorldBackupArchive archive = afterCreate.archives().get(0);
        assertTrue(Files.isRegularFile(archive.archive()));
        assertEquals("source-world", new World(archive.archive()).getWorldName());

        WorldBackupSnapshot afterRestore = catalog.restoreBackup(archive, "restored-world").toCompletableFuture().join();

        assertTrue(afterRestore.sources().stream()
                .anyMatch(candidate -> candidate.directoryName().equals("restored-world")));
        assertEquals("restored-world", new World(savesDirectory.resolve("restored-world")).getWorldName());

        WorldBackupSnapshot afterDelete = catalog.deleteBackup(archive).toCompletableFuture().join();

        assertTrue(afterDelete.archives().isEmpty());
        assertFalse(Files.exists(archive.archive()));
    }

    /// A backup is moved to the selected recycle-bin boundary without permanent deletion.
    @Test
    void deletesBackupThroughRecycleBinBoundary() throws IOException {
        Path runDirectory = Files.createDirectories(temporaryDirectory.resolve("recycle-run"));
        Path backupsDirectory = Files.createDirectories(runDirectory.resolve("backups"));
        Path archiveFile = Files.writeString(backupsDirectory.resolve("backup.zip"), "archive");
        Path trash = temporaryDirectory.resolve("trash");
        FileSystemWorldBackupCatalog catalog = new FileSystemWorldBackupCatalog(
                runDirectory,
                DIRECT_WORKER,
                new RecordingTrashOperations(trash, false));
        WorldBackupArchive archive = catalog.load().toCompletableFuture().join().archives().get(0);

        catalog.deleteBackup(archive, DeletionMode.RECYCLE_BIN_FIRST).toCompletableFuture().join();

        assertFalse(Files.exists(archiveFile));
        assertTrue(Files.exists(trash.resolve("backup.zip")));
    }

    /// A refused recycle-bin move leaves the backup intact and reports the exact failed path.
    @Test
    void preservesBackupWhenRecycleBinMoveFails() throws IOException {
        Path runDirectory = Files.createDirectories(temporaryDirectory.resolve("failed-run"));
        Path backupsDirectory = Files.createDirectories(runDirectory.resolve("backups"));
        Path archiveFile = Files.writeString(backupsDirectory.resolve("backup.zip"), "archive");
        FileSystemWorldBackupCatalog catalog = new FileSystemWorldBackupCatalog(
                runDirectory,
                DIRECT_WORKER,
                new RecordingTrashOperations(temporaryDirectory.resolve("trash"), true));
        WorldBackupArchive archive = catalog.load().toCompletableFuture().join().archives().get(0);

        java.util.concurrent.CompletionException completion = assertThrows(
                java.util.concurrent.CompletionException.class,
                () -> catalog.deleteBackup(archive, DeletionMode.RECYCLE_BIN_FIRST).toCompletableFuture().join());
        TrashMoveException failure = org.junit.jupiter.api.Assertions.assertInstanceOf(
                TrashMoveException.class,
                completion.getCause());

        assertEquals(archiveFile.toAbsolutePath().normalize(), failure.failedPaths().get(0));
        assertTrue(Files.exists(archiveFile));
    }

    /// Moves archives into a temporary recycle-bin destination or refuses every move.
    @NotNullByDefault
    private static final class RecordingTrashOperations implements TrashOperations {
        /// Temporary recycle-bin destination.
        private final Path trashDirectory;

        /// Whether every move should be refused.
        private final boolean refuseMoves;

        /// Creates one deterministic recycle-bin substitute.
        ///
        /// @param trashDirectory temporary recycle-bin destination
        /// @param refuseMoves whether all moves should fail
        private RecordingTrashOperations(Path trashDirectory, boolean refuseMoves) {
            this.trashDirectory = trashDirectory;
            this.refuseMoves = refuseMoves;
        }

        /// Reports a supported recycle-bin boundary.
        @Override
        public boolean isSupported() {
            return true;
        }

        /// Moves one archive unless configured to refuse all moves.
        @Override
        public boolean moveToTrash(Path path) {
            if (refuseMoves) {
                return false;
            }
            try {
                Files.createDirectories(trashDirectory);
                Files.move(path, trashDirectory.resolve(path.getFileName()));
                return true;
            } catch (IOException exception) {
                return false;
            }
        }
    }

    /// Creates a direct child world directory with the minimum Core-readable NBT layout.
    ///
    /// @param savesDirectory instance saves root
    /// @param name source directory and stored level name
    /// @return complete valid world directory
    private static Path createWorldDirectory(Path savesDirectory, String name) throws IOException {
        Path directory = Files.createDirectory(savesDirectory.resolve(name));
        CompoundTag data = new CompoundTag()
                .addString("LevelName", name)
                .addLong("LastPlayed", 1L);
        CompoundTag root = new CompoundTag().addTag("Data", data);
        try (OutputStream output = new GZIPOutputStream(Files.newOutputStream(directory.resolve("level.dat")))) {
            NBTCodec.of().writeTag(output, root);
        }
        return directory;
    }
}
