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
// Added by MinecraftSTL in 2026 for safe XoyzNBT file-session coverage.
package space.minecraftstl.xyml.library.nbt.io;

import net.jpountz.lz4.LZ4BlockOutputStream;
import space.minecraftstl.xyml.library.nbt.chunk.Chunk;
import space.minecraftstl.xyml.library.nbt.chunk.ChunkRegion;
import space.minecraftstl.xyml.library.nbt.edit.NBTAddress;
import space.minecraftstl.xyml.library.nbt.edit.NBTEditor;
import space.minecraftstl.xyml.library.nbt.tag.CompoundTag;
import space.minecraftstl.xyml.library.nbt.tag.TagType;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies strict opening, encoding preservation, stale-write rejection, and safe publication.
@NotNullByDefault
public final class NBTFileTest {
    /// Real filesystem directory used to exercise atomic sibling replacement.
    @TempDir
    private Path temporaryDirectory;

    /// Preserves every supported standalone envelope across repeated editor saves.
    ///
    /// @param encoding standalone envelope under test
    /// @throws Exception if fixture creation or a safe save unexpectedly fails
    @ParameterizedTest
    @EnumSource(value = NBTFileEncoding.class, names = {"RAW", "GZIP", "ZLIB", "LZ4"})
    void preservesStandaloneEncodingAcrossRepeatedSaves(NBTFileEncoding encoding) throws Exception {
        Path source = temporaryDirectory.resolve(encoding.name().toLowerCase(Locale.ROOT) + ".dat");
        Files.write(source, encode(new CompoundTag().addInt("value", 1), encoding));

        try (NBTFile<CompoundTag> file = NBTFile.openTag(source, TagType.COMPOUND)) {
            assertEquals(encoding, file.getEncoding());
            NBTEditor<CompoundTag> editor = file.getEditor();
            editor.setScalar(editor.resolve(NBTAddress.root().appendName("value")), "2");
            file.save();
            assertEquals(encoding, NBTFileEncoding.detectStandalone(Files.readAllBytes(source)));

            editor.setScalar(editor.resolve(NBTAddress.root().appendName("value")), "3");
            file.save();
            assertFalse(editor.isDirty());
        }

        assertEquals(encoding, NBTFileEncoding.detectStandalone(Files.readAllBytes(source)));
        assertEquals(3, NBTCodec.of().readTag(source, TagType.COMPOUND).getInt("value"));
    }

    /// Replaces one rolling backup with the exact previous encoded source bytes.
    ///
    /// @throws Exception if fixture creation or safe publication unexpectedly fails
    @Test
    void publishesExactPreviousSourceAsRollingBackup() throws Exception {
        Path source = temporaryDirectory.resolve("level.dat");
        Path backup = temporaryDirectory.resolve("level.dat_old");
        byte[] original = encode(new CompoundTag().addInt("value", 1), NBTFileEncoding.GZIP);
        Files.write(source, original);

        try (NBTFile<CompoundTag> file = NBTFile.openTag(source, TagType.COMPOUND)) {
            NBTEditor<CompoundTag> editor = file.getEditor();
            editor.setScalar(editor.resolve(NBTAddress.root().appendName("value")), "2");
            file.save(NBTSaveOptions.withBackup(backup));
        }

        assertArrayEquals(original, Files.readAllBytes(backup));
        assertEquals(1, NBTCodec.of().readTag(backup, TagType.COMPOUND).getInt("value"));
        assertEquals(2, NBTCodec.of().readTag(source, TagType.COMPOUND).getInt("value"));
    }

    /// Rejects an independently changed source and retains both disk and editor state.
    ///
    /// @throws Exception if fixture creation or opening unexpectedly fails
    @Test
    void refusesToOverwriteChangedSource() throws Exception {
        Path source = temporaryDirectory.resolve("stale.dat");
        Files.write(source, encode(new CompoundTag().addInt("value", 1), NBTFileEncoding.ZLIB));

        try (NBTFile<CompoundTag> file = NBTFile.openTag(source, TagType.COMPOUND)) {
            NBTEditor<CompoundTag> editor = file.getEditor();
            editor.setScalar(editor.resolve(NBTAddress.root().appendName("value")), "2");
            Files.write(source, encode(new CompoundTag().addInt("value", 9), NBTFileEncoding.ZLIB));

            assertThrows(IOException.class, file::save);
            assertTrue(editor.isDirty());
        }

        assertEquals(9, NBTCodec.of().readTag(source, TagType.COMPOUND).getInt("value"));
    }

