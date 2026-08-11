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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies strict local world ZIP imports, staging cleanup, and archive path rejection.
@NotNullByDefault
final class WorldArchiveImporterTest {
    /// Temporary root used for archives, source worlds, and target saves directories.
    @TempDir
    private Path temporaryDirectory;

    /// A single-root archive is stripped, renamed, and published as one direct saves child.
    @Test
    void importsSingleRootWorldArchiveAndRenamesLevelData() throws IOException {
        Path archive = createWorldArchive("downloaded-world", List.of("region/r.0.0.mca"));
        Path savesDirectory = Files.createDirectories(temporaryDirectory.resolve("saves"));

        WorldArchiveImportResult result = new WorldArchiveImporter(testLimits())
                .importArchive(archive, savesDirectory, "Imported World");

        Path imported = savesDirectory.resolve("Imported World");
        assertEquals(imported.toAbsolutePath().normalize(), result.worldDirectory());
        assertTrue(Files.isRegularFile(imported.resolve("level.dat")));
        assertTrue(Files.isRegularFile(imported.resolve("region/r.0.0.mca")));
        assertEquals("Imported World", new World(imported).getWorldName());
        assertEquals(2, result.extractedFileCount());
        assertTrue(result.extractedBytes() > 0L);
        assertNoStagingDirectories(savesDirectory);
    }

    /// Snapshot worlds using the historical special metadata filename remain importable.
    @Test
    void importsSpecialLevelDataArchive() throws IOException {
        Path archive = temporaryDirectory.resolve("special-world.zip");
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(archive))) {
            writeLevelDataEntry(output, "special-world/special_level.dat", "Infinite Snapshot");
        }
        Path savesDirectory = Files.createDirectories(temporaryDirectory.resolve("saves"));

        new WorldArchiveImporter(testLimits()).importArchive(archive, savesDirectory, "Imported Special");

        assertEquals("Imported Special", new World(savesDirectory.resolve("Imported Special")).getWorldName());
        assertNoStagingDirectories(savesDirectory);
    }

    /// Traversal entries are rejected before extraction and leave the saves directory untouched.
    @Test
    void rejectsTraversalEntryBeforeCreatingPublishedWorld() throws IOException {
        Path archive = temporaryDirectory.resolve("traversal.zip");
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(archive))) {
            writeLevelDataEntry(output, "world/level.dat", "Unsafe");
            writeTextEntry(output, "../evil.txt", "evil");
        }
        Path savesDirectory = Files.createDirectories(temporaryDirectory.resolve("saves"));

        assertThrows(IOException.class, () -> new WorldArchiveImporter(testLimits())
                .importArchive(archive, savesDirectory, "Unsafe"));

        assertFalse(Files.exists(savesDirectory.resolve("Unsafe")));
        assertFalse(Files.exists(temporaryDirectory.resolve("evil.txt")));
        assertNoStagingDirectories(savesDirectory);
    }

    /// File and directory path conflicts are rejected during central-directory planning.
    @Test
    void rejectsRegularFileUsedAsDirectoryParent() throws IOException {
        Path archive = temporaryDirectory.resolve("conflict.zip");
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(archive))) {
            writeLevelDataEntry(output, "world/level.dat", "Conflict");
            writeTextEntry(output, "world/data", "file");
            writeTextEntry(output, "world/data/child.dat", "child");
        }
        Path savesDirectory = Files.createDirectories(temporaryDirectory.resolve("saves"));

        assertThrows(IOException.class, () -> new WorldArchiveImporter(testLimits())
                .importArchive(archive, savesDirectory, "Conflict"));

        assertFalse(Files.exists(savesDirectory.resolve("Conflict")));
        assertNoStagingDirectories(savesDirectory);
    }

    /// A destination conflict is detected before staging and never overwrites the existing world.
    @Test
    void refusesExistingDestinationWithoutOverwrite() throws IOException {
        Path archive = createWorldArchive("source-world", List.of());
        Path savesDirectory = Files.createDirectories(temporaryDirectory.resolve("saves"));
        Path existing = Files.createDirectories(savesDirectory.resolve("Existing"));
        Files.writeString(existing.resolve("marker.txt"), "keep");

        assertThrows(FileAlreadyExistsException.class, () -> new WorldArchiveImporter(testLimits())
                .importArchive(archive, savesDirectory, "Existing"));

        assertEquals("keep", Files.readString(existing.resolve("marker.txt")));
        assertNoStagingDirectories(savesDirectory);
    }

    /// Creates a compact import policy suitable for fixture ZIPs.
    ///
    /// @return strict but small test limits
    private static WorldArchiveImportLimits testLimits() {
        return new WorldArchiveImportLimits(64, 16L * 1024L * 1024L, 4L * 1024L * 1024L);
    }

    /// Creates a valid single-root world archive with optional extra files.
    ///
    /// @param rootName enclosing archive root
    /// @param extraFiles extra files relative to the world root
    /// @return created ZIP path
    private Path createWorldArchive(String rootName, List<String> extraFiles) throws IOException {
        Path archive = temporaryDirectory.resolve(rootName + ".zip");
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(archive))) {
            writeLevelDataEntry(output, rootName + "/level.dat", rootName);
            for (String extraFile : extraFiles) {
                writeTextEntry(output, rootName + "/" + extraFile, extraFile);
            }
        }
        return archive;
    }

    /// Writes one valid gzipped level-data entry into an open archive.
    ///
    /// @param output open archive output
    /// @param entryName archive entry name
    /// @param levelName stored world name
    private static void writeLevelDataEntry(
            ZipOutputStream output,
            String entryName,
            String levelName) throws IOException {
        ByteArrayOutputStream encodedLevelData = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(encodedLevelData)) {
            CompoundTag data = new CompoundTag()
                    .addString("LevelName", levelName)
                    .addLong("LastPlayed", 1L);
            CompoundTag root = new CompoundTag().addTag("Data", data);
            NBTCodec.of().writeTag(gzip, root);
        }
        output.putNextEntry(new ZipEntry(entryName));
        encodedLevelData.writeTo(output);
        output.closeEntry();
    }

    /// Writes one UTF-8 text entry into an open archive.
    ///
    /// @param output open archive output
    /// @param entryName archive entry name
    /// @param content entry content
    private static void writeTextEntry(
            ZipOutputStream output,
            String entryName,
            String content) throws IOException {
        output.putNextEntry(new ZipEntry(entryName));
        output.write(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        output.closeEntry();
    }

    /// Confirms that failed imports cleaned every private staging directory.
    ///
    /// @param savesDirectory target saves directory
    private static void assertNoStagingDirectories(Path savesDirectory) throws IOException {
        try (var children = Files.list(savesDirectory)) {
            assertTrue(children.noneMatch(path -> path.getFileName().toString().startsWith(".xyml-world-stage-")));
        }
    }
}
