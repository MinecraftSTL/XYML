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
package space.minecraftstl.xyml.nbt;

import net.jpountz.lz4.LZ4BlockOutputStream;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import space.minecraftstl.xyml.library.nbt.NBTElement;
import space.minecraftstl.xyml.library.nbt.chunk.Chunk;
import space.minecraftstl.xyml.library.nbt.chunk.ChunkRegion;
import space.minecraftstl.xyml.library.nbt.edit.NBTAddress;
import space.minecraftstl.xyml.library.nbt.edit.NBTEditException;
import space.minecraftstl.xyml.library.nbt.edit.NBTEditor;
import space.minecraftstl.xyml.library.nbt.io.NBTCodec;
import space.minecraftstl.xyml.library.nbt.io.NBTFileEncoding;
import space.minecraftstl.xyml.library.nbt.io.NBTReadReport;
import space.minecraftstl.xyml.library.nbt.tag.ByteArrayTag;
import space.minecraftstl.xyml.library.nbt.tag.CompoundTag;
import space.minecraftstl.xyml.library.nbt.tag.IntArrayTag;
import space.minecraftstl.xyml.library.nbt.tag.IntTag;
import space.minecraftstl.xyml.library.nbt.tag.ListTag;
import space.minecraftstl.xyml.library.nbt.tag.LongArrayTag;
import space.minecraftstl.xyml.library.nbt.tag.TagType;
import space.minecraftstl.xyml.task.Task;
import space.minecraftstl.xyml.task.TaskExecutor;
import space.minecraftstl.xyml.task.TaskListener;
import space.minecraftstl.xyml.task.TaskResource;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies that XYML delegates safe editing and persistence to XoyzNBT file sessions.
@NotNullByDefault
final class NBTBackendTest {
    /// Temporary real-filesystem root used for atomic publication and lifecycle checks.
    @TempDir
    private Path temporaryDirectory;

    /// Recognizes only the supported filename families, independent of case.
    @Test
    void detectsSupportedFileFamiliesWithoutTouchingTheFilesystem() {
        assertEquals(NBTFileType.TAG, NBTFileType.detect(Path.of("level.dat")));
        assertEquals(NBTFileType.TAG, NBTFileType.detect(Path.of("level.DAT_OLD")));
        assertEquals(NBTFileType.TAG, NBTFileType.detect(Path.of("structure.nbt")));
        assertEquals(NBTFileType.ANVIL, NBTFileType.detect(Path.of("r.0.0.MCA")));
        assertEquals(NBTFileType.REGION, NBTFileType.detect(Path.of("r.0.0.mcr")));
        assertNull(NBTFileType.detect(Path.of("level.txt")));
        assertFalse(NBTFileType.supports(Path.of("level")));
    }

    /// Defers strict file-session open until the supplied executor runs the queued operation.
    @Test
    void dispatchesOpenWorkToTheCallerOwnedExecutor() throws Exception {
        Path source = temporaryDirectory.resolve("level.dat");
        writeTag(source, NBTFileEncoding.GZIP, sampleRoot());
        ManualExecutor executor = new ManualExecutor();
        NBTDocumentService service = new NBTDocumentService(executor);

        CompletableFuture<NBTDocument> future = service.open(source);
        assertFalse(future.isDone());
        Runnable openCommand = executor.takeNext();
        assertEquals(0, executor.pendingCount());

        openCommand.run();
        try (NBTDocument document = future.join()) {
            assertEquals(source.toAbsolutePath().normalize(), document.file());
            assertEquals(NBTFileEncoding.GZIP, document.encoding());
            assertEquals(NBTStorageEncoding.GZIP, document.storageEncoding());
            assertEquals(0, executor.pendingCount());
        }
    }

    /// Creates a new standalone document through the service and preserves the filename-derived default envelope.
    @Test
    void createsNewStandaloneDocumentWithDefaultEncoding() throws Exception {
        Path source = temporaryDirectory.resolve("new-level.dat");
        NBTDocumentService service = new NBTDocumentService(Runnable::run);
        try (NBTDocument document = service.create(source).join()) {
            assertEquals(NBTFileType.TAG, document.fileType());
            assertEquals(NBTFileEncoding.GZIP, document.encoding());
            assertTrue(document.isDirty());
            service.save(document).join();
            assertFalse(document.isDirty());
        }
        assertTrue(Files.isRegularFile(source));
        assertEquals(NBTFileEncoding.GZIP, NBTFileEncoding.detectStandalone(Files.readAllBytes(source)));
    }

