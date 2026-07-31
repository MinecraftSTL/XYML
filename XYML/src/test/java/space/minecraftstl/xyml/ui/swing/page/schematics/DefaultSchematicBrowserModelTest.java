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
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import space.minecraftstl.xyml.ui.swing.choice.ChoicePage;
import space.minecraftstl.xyml.ui.swing.choice.IndexRange;
import space.minecraftstl.xyml.ui.swing.choice.LoadCancellation;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests shallow discovery, viewport parsing, navigation, cancellation, and closure.
@NotNullByDefault
public final class DefaultSchematicBrowserModelTest {
    /// Construction performs no I/O, and production discovery is shallow, filtered, and stable.
    @Test
    public void constructsWithoutIoAndScansOnlyDirectSupportedChildren() throws Exception {
        try (FileSystem fileSystem = Jimfs.newFileSystem(Configuration.unix())) {
            Path root = fileSystem.getPath("/schematics");
            Files.createDirectories(root.resolve("zeta/nested"));
            Files.createDirectories(root.resolve("Alpha"));
            Files.writeString(root.resolve("zeta/nested/hidden.litematic"), "invalid");
            Files.writeString(root.resolve("beta.litematic"), "invalid");
            Files.writeString(root.resolve("A.LITEMATIC"), "invalid");
            Files.writeString(root.resolve("ignored.txt"), "ignored");
            Files.createSymbolicLink(root.resolve("linked-directory"), root.resolve("zeta"));
            Files.createSymbolicLink(root.resolve("linked-file.litematic"), root.resolve("beta.litematic"));
            QueuedExecutor executor = new QueuedExecutor();
            DefaultSchematicBrowserModel model = new DefaultSchematicBrowserModel(root, executor);

            assertAll(
                    () -> assertEquals(0, executor.pendingCount()),
                    () -> assertTrue(model.exactItemCount().isEmpty()),
                    () -> assertEquals(SchematicBrowserStatus.IDLE, model.snapshot().status()));

            CompletionStage<SchematicBrowserSnapshot> initialScan = model.loadIfNeeded();
            assertAll(
                    () -> assertEquals(1, executor.pendingCount()),
                    () -> assertFalse(initialScan.toCompletableFuture().isDone()),
                    () -> assertEquals(SchematicBrowserStatus.LOADING, model.snapshot().status()));
            executor.runNext();

            assertAll(
                    () -> assertEquals(4, initialScan.toCompletableFuture().join().itemCount().orElseThrow()),
                    () -> assertEquals(1L, model.snapshot().contentRevision()),
                    () -> assertEquals(SchematicBrowserStatus.READY, model.snapshot().status()));

            CompletionStage<ChoicePage<SchematicBrowserItem>> rows = model.load(
                    new IndexRange(0, 20), new LoadCancellation());
            executor.runNext();
            ChoicePage<SchematicBrowserItem> page = rows.toCompletableFuture().join();
            assertAll(
                    () -> assertEquals(new IndexRange(0, 4), page.range()),
                    () -> assertEquals(List.of("Alpha", "zeta", "A.LITEMATIC", "beta.litematic"),
                            page.items().stream().map(SchematicBrowserItem::fileName).toList()),
                    () -> assertInstanceOf(SchematicDirectoryItem.class, page.items().get(0)),
                    () -> assertInstanceOf(SchematicDirectoryItem.class, page.items().get(1)),
                    () -> assertFalse(assertInstanceOf(SchematicFileItem.class, page.items().get(2)).readable()),
                    () -> assertFalse(assertInstanceOf(SchematicFileItem.class, page.items().get(3)).readable()));
            model.close();
        }
    }

    /// Scans hide exact private mutation artifacts while retaining ordinary prefix-sharing directories.
    @Test
    public void hidesOnlyCanonicalPrivateMutationArtifacts() throws Exception {
        try (FileSystem fileSystem = Jimfs.newFileSystem(Configuration.unix())) {
            Path root = Files.createDirectories(fileSystem.getPath("/schematics"));
            Files.createDirectory(root.resolve(
                    ".xyml-delete-11111111-1111-1111-1111-111111111111.tmp"));
            Files.createDirectory(root.resolve(
                    ".xyml-litematic-import-22222222-2222-2222-2222-222222222222.tmp"));
            Path visible = Files.createDirectory(root.resolve(".xyml-delete-not-a-uuid.tmp"));
            QueuedExecutor executor = new QueuedExecutor();
            DefaultSchematicBrowserModel model = new DefaultSchematicBrowserModel(root, executor);
            try {
                CompletionStage<SchematicBrowserSnapshot> scan = model.loadIfNeeded();
                executor.runNext();
                CompletionStage<ChoicePage<SchematicBrowserItem>> rows = model.load(
                        new IndexRange(0, 10), new LoadCancellation());
                executor.runNext();

                assertAll(
                        () -> assertEquals(1, scan.toCompletableFuture().join().itemCount().orElseThrow()),
                        () -> assertEquals(
                                List.of(visible.getFileName().toString()),
                                rows.toCompletableFuture().join().items().stream()
                                        .map(SchematicBrowserItem::fileName)
                                        .toList()));
            } finally {
                model.close();
            }
        }
    }

    /// A missing root is an exact empty schematic collection and is never created as a scan side effect.
    @Test
    public void missingRootIsReadyAndEmptyWithoutCreatingDirectory() throws Exception {
        try (FileSystem fileSystem = Jimfs.newFileSystem(Configuration.unix())) {
            Path root = fileSystem.getPath("/missing/schematics");
            QueuedExecutor executor = new QueuedExecutor();
            DefaultSchematicBrowserModel model = new DefaultSchematicBrowserModel(root, executor);
            try {
                CompletionStage<SchematicBrowserSnapshot> scan = model.loadIfNeeded();
                executor.runNext();

                assertAll(
                        () -> assertEquals(SchematicBrowserStatus.READY, scan.toCompletableFuture().join().status()),
                        () -> assertEquals(0, model.exactItemCount().orElseThrow()),
                        () -> assertFalse(Files.exists(root)),
                        () -> assertFalse(Files.exists(root.getParent())));
            } finally {
                model.close();
            }
        }
    }

    /// Import and child creation safely create a missing root and publish exact reconciled listings.
    @Test
    public void importsAndCreatesInsideAMissingRootWithSerializedBusyState() throws Exception {
        try (FileSystem fileSystem = Jimfs.newFileSystem(Configuration.unix())) {
            Path root = fileSystem.getPath("/missing/schematics");
            Path sources = Files.createDirectories(fileSystem.getPath("/imports"));
            Path first = Files.writeString(sources.resolve("first.litematic"), "first");
            Path second = Files.writeString(sources.resolve("second.LITEMATIC"), "second");
            QueuedExecutor executor = new QueuedExecutor();
            DefaultSchematicBrowserModel model = new DefaultSchematicBrowserModel(root, executor);
            try {
                model.loadIfNeeded();
                executor.runNext();

                CompletionStage<SchematicBrowserSnapshot> imported =
                        model.importFiles(List.of(first, second));
                CompletionStage<SchematicBrowserSnapshot> shared = model.loadIfNeeded();
                CompletionStage<SchematicBrowserSnapshot> rejectedRefresh = model.refresh();
                CompletionStage<SchematicBrowserSnapshot> rejectedNavigation =
                        model.returnToParent();
                CompletionStage<ChoicePage<SchematicBrowserItem>> rejectedRange = model.load(
                        new IndexRange(0, 1), new LoadCancellation());

                assertAll(
                        () -> assertSame(imported, shared),
                        () -> assertEquals(
                                SchematicBrowserWriteStatus.BUSY,
                                model.snapshot().writeStatus()),
                        () -> assertInstanceOf(
                                IllegalStateException.class,
                                failureCause(rejectedRefresh)),
                        () -> assertInstanceOf(
                                IllegalStateException.class,
                                failureCause(rejectedNavigation)),
                        () -> assertInstanceOf(
                                IllegalStateException.class,
                                failureCause(rejectedRange)),
                        () -> assertFalse(Files.exists(root)));

                executor.runNext();
                SchematicBrowserSnapshot importedSnapshot = imported.toCompletableFuture().join();
                assertAll(
                        () -> assertEquals("first", Files.readString(root.resolve("first.litematic"))),
                        () -> assertEquals("second", Files.readString(root.resolve("second.LITEMATIC"))),
                        () -> assertEquals(2, importedSnapshot.itemCount().orElseThrow()),
                        () -> assertEquals(2L, importedSnapshot.contentRevision()),
                        () -> assertEquals(
                                SchematicBrowserWriteStatus.IDLE,
                                importedSnapshot.writeStatus()),
                        () -> assertNull(importedSnapshot.writeFailureMessage()));

                CompletionStage<SchematicBrowserSnapshot> created =
                        model.createDirectory("group");
                executor.runNext();
                SchematicBrowserSnapshot createdSnapshot = created.toCompletableFuture().join();
                assertAll(
                        () -> assertTrue(Files.isDirectory(root.resolve("group"))),
                        () -> assertEquals(3, createdSnapshot.itemCount().orElseThrow()),
                        () -> assertEquals(3L, createdSnapshot.contentRevision()));
            } finally {
                model.close();
            }
        }
    }

