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
package space.minecraftstl.xyml.util.tree;

import kala.compress.archivers.tar.TarArchiveOutputStream;
import kala.compress.archivers.tar.TarArchiveReader;
import kala.compress.archivers.zip.ZipArchiveReader;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/// Checks lazy materialization, strict whole-archive validation and borrowed-reader ownership.
@NotNullByDefault
public final class LazyArchiveFileTreeTest {
    /// Known files, aliases and normal misses never require directory nodes.
    @Test
    void pointQueriesDoNotBuildTree(@TempDir Path directory) throws IOException {
        Path archive = zip(directory, Map.of("pack.mcmeta", "meta", "./nested/icon.txt", "icon"));
        try (ZipFileTree tree = new ZipFileTree(new ZipArchiveReader(archive))) {
            assertFalse(tree.isTreeMaterialized());
            assertEquals("meta", tree.readTextEntry("/pack.mcmeta"));
            assertEquals("icon", tree.readTextEntry("/nested/icon.txt"));
            assertNull(tree.getEntry("missing"));
            assertFalse(tree.isTreeMaterialized());
            assertSame(tree.getRoot(), tree.getRoot());
            assertEquals("nested", tree.getDirectory("nested").getFullName());
        }
    }

    /// Malformed unrelated paths are rejected even when the requested metadata file is safe.
    @Test
    void pointReadsStillValidateWholeArchive(@TempDir Path directory) throws IOException {
        Path archive = zip(directory, Map.of("pack.mcmeta", "safe", "a/../escape", "invalid"));
        try (ZipFileTree tree = new ZipFileTree(new ZipArchiveReader(archive))) {
            assertThrows(IOException.class, () -> tree.getEntry("pack.mcmeta"));
            assertFalse(tree.isTreeMaterialized());
            assertThrows(IOException.class, tree::getRoot);
            assertFalse(tree.isTreeMaterialized());
        }
    }

    /// Logical duplicates and file/directory conflicts remain errors, not warning-and-skip behavior.
    @Test
    void rejectsDuplicatesAndConflicts(@TempDir Path directory) throws IOException {
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("same", "first");
        entries.put("./same", "second");
        Path archive = zip(directory, entries);
        try (ZipFileTree tree = new ZipFileTree(new ZipArchiveReader(archive))) {
            assertThrows(IOException.class, () -> tree.getEntry("same"));
        }
        entries.clear();
        entries.put("parent", "file");
        entries.put("parent/child", "child");
        archive = zip(directory, entries);
        try (ZipFileTree tree = new ZipFileTree(new ZipArchiveReader(archive))) {
            assertThrows(IOException.class, tree::getRoot);
        }
    }

    /// Wrapper closure forbids reads but does not close a borrowed ZIP reader.
    @Test
    void preservesBorrowedReader(@TempDir Path directory) throws IOException {
        Path archive = zip(directory, Map.of("file", "content"));
        try (ZipArchiveReader reader = new ZipArchiveReader(archive)) {
            ZipFileTree tree = new ZipFileTree(reader, false);
            tree.close();
            tree.close();
            assertThrows(IOException.class, tree::getRoot);
            assertThrows(IOException.class, () -> tree.getEntry("file"));
            try (var input = reader.getInputStream(reader.getEntry("file"))) {
                assertEquals("content", new String(input.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
    }

    /// TAR closure and deferred construction do not delete the original archive.
    @Test
    void tarBuildsOnlyWhenRequested(@TempDir Path directory) throws IOException {
        Path archive = directory.resolve("empty.tar");
        try (TarArchiveOutputStream output = new TarArchiveOutputStream(Files.newOutputStream(archive))) {
            output.finish();
        }
        TarFileTree tree = new TarFileTree(new TarArchiveReader(archive), null);
        assertTrue(tree.getRoot().getFiles().isEmpty());
        assertSame(tree.getRoot(), tree.getRoot());
        tree.close();
        assertThrows(IOException.class, tree::getRoot);
        assertTrue(Files.exists(archive));
    }

    /// Creates actual ZIP fixtures without depending on a mocked archive reader.
    private static Path zip(Path directory, Map<String, String> entries) throws IOException {
        Path file = directory.resolve("fixture.zip");
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(file))) {
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                output.putNextEntry(new ZipEntry(entry.getKey()));
                output.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                output.closeEntry();
            }
        }
        return file;
    }
}
