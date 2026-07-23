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
package space.minecraftstl.xyml.ui.swing.page.schematics;

import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import space.minecraftstl.xyml.ui.swing.choice.LoadCancellation;

import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests batch-import rollback and cleanup diagnostics at the production file-system boundary.
@NotNullByDefault
public final class FileSystemSchematicMutationIoTest {
    /// A second final-move failure removes the first destination and every remaining temporary file.
    @Test
    public void rollsBackCommittedAndStagedFilesAfterPartialMoveFailure() throws Exception {
        try (FileSystem fileSystem = Jimfs.newFileSystem(Configuration.unix())) {
            Path root = Files.createDirectories(fileSystem.getPath("/schematics"));
            Path sources = Files.createDirectories(fileSystem.getPath("/sources"));
            Path first = Files.writeString(sources.resolve("first.litematic"), "first");
            Path second = Files.writeString(sources.resolve("second.litematic"), "second");
            IOException primary = new IOException("second move failed");
            AtomicInteger moves = new AtomicInteger();
            FileSystemSchematicMutationIo mutationIo = new FileSystemSchematicMutationIo(
                    root,
                    (source, destination) -> {
                        Files.move(source, destination);
                        if (moves.incrementAndGet() == 2) {
                            throw primary;
                        }
                    });

            IOException observed = assertThrows(
                    IOException.class,
                    () -> mutationIo.importFiles(
                            root, List.of(first, second), new LoadCancellation()));

            assertAll(
                    () -> assertSame(primary, observed),
                    () -> assertFalse(Files.exists(root.resolve("first.litematic"))),
                    () -> assertFalse(Files.exists(root.resolve("second.litematic"))),
                    () -> assertEquals(0L, temporaryFileCount(root)));
        }
    }

    /// Invalid source preflight finishes before a missing destination root can be created.
    @Test
    public void invalidBatchDoesNotCreateMissingRoot() throws Exception {
        try (FileSystem fileSystem = Jimfs.newFileSystem(Configuration.unix())) {
            Path root = fileSystem.getPath("/missing/schematics");
            Path sources = Files.createDirectories(fileSystem.getPath("/sources"));
            Path valid = Files.writeString(sources.resolve("valid.litematic"), "valid");
            Path invalid = Files.writeString(sources.resolve("invalid.txt"), "invalid");
            FileSystemSchematicMutationIo mutationIo =
                    new FileSystemSchematicMutationIo(root);

            assertThrows(
                    IOException.class,
                    () -> mutationIo.importFiles(
                            root, List.of(valid, invalid), new LoadCancellation()));

            assertAll(
                    () -> assertFalse(Files.exists(root, LinkOption.NOFOLLOW_LINKS)),
                    () -> assertFalse(Files.exists(root.getParent(), LinkOption.NOFOLLOW_LINKS)),
                    () -> assertEquals("valid", Files.readString(valid)),
                    () -> assertEquals("invalid", Files.readString(invalid)));
        }
    }

    /// A replacement installed after the owned output opens is never followed or modified.
    @Test
    public void importOutputDoesNotFollowAReplacedTemporarySymlink() throws Exception {
        try (FileSystem fileSystem = Jimfs.newFileSystem(Configuration.unix())) {
            Path root = Files.createDirectories(fileSystem.getPath("/schematics"));
            Path sources = Files.createDirectories(fileSystem.getPath("/sources"));
            Path source = Files.writeString(sources.resolve("source.litematic"), "source");
            Path outside = Files.writeString(fileSystem.getPath("/outside.txt"), "outside");
            AtomicReference<@Nullable Path> replacedTemporary = new AtomicReference<>();
            FileSystemSchematicMutationIo mutationIo = new FileSystemSchematicMutationIo(
                    root,
                    Files::move,
                    temporary -> {
                        Files.delete(temporary);
                        Files.createSymbolicLink(temporary, outside);
                        replacedTemporary.set(temporary);
                    },
                    isolated -> {
                    });

            IOException observed = assertThrows(
                    IOException.class,
                    () -> mutationIo.importFiles(
                            root, List.of(source), new LoadCancellation()));

            Path temporary = Objects.requireNonNull(replacedTemporary.get());
            assertAll(
                    () -> assertEquals("outside", Files.readString(outside)),
                    () -> assertFalse(Files.exists(root.resolve("source.litematic"))),
                    () -> assertTrue(Files.isSymbolicLink(temporary)),
                    () -> assertEquals(1L, temporaryFileCount(root)),
                    () -> assertEquals(1, observed.getSuppressed().length),
                    () -> assertTrue(observed.getSuppressed()[0].getMessage()
                            .contains("ownership could not be confirmed")));
        }
    }