    /// Keeps a new `.nbt` document uncompressed by default and rejects an occupied target.
    @Test
    void createsRawNbtAndRejectsOccupiedTarget() throws Exception {
        Path source = temporaryDirectory.resolve("new-structure.nbt");
        NBTDocumentService service = new NBTDocumentService(Runnable::run);
        try (NBTDocument document = service.create(source).join()) {
            assertEquals(NBTFileEncoding.RAW, document.encoding());
            service.save(document).join();
        }
        assertEquals(NBTFileEncoding.RAW, NBTFileEncoding.detectStandalone(Files.readAllBytes(source)));

        assertThrows(CompletionException.class, () -> service.create(source).join());
    }

    /// Creates an empty Java Edition region with exactly two zero-filled header sectors.
    ///
    /// @param extension Anvil or legacy Region extension
    @ParameterizedTest
    @ValueSource(strings = {"mca", "mcr"})
    void createsStrictlyReadableZeroFilledRegion(String extension) throws Exception {
        Path source = temporaryDirectory.resolve("r.0.0." + extension);
        NBTDocumentService service = new NBTDocumentService(Runnable::run);

        try (NBTDocument document = service.create(source).join()) {
            assertEquals("mca".equals(extension) ? NBTFileType.ANVIL : NBTFileType.REGION,
                    document.fileType());
            assertEquals(NBTFileEncoding.REGION, document.encoding());
            assertFalse(document.isDirty());
            byte[] bytes = Files.readAllBytes(source);
            assertEquals(8192, bytes.length);
            assertArrayEquals(new byte[8192], bytes);
        }

        ChunkRegion region = NBTCodec.of().readRegion(source);
        assertEquals(1024, region.size());
        assertTrue(region.stream().allMatch(chunk -> chunk.getRootTag() == null));
        try (NBTDocument reopened = service.open(source).join()) {
            assertEquals(NBTReadReport.Severity.CLEAN, reopened.readReport().severity());
            assertEquals(NBTFileEncoding.REGION, reopened.encoding());
        }
    }

    /// Defers a save and leaves source bytes unchanged until the supplied executor runs it.
    @Test
    void dispatchesSaveWorkToTheCallerOwnedExecutor() throws Exception {
        Path source = temporaryDirectory.resolve("queued-save.nbt");
        writeTag(source, NBTFileEncoding.RAW, sampleRoot());
        NBTDocument document = new NBTDocumentService(Runnable::run).open(source).join();
        setScalar(document.editor(), NBTAddress.root().appendName("value"), "2");
        ManualExecutor executor = new ManualExecutor();
        NBTDocumentService service = new NBTDocumentService(executor);

        CompletableFuture<Void> future = service.save(document);
        assertFalse(future.isDone());
        assertEquals(1, NBTCodec.of().readTag(source, TagType.COMPOUND).getInt("value"));
        executor.runNext();
        future.join();
        assertEquals(2, NBTCodec.of().readTag(source, TagType.COMPOUND).getInt("value"));
        document.close();
    }

