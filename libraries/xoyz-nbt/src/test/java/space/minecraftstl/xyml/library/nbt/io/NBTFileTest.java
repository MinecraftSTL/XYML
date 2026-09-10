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
import space.minecraftstl.xyml.library.nbt.tag.IntTag;
import space.minecraftstl.xyml.library.nbt.tag.TagType;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Assumptions;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.CRC32;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies strict opening, encoding preservation, default backups, and safe publication.
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

    /// Creates a new standalone source and publishes its first generation using the selected envelope.
    ///
    /// @param encoding standalone envelope under test
    /// @throws Exception if creation or strict first-save publication fails
    @ParameterizedTest
    @EnumSource(value = NBTFileEncoding.class, names = {"RAW", "GZIP", "ZLIB", "LZ4"})
    void createsStandaloneSourceWithSelectedEncoding(NBTFileEncoding encoding) throws Exception {
        Path source = temporaryDirectory.resolve("new-" + encoding.name().toLowerCase(Locale.ROOT) + ".nbt");
        try (NBTFile<CompoundTag> file = NBTFile.createTag(source, encoding)) {
            assertTrue(file.isDirty());
            assertEquals(encoding, file.getEncoding());
            file.save();
            assertFalse(file.isDirty());
        }

        assertTrue(Files.isRegularFile(source));
        assertEquals(encoding, NBTFileEncoding.detectStandalone(Files.readAllBytes(source)));
        assertFalse(Files.exists(source.resolveSibling(source.getFileName() + ".xyml_old")));
        try (NBTFile<CompoundTag> reopened = NBTFile.openTag(source, TagType.COMPOUND)) {
            assertFalse(reopened.isDirty());
        }
    }

    /// Selects RAW for `.nbt` and GZIP for Minecraft standalone data-file names when no envelope is supplied.
    ///
    /// @param extension target filename extension without its leading dot
    /// @throws Exception if creation or strict first-save publication fails
    @ParameterizedTest
    @ValueSource(strings = {"nbt", "dat", "dat_old", "xyml_old"})
    void createsStandaloneSourceWithFilenameDefault(String extension) throws Exception {
        Path source = temporaryDirectory.resolve("default." + extension);
        NBTFileEncoding expected = "nbt".equals(extension) ? NBTFileEncoding.RAW : NBTFileEncoding.GZIP;
        try (NBTFile<CompoundTag> file = NBTFile.createTag(source)) {
            assertEquals(expected, file.getEncoding());
            file.save(NBTSaveOptions.withoutBackup());
        }
        assertEquals(expected, NBTFileEncoding.detectStandalone(Files.readAllBytes(source)));
    }

    /// Refuses to create a source when another filesystem object occupies the requested target.
    @Test
    void rejectsCreatingOverExistingStandaloneSource() throws Exception {
        Path source = temporaryDirectory.resolve("already-present.nbt");
        Files.write(source, new byte[]{1});
        assertThrows(IOException.class, () -> NBTFile.createTag(source));
    }

    /// Creates and reopens a standalone scalar root without imposing Anvil's compound-root rule.
    @Test
    void createsStandaloneNamedScalarRoot() throws Exception {
        Path source = temporaryDirectory.resolve("scalar-root.nbt");
        IntTag root = new IntTag(42);
        root.setName("answer");
        try (NBTFile<IntTag> file = NBTFile.createTag(source, root, NBTFileEncoding.RAW)) {
            file.save(NBTSaveOptions.withoutBackup());
        }

        try (NBTFile<IntTag> reopened = NBTFile.openTag(source, IntTag.class, NBTCodec.of())) {
            assertEquals("answer", reopened.getEditor().snapshot().getName());
            assertEquals(42, reopened.getEditor().snapshot().get());
        }
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

    /// Treats a first-generation `.xyml_old` file as an ordinary source and creates the next
    /// backup generation beside it.
    ///
    /// @throws Exception if fixture creation or safe publication unexpectedly fails
    @Test
    void backsUpAnOldFileWithoutOverwritingItsSource() throws Exception {
        Path source = temporaryDirectory.resolve("level.dat.xyml_old");
        byte[] original = encode(new CompoundTag().addInt("value", 1), NBTFileEncoding.RAW);
        Files.write(source, original);

        try (NBTFile<CompoundTag> file = NBTFile.openTag(source, TagType.COMPOUND)) {
            NBTEditor<CompoundTag> editor = file.getEditor();
            editor.setScalar(editor.resolve(NBTAddress.root().appendName("value")), "2");
            file.save();
        }

        assertArrayEquals(original, Files.readAllBytes(source.resolveSibling("level.dat.xyml_old.xyml_old")));
        assertEquals(2, NBTCodec.of().readTag(source, TagType.COMPOUND).getInt("value"));
    }

    /// Keeps deterministic staging files out of the ordinary editor target set.
    ///
    /// @throws Exception if fixture creation unexpectedly fails
    @Test
    void rejectsDeterministicNewFileAsEditTarget() throws Exception {
        Path source = temporaryDirectory.resolve("level.dat.xyml_new");
        Files.write(source, encode(new CompoundTag().addInt("value", 1), NBTFileEncoding.RAW));

        assertThrows(IOException.class, () -> NBTFile.openTag(source, TagType.COMPOUND));
        assertThrows(IOException.class, () -> NBTFile.openTagTolerant(source, TagType.COMPOUND));
    }

    /// Rejects a source reached through a symbolic-link parent directory.
    @Test
    void rejectsStandaloneSourceUnderSymbolicParent() throws Exception {
        Path realDirectory = temporaryDirectory.resolve("real-source");
        Files.createDirectories(realDirectory);
        Path linkedDirectory = temporaryDirectory.resolve("linked-source");
        try {
            Files.createSymbolicLink(linkedDirectory, realDirectory);
        } catch (UnsupportedOperationException | IOException | SecurityException unsupported) {
            Assumptions.abort("symbolic links are unavailable on this filesystem");
        }
        Path source = linkedDirectory.resolve("level.dat");
        Files.write(source, encode(new CompoundTag().addInt("value", 1), NBTFileEncoding.RAW));

        assertThrows(IOException.class, () -> NBTFile.openTag(source, TagType.COMPOUND));
    }

    /// Allows an independently changed source and backs up its exact bytes before replacement.
    ///
    /// @throws Exception if fixture creation or opening unexpectedly fails
    @Test
    void allowsChangedSourceAndCreatesDefaultBackup() throws Exception {
        Path source = temporaryDirectory.resolve("stale.dat");
        byte[] initial = encode(new CompoundTag().addInt("value", 1), NBTFileEncoding.ZLIB);
        byte[] externallyUpdated = encode(new CompoundTag().addInt("value", 9), NBTFileEncoding.ZLIB);
        Files.write(source, initial);

        try (NBTFile<CompoundTag> file = NBTFile.openTag(source, TagType.COMPOUND)) {
            NBTEditor<CompoundTag> editor = file.getEditor();
            editor.setScalar(editor.resolve(NBTAddress.root().appendName("value")), "2");
            Files.write(source, externallyUpdated);

            file.save();
            assertFalse(editor.isDirty());
        }

        Path backup = temporaryDirectory.resolve("stale.dat.xyml_old");
        assertArrayEquals(externallyUpdated, Files.readAllBytes(backup));
        assertEquals(2, NBTCodec.of().readTag(source, TagType.COMPOUND).getInt("value"));
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

    /// Recovers a complete GZIP payload when only its eight-byte footer is missing.
    ///
    /// @throws Exception if fixture creation or tolerant opening unexpectedly fails
    @Test
    void classifiesMissingGzipFooterAsRecovered() throws Exception {
        Path source = temporaryDirectory.resolve("missing-gzip-footer.dat");
        byte[] encoded = encode(new CompoundTag().addInt("value", 1), NBTFileEncoding.GZIP);
        Files.write(source, Arrays.copyOf(encoded, encoded.length - 8));

        try (NBTFile<CompoundTag> file = NBTFile.openTagTolerant(source, TagType.COMPOUND)) {
            assertEquals(NBTReadReport.Severity.RECOVERED, file.readReport().severity());
            assertTrue(file.requiresRepair());
            assertEquals(1, file.getEditor().snapshot().getInt("value"));
        }
    }

    /// Recovers a GZIP member whose optional header checksum is wrong while preserving its payload.
    ///
    /// @throws Exception if fixture creation or tolerant opening unexpectedly fails
    @Test
    void classifiesDamagedGzipHeaderChecksumAsRecovered() throws Exception {
        Path source = temporaryDirectory.resolve("damaged-gzip-header-checksum.dat");
        byte[] encoded = encode(new CompoundTag().addInt("value", 1), NBTFileEncoding.GZIP);
        byte[] withHeaderChecksum = new byte[encoded.length + 2];
        System.arraycopy(encoded, 0, withHeaderChecksum, 0, 10);
        withHeaderChecksum[3] |= 0x02;
        CRC32 checksum = new CRC32();
        checksum.update(withHeaderChecksum, 0, 10);
        int checksumValue = (int) checksum.getValue();
        withHeaderChecksum[10] = (byte) checksumValue;
        withHeaderChecksum[11] = (byte) (checksumValue >>> 8);
        System.arraycopy(encoded, 10, withHeaderChecksum, 12, encoded.length - 10);
        withHeaderChecksum[10] ^= 0x01;
        Files.write(source, withHeaderChecksum);

        try (NBTFile<CompoundTag> file = NBTFile.openTagTolerant(source, TagType.COMPOUND)) {
            assertEquals(NBTReadReport.Severity.RECOVERED, file.readReport().severity());
            assertTrue(file.readReport().issues().stream()
                    .anyMatch(issue -> "GZIP_HEADER_CHECKSUM_INVALID".equals(issue.code())));
            assertEquals(1, file.getEditor().snapshot().getInt("value"));
        }
    }

    /// Classifies non-zero bytes after a complete LZ4 member as possible data loss.
    ///
    /// @throws Exception if fixture creation or tolerant opening unexpectedly fails
    @Test
    void classifiesLz4TrailingBytesAsPartialDataLoss() throws Exception {
        Path source = temporaryDirectory.resolve("trailing-lz4.dat");
        byte[] encoded = encode(new CompoundTag().addInt("value", 1), NBTFileEncoding.LZ4);
        byte[] trailing = Arrays.copyOf(encoded, encoded.length + 3);
        Arrays.fill(trailing, encoded.length, trailing.length, (byte) 0x55);
        Files.write(source, trailing);

        try (NBTFile<CompoundTag> file = NBTFile.openTagTolerant(source, TagType.COMPOUND)) {
            assertEquals(NBTReadReport.Severity.PARTIAL_DATA_LOSS, file.readReport().severity());
            assertTrue(file.requiresRepair());
            assertEquals(1, file.getEditor().snapshot().getInt("value"));
        }
    }

    /// Treats zero padding after a complete LZ4 member as a recoverable framing defect.
    ///
    /// @throws Exception if fixture creation or tolerant opening unexpectedly fails
    @Test
    void classifiesLz4ZeroPaddingAsRecovered() throws Exception {
        Path source = temporaryDirectory.resolve("padded-lz4.dat");
        byte[] encoded = encode(new CompoundTag().addInt("value", 1), NBTFileEncoding.LZ4);
        byte[] padded = Arrays.copyOf(encoded, encoded.length + 3);
        Files.write(source, padded);

        try (NBTFile<CompoundTag> file = NBTFile.openTagTolerant(source, TagType.COMPOUND)) {
            assertEquals(NBTReadReport.Severity.RECOVERED, file.readReport().severity());
            assertTrue(file.readReport().issues().stream()
                    .anyMatch(issue -> "LZ4_ZERO_PADDING".equals(issue.code())));
            assertTrue(file.requiresRepair());
            assertEquals(1, file.getEditor().snapshot().getInt("value"));
        }
    }

    /// Keeps the informational LZ4 extension warning visible through a strict open and save.
    ///
    /// @throws Exception if fixture creation or publication unexpectedly fails
    @Test
    void retainsStandaloneLz4ExtensionWarningAfterSave() throws Exception {
        Path source = temporaryDirectory.resolve("extension-lz4.dat");
        Files.write(source, encode(new CompoundTag().addInt("value", 1), NBTFileEncoding.LZ4));

        try (NBTFile<CompoundTag> file = NBTFile.openTag(source, TagType.COMPOUND)) {
            assertTrue(file.readReport().hasInformationalIssues());
            assertTrue(file.readReport().issues().stream()
                    .anyMatch(issue -> "LZ4_EXTENSION".equals(issue.code())));
            assertFalse(file.requiresRepair());
            file.save(NBTSaveOptions.withoutBackup());
            assertTrue(file.readReport().hasInformationalIssues());
            assertTrue(file.readReport().issues().stream()
                    .anyMatch(issue -> "LZ4_EXTENSION".equals(issue.code())));
        }
    }

    /// Stops tolerant recovery at a truncated list element instead of parsing a residual sibling tag.
    @Test
    void doesNotScanPastUncertainListBoundary() throws Exception {
        NBTReadResult<CompoundTag> result = NBTRepairReader.read(
                truncatedListWithResidualSibling(), CompoundTag.class, NBTCodec.of(), NBTReadLimits.defaults());

        assertNotNull(result.root().get("list"));
        assertTrue(result.root().get("after") == null,
                "bytes after an uncertain list boundary must not become a sibling tag");
        assertTrue(result.report().issues().stream()
                .anyMatch(issue -> "COMPOUND_CHILD_TRUNCATED".equals(issue.code())));
        assertTrue(result.report().issues().stream()
                .anyMatch(issue -> "TRAILING_BYTES".equals(issue.code())));
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

    /// Rejects an explicit backup path in the reserved deterministic staging namespace.
    ///
    /// @throws Exception if fixture creation or opening unexpectedly fails
    @Test
    void rejectsStagingFileAsBackupDestination() throws Exception {
        Path source = temporaryDirectory.resolve("staging-backup.dat");
        Path backup = temporaryDirectory.resolve("staging-backup.dat.xyml_new");
        byte[] original = encode(new CompoundTag().addInt("value", 1), NBTFileEncoding.RAW);
        Files.write(source, original);

        try (NBTFile<CompoundTag> file = NBTFile.openTag(source, TagType.COMPOUND)) {
            NBTEditor<CompoundTag> editor = file.getEditor();
            editor.setScalar(editor.resolve(NBTAddress.root().appendName("value")), "2");

            assertThrows(IOException.class, () -> file.save(NBTSaveOptions.withBackup(backup)));
            assertArrayEquals(original, Files.readAllBytes(source));
            assertFalse(Files.exists(backup));
            assertTrue(editor.isDirty());
        }
    }

    /// Rejects an oversized source before allocating an input snapshot.
    @Test
    void rejectsOversizedStandaloneBeforeReading() throws Exception {
        Path source = temporaryDirectory.resolve("oversized.dat");
        long oversizedPosition = NBTReadLimits.defaults().maxEncodedBytes();
        try (FileChannel channel = FileChannel.open(source, StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE)) {
            channel.position(oversizedPosition);
            channel.write(ByteBuffer.wrap(new byte[]{0}));
        }

        assertThrows(IOException.class, () -> NBTFile.openTagTolerant(source));
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

    /// Ignores an external Region header change when editor history is dirty but semantically unchanged.
    @Test
    void noOpRegionSaveIgnoresExternalHeaderChange() throws Exception {
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

            file.save();
            assertFalse(editor.isDirty());
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

    /// Builds a root compound containing a truncated nested list element followed by a plausible sibling.
    ///
    /// @return malformed raw Java Edition NBT bytes
    private static byte[] truncatedListWithResidualSibling() throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write(10); // root compound
        output.write(0);
        output.write(0); // root name
        output.write(9); // list tag
        output.write(0);
        output.write(4);
        output.write("list".getBytes(StandardCharsets.UTF_8));
        output.write(10); // list element type: compound
        output.write(new byte[]{0, 0, 0, 1}); // one element
        output.write(1); // nested byte child
        output.write(0);
        output.write(0); // nested child name
        output.write(42); // nested byte value
        output.write(99); // invalid child type; leaves the following bytes as residual
        output.write(3); // plausible sibling int tag
        output.write(0);
        output.write(5);
        output.write("after".getBytes(StandardCharsets.UTF_8));
        output.write(new byte[]{0, 0, 0, 7});
        output.write(0); // root TAG_End (must remain unconsumed after uncertainty)
        return output.toByteArray();
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
