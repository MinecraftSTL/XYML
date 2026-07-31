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
import org.glavo.nbt.chunk.Chunk;
import org.glavo.nbt.chunk.ChunkRegion;
import org.glavo.nbt.io.NBTCodec;
import org.glavo.nbt.tag.CompoundTag;
import org.glavo.nbt.tag.IntArrayTag;
import org.glavo.nbt.tag.IntTag;
import org.glavo.nbt.tag.ListTag;
import org.glavo.nbt.tag.TagType;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies extension detection, executor dispatch, indexed lazy trees, and validated atomic saves.
@NotNullByDefault
final class NBTBackendTest {
    /// Temporary real-filesystem root used to exercise same-directory atomic replacement.
    @TempDir
    private Path temporaryDirectory;

    /// Recognizes only the file families supported by the legacy NBT page, independent of case.
    @Test
    void detectsSupportedFileFamiliesWithoutTouchingTheFilesystem() {
        assertEquals(NBTFileType.TAG, NBTFileType.detect(Path.of("level.dat")));
        assertEquals(NBTFileType.TAG, NBTFileType.detect(Path.of("level.DAT_OLD")));
        assertEquals(NBTFileType.ANVIL, NBTFileType.detect(Path.of("r.0.0.MCA")));
        assertEquals(NBTFileType.REGION, NBTFileType.detect(Path.of("r.0.0.mcr")));
        assertNull(NBTFileType.detect(Path.of("level.nbt")));
        assertFalse(NBTFileType.supports(Path.of("level")));
    }

    /// Defers all parsing work until the supplied background executor runs the queued operation.
    @Test
    void dispatchesOpenWorkToTheCallerOwnedExecutor() throws Exception {
        Path source = temporaryDirectory.resolve("level.dat");
        writeTag(source, NBTStorageEncoding.GZIP, new CompoundTag().addInt("value", 1));
        ManualExecutor executor = new ManualExecutor();
        NBTDocumentService service = new NBTDocumentService(executor);

        CompletableFuture<NBTDocument> future = service.open(source);
        assertFalse(future.isDone());
        assertEquals(1, executor.pendingCount());

        executor.runNext();
        NBTDocument document = future.join();
        assertEquals(source.toAbsolutePath().normalize(), document.file());
        assertEquals(NBTStorageEncoding.GZIP, document.storageEncoding());
        assertEquals(0, executor.pendingCount());
    }

    /// Materializes only requested direct children, including one of a region's 1024 chunk slots.
    @Test
    void materializesTreeNodesOneIndexAtATime() {
        ListTag<IntTag> values = new ListTag<>(TagType.INT);
        values.addTag(new IntTag(7));
        CompoundTag root = new CompoundTag()
                .addInt("answer", 42)
                .addTag("values", values)
                .addTag("positions", new IntArrayTag(new int[]{11, 12}));
        NBTTreeNode rootNode = new NBTTreeNode(root, "level.dat");

        assertEquals(3, rootNode.childCount());
        assertEquals(0, rootNode.materializedChildCount());
        NBTTreeNode answerNode = rootNode.childAt(0);
        assertEquals(1, rootNode.materializedChildCount());
        assertEquals("answer", answerNode.displayName());
        assertEquals(NBTNodeType.INT, answerNode.type());
        assertEquals("42", answerNode.scalarValue());
        assertSame(answerNode, rootNode.childAt(0));
        assertEquals(1, rootNode.materializedChildCount());

        NBTTreeNode listNode = rootNode.childAt(1);
        assertEquals(NBTNodeType.LIST, listNode.type());
        assertEquals(1, listNode.childCount());
        assertEquals("0", listNode.childAt(0).displayName());

        NBTTreeNode arrayNode = rootNode.childAt(2);
        assertEquals(NBTNodeType.INT_ARRAY, arrayNode.type());
        assertEquals("1", arrayNode.childAt(1).displayName());
        assertEquals("12", arrayNode.childAt(1).scalarValue());

        NBTTreeNode regionNode = new NBTTreeNode(new ChunkRegion(), "r.0.0.mca");
        assertEquals(1024, regionNode.childCount());
        assertEquals(0, regionNode.materializedChildCount());
        NBTTreeNode lastChunk = regionNode.childAt(1023);
        assertEquals("Chunk (31, 31)", lastChunk.displayName());
        assertTrue(lastChunk.isLeaf());
        assertEquals(1, regionNode.materializedChildCount());
    }

    /// Preserves RAW, GZIP, and LZ4 envelopes while persisting supported HelloNBT mutations.
    @Test
    void atomicallySavesStandaloneTagsWithTheirOriginalEncoding() throws Exception {
        assertTagEncodingRoundTrip(temporaryDirectory.resolve("raw.dat"), NBTStorageEncoding.RAW);
        assertTagEncodingRoundTrip(temporaryDirectory.resolve("gzip.dat"), NBTStorageEncoding.GZIP);
        assertTagEncodingRoundTrip(temporaryDirectory.resolve("lz4.dat"), NBTStorageEncoding.LZ4);
    }