    /// Rejects trailing or truncated data before an editable session is returned.
    ///
    /// @param encoding standalone envelope under test
    /// @throws Exception if fixture creation unexpectedly fails
    @ParameterizedTest
    @EnumSource(value = NBTFileEncoding.class, names = {"RAW", "GZIP", "ZLIB", "LZ4"})
    void rejectsIncompleteOrTrailingStandaloneEnvelope(NBTFileEncoding encoding) throws Exception {
        byte[] encoded = encode(new CompoundTag().addInt("value", 1), encoding);
        Path trailing = temporaryDirectory.resolve(encoding.name().toLowerCase(Locale.ROOT) + "-trailing.dat");
        byte[] withTrailingByte = Arrays.copyOf(encoded, encoded.length + 1);
        withTrailingByte[withTrailingByte.length - 1] = 1;
        Files.write(trailing, withTrailingByte);
        assertThrows(IOException.class, () -> NBTFile.openTag(trailing));

        Path truncated = temporaryDirectory.resolve(encoding.name().toLowerCase(Locale.ROOT) + "-truncated.dat");
        Files.write(truncated, Arrays.copyOf(encoded, encoded.length - 1));
        assertThrows(IOException.class, () -> NBTFile.openTag(truncated));
    }

    /// Rejects a damaged checksum in every checksummed standalone compression format.
    ///
    /// @param encoding compressed standalone envelope under test
    /// @throws Exception if fixture creation unexpectedly fails
    @ParameterizedTest
    @EnumSource(value = NBTFileEncoding.class, names = {"GZIP", "ZLIB", "LZ4"})
    void rejectsDamagedStandaloneChecksum(NBTFileEncoding encoding) throws Exception {
        byte[] encoded = encode(new CompoundTag().addInt("value", 1), encoding);
        int checksumIndex = switch (encoding) {
            case GZIP -> encoded.length - 8;
            case ZLIB -> encoded.length - 1;
            case LZ4 -> 17;
            case RAW, REGION -> throw new AssertionError("Unexpected encoding: " + encoding);
        };
        encoded[checksumIndex] ^= 0x40;
        Path source = temporaryDirectory.resolve(encoding.name().toLowerCase(Locale.ROOT) + "-checksum.dat");
        Files.write(source, encoded);

        assertThrows(IOException.class, () -> NBTFile.openTag(source));
    }

    /// Rejects a backup destination equal to the source without publishing either state.
    ///
    /// @throws Exception if fixture creation or opening unexpectedly fails
    @Test
    void rejectsSourceAsBackupDestination() throws Exception {
        Path source = temporaryDirectory.resolve("same-backup.dat");
        byte[] original = encode(new CompoundTag().addInt("value", 1), NBTFileEncoding.GZIP);
        Files.write(source, original);

        try (NBTFile<CompoundTag> file = NBTFile.openTag(source, TagType.COMPOUND)) {
            NBTEditor<CompoundTag> editor = file.getEditor();
            editor.setScalar(editor.resolve(NBTAddress.root().appendName("value")), "2");

            assertThrows(IOException.class, () -> file.save(NBTSaveOptions.withBackup(source)));
            assertArrayEquals(original, Files.readAllBytes(source));
            assertTrue(editor.isDirty());
        }
    }

    /// Rejects publication after close while leaving the detached editor state intact.
    ///
    /// @throws Exception if fixture creation or opening unexpectedly fails
    @Test
    void rejectsSaveAfterClose() throws Exception {
        Path source = temporaryDirectory.resolve("closed.dat");
        byte[] original = encode(new CompoundTag().addInt("value", 1), NBTFileEncoding.RAW);
        Files.write(source, original);
        NBTFile<CompoundTag> file = NBTFile.openTag(source, TagType.COMPOUND);
        NBTEditor<CompoundTag> editor = file.getEditor();
        editor.setScalar(editor.resolve(NBTAddress.root().appendName("value")), "2");
        file.close();

        assertThrows(IOException.class, file::save);
        assertArrayEquals(original, Files.readAllBytes(source));
        assertTrue(editor.isDirty());
    }

    /// Delegates changed region slots to copy-on-write storage and preserves unchanged slots.
    ///
    /// @throws Exception if region creation or publication unexpectedly fails
    @Test
    void savesChangedRegionSlotsThroughRegionStorage() throws Exception {
        Path source = temporaryDirectory.resolve("r.0.0.mca");
        try (NBTRegionFile storage = NBTRegionFile.open(source)) {
            storage.writeChunk(0, new Chunk(new CompoundTag().addInt("value", 1)));
            storage.writeChunk(1, new Chunk(new CompoundTag().addString("untouched", "yes")));
            storage.flush();
        }

        try (NBTFile<ChunkRegion> file = NBTFile.openRegion(source)) {
            assertEquals(NBTFileEncoding.REGION, file.getEncoding());
            NBTAddress value = NBTAddress.root()
                    .appendChunk(0)
                    .appendChunkRoot()
                    .appendName("value");
            file.getEditor().setScalar(file.getEditor().resolve(value), "2");
            file.save();
            assertFalse(file.getEditor().isDirty());
        }

        try (NBTRegionFile storage = NBTRegionFile.open(source)) {
            assertEquals(2, storage.readChunk(0).getRootTag().getInt("value"));
            assertEquals("yes", storage.readChunk(1).getRootTag().getString("untouched"));
        }
    }