    /// A replaced committed destination is preserved and diagnosed without replacing the primary failure.
    @Test
    public void preservesPrimaryFailureAndSuppressesRollbackFailure() throws Exception {
        try (FileSystem fileSystem = Jimfs.newFileSystem(Configuration.unix())) {
            Path root = Files.createDirectories(fileSystem.getPath("/schematics"));
            Path sources = Files.createDirectories(fileSystem.getPath("/sources"));
            Path first = Files.writeString(sources.resolve("first.litematic"), "first");
            Path second = Files.writeString(sources.resolve("second.litematic"), "second");
            IOException primary = new IOException("second move failed");
            AtomicInteger moves = new AtomicInteger();
            AtomicReference<@Nullable Path> firstDestination = new AtomicReference<>();
            FileSystemSchematicMutationIo mutationIo = new FileSystemSchematicMutationIo(
                    root,
                    (source, destination) -> {
                        if (moves.incrementAndGet() == 1) {
                            Files.move(source, destination);
                            firstDestination.set(destination);
                            return;
                        }
                        Path committed = Objects.requireNonNull(firstDestination.get());
                        Files.delete(committed);
                        Files.writeString(committed, "external replacement");
                        throw primary;
                    });

            IOException observed = assertThrows(
                    IOException.class,
                    () -> mutationIo.importFiles(
                            root, List.of(first, second), new LoadCancellation()));

            assertAll(
                    () -> assertSame(primary, observed),
                    () -> assertEquals(1, observed.getSuppressed().length),
                    () -> assertInstanceOf(
                            IOException.class,
                            observed.getSuppressed()[0]),
                    () -> assertTrue(observed.getSuppressed()[0].getMessage()
                            .contains("ownership could not be confirmed")),
                    () -> assertEquals(
                            "external replacement",
                            Files.readString(Objects.requireNonNull(firstDestination.get()))),
                    () -> assertFalse(Files.exists(root.resolve("second.litematic"))),
                    () -> assertEquals(0L, temporaryFileCount(root)));
        }
    }

    /// Delete cleanup preserves a same-type replacement installed after isolation commits.
    @Test
    public void deleteDoesNotRemoveAReplacementInstalledAtItsIsolationPath() throws Exception {
        try (FileSystem fileSystem = Jimfs.newFileSystem(Configuration.unix())) {
            Path root = Files.createDirectories(fileSystem.getPath("/schematics"));
            Path target = Files.writeString(root.resolve("target.litematic"), "original");
            Path replacement = Files.writeString(
                    fileSystem.getPath("/replacement.litematic"), "external replacement");
            BasicFileAttributes attributes = Files.readAttributes(
                    target, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            DefaultSchematicBrowserModel.DiscoveredEntry entry =
                    new DefaultSchematicBrowserModel.DiscoveredEntry(
                            target,
                            target.getFileName().toString(),
                            false,
                            keylessIdentity(attributes));
            AtomicReference<@Nullable Path> isolatedPath = new AtomicReference<>();
            FileSystemSchematicMutationIo mutationIo = new FileSystemSchematicMutationIo(
                    root,
                    Files::move,
                    temporary -> {
                    },
                    isolated -> {
                        Files.delete(isolated);
                        Files.move(replacement, isolated);
                        isolatedPath.set(isolated);
                    });

            assertThrows(
                    IOException.class,
                    () -> mutationIo.delete(root, entry, new LoadCancellation()));

            Path isolated = Objects.requireNonNull(isolatedPath.get());
            assertAll(
                    () -> assertFalse(Files.exists(target, LinkOption.NOFOLLOW_LINKS)),
                    () -> assertTrue(Files.exists(isolated, LinkOption.NOFOLLOW_LINKS)),
                    () -> assertEquals("external replacement", Files.readString(isolated)));
        }
    }

    /// Keyless Windows-style identity supports successful copy and owned partial-copy rollback.
    @Test
    public void keylessIdentitySupportsImportAndPartialCopyCleanup() throws Exception {
        try (FileSystem fileSystem = Jimfs.newFileSystem(Configuration.unix())) {
            Path root = Files.createDirectories(fileSystem.getPath("/schematics"));
            Path sources = Files.createDirectories(fileSystem.getPath("/sources"));
            Path successful = Files.writeString(
                    sources.resolve("successful.litematic"), "successful");
            FileSystemSchematicMutationIo successfulIo = new FileSystemSchematicMutationIo(
                    root,
                    Files::move,
                    temporary -> {
                    },
                    isolated -> {
                    },
                    FileSystemSchematicMutationIoTest::keylessIdentity);

            successfulIo.importFiles(
                    root, List.of(successful), new LoadCancellation());

            Path failing = Files.writeString(sources.resolve("failing.litematic"), "failing");
            IOException primary = new IOException("partial copy failed");
            FileSystemSchematicMutationIo failingIo = new FileSystemSchematicMutationIo(
                    root,
                    Files::move,
                    temporary -> {
                        Files.writeString(temporary, "partial", StandardOpenOption.WRITE);
                        throw primary;
                    },
                    isolated -> {
                    },
                    FileSystemSchematicMutationIoTest::keylessIdentity);

            IOException observed = assertThrows(
                    IOException.class,
                    () -> failingIo.importFiles(
                            root, List.of(failing), new LoadCancellation()));

            assertAll(
                    () -> assertEquals(
                            "successful", Files.readString(root.resolve("successful.litematic"))),
                    () -> assertSame(primary, observed),
                    () -> assertFalse(Files.exists(root.resolve("failing.litematic"))),
                    () -> assertEquals(0L, temporaryFileCount(root)));
        }
    }

    /// Captures a Windows-style fingerprint while intentionally omitting provider file keys.
    ///
    /// @param attributes current no-follow attributes
    /// @return keyless cross-platform identity
    private static DefaultSchematicBrowserModel.FileIdentity keylessIdentity(
            BasicFileAttributes attributes) {
        return new DefaultSchematicBrowserModel.FileIdentity(
                null,
                attributes.creationTime(),
                attributes.lastModifiedTime(),
                attributes.size(),
                attributes.isDirectory());
    }

    /// Counts private import temporary files while closing the directory stream deterministically.
    ///
    /// @param directory import destination
    /// @return number of retained private temporary files
    private static long temporaryFileCount(Path directory) throws IOException {
        try (Stream<Path> entries = Files.list(directory)) {
            return entries
                    .filter(path -> path.getFileName().toString()
                            .startsWith(".xyml-litematic-import-"))
                    .count();
        }
    }
}