    /// Uses HelloNBT's proven Region writer and atomically publishes a single-file region update.
    @Test
    void atomicallySavesARegionThatNeedsNoExternalChunks() throws Exception {
        Path source = temporaryDirectory.resolve("r.0.0.mca");
        ChunkRegion initialRegion = new ChunkRegion();
        initialRegion.setChunk(0, new Chunk(new CompoundTag().addInt("DataVersion", 1)));
        NBTCodec.of().writeRegion(source, initialRegion);

        NBTDocumentService service = new NBTDocumentService(Runnable::run);
        NBTDocument document = service.open(source).join();
        assertEquals(NBTStorageEncoding.REGION, document.storageEncoding());
        ChunkRegion workingRegion = assertInstanceOf(ChunkRegion.class, document.rootElement());
        CompoundTag rootTag = assertInstanceOf(CompoundTag.class, workingRegion.getChunk(0).getRootTag());
        assertEquals(1, rootTag.getInt("DataVersion"));
        rootTag.setInt("DataVersion", 2);

        service.save(document).join();

        ChunkRegion savedRegion = NBTCodec.of().readRegion(source);
        CompoundTag savedRoot = assertInstanceOf(CompoundTag.class, savedRegion.getChunk(0).getRootTag());
        assertEquals(2, savedRoot.getInt("DataVersion"));
    }

    /// Rejects a stale document and leaves independently replaced source bytes untouched.
    @Test
    void refusesToOverwriteAFileChangedAfterOpen() throws Exception {
        Path source = temporaryDirectory.resolve("stale.dat");
        writeTag(source, NBTStorageEncoding.GZIP, new CompoundTag().addInt("value", 1));
        NBTDocumentService service = new NBTDocumentService(Runnable::run);
        NBTDocument document = service.open(source).join();
        CompoundTag workingRoot = assertInstanceOf(CompoundTag.class, document.rootElement());
        workingRoot.setInt("value", 2);

        writeTag(source, NBTStorageEncoding.GZIP, new CompoundTag().addInt("value", 99));
        CompletionException failure = assertThrows(CompletionException.class, () -> service.save(document).join());
        assertInstanceOf(IOException.class, failure.getCause());

        CompoundTag retainedRoot = NBTCodec.of().readTag(source, TagType.COMPOUND);
        assertEquals(99, retainedRoot.getInt("value"));
    }

    /// Exercises one standalone encoding through open, HelloNBT mutation, atomic save, and reopen.
    ///
    /// @param source unique test source
    /// @param encoding source envelope to preserve
    /// @throws Exception when fixture or backend I/O unexpectedly fails
    private static void assertTagEncodingRoundTrip(Path source, NBTStorageEncoding encoding) throws Exception {
        writeTag(source, encoding, new CompoundTag().addInt("value", 1));
        NBTDocumentService service = new NBTDocumentService(Runnable::run);
        NBTDocument document = service.open(source).join();
        assertEquals(encoding, document.storageEncoding());
        CompoundTag root = assertInstanceOf(CompoundTag.class, document.rootElement());
        root.setInt("value", 2);

        service.save(document).join();
        root.setInt("value", 3);
        service.save(document).join();

        NBTDocument reopened = service.open(source).join();
        assertEquals(encoding, reopened.storageEncoding());
        CompoundTag reopenedRoot = assertInstanceOf(CompoundTag.class, reopened.rootElement());
        assertEquals(3, reopenedRoot.getInt("value"));
    }

    /// Writes a deterministic standalone NBT fixture with the requested outer encoding.
    ///
    /// @param target fixture target
    /// @param encoding RAW, GZIP, or LZ4 encoding
    /// @param root compound root
    /// @throws IOException when fixture serialization fails
    private static void writeTag(
            Path target,
            NBTStorageEncoding encoding,
            CompoundTag root) throws IOException {
        try (OutputStream rawOutput = new BufferedOutputStream(Files.newOutputStream(target))) {
            switch (encoding) {
                case RAW -> NBTCodec.of().writeTag(rawOutput, root);
                case GZIP -> {
                    try (GZIPOutputStream gzipOutput = new GZIPOutputStream(rawOutput)) {
                        NBTCodec.of().writeTag(gzipOutput, root);
                    }
                }
                case LZ4 -> {
                    try (LZ4BlockOutputStream lz4Output = new LZ4BlockOutputStream(rawOutput)) {
                        NBTCodec.of().writeTag(lz4Output, root);
                    }
                }
                case REGION -> throw new IllegalArgumentException("Region is not a standalone tag encoding");
            }
        }
    }

    /// Deterministic executor that proves an asynchronous API does not perform work before dispatch.
    @NotNullByDefault
    private static final class ManualExecutor implements Executor {
        /// FIFO of submitted operations awaiting explicit test execution.
        private final Queue<Runnable> commands = new ArrayDeque<>();

        /// Queues one operation without running it.
        ///
        /// @param command submitted non-null operation
        @Override
        public void execute(Runnable command) {
            commands.add(command);
        }

        /// Runs and removes the next queued operation.
        private void runNext() {
            Runnable command = commands.remove();
            command.run();
        }

        /// Returns the exact number of operations not yet run.
        ///
        /// @return pending command count
        private int pendingCount() {
            return commands.size();
        }
    }
}