    /// Rejects an external Region change when editor history is dirty but semantically unchanged.
    @Test
    void noOpRegionSaveStillChecksSourceFingerprint() throws Exception {
        Path source = temporaryDirectory.resolve("r.0.0.mca");
        try (NBTRegionFile storage = NBTRegionFile.open(source)) {
            storage.writeChunk(0, new Chunk(new CompoundTag().addInt("value", 1)));
            storage.flush();
        }

        try (NBTFile<ChunkRegion> file = NBTFile.openRegion(source)) {
            NBTEditor<ChunkRegion> editor = file.getEditor();
            NBTAddress value = regionValueAddress(0);
            editor.setScalar(editor.resolve(value), "2");
            editor.setScalar(editor.resolve(value), "1");
            assertTrue(editor.isDirty());
            overwriteTimestampByte(source, 0, (byte) 0x55);

            assertThrows(IOException.class, file::save);
            assertTrue(editor.isDirty());
        }
    }

    /// Synchronizes stale pending slots with the editor snapshot after a partial region save.
    ///
    /// The first save commits slot A and fails slot B. The editor then always undoes B and may also
    /// undo the already committed A. A retry must cancel B's old pending value and, when requested,
    /// schedule the reverted A so the visible region is exactly the current editor snapshot.
    ///
    /// @param undoCommittedChunk whether the retry also reverts already committed slot A
    /// @throws Exception if fixture creation, editing, or the recovery save unexpectedly fails
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void synchronizesPendingSlotsAfterPartialRegionSave(boolean undoCommittedChunk) throws Exception {
        Path source = temporaryDirectory.resolve("r.0.0.mca");
        try (NBTRegionFile initial = NBTRegionFile.open(source)) {
            initial.writeChunk(0, new Chunk(new CompoundTag().addInt("value", 1)));
            initial.writeChunk(1, new Chunk(new CompoundTag().addInt("value", 1)));
            initial.flush();
        }

        AtomicBoolean injectFailure = new AtomicBoolean(true);
        NBTRegionFile.CommitHook hook = (stage, localIndex) -> {
            if (localIndex == 1
                    && stage == NBTRegionFile.CommitStage.PAYLOAD_WRITTEN
                    && injectFailure.compareAndSet(true, false)) {
                throw new IOException("injected second-slot failure");
            }
        };
        NBTRegionFile storage = NBTRegionFile.open(source, ExternalChunkAccessor.of(source), hook);
        try (NBTFile<ChunkRegion> file = NBTFile.openRegion(storage)) {
            NBTEditor<ChunkRegion> editor = file.getEditor();
            editor.setScalar(editor.resolve(regionValueAddress(0)), "10");
            editor.setScalar(editor.resolve(regionValueAddress(1)), "11");

            NBTPartialSaveException partial = assertThrows(NBTPartialSaveException.class, file::save);
            assertEquals(List.of(0), partial.committedIndexes());
            assertEquals(1, partial.failedIndex());
            assertEquals(List.of(1), storage.dirtyChunkIndexes());

            editor.undo();
            if (undoCommittedChunk) {
                editor.undo();
            }
            ChunkRegion expected = editor.snapshot();
            file.save();

            assertFalse(storage.isDirty());
            assertFalse(editor.isDirty());
            assertEquals(expected, NBTCodec.of().readRegion(source));
        }
    }

    /// Builds the scalar `value` address for one fixed region slot.
    ///
    /// @param localIndex local region slot
    /// @return immutable scalar address
    private static NBTAddress regionValueAddress(int localIndex) {
        return NBTAddress.root()
                .appendChunk(localIndex)
                .appendChunkRoot()
                .appendName("value");
    }

    /// Simulates a valid external timestamp-header change.
    ///
    /// @param file region path
    /// @param localIndex timestamp slot
    /// @param value replacement byte
    /// @throws IOException if the header cannot be changed
    private static void overwriteTimestampByte(Path file, int localIndex, byte value) throws IOException {
        try (FileChannel output = FileChannel.open(file, StandardOpenOption.WRITE)) {
            output.write(ByteBuffer.wrap(new byte[]{value}), 4096L + (long) localIndex * Integer.BYTES);
            output.force(true);
        }
    }

    /// Encodes one deterministic semantic tag in the requested outer envelope.
    ///
    /// @param root tag to encode
    /// @param encoding standalone envelope
    /// @return complete encoded bytes
    /// @throws IOException if serialization or compression fails
    private static byte[] encode(CompoundTag root, NBTFileEncoding encoding) throws IOException {
        ByteArrayOutputStream rawOutput = new ByteArrayOutputStream();
        NBTCodec.of().writeTag(rawOutput, root);
        byte[] raw = rawOutput.toByteArray();
        if (encoding == NBTFileEncoding.RAW) {
            return raw;
        }
        ByteArrayOutputStream encodedOutput = new ByteArrayOutputStream();
        try (OutputStream compressor = switch (encoding) {
                case GZIP -> new GZIPOutputStream(encodedOutput);
                case ZLIB -> new DeflaterOutputStream(encodedOutput);
                case LZ4 -> new LZ4BlockOutputStream(encodedOutput);
                case RAW, REGION -> throw new IllegalArgumentException("Not a compressed standalone encoding: "
                        + encoding);
            }) {
            compressor.write(raw);
        }
        return encodedOutput.toByteArray();
    }
}