    /// Portable validation rejects blank, boundary, separated, padded, and invalid child names.
    @Test
    public void rejectsInvalidDirectoryNamesBeforeSchedulingIo() throws Exception {
        try (FileSystem fileSystem = Jimfs.newFileSystem(Configuration.unix())) {
            Path root = Files.createDirectories(fileSystem.getPath("/schematics"));
            QueuedExecutor executor = new QueuedExecutor();
            DefaultSchematicBrowserModel model = new DefaultSchematicBrowserModel(root, executor);
            try {
                model.loadIfNeeded();
                executor.runNext();

                for (String invalid : List.of(
                        "", " ", ".", "..", " child", "child ", "a/b", "a\\b", "bad\0name")) {
                    CompletionStage<SchematicBrowserSnapshot> result =
                            model.createDirectory(invalid);
                    assertInstanceOf(IllegalArgumentException.class, failureCause(result));
                }

                assertAll(
                        () -> assertEquals(0, executor.pendingCount()),
                        () -> assertEquals(
                                SchematicBrowserWriteStatus.IDLE,
                                model.snapshot().writeStatus()),
                        () -> assertEquals(0, model.snapshot().itemCount().orElseThrow()));
            } finally {
                model.close();
            }
        }
    }

    /// Import preflight checks the complete batch before writing and never overwrites a collision.
    @Test
    public void importConflictFailsBeforeAnyBatchDestinationIsWritten() throws Exception {
        try (FileSystem fileSystem = Jimfs.newFileSystem(Configuration.unix())) {
            Path root = Files.createDirectories(fileSystem.getPath("/schematics"));
            Files.writeString(root.resolve("existing.litematic"), "original");
            Path sources = Files.createDirectories(fileSystem.getPath("/imports"));
            Path newSource = Files.writeString(sources.resolve("new.litematic"), "new");
            Path conflictingSource = Files.writeString(
                    sources.resolve("existing.litematic"), "replacement");
            QueuedExecutor executor = new QueuedExecutor();
            DefaultSchematicBrowserModel model = new DefaultSchematicBrowserModel(root, executor);
            try {
                model.loadIfNeeded();
                executor.runNext();
                long revision = model.snapshot().contentRevision();

                CompletionStage<SchematicBrowserSnapshot> imported =
                        model.importFiles(List.of(newSource, conflictingSource));
                executor.runNext();

                assertAll(
                        () -> assertInstanceOf(FileAlreadyExistsException.class, failureCause(imported)),
                        () -> assertFalse(Files.exists(root.resolve("new.litematic"))),
                        () -> assertEquals(
                                "original", Files.readString(root.resolve("existing.litematic"))),
                        () -> assertEquals(revision, model.snapshot().contentRevision()),
                        () -> assertEquals(
                                SchematicBrowserWriteStatus.ERROR,
                                model.snapshot().writeStatus()),
                        () -> assertNotNull(model.snapshot().writeFailureMessage()));
            } finally {
                model.close();
            }
        }
    }

    /// Imports reject symbolic-link sources and writes reject a linked current-directory chain.
    @Test
    public void writesRejectSourceAndCurrentDirectorySymbolicLinks() throws Exception {
        try (FileSystem fileSystem = Jimfs.newFileSystem(Configuration.unix())) {
            Path root = Files.createDirectories(fileSystem.getPath("/schematics"));
            Path outside = Files.createDirectories(fileSystem.getPath("/outside"));
            Path realSource = Files.writeString(outside.resolve("real.litematic"), "outside");
            Path linkedSource = Files.createSymbolicLink(
                    fileSystem.getPath("/linked.litematic"), realSource);
            QueuedExecutor executor = new QueuedExecutor();
            DefaultSchematicBrowserModel model = new DefaultSchematicBrowserModel(root, executor);
            try {
                model.loadIfNeeded();
                executor.runNext();
                CompletionStage<SchematicBrowserSnapshot> linkedImport =
                        model.importFiles(List.of(linkedSource));
                executor.runNext();
                assertAll(
                        () -> assertInstanceOf(IOException.class, failureCause(linkedImport)),
                        () -> assertFalse(Files.exists(root.resolve("linked.litematic"))));

                CompletionStage<SchematicBrowserSnapshot> recovery = model.refresh();
                executor.runNext();
                recovery.toCompletableFuture().join();
                Files.delete(root);
                Files.createSymbolicLink(root, outside);
                CompletionStage<SchematicBrowserSnapshot> linkedRootWrite =
                        model.createDirectory("escaped");
                executor.runNext();

                assertAll(
                        () -> assertInstanceOf(IOException.class, failureCause(linkedRootWrite)),
                        () -> assertFalse(Files.exists(outside.resolve("escaped"))));
            } finally {
                model.close();
            }
        }
    }

    /// Recursive deletion removes nested content and child links without traversing their targets.
    @Test
    public void recursivelyDeletesKnownDirectoryWithoutFollowingChildLinks() throws Exception {
        try (FileSystem fileSystem = Jimfs.newFileSystem(Configuration.unix())) {
            Path root = Files.createDirectories(fileSystem.getPath("/schematics"));
            Path directory = Files.createDirectories(root.resolve("group/nested"))
                    .getParent();
            Files.writeString(directory.resolve("nested/item.litematic"), "item");
            Path outside = Files.createDirectories(fileSystem.getPath("/outside"));
            Path outsideFile = Files.writeString(outside.resolve("keep.txt"), "keep");
            Files.createSymbolicLink(directory.resolve("outside-link"), outside);
            QueuedExecutor executor = new QueuedExecutor();
            DefaultSchematicBrowserModel model = new DefaultSchematicBrowserModel(root, executor);
            try {
                model.loadIfNeeded();
                executor.runNext();
                CompletionStage<SchematicBrowserSnapshot> deleted = model.delete(directory);
                executor.runNext();

                SchematicBrowserSnapshot snapshot = deleted.toCompletableFuture().join();
                assertAll(
                        () -> assertFalse(Files.exists(directory, LinkOption.NOFOLLOW_LINKS)),
                        () -> assertEquals("keep", Files.readString(outsideFile)),
                        () -> assertEquals(0, snapshot.itemCount().orElseThrow()),
                        () -> assertEquals(
                                SchematicBrowserWriteStatus.IDLE,
                                snapshot.writeStatus()));
            } finally {
                model.close();
            }
        }
    }

