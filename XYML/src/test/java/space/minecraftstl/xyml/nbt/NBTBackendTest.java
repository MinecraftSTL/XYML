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
import space.minecraftstl.xyml.library.nbt.NBTElement;
import space.minecraftstl.xyml.library.nbt.chunk.Chunk;
import space.minecraftstl.xyml.library.nbt.chunk.ChunkRegion;
import space.minecraftstl.xyml.library.nbt.edit.NBTAddress;
import space.minecraftstl.xyml.library.nbt.edit.NBTEditException;
import space.minecraftstl.xyml.library.nbt.edit.NBTEditor;
import space.minecraftstl.xyml.library.nbt.io.NBTCodec;
import space.minecraftstl.xyml.library.nbt.io.NBTFileEncoding;
import space.minecraftstl.xyml.library.nbt.tag.ByteArrayTag;
import space.minecraftstl.xyml.library.nbt.tag.CompoundTag;
import space.minecraftstl.xyml.library.nbt.tag.IntArrayTag;
import space.minecraftstl.xyml.library.nbt.tag.IntTag;
import space.minecraftstl.xyml.library.nbt.tag.ListTag;
import space.minecraftstl.xyml.library.nbt.tag.LongArrayTag;
import space.minecraftstl.xyml.library.nbt.tag.TagType;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
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
        assertEquals(1, executor.pendingCount());

        executor.runNext();
        try (NBTDocument document = future.join()) {
            assertEquals(source.toAbsolutePath().normalize(), document.file());
            assertEquals(NBTFileEncoding.GZIP, document.encoding());
            assertEquals(NBTStorageEncoding.GZIP, document.storageEncoding());
            assertEquals(0, executor.pendingCount());
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
        assertTrue(future.cancel(false));
        executor.runNext();
        assertTrue(future.isCancelled());

        Path moved = temporaryDirectory.resolve("cancelled-open-moved.mca");
        Files.move(source, moved, StandardCopyOption.ATOMIC_MOVE);
        assertEquals(1, assertInstanceOf(
                CompoundTag.class,
                NBTCodec.of().readRegion(moved).getChunk(0).getRootTag()).getInt("DataVersion"));
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

    /// Publishes one exact rolling `.dat_old` copy of the source that each save replaces.
    @Test
    void maintainsExactRollingBackupForMainDatFiles() throws Exception {
        Path source = temporaryDirectory.resolve("level.dat");
        Path backup = temporaryDirectory.resolve("level.dat_old");
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

    /// Preserves the source filename's exact spelling when deriving its `_old` backup.
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
                    .anyMatch("LEVEL.DAT_old"::equals));
        }
    }

    /// Does not recursively create backups for `.dat_old` or `.nbt` documents.
    @Test
    void doesNotCreateRecursiveBackupHistory() throws Exception {
        Path oldSource = temporaryDirectory.resolve("level.dat_old");
        Path nbtSource = temporaryDirectory.resolve("structure.nbt");
        writeTag(oldSource, NBTFileEncoding.GZIP, sampleRoot());
        writeTag(nbtSource, NBTFileEncoding.RAW, sampleRoot());
        NBTDocumentService service = new NBTDocumentService(Runnable::run);

        editAndSaveValue(service, oldSource, 4);
        editAndSaveValue(service, nbtSource, 5);

        assertFalse(Files.exists(temporaryDirectory.resolve("level.dat_old_old")));
        assertFalse(Files.exists(temporaryDirectory.resolve("structure.nbt_old")));
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

    /// Rejects a stale document and retains independently replaced source bytes.
    @Test
    void refusesToOverwriteAFileChangedAfterOpen() throws Exception {
        Path source = temporaryDirectory.resolve("stale.dat");
        writeTag(source, NBTFileEncoding.GZIP, sampleRoot());
        NBTDocumentService service = new NBTDocumentService(Runnable::run);

        try (NBTDocument document = service.open(source).join()) {
            setScalar(document.editor(), NBTAddress.root().appendName("value"), "2");
            CompoundTag replacement = sampleRoot();
            replacement.setInt("value", 99);
            writeTag(source, NBTFileEncoding.GZIP, replacement);

            CompletionException failure = assertThrows(
                    CompletionException.class,
                    () -> service.save(document).join());
            assertInstanceOf(IOException.class, failure.getCause());
            assertTrue(document.isDirty());
        }
        assertEquals(99, NBTCodec.of().readTag(source, TagType.COMPOUND).getInt("value"));
        assertFalse(Files.exists(temporaryDirectory.resolve("stale.dat_old")));
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
        private final Queue<Runnable> commands = new ArrayDeque<>();

        /// Queues one operation without running it.
        ///
        /// @param command submitted operation
        @Override
        public void execute(Runnable command) {
            commands.add(command);
        }

        /// Runs and removes the next queued operation.
        private void runNext() {
            commands.remove().run();
        }

        /// Returns the exact number of operations not yet run.
        ///
        /// @return pending operation count
        private int pendingCount() {
            return commands.size();
        }
    }
}