    /// Allows a synchronous save continuation to close and wait without blocking the session operation queue.
    @Test
    void completesSaveAwayFromTheOperationWorkerBeforeQueuedClose() throws Exception {
        Path source = temporaryDirectory.resolve("save-then-close.nbt");
        writeTag(source, NBTFileEncoding.RAW, sampleRoot());
        ExecutorService executor = Executors.newSingleThreadExecutor();
        NBTDocumentService service = new NBTDocumentService(executor);
        try {
            NBTDocument document = service.open(source).get(5L, TimeUnit.SECONDS);
            setScalar(document.editor(), NBTAddress.root().appendName("value"), "2");

            service.save(document)
                    .thenRun(() -> service.close(document).join())
                    .get(5L, TimeUnit.SECONDS);

            assertTrue(document.isClosed());
            assertEquals(2, NBTCodec.of().readTag(source, TagType.COMPOUND).getInt("value"));
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5L, TimeUnit.SECONDS));
        }
    }

    /// Reports an unsupported extension as an asynchronous I/O failure.
    @Test
    void rejectsUnsupportedFilenameBeforeOpeningAFileSession() {
        Path source = temporaryDirectory.resolve("level.txt");
        CompletionException failure = assertThrows(
                CompletionException.class,
                () -> new NBTDocumentService(Runnable::run).open(source).join());
        assertInstanceOf(IOException.class, failure.getCause());
        assertFalse(Files.exists(source));
    }

    /// Does not let the library's create-or-open region API create a missing UI source.
    @Test
    void rejectsMissingRegionWithoutCreatingIt() {
        Path source = temporaryDirectory.resolve("missing.mca");

        CompletionException failure = assertThrows(
                CompletionException.class,
                () -> new NBTDocumentService(Runnable::run).open(source).join());

        assertInstanceOf(IOException.class, failure.getCause());
        assertFalse(Files.exists(source));
    }

    /// Closes a region session which finishes opening after its public future was cancelled.
    @Test
    void releasesLateRegionSessionAfterOpenCancellation() throws Exception {
        Path source = temporaryDirectory.resolve("cancelled-open.mca");
        ChunkRegion initialRegion = new ChunkRegion();
        initialRegion.setChunk(0, new Chunk(new CompoundTag().addInt("DataVersion", 1)));
        NBTCodec.of().writeRegion(source, initialRegion);
        ManualExecutor executor = new ManualExecutor();
        NBTDocumentService service = new NBTDocumentService(executor);

        CompletableFuture<NBTDocument> future = service.open(source);
        Runnable openCommand = executor.takeNext();
        assertTrue(future.cancel(false));
        openCommand.run();
        assertTrue(future.isCancelled());

        Path moved = temporaryDirectory.resolve("cancelled-open-moved.mca");
        Files.move(source, moved, StandardCopyOption.ATOMIC_MOVE);
        assertEquals(1, assertInstanceOf(
                CompoundTag.class,
                NBTCodec.of().readRegion(moved).getChunk(0).getRootTag()).getInt("DataVersion"));
    }

    /// Keeps one exact source lease until direct document closure lets a second service open the same path.
    @Test
    void serializesSameFileSessionsUntilDocumentClose() throws Exception {
        Path source = temporaryDirectory.resolve("shared.nbt");
        writeTag(source, NBTFileEncoding.RAW, sampleRoot());
        ExecutorService executor = Executors.newFixedThreadPool(2);
        NBTDocumentService firstService = new NBTDocumentService(executor);
        NBTDocumentService secondService = new NBTDocumentService(executor);
        @Nullable NBTDocument first = null;
        @Nullable NBTDocument second = null;
        try {
            first = firstService.open(source).get(5L, TimeUnit.SECONDS);
            CompletableFuture<NBTDocument> waiting = secondService.open(source);

            assertThrows(TimeoutException.class, () -> waiting.get(200L, TimeUnit.MILLISECONDS));
            first.close();
            first = null;
            second = waiting.get(5L, TimeUnit.SECONDS);
        } finally {
            if (first != null) {
                first.close();
            }
            if (second != null) {
                second.close();
            }
            executor.shutdownNow();
        }
    }

    /// Allows two standalone files to remain open concurrently under distinct exact-file resources.
    @Test
    void allowsDifferentFileSessionsToRunConcurrently() throws Exception {
        Path firstSource = temporaryDirectory.resolve("first.nbt");
        Path secondSource = temporaryDirectory.resolve("second.nbt");
        writeTag(firstSource, NBTFileEncoding.RAW, sampleRoot());
        writeTag(secondSource, NBTFileEncoding.RAW, sampleRoot());
        ExecutorService executor = Executors.newFixedThreadPool(2);
        NBTDocumentService service = new NBTDocumentService(executor);
        @Nullable NBTDocument first = null;
        @Nullable NBTDocument second = null;
        try {
            CompletableFuture<NBTDocument> firstOpen = service.open(firstSource);
            CompletableFuture<NBTDocument> secondOpen = service.open(secondSource);

            first = firstOpen.get(5L, TimeUnit.SECONDS);
            second = secondOpen.get(5L, TimeUnit.SECONDS);
        } finally {
            if (first != null) {
                first.close();
            }
            if (second != null) {
                second.close();
            }
            executor.shutdownNow();
        }
    }

    /// Protects the deterministic `.xyml_old` target for the complete level-data editing session.
    @Test
    void holdsRollingBackupResourceUntilDocumentClose() throws Exception {
        Path source = temporaryDirectory.resolve("level.dat");
        Path backup = temporaryDirectory.resolve("level.dat.xyml_old");
        writeTag(source, NBTFileEncoding.GZIP, sampleRoot());
        NBTDocumentService service = new NBTDocumentService(Runnable::run);
        NBTDocument document = service.open(source).get(5L, TimeUnit.SECONDS);
        CountDownLatch taskEntered = new CountDownLatch(1);
        CountDownLatch taskStopped = new CountDownLatch(1);
        Task<?> backupTask = Task.runAsync(taskEntered::countDown)
                .setResources(TaskResource.nbtFile(backup));
        TaskExecutor taskExecutor = backupTask.executor(new TaskListener() {
            @Override
            public void onStop(boolean success, TaskExecutor executor) {
                taskStopped.countDown();
            }
        });

        taskExecutor.start();
        assertFalse(taskEntered.await(200L, TimeUnit.MILLISECONDS));
        document.close();

        assertTrue(taskEntered.await(5L, TimeUnit.SECONDS));
        assertTrue(taskStopped.await(5L, TimeUnit.SECONDS));
    }

    /// Reopens under the existing lease without admitting a competing same-file session between handles.
    @Test
    void reloadReusesSessionWithoutSelfDeadlock() throws Exception {
        Path source = temporaryDirectory.resolve("reload-level.dat");
        writeTag(source, NBTFileEncoding.RAW, sampleRoot());
        NBTDocumentService service = new NBTDocumentService(Runnable::run);
        NBTDocumentService competingService = new NBTDocumentService(Runnable::run);
        NBTDocument original = service.open(source).get(5L, TimeUnit.SECONDS);
        CompletableFuture<NBTDocument> waiting = competingService.open(source);
        assertThrows(TimeoutException.class, () -> waiting.get(200L, TimeUnit.MILLISECONDS));

        NBTDocument replacement = service.reload(original).get(5L, TimeUnit.SECONDS);

        assertNotSame(original, replacement);
        assertTrue(original.isClosed());
        assertFalse(replacement.isClosed());
        assertThrows(TimeoutException.class, () -> waiting.get(200L, TimeUnit.MILLISECONDS));
        replacement.close();
        NBTDocument competing = waiting.get(5L, TimeUnit.SECONDS);
        assertFalse(competing.isClosed());
        competing.close();
    }

    /// A cancelled queued reload leaves the current session open and usable instead of discarding its recovery state.
    @Test
    void cancelledReloadKeepsOriginalSession() throws Exception {
        Path source = temporaryDirectory.resolve("cancelled-reload-level.dat");
        writeTag(source, NBTFileEncoding.RAW, sampleRoot());
        ManualExecutor executor = new ManualExecutor();
        NBTDocumentService service = new NBTDocumentService(executor);
        CompletableFuture<NBTDocument> opening = service.open(source);
        executor.runNext();
        NBTDocument original = opening.get(5L, TimeUnit.SECONDS);

        CompletableFuture<NBTDocument> reload = service.reload(original);
        Runnable reloadCommand = executor.takeNext();
        assertTrue(reload.cancel(false));
        reloadCommand.run();

        assertTrue(reload.isCancelled());
        assertFalse(original.isClosed());
        setScalar(original.editor(), NBTAddress.root().appendName("value"), "2");
        CompletableFuture<Void> save = service.save(original);
        executor.runNext();
        save.get(5L, TimeUnit.SECONDS);
        CompletableFuture<Void> close = service.close(original);
        executor.runNext();
        close.get(5L, TimeUnit.SECONDS);
        assertTrue(original.isClosed());
    }

    /// Serializes distinct region files in one directory because their external companions share that directory.
    @Test
    void serializesRegionDirectorySessionsForCompanionSafety() throws Exception {
        Path firstSource = temporaryDirectory.resolve("r.0.0.mca");
        Path secondSource = temporaryDirectory.resolve("r.1.0.mca");
        NBTCodec.of().writeRegion(firstSource, new ChunkRegion());
        NBTCodec.of().writeRegion(secondSource, new ChunkRegion());
        ExecutorService executor = Executors.newFixedThreadPool(2);
        NBTDocumentService service = new NBTDocumentService(executor);
        @Nullable NBTDocument first = null;
        @Nullable NBTDocument second = null;
        try {
            first = service.open(firstSource).get(5L, TimeUnit.SECONDS);
            CompletableFuture<NBTDocument> waiting = service.open(secondSource);

            assertThrows(TimeoutException.class, () -> waiting.get(200L, TimeUnit.MILLISECONDS));
            first.close();
            first = null;
            second = waiting.get(5L, TimeUnit.SECONDS);
        } finally {
            if (first != null) {
                first.close();
            }
            if (second != null) {
                second.close();
            }
            executor.shutdownNow();
        }
    }

    /// Falls back to the shared I/O scheduler when a caller-owned executor rejects delayed physical closure.
    @Test
    void closesAndReleasesSessionAfterCallerExecutorShutdown() throws Exception {
        Path source = temporaryDirectory.resolve("rejected-close.nbt");
        writeTag(source, NBTFileEncoding.RAW, sampleRoot());
        ExecutorService rejectedExecutor = Executors.newSingleThreadExecutor();
        NBTDocumentService rejectedService = new NBTDocumentService(rejectedExecutor);
        NBTDocument first = rejectedService.open(source).get(5L, TimeUnit.SECONDS);
        rejectedExecutor.shutdownNow();
        assertTrue(rejectedExecutor.awaitTermination(5L, TimeUnit.SECONDS));

        rejectedService.close(first).get(5L, TimeUnit.SECONDS);
        try (NBTDocument second = new NBTDocumentService(Runnable::run)
                .open(source)
                .get(5L, TimeUnit.SECONDS)) {
            assertEquals(source.toAbsolutePath().normalize(), second.file());
        }
    }

    /// Treats a direct physical close as the successful completion of an already queued service close.
    @Test
    void keepsServiceCloseIdempotentWhenDirectCloseWinsTheRace() throws Exception {
        Path source = temporaryDirectory.resolve("concurrent-close.nbt");
        writeTag(source, NBTFileEncoding.RAW, sampleRoot());
        NBTDocument document = new NBTDocumentService(Runnable::run)
                .open(source)
                .get(5L, TimeUnit.SECONDS);
        ManualExecutor closeExecutor = new ManualExecutor();

        CompletableFuture<Void> close = new NBTDocumentService(closeExecutor).close(document);
        Runnable closeCommand = closeExecutor.takeNext();
        document.close();
        closeCommand.run();

        close.get(5L, TimeUnit.SECONDS);
        assertTrue(document.isClosed());
    }

    /// Exposes editor handles for mutation while every compatibility root remains detached.
    @Test
    void exposesOnlyDetachedRootSnapshotsOutsideTheEditor() throws Exception {
        Path source = temporaryDirectory.resolve("detached.nbt");
        writeTag(source, NBTFileEncoding.RAW, sampleRoot());
        NBTDocumentService service = new NBTDocumentService(Runnable::run);

        try (NBTDocument document = service.open(source).join()) {
            CompoundTag first = assertInstanceOf(CompoundTag.class, document.rootElement());
            CompoundTag second = assertInstanceOf(CompoundTag.class, document.rootSnapshot());
            assertNotSame(first, second);
            first.setInt("value", 99);

            CompoundTag retained = assertInstanceOf(CompoundTag.class, document.rootSnapshot());
            assertEquals(1, retained.getInt("value"));
            assertFalse(document.isDirty());

            setScalar(document.editor(), NBTAddress.root().appendName("value"), "2");
            assertEquals(2, assertInstanceOf(CompoundTag.class, document.rootSnapshot()).getInt("value"));
            assertTrue(document.isDirty());
        }
    }

    /// Materializes only requested immutable node metadata and rejects expansion after revision change.
    @Test
    void materializesImmutableTreeNodesOneIndexAtATime() throws Exception {
        CompoundTag source = sampleRoot();
        NBTTreeNode rootNode = new NBTTreeNode(source, "level.dat");
        source.setInt("value", 99);

        assertEquals(5, rootNode.childCount());
        assertEquals(0, rootNode.materializedChildCount());
        NBTTreeNode valueNode = rootNode.childAt(0);
        assertEquals(1, rootNode.materializedChildCount());
        assertEquals("value", valueNode.displayName());
        assertEquals(NBTNodeType.INT, valueNode.type());
        assertEquals("1", valueNode.scalarValue());
        assertSame(valueNode, rootNode.childAt(0));

        NBTTreeNode listNode = rootNode.childAt(1);
        assertEquals(NBTNodeType.LIST, listNode.type());
        assertEquals("0", listNode.childAt(0).displayName());
        NBTTreeNode arrayNode = rootNode.childAt(3);
        assertEquals(NBTNodeType.INT_ARRAY, arrayNode.type());
        assertEquals("20", arrayNode.childAt(1).scalarValue());

        NBTEditor<CompoundTag> editor = NBTEditor.of(sampleRoot());
        NBTTreeNode revisionView = NBTTreeNode.forDocument(editor, "level.dat", NBTFileType.TAG);
        NBTTreeNode cachedValue = revisionView.childAt(0);
        setScalar(editor, NBTAddress.root().appendName("value"), "3");
        assertThrows(IllegalStateException.class, () -> revisionView.childAt(0));
        assertThrows(IllegalStateException.class, () -> revisionView.childAt(1));
        assertEquals("1", cachedValue.scalarValue());

        NBTTreeNode regionNode = new NBTTreeNode(new ChunkRegion(), "r.0.0.mca");
        assertEquals(1024, regionNode.childCount());
        assertEquals("Chunk (31, 31)", regionNode.childAt(1023).displayName());
        assertEquals(NBTNodeType.CHUNK, regionNode.childAt(1023).type());
    }

    /// Preserves all standalone envelopes through repeated editor saves.
    @Test
    void preservesEveryStandaloneEncodingAcrossRepeatedSaves() throws Exception {
        assertTagEncodingRoundTrip(temporaryDirectory.resolve("raw.nbt"), NBTFileEncoding.RAW);
        assertTagEncodingRoundTrip(temporaryDirectory.resolve("gzip.nbt"), NBTFileEncoding.GZIP);
        assertTagEncodingRoundTrip(temporaryDirectory.resolve("zlib.nbt"), NBTFileEncoding.ZLIB);
        assertTagEncodingRoundTrip(temporaryDirectory.resolve("lz4.nbt"), NBTFileEncoding.LZ4);
    }

    /// Saves a realistic `level.dat` containing all primitive arrays twice without damaging them.
    @Test
    void repeatedlySavesLevelDatWithPrimitiveArrays() throws Exception {
        Path source = temporaryDirectory.resolve("level.dat");
        writeTag(source, NBTFileEncoding.GZIP, sampleRoot());
        NBTDocumentService service = new NBTDocumentService(Runnable::run);

        try (NBTDocument document = service.open(source).join()) {
            setArrayElement(document.editor(), "bytes", 1, "12");
            setArrayElement(document.editor(), "ints", 0, "101");
            setArrayElement(document.editor(), "longs", 1, "1002");
            service.save(document).join();

            setArrayElement(document.editor(), "bytes", 0, "11");
            setArrayElement(document.editor(), "ints", 1, "202");
            setArrayElement(document.editor(), "longs", 0, "1001");
            service.save(document).join();
            assertFalse(document.isDirty());
        }

        CompoundTag saved = NBTCodec.of().readTag(source, TagType.COMPOUND);
        assertEquals(11, assertInstanceOf(ByteArrayTag.class, saved.get("bytes")).get(0));
        assertEquals(12, assertInstanceOf(ByteArrayTag.class, saved.get("bytes")).get(1));
        assertEquals(101, assertInstanceOf(IntArrayTag.class, saved.get("ints")).get(0));
        assertEquals(202, assertInstanceOf(IntArrayTag.class, saved.get("ints")).get(1));
        assertEquals(1001L, assertInstanceOf(LongArrayTag.class, saved.get("longs")).get(0));
        assertEquals(1002L, assertInstanceOf(LongArrayTag.class, saved.get("longs")).get(1));
    }

    /// Publishes one exact rolling `.xyml_old` copy of the source that each save replaces.
    @Test
    void maintainsExactRollingBackupForMainDatFiles() throws Exception {
        Path source = temporaryDirectory.resolve("level.dat");
        Path backup = temporaryDirectory.resolve("level.dat.xyml_old");
        writeTag(source, NBTFileEncoding.GZIP, sampleRoot());
        byte[] initialBytes = Files.readAllBytes(source);
        NBTDocumentService service = new NBTDocumentService(Runnable::run);

        try (NBTDocument document = service.open(source).join()) {
            setScalar(document.editor(), NBTAddress.root().appendName("value"), "2");
            service.save(document).join();
            assertArrayEquals(initialBytes, Files.readAllBytes(backup));
            byte[] firstSaveBytes = Files.readAllBytes(source);

            setScalar(document.editor(), NBTAddress.root().appendName("value"), "3");
            service.save(document).join();
            assertArrayEquals(firstSaveBytes, Files.readAllBytes(backup));
        }
        assertEquals(3, NBTCodec.of().readTag(source, TagType.COMPOUND).getInt("value"));
    }

    /// Preserves the source filename's exact spelling when deriving its `.xyml_old` backup.
    @Test
    void preservesFilenameCaseInDatBackupPath() throws Exception {
        Path source = temporaryDirectory.resolve("LEVEL.DAT");
        writeTag(source, NBTFileEncoding.RAW, sampleRoot());
        NBTDocumentService service = new NBTDocumentService(Runnable::run);

        editAndSaveValue(service, source, 8);

        try (var files = Files.list(temporaryDirectory)) {
            assertTrue(files
                    .map(Path::getFileName)
                    .map(Path::toString)
                    .anyMatch("LEVEL.DAT.xyml_old"::equals));
        }
    }

    /// Creates the next deterministic backup generation when the source is already `.xyml_old`.
    @Test
    void createsNextRecursiveBackupGeneration() throws Exception {
        Path oldSource = temporaryDirectory.resolve("level.dat.xyml_old");
        Path nbtSource = temporaryDirectory.resolve("structure.nbt");
        writeTag(oldSource, NBTFileEncoding.GZIP, sampleRoot());
        writeTag(nbtSource, NBTFileEncoding.RAW, sampleRoot());
        NBTDocumentService service = new NBTDocumentService(Runnable::run);

        editAndSaveValue(service, oldSource, 4);
        editAndSaveValue(service, nbtSource, 5);

        assertTrue(Files.exists(temporaryDirectory.resolve("level.dat.xyml_old.xyml_old")));
        assertTrue(Files.exists(temporaryDirectory.resolve("structure.nbt.xyml_old")));
    }

    /// Delegates changed region slots to the XoyzNBT copy-on-write session.
    @Test
    void savesRegionChangesThroughTheSafeRegionFile() throws Exception {
        Path source = temporaryDirectory.resolve("r.-1.2.mca");
        ChunkRegion initialRegion = new ChunkRegion();
        initialRegion.setChunk(0, new Chunk(new CompoundTag().addInt("DataVersion", 1)));
        NBTCodec.of().writeRegion(source, initialRegion);
        NBTDocumentService service = new NBTDocumentService(Runnable::run);

        try (NBTDocument document = service.open(source).join()) {
            assertEquals(NBTFileEncoding.REGION, document.encoding());
            NBTAddress dataVersion = NBTAddress.root()
                    .appendChunk(0)
                    .appendChunkRoot()
                    .appendName("DataVersion");
            setScalar(document.editor(), dataVersion, "2");
            service.save(document).join();
            assertFalse(document.isDirty());
        }

        ChunkRegion savedRegion = NBTCodec.of().readRegion(source);
        CompoundTag root = assertInstanceOf(CompoundTag.class, savedRegion.getChunk(0).getRootTag());
        assertEquals(2, root.getInt("DataVersion"));
    }

    /// Rewrites a source that changed after open and retains the replaced bytes in the rolling backup.
    @Test
    void rewritesAFileChangedAfterOpen() throws Exception {
        Path source = temporaryDirectory.resolve("stale.dat");
        writeTag(source, NBTFileEncoding.GZIP, sampleRoot());
        NBTDocumentService service = new NBTDocumentService(Runnable::run);

        try (NBTDocument document = service.open(source).join()) {
            setScalar(document.editor(), NBTAddress.root().appendName("value"), "2");
            CompoundTag replacement = sampleRoot();
            replacement.setInt("value", 99);
            writeTag(source, NBTFileEncoding.GZIP, replacement);

            service.save(document).join();
            assertFalse(document.isDirty());
        }
        assertEquals(2, NBTCodec.of().readTag(source, TagType.COMPOUND).getInt("value"));
        assertEquals(99, NBTCodec.of().readTag(
                temporaryDirectory.resolve("stale.dat.xyml_old"), TagType.COMPOUND).getInt("value"));
    }

    /// Releases region resources on close and rejects later document operations.
    @Test
    void closesRegionSessionWithoutPublishingPendingEdits() throws Exception {
        Path source = temporaryDirectory.resolve("r.0.0.mca");
        ChunkRegion initialRegion = new ChunkRegion();
        initialRegion.setChunk(0, new Chunk(new CompoundTag().addInt("DataVersion", 1)));
        NBTCodec.of().writeRegion(source, initialRegion);
        NBTDocumentService service = new NBTDocumentService(Runnable::run);
        NBTDocument document = service.open(source).join();
        setScalar(document.editor(), NBTAddress.root()
                .appendChunk(0)
                .appendChunkRoot()
                .appendName("DataVersion"), "7");

        document.close();
        document.close();
        assertTrue(document.isClosed());
        assertThrows(IllegalStateException.class, document::editor);
        CompletionException failure = assertThrows(
                CompletionException.class,
                () -> service.save(document).join());
        assertInstanceOf(IllegalStateException.class, failure.getCause());

        Path moved = temporaryDirectory.resolve("closed-session.mca");
        Files.move(source, moved, StandardCopyOption.ATOMIC_MOVE);
        ChunkRegion retained = NBTCodec.of().readRegion(moved);
        CompoundTag root = assertInstanceOf(CompoundTag.class, retained.getChunk(0).getRootTag());
        assertEquals(1, root.getInt("DataVersion"));
    }

    /// Exercises one standalone encoding through open, editor mutation, save, and reopen.
    ///
    /// @param source unique fixture path
    /// @param encoding source envelope to preserve
    /// @throws Exception when fixture or delegated file-session I/O fails
    private static void assertTagEncodingRoundTrip(Path source, NBTFileEncoding encoding) throws Exception {
        writeTag(source, encoding, sampleRoot());
        NBTDocumentService service = new NBTDocumentService(Runnable::run);
        try (NBTDocument document = service.open(source).join()) {
            assertEquals(encoding, document.encoding());
            assertEquals(NBTStorageEncoding.valueOf(encoding.name()), document.storageEncoding());
            setScalar(document.editor(), NBTAddress.root().appendName("value"), "2");
            service.save(document).join();
            setScalar(document.editor(), NBTAddress.root().appendName("value"), "3");
            service.save(document).join();
        }

        try (NBTDocument reopened = service.open(source).join()) {
            assertEquals(encoding, reopened.encoding());
            assertEquals(3, assertInstanceOf(CompoundTag.class, reopened.rootSnapshot()).getInt("value"));
        }
    }

    /// Edits and saves one standalone document through the public backend contract.
    ///
    /// @param service synchronous test service
    /// @param source fixture path
    /// @param value replacement integer value
    private static void editAndSaveValue(NBTDocumentService service, Path source, int value) {
        try (NBTDocument document = service.open(source).join()) {
            setScalar(document.editor(), NBTAddress.root().appendName("value"), Integer.toString(value));
            service.save(document).join();
        } catch (IOException failure) {
            throw new AssertionError("Could not close standalone test document", failure);
        } catch (NBTEditException failure) {
            throw new AssertionError("Could not edit standalone test document", failure);
        }
    }

    /// Applies one scalar edit using a fresh revision-bound node handle.
    ///
    /// @param editor document editor
    /// @param address scalar address
    /// @param text strict type-preserving value text
    /// @throws NBTEditException if the fixture address or value is invalid
    private static void setScalar(
            NBTEditor<? extends NBTElement> editor,
            NBTAddress address,
            String text) throws NBTEditException {
        editor.setScalar(editor.resolve(address), text);
    }

    /// Applies one primitive-array element edit using a fresh array node handle.
    ///
    /// @param editor document editor
    /// @param name array name in the root compound
    /// @param index array element index
    /// @param text strict primitive value text
    /// @throws NBTEditException if the fixture address, index, or value is invalid
    private static void setArrayElement(
            NBTEditor<? extends NBTElement> editor,
            String name,
            int index,
            String text) throws NBTEditException {
        editor.setArrayElement(editor.resolve(NBTAddress.root().appendName(name)), index, text);
    }

    /// Creates one realistic compound fixture with a list and all primitive array tags.
    ///
    /// @return new detached fixture root
    private static CompoundTag sampleRoot() {
        ListTag<IntTag> values = new ListTag<>(TagType.INT);
        values.addTag(new IntTag(7));
        return new CompoundTag()
                .addInt("value", 1)
                .addTag("values", values)
                .addByteArray("bytes", new byte[]{1, 2})
                .addIntArray("ints", new int[]{10, 20})
                .addLongArray("longs", new long[]{100L, 200L});
    }

    /// Writes a deterministic standalone fixture with the requested outer encoding.
    ///
    /// @param target fixture target
    /// @param encoding standalone encoding
    /// @param root compound root
    /// @throws IOException when fixture serialization fails
    private static void writeTag(
            Path target,
            NBTFileEncoding encoding,
            CompoundTag root) throws IOException {
        try (OutputStream rawOutput = new BufferedOutputStream(Files.newOutputStream(target))) {
            switch (encoding) {
                case RAW -> NBTCodec.of().writeTag(rawOutput, root);
                case GZIP -> {
                    try (GZIPOutputStream output = new GZIPOutputStream(rawOutput)) {
                        NBTCodec.of().writeTag(output, root);
                    }
                }
                case ZLIB -> {
                    try (DeflaterOutputStream output = new DeflaterOutputStream(rawOutput)) {
                        NBTCodec.of().writeTag(output, root);
                    }
                }
                case LZ4 -> {
                    try (LZ4BlockOutputStream output = new LZ4BlockOutputStream(rawOutput)) {
                        NBTCodec.of().writeTag(output, root);
                    }
                }
                case REGION -> throw new IllegalArgumentException("Region is not a standalone tag encoding");
            }
        }
    }

    /// Deterministic executor proving that backend APIs dispatch instead of blocking the caller.
    @NotNullByDefault
    private static final class ManualExecutor implements Executor {
        /// FIFO of submitted operations awaiting explicit test execution.
        private final BlockingQueue<Runnable> commands = new LinkedBlockingQueue<>();

        /// Queues one operation without running it.
        ///
        /// @param command submitted operation
        @Override
        public void execute(Runnable command) {
            commands.add(command);
        }

        /// Runs and removes the next queued operation.
        private void runNext() throws InterruptedException {
            takeNext().run();
        }

        /// Waits for and removes the next command without executing it.
        ///
        /// @return next submitted command
        /// @throws InterruptedException if the test thread is interrupted
        private Runnable takeNext() throws InterruptedException {
            Runnable command = commands.poll(5L, TimeUnit.SECONDS);
            if (command == null) {
                throw new AssertionError("Timed out waiting for an executor command");
            }
            return command;
        }

        /// Returns the exact number of operations not yet run.
        ///
        /// @return pending operation count
        private int pendingCount() {
            return commands.size();
        }
    }
}