    /// Closing after delete isolation cancels the stage but still finishes private tree cleanup.
    @Test
    public void closeAfterDeleteIsolationStillFinishesRecursiveCleanup() throws Exception {
        try (FileSystem fileSystem = Jimfs.newFileSystem(Configuration.unix())) {
            Path root = Files.createDirectories(fileSystem.getPath("/schematics"));
            Path target = Files.createDirectories(root.resolve("group/nested"))
                    .getParent();
            Files.writeString(target.resolve("nested/item.litematic"), "item");
            DeleteIsolationGate isolationGate = new DeleteIsolationGate();
            FileSystemSchematicMutationIo mutationIo = new FileSystemSchematicMutationIo(
                    root,
                    Files::move,
                    temporary -> {
                    },
                    isolationGate);
            AtomicInteger transitions = new AtomicInteger();
            ExecutorService executor = Executors.newSingleThreadExecutor();
            DefaultSchematicBrowserModel model = new DefaultSchematicBrowserModel(
                    root,
                    executor,
                    (directory, cancellation) -> {
                        cancellation.throwIfCancelled();
                        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                            return List.of();
                        }
                        BasicFileAttributes attributes = Files.readAttributes(
                                target,
                                BasicFileAttributes.class,
                                LinkOption.NOFOLLOW_LINKS);
                        return List.of(new DefaultSchematicBrowserModel.DiscoveredEntry(
                                target,
                                target.getFileName().toString(),
                                true,
                                DefaultSchematicBrowserModel.FileIdentity.capture(attributes)));
                    },
                    path -> {
                        throw new IOException("unused metadata");
                    },
                    mutationIo,
                    () -> {
                    });
            try {
                model.loadIfNeeded().toCompletableFuture().join();
                model.subscribe(change -> transitions.incrementAndGet());
                CompletionStage<SchematicBrowserSnapshot> deletion = model.delete(target);
                assertTrue(isolationGate.awaitIsolated());
                Path isolated = isolationGate.isolatedPath();

                assertAll(
                        () -> assertFalse(Files.exists(target, LinkOption.NOFOLLOW_LINKS)),
                        () -> assertTrue(Files.exists(isolated, LinkOption.NOFOLLOW_LINKS)));
                model.close();
                assertThrows(
                        CancellationException.class,
                        () -> deletion.toCompletableFuture().join());

                isolationGate.release();
                CompletableFuture.runAsync(() -> {
                }, executor).join();
                assertAll(
                        () -> assertFalse(Files.exists(target, LinkOption.NOFOLLOW_LINKS)),
                        () -> assertFalse(Files.exists(isolated, LinkOption.NOFOLLOW_LINKS)),
                        () -> assertEquals(1, transitions.get()));
            } finally {
                isolationGate.release();
                model.close();
                executor.shutdownNow();
                assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
            }
        }
    }

    /// Deletion accepts only exact current descriptors and revalidates a known target before removal.
    @Test
    public void deleteRejectsUnknownAndReplacedTargets() throws Exception {
        try (FileSystem fileSystem = Jimfs.newFileSystem(Configuration.unix())) {
            Path root = Files.createDirectories(fileSystem.getPath("/schematics"));
            Path target = Files.writeString(root.resolve("known.litematic"), "known");
            Path outside = Files.writeString(fileSystem.getPath("/outside.litematic"), "outside");
            QueuedExecutor executor = new QueuedExecutor();
            DefaultSchematicBrowserModel model = new DefaultSchematicBrowserModel(root, executor);
            try {
                model.loadIfNeeded();
                executor.runNext();
                CompletionStage<SchematicBrowserSnapshot> unknown =
                        model.delete(root.resolve("unknown.litematic"));
                assertInstanceOf(IllegalArgumentException.class, failureCause(unknown));
                assertEquals(0, executor.pendingCount());

                Files.delete(target);
                Files.createSymbolicLink(target, outside);
                CompletionStage<SchematicBrowserSnapshot> replaced = model.delete(target);
                executor.runNext();

                assertAll(
                        () -> assertInstanceOf(IOException.class, failureCause(replaced)),
                        () -> assertTrue(Files.isSymbolicLink(target)),
                        () -> assertEquals("outside", Files.readString(outside)));
            } finally {
                model.close();
            }
        }
    }

    /// Deletion rejects a same-type replacement whose file-system identity differs from the scan.
    @Test
    public void deleteRejectsARegularFileReplacedAfterScanning() throws Exception {
        try (FileSystem fileSystem = Jimfs.newFileSystem(Configuration.unix())) {
            Path root = Files.createDirectories(fileSystem.getPath("/schematics"));
            Path target = Files.writeString(root.resolve("known.litematic"), "original");
            QueuedExecutor executor = new QueuedExecutor();
            DefaultSchematicBrowserModel model = new DefaultSchematicBrowserModel(root, executor);
            try {
                model.loadIfNeeded();
                executor.runNext();
                Files.delete(target);
                Files.writeString(target, "replacement");

                CompletionStage<SchematicBrowserSnapshot> deletion = model.delete(target);
                executor.runNext();

                assertAll(
                        () -> assertInstanceOf(IOException.class, failureCause(deletion)),
                        () -> assertEquals("replacement", Files.readString(target)),
                        () -> assertEquals(
                                SchematicBrowserWriteStatus.ERROR,
                                model.snapshot().writeStatus()));
            } finally {
                model.close();
            }
        }
    }

    /// A root that exists as a regular file remains a scan error rather than an empty collection.
    @Test
    public void nonDirectoryRootRemainsAnError() throws Exception {
        try (FileSystem fileSystem = Jimfs.newFileSystem(Configuration.unix())) {
            Path root = Files.writeString(fileSystem.getPath("/schematics"), "not a directory");
            QueuedExecutor executor = new QueuedExecutor();
            DefaultSchematicBrowserModel model = new DefaultSchematicBrowserModel(root, executor);
            try {
                CompletionStage<SchematicBrowserSnapshot> scan = model.loadIfNeeded();
                executor.runNext();

                CompletionException failure = assertThrows(
                        CompletionException.class,
                        () -> scan.toCompletableFuture().join());
                assertAll(
                        () -> assertInstanceOf(IOException.class, failure.getCause()),
                        () -> assertEquals(SchematicBrowserStatus.ERROR, model.snapshot().status()),
                        () -> assertEquals(root.toAbsolutePath().normalize(), model.snapshot().currentDirectory()));
            } finally {
                model.close();
            }
        }
    }

    /// A symbolic-link root remains rejected and never exposes content outside the configured boundary.
    @Test
    public void symbolicLinkRootRemainsAnError() throws Exception {
        try (FileSystem fileSystem = Jimfs.newFileSystem(Configuration.unix())) {
            Path target = Files.createDirectories(fileSystem.getPath("/outside"));
            Path root = Files.createSymbolicLink(fileSystem.getPath("/schematics"), target);
            QueuedExecutor executor = new QueuedExecutor();
            DefaultSchematicBrowserModel model = new DefaultSchematicBrowserModel(root, executor);
            try {
                CompletionStage<SchematicBrowserSnapshot> scan = model.loadIfNeeded();
                executor.runNext();

                CompletionException failure = assertThrows(
                        CompletionException.class,
                        () -> scan.toCompletableFuture().join());
                assertAll(
                        () -> assertInstanceOf(IOException.class, failure.getCause()),
                        () -> assertEquals(SchematicBrowserStatus.ERROR, model.snapshot().status()));
            } finally {
                model.close();
            }
        }
    }

    /// A known child removed before navigation remains an error instead of inheriting root-empty behavior.
    @Test
    public void missingChildDirectoryRemainsAnError() throws Exception {
        try (FileSystem fileSystem = Jimfs.newFileSystem(Configuration.unix())) {
            Path root = fileSystem.getPath("/schematics");
            Path child = Files.createDirectories(root.resolve("child"));
            QueuedExecutor executor = new QueuedExecutor();
            DefaultSchematicBrowserModel model = new DefaultSchematicBrowserModel(root, executor);
            try {
                model.loadIfNeeded();
                executor.runNext();
                Files.delete(child);

                CompletionStage<SchematicBrowserSnapshot> navigation = model.openDirectory(child);
                executor.runNext();
                CompletionException failure = assertThrows(
                        CompletionException.class,
                        () -> navigation.toCompletableFuture().join());

                assertAll(
                        () -> assertInstanceOf(IOException.class, failure.getCause()),
                        () -> assertEquals(SchematicBrowserStatus.ERROR, model.snapshot().status()),
                        () -> assertEquals(root.toAbsolutePath().normalize(), model.snapshot().currentDirectory()));
            } finally {
                model.close();
            }
        }
    }

    /// Metadata parsing touches exactly the requested file indexes and preserves unreadable rows.
    @Test
    public void parsesOnlyFilesInsideTheRequestedRange() {
        Path root = Path.of("schematics").toAbsolutePath().normalize();
        QueuedExecutor executor = new QueuedExecutor();
        List<Path> parsedPaths = new ArrayList<>();
        List<DefaultSchematicBrowserModel.DiscoveredEntry> entries = List.of(
                entry(root.resolve("folder"), true),
                entry(root.resolve("first.litematic"), false),
                entry(root.resolve("second.litematic"), false));
        DefaultSchematicBrowserModel model = new DefaultSchematicBrowserModel(
                root,
                executor,
                (ignored, cancellation) -> entries,
                path -> {
                    parsedPaths.add(path);
                    throw new IOException("bad metadata: " + path.getFileName());
                });

        model.loadIfNeeded();
        executor.runNext();
        CompletionStage<ChoicePage<SchematicBrowserItem>> firstFile = model.load(
                new IndexRange(1, 2), new LoadCancellation());
        assertEquals(List.of(), parsedPaths);
        executor.runNext();
        SchematicFileItem unreadable = assertInstanceOf(
                SchematicFileItem.class,
                firstFile.toCompletableFuture().join().items().get(0));

        CompletionStage<ChoicePage<SchematicBrowserItem>> emptyTail = model.load(
                new IndexRange(20, 30), new LoadCancellation());
        executor.runNext();

        assertAll(
                () -> assertEquals(List.of(root.resolve("first.litematic")), parsedPaths),
                () -> assertFalse(unreadable.readable()),
                () -> assertNull(unreadable.metadata()),
                () -> assertNotNull(unreadable.failureMessage()),
                () -> assertEquals(new IndexRange(3, 3), emptyTail.toCompletableFuture().join().range()),
                () -> assertEquals(3, model.exactItemCount().orElseThrow()));
        model.close();
    }

    /// Lazy metadata loading rejects a scanned file replaced by a symbolic link before parsing.
    @Test
    public void rejectsAFileReplacedByASymbolicLinkBeforeLazyLoad() throws Exception {
        try (FileSystem fileSystem = Jimfs.newFileSystem(Configuration.unix())) {
            Path root = Files.createDirectories(fileSystem.getPath("/schematics"));
            Path file = Files.writeString(root.resolve("replace.litematic"), "original");
            Path outside = Files.writeString(fileSystem.getPath("/outside.litematic"), "outside");
            QueuedExecutor executor = new QueuedExecutor();
            DefaultSchematicBrowserModel model = new DefaultSchematicBrowserModel(root, executor);
            try {
                model.loadIfNeeded();
                executor.runNext();
                Files.delete(file);
                Files.createSymbolicLink(file, outside);

                CompletionStage<ChoicePage<SchematicBrowserItem>> load = model.load(
                        new IndexRange(0, 1), new LoadCancellation());
                executor.runNext();
                SchematicFileItem item = assertInstanceOf(
                        SchematicFileItem.class,
                        load.toCompletableFuture().join().items().get(0));

                assertAll(
                        () -> assertFalse(item.readable()),
                        () -> assertTrue(Objects.requireNonNull(item.failureMessage())
                                .contains("not a real Litematic file")));
            } finally {
                model.close();
            }
        }
    }

    /// A caller cancellation stops queued range parsing before metadata I/O begins.
    @Test
    public void cancelsAViewportRequestBeforeItParses() {
        Path root = Path.of("schematics").toAbsolutePath().normalize();
        QueuedExecutor executor = new QueuedExecutor();
        AtomicInteger parseCalls = new AtomicInteger();
        DefaultSchematicBrowserModel model = new DefaultSchematicBrowserModel(
                root,
                executor,
                (ignored, cancellation) -> List.of(entry(root.resolve("one.litematic"), false)),
                path -> {
                    parseCalls.incrementAndGet();
                    throw new IOException("unreadable");
                });
        model.loadIfNeeded();
        executor.runNext();
        LoadCancellation cancellation = new LoadCancellation();
        CompletionStage<ChoicePage<SchematicBrowserItem>> load = model.load(
                new IndexRange(0, 1), cancellation);

        cancellation.cancel();
        executor.runNext();

        assertAll(
                () -> assertEquals(0, parseCalls.get()),
                () -> assertThrows(CancellationException.class, () -> load.toCompletableFuture().join()));
        model.close();
    }

    /// Starting a write cancels captured viewport work and rejects a second concurrent write.
    @Test
    public void writeCancelsOldRangeAndRejectsConcurrentWrite() throws Exception {
        try (FileSystem fileSystem = Jimfs.newFileSystem(Configuration.unix())) {
            Path root = Files.createDirectories(fileSystem.getPath("/schematics"));
            Files.writeString(root.resolve("one.litematic"), "one");
            QueuedExecutor executor = new QueuedExecutor();
            DefaultSchematicBrowserModel model = new DefaultSchematicBrowserModel(root, executor);
            try {
                model.loadIfNeeded();
                executor.runNext();
                CompletionStage<ChoicePage<SchematicBrowserItem>> oldRange = model.load(
                        new IndexRange(0, 1), new LoadCancellation());

                CompletionStage<SchematicBrowserSnapshot> firstWrite =
                        model.createDirectory("first");
                CompletionStage<SchematicBrowserSnapshot> secondWrite =
                        model.createDirectory("second");

                assertAll(
                        () -> assertThrows(
                                CancellationException.class,
                                () -> oldRange.toCompletableFuture().join()),
                        () -> assertInstanceOf(
                                IllegalStateException.class,
                                failureCause(secondWrite)),
                        () -> assertEquals(2, executor.pendingCount()));

                executor.runNext();
                executor.runNext();
                assertAll(
                        () -> assertTrue(Files.isDirectory(root.resolve("first"))),
                        () -> assertFalse(Files.exists(root.resolve("second"))),
                        () -> assertEquals(
                                SchematicBrowserWriteStatus.IDLE,
                                firstWrite.toCompletableFuture().join().writeStatus()));
            } finally {
                model.close();
            }
        }
    }

    /// Executor rejection preserves failure identity and publishes an independent write error.
    @Test
    public void executorRejectionFailsTheWriteAndReleasesItsSlot() throws Exception {
        try (FileSystem fileSystem = Jimfs.newFileSystem(Configuration.unix())) {
            Path root = Files.createDirectories(fileSystem.getPath("/schematics"));
            QueuedExecutor executor = new QueuedExecutor();
            DefaultSchematicBrowserModel model = new DefaultSchematicBrowserModel(root, executor);
            try {
                model.loadIfNeeded();
                executor.runNext();
                RejectedExecutionException rejection =
                        new RejectedExecutionException("write rejected");
                executor.rejectNewTasks(rejection);

                CompletionStage<SchematicBrowserSnapshot> write =
                        model.createDirectory("never-created");
                CompletionStage<SchematicBrowserSnapshot> secondWrite =
                        model.createDirectory("also-never-created");

                assertAll(
                        () -> assertSame(rejection, failureCause(write)),
                        () -> assertSame(rejection, failureCause(secondWrite)),
                        () -> assertEquals(
                                SchematicBrowserWriteStatus.ERROR,
                                model.snapshot().writeStatus()),
                        () -> assertEquals("write rejected", model.snapshot().writeFailureMessage()),
                        () -> assertFalse(Files.exists(root.resolve("never-created"))));
            } finally {
                model.close();
            }
        }
    }

    /// Mutation failures retain exact identity, suppressed diagnostics, and the stable listing.
    @Test
    public void mutationFailurePreservesIdentityAndSuppressedFailures() {
        Path root = Path.of("schematics").toAbsolutePath().normalize();
        QueuedExecutor executor = new QueuedExecutor();
        IOException primary = new IOException("primary write failure");
        IOException cleanup = new IOException("cleanup failure");
        primary.addSuppressed(cleanup);
        ControlledMutationIo mutationIo = new ControlledMutationIo(
                (directory, name, cancellation) -> {
                    throw primary;
                });
        DefaultSchematicBrowserModel model = new DefaultSchematicBrowserModel(
                root,
                executor,
                (directory, cancellation) -> List.of(),
                path -> {
                    throw new IOException("unused metadata");
                },
                mutationIo,
                () -> {
                });
        try {
            model.loadIfNeeded();
            executor.runNext();
            long revision = model.snapshot().contentRevision();

            CompletionStage<SchematicBrowserSnapshot> write =
                    model.createDirectory("failure");
            executor.runNext();

            Throwable observed = failureCause(write);
            assertAll(
                    () -> assertSame(primary, observed),
                    () -> assertEquals(1, observed.getSuppressed().length),
                    () -> assertSame(cleanup, observed.getSuppressed()[0]),
                    () -> assertEquals(revision, model.snapshot().contentRevision()),
                    () -> assertEquals(0, model.snapshot().itemCount().orElseThrow()),
                    () -> assertEquals(
                            SchematicBrowserWriteStatus.ERROR,
                            model.snapshot().writeStatus()));
        } finally {
            model.close();
        }
    }

    /// Close cancels a blocked write promptly and prevents its late return or rescan from publishing.
    @Test
    public void closeCancelsBlockedWriteAndDropsItsLateResult() throws Exception {
        Path root = Path.of("schematics").toAbsolutePath().normalize();
        BlockingMutationIo mutationIo = new BlockingMutationIo();
        AtomicInteger scans = new AtomicInteger();
        AtomicInteger transitions = new AtomicInteger();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        DefaultSchematicBrowserModel model = new DefaultSchematicBrowserModel(
                root,
                executor,
                (directory, cancellation) -> {
                    scans.incrementAndGet();
                    return List.of();
                },
                path -> {
                    throw new IOException("unused metadata");
                },
                mutationIo,
                () -> {
                });
        try {
            model.loadIfNeeded().toCompletableFuture().join();
            model.subscribe(change -> transitions.incrementAndGet());
            CompletionStage<SchematicBrowserSnapshot> write =
                    model.createDirectory("blocked");
            assertTrue(mutationIo.awaitStarted());

            model.close();

            assertThrows(CancellationException.class, () -> write.toCompletableFuture().join());
            mutationIo.release();
        } finally {
            mutationIo.release();
            model.close();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
        assertAll(
                () -> assertEquals(1, scans.get()),
                () -> assertEquals(1, transitions.get()));
    }

    /// Close wins after state commit but before terminal write completion and listener publication.
    @Test
    public void closeCancelsACommittedWriteBeforeItsTerminalFutureCompletes() throws Exception {
        Path root = Path.of("schematics").toAbsolutePath().normalize();
        CompletionGate completionGate = new CompletionGate();
        AtomicInteger transitions = new AtomicInteger();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        DefaultSchematicBrowserModel model = new DefaultSchematicBrowserModel(
                root,
                executor,
                (directory, cancellation) -> List.of(),
                path -> {
                    throw new IOException("unused metadata");
                },
                new ControlledMutationIo((directory, name, cancellation) -> {
                }),
                completionGate);
        try {
            model.loadIfNeeded().toCompletableFuture().join();
            model.subscribe(change -> transitions.incrementAndGet());
            completionGate.arm();
            CompletionStage<SchematicBrowserSnapshot> write =
                    model.createDirectory("committed");
            assertTrue(completionGate.awaitEntered());

            model.close();

            assertAll(
                    () -> assertThrows(
                            CancellationException.class,
                            () -> write.toCompletableFuture().join()),
                    () -> assertThrows(
                            IllegalStateException.class,
                            () -> model.createDirectory("after-close")));
            completionGate.release();
        } finally {
            completionGate.release();
            model.close();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
        assertEquals(1, transitions.get());
    }

    /// Cancelling the exposed future cannot release the internal slot while mutation I/O is active.
    @Test
    public void externalFutureCancellationDoesNotReleaseActiveWrite() throws Exception {
        Path root = Path.of("schematics").toAbsolutePath().normalize();
        BlockingMutationIo mutationIo = new BlockingMutationIo();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        DefaultSchematicBrowserModel model = new DefaultSchematicBrowserModel(
                root,
                executor,
                (directory, cancellation) -> List.of(),
                path -> {
                    throw new IOException("unused metadata");
                },
                mutationIo,
                () -> {
                });
        try {
            model.loadIfNeeded().toCompletableFuture().join();
            CompletionStage<SchematicBrowserSnapshot> write =
                    model.createDirectory("blocked");
            assertTrue(mutationIo.awaitStarted());

            assertTrue(write.toCompletableFuture().cancel(false));
            CompletionStage<SchematicBrowserSnapshot> second =
                    model.createDirectory("must-wait");
            CompletionStage<SchematicBrowserSnapshot> refresh = model.refresh();

            assertAll(
                    () -> assertInstanceOf(IllegalStateException.class, failureCause(second)),
                    () -> assertInstanceOf(IllegalStateException.class, failureCause(refresh)),
                    () -> assertEquals(
                            SchematicBrowserWriteStatus.BUSY,
                            model.snapshot().writeStatus()));

            mutationIo.release();
            CompletableFuture.runAsync(() -> {
            }, executor).join();
            assertEquals(SchematicBrowserWriteStatus.IDLE, model.snapshot().writeStatus());
        } finally {
            mutationIo.release();
            model.close();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    /// A synchronous success callback observes the terminal slot as free and may start another write.
    @Test
    public void successFutureCallbackMayStartTheNextWrite() throws Exception {
        try (FileSystem fileSystem = Jimfs.newFileSystem(Configuration.unix())) {
            Path root = Files.createDirectories(fileSystem.getPath("/schematics"));
            QueuedExecutor executor = new QueuedExecutor();
            DefaultSchematicBrowserModel model = new DefaultSchematicBrowserModel(root, executor);
            AtomicReference<@Nullable CompletionStage<SchematicBrowserSnapshot>> second =
                    new AtomicReference<>();
            try {
                model.loadIfNeeded();
                executor.runNext();
                CompletionStage<SchematicBrowserSnapshot> first =
                        model.createDirectory("first");
                first.whenComplete((
                        @Nullable SchematicBrowserSnapshot snapshot,
                        @Nullable Throwable failure) ->
                        second.set(model.createDirectory("second")));

                executor.runNext();

                CompletionStage<SchematicBrowserSnapshot> secondWrite =
                        Objects.requireNonNull(second.get());
                assertAll(
                        () -> assertEquals(
                                SchematicBrowserWriteStatus.BUSY,
                                model.snapshot().writeStatus()),
                        () -> assertEquals(1, executor.pendingCount()));
                executor.runNext();
                secondWrite.toCompletableFuture().join();
                assertAll(
                        () -> assertTrue(Files.isDirectory(root.resolve("first"))),
                        () -> assertTrue(Files.isDirectory(root.resolve("second"))));
            } finally {
                model.close();
            }
        }
    }

    /// A synchronous failure callback may schedule refresh after the original identity is preserved.
    @Test
    public void failureFutureCallbackMayStartRefresh() {
        Path root = Path.of("schematics").toAbsolutePath().normalize();
        QueuedExecutor executor = new QueuedExecutor();
        IOException primary = new IOException("write failed");
        AtomicReference<@Nullable CompletionStage<SchematicBrowserSnapshot>> refresh =
                new AtomicReference<>();
        DefaultSchematicBrowserModel model = new DefaultSchematicBrowserModel(
                root,
                executor,
                (directory, cancellation) -> List.of(),
                path -> {
                    throw new IOException("unused metadata");
                },
                new ControlledMutationIo((directory, name, cancellation) -> {
                    throw primary;
                }),
                () -> {
                });
        try {
            model.loadIfNeeded();
            executor.runNext();
            CompletionStage<SchematicBrowserSnapshot> write =
                    model.createDirectory("failure");
            write.whenComplete((
                    @Nullable SchematicBrowserSnapshot snapshot,
                    @Nullable Throwable failure) -> refresh.set(model.refresh()));

            executor.runNext();

            assertAll(
                    () -> assertSame(primary, failureCause(write)),
                    () -> assertEquals(1, executor.pendingCount()));
            executor.runNext();
            assertEquals(
                    SchematicBrowserStatus.READY,
                    Objects.requireNonNull(refresh.get()).toCompletableFuture().join().status());
        } finally {
            model.close();
        }
    }

    /// A terminal write listener may start the next write without locks or stale busy state.
    @Test
    public void listenerMayStartNextWriteFromCommittedIdleState() throws Exception {
        try (FileSystem fileSystem = Jimfs.newFileSystem(Configuration.unix())) {
            Path root = Files.createDirectories(fileSystem.getPath("/schematics"));
            QueuedExecutor executor = new QueuedExecutor();
            DefaultSchematicBrowserModel model = new DefaultSchematicBrowserModel(root, executor);
            AtomicReference<@Nullable CompletionStage<SchematicBrowserSnapshot>> second =
                    new AtomicReference<>();
            AtomicReference<@Nullable CompletionStage<SchematicBrowserSnapshot>> busyRefresh =
                    new AtomicReference<>();
            AtomicBoolean startSecond = new AtomicBoolean(true);
            try {
                model.loadIfNeeded();
                executor.runNext();
                model.subscribe(change -> {
                    SchematicBrowserSnapshot snapshot = change.currentValue();
                    if (snapshot.writeStatus() == SchematicBrowserWriteStatus.BUSY) {
                        busyRefresh.compareAndSet(null, model.refresh());
                    } else if (snapshot.writeStatus() == SchematicBrowserWriteStatus.IDLE
                            && snapshot.contentRevision() == 2L
                            && startSecond.compareAndSet(true, false)) {
                        second.set(model.createDirectory("second"));
                    }
                });

                CompletionStage<SchematicBrowserSnapshot> first =
                        model.createDirectory("first");
                executor.runNext();
                first.toCompletableFuture().join();

                CompletionStage<SchematicBrowserSnapshot> secondWrite =
                        Objects.requireNonNull(second.get());
                assertAll(
                        () -> assertInstanceOf(
                                IllegalStateException.class,
                                failureCause(Objects.requireNonNull(busyRefresh.get()))),
                        () -> assertEquals(
                                SchematicBrowserWriteStatus.BUSY,
                                model.snapshot().writeStatus()),
                        () -> assertEquals(1, executor.pendingCount()));

                executor.runNext();
                SchematicBrowserSnapshot finalSnapshot =
                        secondWrite.toCompletableFuture().join();
                assertAll(
                        () -> assertTrue(Files.isDirectory(root.resolve("first"))),
                        () -> assertTrue(Files.isDirectory(root.resolve("second"))),
                        () -> assertEquals(2, finalSnapshot.itemCount().orElseThrow()),
                        () -> assertEquals(3L, finalSnapshot.contentRevision()),
                        () -> assertEquals(
                                SchematicBrowserWriteStatus.IDLE,
                                finalSnapshot.writeStatus()));
            } finally {
                model.close();
            }
        }
    }

    /// Refresh cancellation wins while an old range is between validation and terminal completion.
    @Test
    public void refreshCancelsARangeBeforeItsOldSuccessCompletes() throws Exception {
        Path root = Path.of("schematics").toAbsolutePath().normalize();
        CompletionGate completionGate = new CompletionGate();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        DefaultSchematicBrowserModel model = new DefaultSchematicBrowserModel(
                root,
                executor,
                (ignored, cancellation) -> List.of(entry(root.resolve("folder"), true)),
                path -> {
                    throw new IOException("unused metadata");
                },
                completionGate);
        try {
            model.loadIfNeeded().toCompletableFuture().join();
            completionGate.arm();
            CompletionStage<ChoicePage<SchematicBrowserItem>> oldRange = model.load(
                    new IndexRange(0, 1), new LoadCancellation());
            assertTrue(completionGate.awaitEntered());

            CompletionStage<SchematicBrowserSnapshot> refresh = model.refresh();

            assertThrows(CancellationException.class, () -> oldRange.toCompletableFuture().join());
            completionGate.release();
            assertEquals(SchematicBrowserStatus.READY, refresh.toCompletableFuture().join().status());
        } finally {
            completionGate.release();
            model.close();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    /// Close cancellation wins while a committed scan is waiting to complete its public future.
    @Test
    public void closeCancelsAScanBeforeItsOldSuccessCompletes() throws Exception {
        Path root = Path.of("schematics").toAbsolutePath().normalize();
        CompletionGate completionGate = new CompletionGate();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        DefaultSchematicBrowserModel model = new DefaultSchematicBrowserModel(
                root,
                executor,
                (ignored, cancellation) -> List.of(),
                path -> {
                    throw new IOException("unused metadata");
                },
                completionGate);
        completionGate.arm();
        CompletionStage<SchematicBrowserSnapshot> oldScan = model.loadIfNeeded();
        try {
            assertTrue(completionGate.awaitEntered());

            model.close();

            assertThrows(CancellationException.class, () -> oldScan.toCompletableFuture().join());
        } finally {
            completionGate.release();
            model.close();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    /// Refresh cancellation wins while loadIfNeeded is completing an already captured snapshot.
    @Test
    public void refreshCancelsAnOldNoOpSnapshotCompletion() throws Exception {
        Path root = Path.of("schematics").toAbsolutePath().normalize();
        CompletionGate completionGate = new CompletionGate();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        DefaultSchematicBrowserModel model = new DefaultSchematicBrowserModel(
                root,
                executor,
                (ignored, cancellation) -> List.of(),
                path -> {
                    throw new IOException("unused metadata");
                },
                completionGate);
        try {
            model.loadIfNeeded().toCompletableFuture().join();
            completionGate.arm();
            CompletionStage<SchematicBrowserSnapshot> oldSnapshot = model.loadIfNeeded();
            assertTrue(completionGate.awaitEntered());

            CompletionStage<SchematicBrowserSnapshot> refresh = model.refresh();

            assertThrows(CancellationException.class, () -> oldSnapshot.toCompletableFuture().join());
            completionGate.release();
            assertEquals(SchematicBrowserStatus.READY, refresh.toCompletableFuture().join().status());
        } finally {
            completionGate.release();
            model.close();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    /// Close cancellation wins while root parent navigation is completing its unchanged snapshot.
    @Test
    public void closeCancelsAnOldRootNoOpSnapshotCompletion() throws Exception {
        Path root = Path.of("schematics").toAbsolutePath().normalize();
        CompletionGate completionGate = new CompletionGate();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        DefaultSchematicBrowserModel model = new DefaultSchematicBrowserModel(
                root,
                executor,
                (ignored, cancellation) -> List.of(),
                path -> {
                    throw new IOException("unused metadata");
                },
                completionGate);
        try {
            model.loadIfNeeded().toCompletableFuture().join();
            completionGate.arm();
            CompletionStage<SchematicBrowserSnapshot> oldSnapshot = model.returnToParent();
            assertTrue(completionGate.awaitEntered());

            model.close();

            assertThrows(CancellationException.class, () -> oldSnapshot.toCompletableFuture().join());
        } finally {
            completionGate.release();
            model.close();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    /// A concurrently finishing old scan cannot overwrite the latest refresh generation.
    @Test
    public void dropsAConcurrentOldScanResult() throws Exception {
        Path root = Path.of("schematics").toAbsolutePath().normalize();
        BlockingDirectoryReader reader = new BlockingDirectoryReader(root);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        DefaultSchematicBrowserModel model = new DefaultSchematicBrowserModel(
                root,
                executor,
                reader,
                path -> {
                    throw new IOException("unused metadata");
                });
        try {
            CompletionStage<SchematicBrowserSnapshot> oldScan = model.loadIfNeeded();
            assertTrue(reader.awaitFirstStart());
            CompletionStage<SchematicBrowserSnapshot> latestScan = model.refresh();

            SchematicBrowserSnapshot latest = latestScan.toCompletableFuture().join();
            reader.releaseFirst();
            assertTrue(reader.awaitFirstReturn());

            assertAll(
                    () -> assertThrows(CancellationException.class, () -> oldScan.toCompletableFuture().join()),
                    () -> assertEquals(1, latest.itemCount().orElseThrow()),
                    () -> assertEquals(1L, latest.contentRevision()),
                    () -> assertEquals(SchematicBrowserStatus.READY, model.snapshot().status()));

            CompletionStage<ChoicePage<SchematicBrowserItem>> currentRows = model.load(
                    new IndexRange(0, 1), new LoadCancellation());
            SchematicFileItem current = assertInstanceOf(
                    SchematicFileItem.class,
                    currentRows.toCompletableFuture().join().items().get(0));
            assertEquals("latest.litematic", current.fileName());
        } finally {
            reader.releaseFirst();
            model.close();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    /// Navigation accepts only known children, returns to root, and schedules root no-ops.
    @Test
    public void navigatesKnownDirectoriesWithoutCrossingTheRoot() throws Exception {
        try (FileSystem fileSystem = Jimfs.newFileSystem(Configuration.unix())) {
            Path root = fileSystem.getPath("/schematics");
            Path child = Files.createDirectories(root.resolve("child/grandchild")).getParent();
            QueuedExecutor executor = new QueuedExecutor();
            DefaultSchematicBrowserModel model = new DefaultSchematicBrowserModel(root, executor);
            model.loadIfNeeded();
            executor.runNext();

            CompletionStage<SchematicBrowserSnapshot> unknown = model.openDirectory(root.resolve("unknown"));
            assertFalse(unknown.toCompletableFuture().isDone());
            executor.runNext();
            CompletionException unknownFailure = assertThrows(
                    CompletionException.class, () -> unknown.toCompletableFuture().join());
            assertInstanceOf(IllegalArgumentException.class, unknownFailure.getCause());

            CompletionStage<SchematicBrowserSnapshot> opened = model.openDirectory(child);
            executor.runNext();
            assertAll(
                    () -> assertEquals(child.toAbsolutePath().normalize(),
                            opened.toCompletableFuture().join().currentDirectory()),
                    () -> assertTrue(model.snapshot().canReturnToParent()),
                    () -> assertEquals(1, model.exactItemCount().orElseThrow()));

            CompletionStage<SchematicBrowserSnapshot> returned = model.returnToParent();
            executor.runNext();
            long rootRevision = returned.toCompletableFuture().join().contentRevision();
            assertAll(
                    () -> assertEquals(root.toAbsolutePath().normalize(), model.snapshot().currentDirectory()),
                    () -> assertFalse(model.snapshot().canReturnToParent()));

            CompletionStage<SchematicBrowserSnapshot> rootNoOp = model.returnToParent();
            assertFalse(rootNoOp.toCompletableFuture().isDone());
            executor.runNext();
            assertEquals(rootRevision, rootNoOp.toCompletableFuture().join().contentRevision());
            model.close();
        }
    }

    /// Root parent navigation during initial loading shares the active scan instead of returning LOADING.
    @Test
    public void rootParentNavigationSharesTheActiveScanCompletion() {
        Path root = Path.of("schematics").toAbsolutePath().normalize();
        QueuedExecutor executor = new QueuedExecutor();
        DefaultSchematicBrowserModel model = new DefaultSchematicBrowserModel(
                root,
                executor,
                (ignored, cancellation) -> List.of(),
                path -> {
                    throw new IOException("unused metadata");
                });
        try {
            CompletionStage<SchematicBrowserSnapshot> initialScan = model.loadIfNeeded();
            CompletionStage<SchematicBrowserSnapshot> rootReturn = model.returnToParent();

            assertSame(initialScan, rootReturn);
            assertFalse(rootReturn.toCompletableFuture().isDone());
            executor.runNext();
            assertEquals(SchematicBrowserStatus.READY, rootReturn.toCompletableFuture().join().status());
        } finally {
            model.close();
        }
    }

    /// Root parent navigation supersedes a pending child scan instead of sharing its destination.
    @Test
    public void rootParentNavigationCancelsAPendingChildScan() throws Exception {
        try (FileSystem fileSystem = Jimfs.newFileSystem(Configuration.unix())) {
            Path root = fileSystem.getPath("/schematics");
            Path child = Files.createDirectories(root.resolve("child"));
            QueuedExecutor executor = new QueuedExecutor();
            DefaultSchematicBrowserModel model = new DefaultSchematicBrowserModel(root, executor);
            try {
                model.loadIfNeeded();
                executor.runNext();
                CompletionStage<SchematicBrowserSnapshot> childScan = model.openDirectory(child);

                CompletionStage<SchematicBrowserSnapshot> rootReturn = model.returnToParent();

                assertThrows(CancellationException.class, () -> childScan.toCompletableFuture().join());
                executor.runNext();
                executor.runNext();
                assertAll(
                        () -> assertEquals(root.toAbsolutePath().normalize(),
                                rootReturn.toCompletableFuture().join().currentDirectory()),
                        () -> assertFalse(model.snapshot().canReturnToParent()));
            } finally {
                model.close();
            }
        }
    }

    /// Refresh rejects a current path whose intermediate descendant was replaced by an outside link.
    @Test
    public void rejectsAnIntermediateSymbolicLinkAfterNavigation() throws Exception {
        try (FileSystem fileSystem = Jimfs.newFileSystem(Configuration.unix())) {
            Path root = fileSystem.getPath("/schematics");
            Path parent = Files.createDirectories(root.resolve("parent/grandchild")).getParent();
            Path grandchild = parent.resolve("grandchild");
            Path outsideGrandchild = Files.createDirectories(fileSystem.getPath("/outside/grandchild"));
            Files.writeString(outsideGrandchild.resolve("external.litematic"), "invalid");
            QueuedExecutor executor = new QueuedExecutor();
            DefaultSchematicBrowserModel model = new DefaultSchematicBrowserModel(root, executor);
            try {
                model.loadIfNeeded();
                executor.runNext();
                CompletionStage<SchematicBrowserSnapshot> openedParent = model.openDirectory(parent);
                executor.runNext();
                openedParent.toCompletableFuture().join();
                CompletionStage<SchematicBrowserSnapshot> openedGrandchild = model.openDirectory(grandchild);
                executor.runNext();
                openedGrandchild.toCompletableFuture().join();

                Files.delete(grandchild);
                Files.delete(parent);
                Files.createSymbolicLink(parent, fileSystem.getPath("/outside"));
                CompletionStage<SchematicBrowserSnapshot> refresh = model.refresh();
                executor.runNext();

                CompletionException failure = assertThrows(
                        CompletionException.class, () -> refresh.toCompletableFuture().join());
                assertAll(
                        () -> assertInstanceOf(IOException.class, failure.getCause()),
                        () -> assertEquals(SchematicBrowserStatus.ERROR, model.snapshot().status()),
                        () -> assertEquals(grandchild.toAbsolutePath().normalize(),
                                model.snapshot().currentDirectory()),
                        () -> assertEquals(0, model.exactItemCount().orElseThrow()));
            } finally {
                model.close();
            }
        }
    }

    /// Closing cancels queued scans and ranges, ignores queued work, and rejects new commands.
    @Test
    public void closeCancelsQueuedWorkAndRejectsNewCommands() {
        Path root = Path.of("schematics").toAbsolutePath().normalize();
        QueuedExecutor executor = new QueuedExecutor();
        AtomicInteger scanCalls = new AtomicInteger();
        DefaultSchematicBrowserModel model = new DefaultSchematicBrowserModel(
                root,
                executor,
                (ignored, cancellation) -> {
                    scanCalls.incrementAndGet();
                    return List.of();
                },
                path -> {
                    throw new IOException("unused metadata");
                });
        CompletionStage<SchematicBrowserSnapshot> scan = model.loadIfNeeded();

        model.close();
        model.close();
        executor.runNext();

        assertAll(
                () -> assertEquals(0, scanCalls.get()),
                () -> assertThrows(CancellationException.class, () -> scan.toCompletableFuture().join()),
                () -> assertThrows(IllegalStateException.class, model::refresh),
                () -> assertThrows(IllegalStateException.class, model::loadIfNeeded),
                () -> assertThrows(IllegalStateException.class,
                        () -> model.load(new IndexRange(0, 1), new LoadCancellation())));
    }

    /// Returns the exact underlying failure from one non-cancellation completion stage.
    ///
    /// @param stage failed stage
    /// @return underlying failure identity
    private static Throwable failureCause(CompletionStage<?> stage) {
        CompletionException failure = assertThrows(
                CompletionException.class,
                () -> stage.toCompletableFuture().join());
        return Objects.requireNonNull(failure.getCause());
    }

    /// Creates one test descriptor with a file-name component matching the path.
    ///
    /// @param path descriptor path
    /// @param directory whether the path is a directory
    /// @return test descriptor
    private static DefaultSchematicBrowserModel.DiscoveredEntry entry(Path path, boolean directory) {
        return new DefaultSchematicBrowserModel.DiscoveredEntry(
                path.toAbsolutePath().normalize(), path.getFileName().toString(), directory);
    }

    /// Test checkpoint that pauses recursive cleanup after delete isolation commits.
    @NotNullByDefault
    private static final class DeleteIsolationGate
            implements FileSystemSchematicMutationIo.DeleteIsolationCheckpoint {
        /// Signals that the target reached its private isolation path.
        private final CountDownLatch isolated = new CountDownLatch(1);

        /// Releases recursive cleanup.
        private final CountDownLatch release = new CountDownLatch(1);

        /// Captured private isolation path.
        private final AtomicReference<@Nullable Path> isolatedPath = new AtomicReference<>();

        /// Captures the committed isolation path and blocks until cleanup is released.
        ///
        /// @param path committed private isolation path
        /// @throws IOException when interrupted while waiting for release
        @Override
        public void isolated(Path path) throws IOException {
            isolatedPath.set(path);
            isolated.countDown();
            try {
                if (!release.await(5, TimeUnit.SECONDS)) {
                    throw new IOException("Timed out waiting to release delete cleanup");
                }
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new IOException("Delete cleanup wait was interrupted", failure);
            }
        }

        /// Waits for delete isolation to commit.
        ///
        /// @return whether isolation committed before the timeout
        /// @throws InterruptedException when the test thread is interrupted
        private boolean awaitIsolated() throws InterruptedException {
            return isolated.await(5, TimeUnit.SECONDS);
        }

        /// Returns the captured committed isolation path.
        ///
        /// @return committed private path
        private Path isolatedPath() {
            return Objects.requireNonNull(isolatedPath.get());
        }

        /// Releases recursive cleanup.
        private void release() {
            release.countDown();
        }
    }

    /// One-shot test hook that pauses a terminal future immediately before its completion CAS.
    @NotNullByDefault
    private static final class CompletionGate implements Runnable {
        /// Signals that the armed completion boundary was reached.
        private final CountDownLatch entered = new CountDownLatch(1);

        /// Releases the paused completion boundary.
        private final CountDownLatch release = new CountDownLatch(1);

        /// Whether subsequent hook calls should wait at this boundary.
        private volatile boolean armed;

        /// Arms this one-shot boundary after setup completions have finished.
        private void arm() {
            armed = true;
        }

        /// Waits for a worker to reach the armed completion boundary.
        ///
        /// @return whether the boundary was reached within the test timeout
        /// @throws InterruptedException when the test thread is interrupted
        private boolean awaitEntered() throws InterruptedException {
            return entered.await(5, TimeUnit.SECONDS);
        }

        /// Releases every worker waiting at this completion boundary.
        private void release() {
            release.countDown();
        }

        /// Blocks only armed terminal completions without propagating test-thread interruption.
        @Override
        public void run() {
            if (!armed) {
                return;
            }
            entered.countDown();
            boolean interrupted = false;
            while (true) {
                try {
                    release.await();
                    break;
                } catch (InterruptedException ignored) {
                    interrupted = true;
                }
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /// Deterministic caller-owned executor that runs submitted work only when requested by a test.
    @NotNullByDefault
    private static final class QueuedExecutor implements Executor {
        /// Pending tasks in submission order.
        private final Deque<Runnable> tasks = new ArrayDeque<>();

        /// Failure thrown for later submissions, or null while accepting work.
        private @Nullable RuntimeException rejection;

        /// Enqueues work without running it.
        ///
        /// @param command submitted work
        @Override
        public synchronized void execute(Runnable command) {
            if (rejection != null) {
                throw rejection;
            }
            tasks.addLast(command);
        }

        /// Rejects every later task with the exact supplied failure.
        ///
        /// @param failure rejection identity
        private synchronized void rejectNewTasks(RuntimeException failure) {
            rejection = Objects.requireNonNull(failure);
        }

        /// Runs the oldest queued task.
        private void runNext() {
            Runnable task;
            synchronized (this) {
                task = tasks.removeFirst();
            }
            task.run();
        }

        /// Returns the number of queued tasks.
        ///
        /// @return pending task count
        private synchronized int pendingCount() {
            return tasks.size();
        }
    }

    /// Mutator delegating directory creation to one deterministic test action.
    @NotNullByDefault
    private static final class ControlledMutationIo
            implements DefaultSchematicBrowserModel.MutationIo {
        /// Directory-creation behavior.
        private final CreateMutation createMutation;

        /// Creates a mutator with one directory-creation behavior.
        ///
        /// @param createMutation deterministic creation behavior
        private ControlledMutationIo(CreateMutation createMutation) {
            this.createMutation = createMutation;
        }

        /// Accepts unused imports without file-system effects.
        @Override
        public void importFiles(
                Path currentDirectory,
                @Unmodifiable List<Path> sourceFiles,
                LoadCancellation cancellation) {
        }

        /// Delegates directory creation to the configured test behavior.
        @Override
        public void createDirectory(
                Path currentDirectory,
                String directoryName,
                LoadCancellation cancellation) throws IOException {
            createMutation.run(currentDirectory, directoryName, cancellation);
        }

        /// Accepts unused deletions without file-system effects.
        @Override
        public void delete(
                Path currentDirectory,
                DefaultSchematicBrowserModel.DiscoveredEntry entry,
                LoadCancellation cancellation) {
        }
    }

    /// Directory mutation callback used by [ControlledMutationIo].
    @FunctionalInterface
    @NotNullByDefault
    private interface CreateMutation {
        /// Runs deterministic creation work.
        ///
        /// @param currentDirectory captured stable directory
        /// @param directoryName requested child name
        /// @param cancellation model cancellation signal
        /// @throws IOException when the controlled operation fails
        void run(
                Path currentDirectory,
                String directoryName,
                LoadCancellation cancellation) throws IOException;
    }

    /// Mutator whose creation call remains externally blocked after it starts.
    @NotNullByDefault
    private static final class BlockingMutationIo
            implements DefaultSchematicBrowserModel.MutationIo {
        /// Signals that directory creation entered external mutation work.
        private final CountDownLatch started = new CountDownLatch(1);

        /// Releases the blocked mutation.
        private final CountDownLatch released = new CountDownLatch(1);

        /// Accepts unused imports without file-system effects.
        @Override
        public void importFiles(
                Path currentDirectory,
                @Unmodifiable List<Path> sourceFiles,
                LoadCancellation cancellation) {
        }

        /// Blocks until explicitly released while intentionally ignoring cooperative cancellation.
        @Override
        public void createDirectory(
                Path currentDirectory,
                String directoryName,
                LoadCancellation cancellation) throws IOException {
            started.countDown();
            try {
                if (!released.await(5, TimeUnit.SECONDS)) {
                    throw new IOException("Timed out waiting to release blocked mutation");
                }
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new IOException("Blocked mutation was interrupted", failure);
            }
        }

        /// Accepts unused deletions without file-system effects.
        @Override
        public void delete(
                Path currentDirectory,
                DefaultSchematicBrowserModel.DiscoveredEntry entry,
                LoadCancellation cancellation) {
        }

        /// Waits for the controlled mutation to begin.
        ///
        /// @return whether creation began before the timeout
        /// @throws InterruptedException when the test thread is interrupted
        private boolean awaitStarted() throws InterruptedException {
            return started.await(5, TimeUnit.SECONDS);
        }

        /// Releases the controlled mutation.
        private void release() {
            released.countDown();
        }
    }

    /// Reader whose first call returns late and whose second call returns the latest listing immediately.
    @NotNullByDefault
    private static final class BlockingDirectoryReader
            implements DefaultSchematicBrowserModel.DirectoryReader {
        /// Root used to create deterministic descriptors.
        private final Path root;

        /// Number of reader invocations.
        private final AtomicInteger calls = new AtomicInteger();

        /// Signals that the first read entered external work.
        private final CountDownLatch firstStarted = new CountDownLatch(1);

        /// Releases the intentionally stale first read.
        private final CountDownLatch releaseFirst = new CountDownLatch(1);

        /// Signals that the stale read returned to model code.
        private final CountDownLatch firstReturned = new CountDownLatch(1);

        /// Creates a controlled reader for one root.
        ///
        /// @param root descriptor root
        private BlockingDirectoryReader(Path root) {
            this.root = root;
        }

        /// Blocks the first read and immediately serves the second generation.
        ///
        /// @param directory ignored current directory
        /// @param cancellation intentionally ignored until the stale result is ready
        /// @return old or latest one-row listing
        /// @throws IOException when the wait is interrupted
        @Override
        public List<DefaultSchematicBrowserModel.DiscoveredEntry> read(
                Path directory,
                LoadCancellation cancellation) throws IOException {
            int call = calls.incrementAndGet();
            if (call == 1) {
                firstStarted.countDown();
                try {
                    if (!releaseFirst.await(5, TimeUnit.SECONDS)) {
                        throw new IOException("Timed out waiting to release stale scan");
                    }
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted while waiting to release stale scan", failure);
                } finally {
                    firstReturned.countDown();
                }
                return List.of(entry(root.resolve("old.litematic"), false));
            }
            return List.of(entry(root.resolve("latest.litematic"), false));
        }

        /// Waits for the stale reader to begin.
        ///
        /// @return whether the reader began before the timeout
        /// @throws InterruptedException when the test thread is interrupted
        private boolean awaitFirstStart() throws InterruptedException {
            return firstStarted.await(5, TimeUnit.SECONDS);
        }

        /// Allows the stale reader to return.
        private void releaseFirst() {
            releaseFirst.countDown();
        }

        /// Waits for the stale reader to return.
        ///
        /// @return whether the reader returned before the timeout
        /// @throws InterruptedException when the test thread is interrupted
        private boolean awaitFirstReturn() throws InterruptedException {
            return firstReturned.await(5, TimeUnit.SECONDS);
        }
    }
}
