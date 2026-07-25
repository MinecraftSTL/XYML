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

import org.glavo.nbt.io.NBTCodec;
import org.glavo.nbt.tag.CompoundTag;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import space.minecraftstl.xyml.game.World;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Executor;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
