/*
 * Copyright 2026 Glavo
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
// Added by MinecraftSTL in 2026 for copy-on-write region storage coverage.
package space.minecraftstl.xyml.library.nbt.io;

import space.minecraftstl.xyml.library.nbt.TestResources;
import space.minecraftstl.xyml.library.nbt.chunk.Chunk;
import space.minecraftstl.xyml.library.nbt.chunk.ChunkRegion;
import space.minecraftstl.xyml.library.nbt.internal.ChunkUtils;
import space.minecraftstl.xyml.library.nbt.tag.ByteArrayTag;
import space.minecraftstl.xyml.library.nbt.tag.CompoundTag;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies strict parsing and copy-on-write publication for Java region files.
@NotNullByDefault
public final class NBTRegionFileTest {
    /// Real-filesystem root required for force and atomic companion publication.
    @TempDir
    private Path temporaryDirectory;

    /// Writes, detaches, reopens, and clears one ordinary inline chunk.
    @Test
    void writesReadsAndClearsInlineChunk() throws Exception {
        Path file = temporaryDirectory.resolve("r.0.0.mca");
        CompoundTag root = new CompoundTag().addString("name", "before");
        try (NBTRegionFile region = NBTRegionFile.open(file)) {
            region.writeChunk(2, new Chunk(Instant.ofEpochSecond(1234), root));
            assertTrue(region.isDirty());
            region.flush();
            assertFalse(region.isDirty());

            Chunk read = region.readChunk(2);
            assertEquals(Instant.ofEpochSecond(1234), read.getTimestamp());
            assertEquals(root, read.getRootTag());
            read.getRootTag().setString("name", "detached");
            assertEquals("before", region.readChunk(2).getRootTag().getString("name"));

            region.clearChunk(2);
            region.flush();
            Chunk cleared = region.readChunk(2);
            assertNull(cleared.getRootTag());
            assertEquals(Instant.ofEpochSecond(1234), cleared.getTimestamp());
        }
        assertEquals(0L, Files.size(file) % 4096L);
    }

    /// Charges every strict slot read to one cumulative region budget.
    @Test
    void strictReadsShareCumulativeDecompressionBudget() throws Exception {
        Path file = temporaryDirectory.resolve("budget.0.0.mca");
        CompoundTag root = new CompoundTag().addInt("value", 1);
        try (NBTRegionFile region = NBTRegionFile.open(file)) {
            region.writeChunk(0, new Chunk(root), NBTRegionFile.CompressionType.UNCOMPRESSED);
            region.writeChunk(1, new Chunk(root), NBTRegionFile.CompressionType.UNCOMPRESSED);
            region.flush();
        }

        int onePayloadBytes = NBTCodec.of().writeTagToByteArray(root).length;
        ReadLimits limits = new ReadLimits(
                1_000_000L, 1_000_000L, onePayloadBytes,
                100L, 100L, 100L, 100L, 1_000L);
        ReadLimits.Budget budget = limits.newDocumentBudget();
        try (NBTRegionFile region = NBTRegionFile.open(file)) {
            assertDoesNotThrow(() -> region.readChunk(0, budget));
            assertThrows(IOException.class, () -> region.readChunk(1, budget));
        }
    }

    /// Charges tolerant slot trees to one region-wide logical-node budget.
    @Test
    void tolerantRegionSharesNodeBudgetAcrossSlots() throws Exception {
        Path file = temporaryDirectory.resolve("node-budget.0.0.mca");
        CompoundTag root = new CompoundTag().addInt("value", 1);
        try (NBTRegionFile region = NBTRegionFile.open(file)) {
            region.writeChunk(0, new Chunk(root), NBTRegionFile.CompressionType.UNCOMPRESSED);
            region.writeChunk(1, new Chunk(root), NBTRegionFile.CompressionType.UNCOMPRESSED);
            region.flush();
        }

        ReadLimits defaults = ReadLimits.defaults();
        ReadLimits limits = new ReadLimits(
                defaults.maxEncodedBytes(), defaults.maxDecompressedBytes(),
                defaults.maxDocumentDecompressedBytes(), 2L, defaults.maxDepth(),
                defaults.maxStringBytes(), defaults.maxArrayLength(), defaults.maxArrayBytes());
        try (NBTFile<ChunkRegion> fileSession = NBTFile.openRegionTolerant(file, limits)) {
            ChunkRegion opened = fileSession.getEditor().snapshot();
            assertEquals(1, opened.getChunk(0).getRootTag().getInt("value"));
            assertNull(opened.getChunk(1).getRootTag());
            assertEquals(NBTReadReport.Severity.PARTIAL_DATA_LOSS, fileSession.readReport().severity());
        }
    }

    /// Charges strict slot trees to one caller-owned logical-node budget.
    @Test
    void strictRegionSharesNodeBudgetAcrossSlots() throws Exception {
        Path file = temporaryDirectory.resolve("strict-node-budget.0.0.mca");
        CompoundTag root = new CompoundTag().addInt("value", 1);
        try (NBTRegionFile region = NBTRegionFile.open(file)) {
            region.writeChunk(0, new Chunk(root), NBTRegionFile.CompressionType.UNCOMPRESSED);
            region.writeChunk(1, new Chunk(root), NBTRegionFile.CompressionType.UNCOMPRESSED);
            region.flush();
        }

        ReadLimits defaults = ReadLimits.defaults();
        ReadLimits limits = new ReadLimits(
                defaults.maxEncodedBytes(), defaults.maxDecompressedBytes(),
                defaults.maxDocumentDecompressedBytes(), 2L, defaults.maxDepth(),
                defaults.maxStringBytes(), defaults.maxArrayLength(), defaults.maxArrayBytes());
        try (NBTRegionFile region = NBTRegionFile.open(file)) {
            ReadLimits.Budget byteBudget = limits.newDocumentBudget();
            ReadLimits.NodeBudget nodeBudget = limits.newNodeBudget();
            assertDoesNotThrow(() -> region.readChunk(0, byteBudget, nodeBudget));
            assertThrows(IOException.class, () -> region.readChunk(1, byteBudget, nodeBudget));
        }
    }

    /// Refuses to open the deterministic staging namespace as an editable region target.
    @Test
    void rejectsDeterministicNewRegionTarget() throws Exception {
        Path file = temporaryDirectory.resolve("r.0.0.mca.xyml_new");
        Files.write(file, new byte[ChunkUtils.SECTOR_BYTES * 2]);

        assertThrows(IOException.class, () -> NBTRegionFile.open(file));
        assertThrows(IOException.class, () -> NBTRegionFile.openTolerant(file));
    }

    /// Round-trips every compression identifier supported by the region format.
    ///
    /// @param compression selected chunk compression
    @ParameterizedTest
    @EnumSource(NBTRegionFile.CompressionType.class)
    void roundTripsEveryChunkCompression(NBTRegionFile.CompressionType compression) throws Exception {
        Path file = temporaryDirectory.resolve("r.1.1.mca");
        CompoundTag root = new CompoundTag().addInt("value", compression.id());
        try (NBTRegionFile region = NBTRegionFile.open(file)) {
            region.writeChunk(17, new Chunk(root), compression);
            region.flush();
            StorageProfile.RegionSlot profileSlot = region.storageProfile().regionSlot(17);
            assertEquals(compression.id(), profileSlot.marker());
            assertTrue(profileSlot.isOccupied());
            assertFalse(profileSlot.isExternal());
        }

        assertEquals(compression.id(), compressionMarker(file, 17) & 0x7F);
        try (NBTRegionFile reopened = NBTRegionFile.open(file)) {
            assertEquals(root, reopened.readChunk(17).getRootTag());
        }
    }

    /// Opens complete real-world zlib and LZ4 region fixtures already used by the codec suite.
    ///
    /// @param resource decompressed fixture resource
    @ParameterizedTest
    @ValueSource(strings = {"/assets/region/zlib.mca", "/assets/region/lz4.mca"})
    void validatesRealRegionFixtures(String resource) throws Exception {
        Path source = TestResources.getResource(resource);
        Path copy = temporaryDirectory.resolve("r.0.0.mca");
        Files.copy(source, copy);
        try (NBTRegionFile ignored = NBTRegionFile.open(copy)) {
            assertTrue(Files.size(copy) >= 8192L);
        }
    }

    /// Publishes payloads on both sides of the inline threshold and preserves an existing companion.
    @Test
    void convertsBetweenInlineAndExternalStorage() throws Exception {
        Path file = temporaryDirectory.resolve("r.0.0.mca");
        Path companion = temporaryDirectory.resolve("c.0.0.mcc");
        byte[] inline = payload(1_000_000);
        byte[] external = payload(1_100_000);

        try (NBTRegionFile region = NBTRegionFile.open(file)) {
            region.writeChunk(0, chunkWithPayload(inline), NBTRegionFile.CompressionType.UNCOMPRESSED);
            region.flush();
            assertEquals(0, compressionMarker(file, 0) & 0x80);
            assertFalse(Files.exists(companion));

            region.writeChunk(0, chunkWithPayload(external), NBTRegionFile.CompressionType.UNCOMPRESSED);
            region.flush();
            assertEquals(0x80, compressionMarker(file, 0) & 0x80);
            assertTrue(Files.isRegularFile(companion));
            assertEquals(1, region.storageProfileChanges().size());
            StorageProfileChange externalization = region.storageProfileChanges().get(0);
            assertEquals(0, externalization.localIndex());
            assertFalse(externalization.before().isExternal());
            assertTrue(externalization.after().isExternal());
            assertTrue(externalization.changedToExternal());
            assertEquals(NBTRegionFile.CompressionType.UNCOMPRESSED.id(), externalization.before().marker());
            assertEquals(NBTRegionFile.CompressionType.UNCOMPRESSED.id(), externalization.after().marker());
            CompoundTag externalRoot = new CompoundTag().addTag("payload", new ByteArrayTag(external));
            assertArrayEquals(NBTCodec.of().writeTagToByteArray(externalRoot), Files.readAllBytes(companion));
            assertEquals(chunkWithPayload(external).getRootTag(), region.readChunk(0).getRootTag());

            byte[] externalReplacement = external.clone();
            externalReplacement[0] ^= 1;
            region.writeChunk(0, chunkWithPayload(externalReplacement),
                    NBTRegionFile.CompressionType.UNCOMPRESSED);
            region.flush();
            assertEquals(chunkWithPayload(externalReplacement).getRootTag(), region.readChunk(0).getRootTag());

            CompoundTag smallRoot = new CompoundTag().addInt("value", 4);
            region.writeChunk(0, new Chunk(smallRoot));
            region.flush();
            assertEquals(0x80, compressionMarker(file, 0) & 0x80);
            assertTrue(Files.isRegularFile(companion));
            assertArrayEquals(NBTCodec.of().writeTagToByteArray(smallRoot),
                    Files.readAllBytes(companion));
        }
    }

    /// Rejects replacing an external marker in `.mcr` because its storage profile cannot be preserved.
    ///
    /// `.mcr` has no `.mcc` companion contract. A tolerant session may still encounter a stale
    /// external bit, but it must neither create a companion nor silently convert that slot inline.
    ///
    /// @throws Exception if fixture preparation or the bounded repair publication fails
    @Test
    void rejectsReplacingMcrExternalMarkerWithoutChangingStorage() throws Exception {
        Path mca = initialTwoChunkRegion();
        Path file = temporaryDirectory.resolve("r.0.0.mcr");
        Files.copy(mca, file);
        byte[] bytes = Files.readAllBytes(file);
        int frameOffset = sectorOffset(bytes, 0) * ChunkUtils.SECTOR_BYTES;
        bytes[frameOffset + Integer.BYTES] |= (byte) 0x80;
        Files.write(file, bytes);
        byte[] original = Files.readAllBytes(file);

        CompoundTag replacement = new CompoundTag().addInt("value", 42);
        try (NBTRegionFile region = NBTRegionFile.openTolerant(file)) {
            NBTReadResult<Chunk> damaged = region.readChunkTolerant(0);
            assertNull(damaged.root().getRootTag());
            assertTrue(damaged.report().issues().stream()
                    .anyMatch(issue -> "REGION_EXTERNAL_COMPANION_MISSING".equals(issue.code())));

            region.writeChunk(0, new Chunk(replacement));
            assertThrows(IOException.class, region::flush);
            assertTrue(region.isDirty());
        }

        assertArrayEquals(original, Files.readAllBytes(file));
        try (var files = Files.list(temporaryDirectory)) {
            assertFalse(files.anyMatch(path -> path.getFileName().toString()
                    .toLowerCase(java.util.Locale.ROOT).endsWith(".mcc")));
        }
    }

    /// Uses signed region coordinates when deriving a standard external companion filename.
    @Test
    void derivesExternalCompanionFromSignedCoordinates() throws Exception {
        Path file = temporaryDirectory.resolve("r.-2.3.mca");
        Path companion = temporaryDirectory.resolve("c.-33.127.mcc");
        try (NBTRegionFile region = NBTRegionFile.open(file)) {
            region.writeChunk(31, 31, chunkWithPayload(payload(1_100_000)),
                    NBTRegionFile.CompressionType.UNCOMPRESSED);
            region.flush();
        }
        assertTrue(Files.isRegularFile(companion));
    }

    /// Finds an existing external companion whose `.mcc` extension has different case.
    @Test
    void readsCaseInsensitiveExternalCompanionName() throws Exception {
        Path file = temporaryDirectory.resolve("r.0.0.mca");
        Path companion = temporaryDirectory.resolve("c.0.0.mcc");
        Path renameStage = temporaryDirectory.resolve("companion-rename.tmp");
        Path upperCompanion = temporaryDirectory.resolve("c.0.0.MCC");
        CompoundTag expected = new CompoundTag()
                .addTag("payload", new ByteArrayTag(randomPayload(1_100_000, 97L)));
        try (NBTRegionFile region = NBTRegionFile.open(file)) {
            region.writeChunk(0, new Chunk(expected), NBTRegionFile.CompressionType.UNCOMPRESSED);
            region.flush();
        }
        Files.move(companion, renameStage);
        Files.move(renameStage, upperCompanion);

        try (NBTRegionFile region = NBTRegionFile.open(file)) {
            assertEquals(expected, region.readChunk(0).getRootTag());
        }
    }

    /// Reuses a sector only after its old header entry has been cleared and forced.
    @Test
    void reusesReleasedSectorsWithoutGrowingSparseFile() throws Exception {
        Path file = temporaryDirectory.resolve("r.0.0.mca");
        try (NBTRegionFile region = NBTRegionFile.open(file)) {
            region.writeChunk(0, new Chunk(new CompoundTag().addInt("slot", 0)),
                    NBTRegionFile.CompressionType.UNCOMPRESSED);
            region.writeChunk(1023, new Chunk(new CompoundTag().addInt("slot", 1023)),
                    NBTRegionFile.CompressionType.UNCOMPRESSED);
            region.flush();
            long size = Files.size(file);
            int releasedOffset = sectorOffset(file, 0);

            region.clearChunk(0);
            region.flush();
            region.writeChunk(512, new Chunk(new CompoundTag().addInt("slot", 512)),
                    NBTRegionFile.CompressionType.UNCOMPRESSED);
            region.flush();

            assertEquals(releasedOffset, sectorOffset(file, 512));
            assertEquals(size, Files.size(file));
            assertEquals(1023, region.readChunk(1023).getRootTag().getInt("slot"));
        }
    }

    /// Rejects malformed, overlapping, and out-of-file header entries before returning a session.
    @Test
    void rejectsInvalidHeaders() throws Exception {
        Path overlap = temporaryDirectory.resolve("r.0.0.mca");
        byte[] overlapping = new byte[3 * 4096];
        setLocation(overlapping, 0, 2, 1);
        setLocation(overlapping, 1, 2, 1);
        Files.write(overlap, overlapping);
        assertThrows(IOException.class, () -> NBTRegionFile.open(overlap));

        Path outside = temporaryDirectory.resolve("r.1.0.mca");
        byte[] outOfFile = new byte[2 * 4096];
        setLocation(outOfFile, 0, 2, 1);
        Files.write(outside, outOfFile);
        assertThrows(IOException.class, () -> NBTRegionFile.open(outside));

        Path halfEmpty = temporaryDirectory.resolve("r.2.0.mca");
        byte[] half = new byte[2 * 4096];
        setLocation(half, 0, 0, 1);
        Files.write(halfEmpty, half);
        assertThrows(IOException.class, () -> NBTRegionFile.open(halfEmpty));
    }

    /// Isolates overlapping slots while leaving a disjoint valid slot readable.
    @Test
    void tolerantOpenIsolatesOverlappingSlotsOnly() throws Exception {
        Path file = threeChunkRegion();
        byte[] bytes = Files.readAllBytes(file);
        setLocation(bytes, 1, sectorOffset(bytes, 0), 1);
        Files.write(file, bytes);

        try (NBTRegionFile region = NBTRegionFile.openTolerant(file)) {
            NBTReadResult<Chunk> first = region.readChunkTolerant(0);
            NBTReadResult<Chunk> second = region.readChunkTolerant(1);
            assertNull(first.root().getRootTag());
            assertNull(second.root().getRootTag());
            assertTrue(first.report().issues().stream()
                    .anyMatch(issue -> "REGION_HEADER_OVERLAP".equals(issue.code())));
            assertEquals(9, region.readChunkTolerant(2).root().getRootTag().getInt("value"));
        }
    }

    /// Isolates a maximum 24-bit out-of-range offset without overflowing range arithmetic.
    @Test
    void tolerantOpenIsolatesMaximumSectorOffsetOnly() throws Exception {
        Path file = threeChunkRegion();
        byte[] bytes = Files.readAllBytes(file);
        setLocation(bytes, 0, 0xFF_FFFF, 0xFF);
        Files.write(file, bytes);

        try (NBTRegionFile region = NBTRegionFile.openTolerant(file)) {
            NBTReadResult<Chunk> invalid = region.readChunkTolerant(0);
            assertNull(invalid.root().getRootTag());
            assertTrue(invalid.report().issues().stream()
                    .anyMatch(issue -> "REGION_HEADER_SLOT_OUT_OF_RANGE".equals(issue.code())));
            assertEquals(1, region.readChunkTolerant(1).root().getRootTag().getInt("value"));
            assertEquals(9, region.readChunkTolerant(2).root().getRootTag().getInt("value"));
        }
    }

    /// Rejects arithmetic ranges that cannot be represented as one addressable byte array.
    @Test
    void rejectsByteRangesWhoseLongEndWouldOverflow() throws Exception {
        try (FileChannel channel = FileChannel.open(
                threeChunkRegion(), StandardOpenOption.READ)) {
            assertThrows(IOException.class, () -> NBTRegionFileIO.readBytes(
                    channel, Long.MAX_VALUE - 1L, 4L));
        }
    }

    /// Isolates a missing external companion while retaining another valid slot.
    @Test
    void tolerantOpenIsolatesMissingCompanionOnly() throws Exception {
        Path file = threeChunkRegion();
        byte[] bytes = Files.readAllBytes(file);
        int frameOffset = sectorOffset(bytes, 0) * ChunkUtils.SECTOR_BYTES;
        bytes[frameOffset + Integer.BYTES] |= (byte) 0x80;
        Files.write(file, bytes);

        try (NBTRegionFile region = NBTRegionFile.openTolerant(file)) {
            NBTReadResult<Chunk> invalid = region.readChunkTolerant(0);
            assertNull(invalid.root().getRootTag());
            assertTrue(invalid.report().issues().stream()
                    .anyMatch(issue -> "REGION_EXTERNAL_COMPANION_MISSING".equals(issue.code())));
            assertEquals(1, region.readChunkTolerant(1).root().getRootTag().getInt("value"));
            assertEquals(9, region.readChunkTolerant(2).root().getRootTag().getInt("value"));
        }
    }

    /// Isolates an external slot when companion discovery itself fails without blocking other slots.
    @Test
    void tolerantOpenIsolatesUnavailableCompanionOnly() throws Exception {
        Path file = threeChunkRegion();
        byte[] bytes = Files.readAllBytes(file);
        int frameOffset = sectorOffset(bytes, 0) * ChunkUtils.SECTOR_BYTES;
        bytes[frameOffset + Integer.BYTES] |= (byte) 0x80;
        Files.write(file, bytes);
        ExternalChunkAccessor failingAccessor = new ExternalChunkAccessor() {
            /// Fails discovery for the damaged slot while leaving other slots unsupported.
            ///
            /// @param localX local chunk X coordinate
            /// @param localZ local chunk Z coordinate
            /// @return no stream for non-damaged slots
            /// @throws IOException when the damaged slot is probed
            @Override
            public @Nullable InputStream openInputStream(int localX, int localZ) throws IOException {
                if (localX == 0 && localZ == 0) {
                    throw new IOException("injected companion discovery failure");
                }
                return null;
            }
        };

        try (NBTRegionFile region = NBTRegionFile.openTolerant(file, failingAccessor)) {
            NBTReadResult<Chunk> invalid = region.readChunkTolerant(0);
            assertNull(invalid.root().getRootTag());
            assertTrue(invalid.report().issues().stream()
                    .anyMatch(issue -> "REGION_EXTERNAL_COMPANION_UNAVAILABLE".equals(issue.code())));
            assertEquals(1, region.readChunkTolerant(1).root().getRootTag().getInt("value"));
            assertEquals(9, region.readChunkTolerant(2).root().getRootTag().getInt("value"));
        }
    }

    /// Rejects a new region path whose parent is a symbolic link.
    @Test
    void rejectsRegionUnderSymbolicParent() throws Exception {
        Path realDirectory = temporaryDirectory.resolve("real-region");
        Files.createDirectories(realDirectory);
        Path linkedDirectory = temporaryDirectory.resolve("linked-region");
        try {
            Files.createSymbolicLink(linkedDirectory, realDirectory);
        } catch (UnsupportedOperationException | IOException | SecurityException unsupported) {
            Assumptions.abort("symbolic links are unavailable on this filesystem");
        }

        assertThrows(IOException.class, () -> NBTRegionFile.open(linkedDirectory.resolve("r.0.0.mca")));
    }

    /// Does not use a source fingerprint when another writer changes a companion.
    @Test
    void allowsAnExternallyChangedCompanion() throws Exception {
        Path file = temporaryDirectory.resolve("r.0.0.mca");
        Path companion = temporaryDirectory.resolve("c.0.0.mcc");
        try (NBTRegionFile initial = NBTRegionFile.open(file)) {
            initial.writeChunk(0, chunkWithPayload(payload(1_100_000)),
                    NBTRegionFile.CompressionType.UNCOMPRESSED);
            initial.flush();
        }

        try (NBTRegionFile region = NBTRegionFile.open(file)) {
            region.writeChunk(1, new Chunk(new CompoundTag().addInt("value", 1)));
            byte[] changed = Files.readAllBytes(companion);
            changed[changed.length - 1] ^= 1;
            Files.write(companion, changed);
            region.flush();
            assertEquals(List.of(), region.dirtyChunkIndexes());
        }
    }

    /// Detects an atomic replacement of the main path instead of writing an unlinked old handle.
    @Test
    void rejectsAtomicallyReplacedMainRegionPath() throws Exception {
        Path file = initialTwoChunkRegion();
        Path replacement = temporaryDirectory.resolve("replacement.mca");
        try (NBTRegionFile replacementRegion = NBTRegionFile.open(replacement)) {
            replacementRegion.writeChunk(0, new Chunk(new CompoundTag().addInt("value", 99)));
            replacementRegion.flush();
        }

        try (NBTRegionFile region = NBTRegionFile.open(file)) {
            region.writeChunk(0, new Chunk(new CompoundTag().addInt("value", 2)));
            try {
                Files.move(
                        replacement,
                        file,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException | AccessDeniedException unsupported) {
                return;
            }

            assertThrows(IOException.class, region::flush);
            assertEquals(List.of(0), region.dirtyChunkIndexes());
            assertThrows(IOException.class, () -> region.readChunk(0));
        }
        assertEquals(99, readValue(file, 0));
    }

    /// Leaves the old header visible at each inline failure point before a forced header switch.
    ///
    /// @param failedStageName injected commit stage
    @ParameterizedTest
    @ValueSource(strings = {"PAYLOAD_WRITTEN", "PAYLOAD_FORCED", "HEADER_WRITTEN"})
    void retainsOldChunkWhenInlineStageFails(String failedStageName) throws Exception {
        Path file = initialTwoChunkRegion();
        NBTRegionFile.CommitStage failedStage = NBTRegionFile.CommitStage.valueOf(failedStageName);
        NBTRegionFile.CommitHook failure = (stage, index) -> {
            if (index == 0 && stage == failedStage) {
                throw new IOException("injected payload failure");
            }
        };
        try (NBTRegionFile region = NBTRegionFile.open(file, ExternalChunkAccessor.of(file), failure)) {
            region.writeChunk(0, new Chunk(new CompoundTag().addInt("value", 2)));
            assertThrows(IOException.class, region::flush);
            assertEquals(List.of(0), region.dirtyChunkIndexes());
        }
        assertEquals(1, readValue(file, 0));
    }

    /// Restores an allocation after a write fails so a retry can commit.
    ///
    /// @param runtimeFailure whether the first allocation failure is unchecked
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void retriesAfterAllocationWriteFailure(boolean runtimeFailure) throws Exception {
        Path file = initialTwoChunkRegion();
        AtomicBoolean firstAllocation = new AtomicBoolean(true);
        NBTRegionFile.CommitHook failure = (stage, index) -> {
            if (index == 0
                    && stage == NBTRegionFile.CommitStage.ALLOCATION_WRITTEN
                    && firstAllocation.compareAndSet(true, false)) {
                if (runtimeFailure) {
                    throw new IllegalStateException("injected runtime allocation failure");
                }
                throw new IOException("injected allocation failure");
            }
        };
        try (NBTRegionFile region = NBTRegionFile.open(file, ExternalChunkAccessor.of(file), failure)) {
            region.writeChunk(0, new Chunk(new CompoundTag().addInt("value", 2)));
            assertThrows(IOException.class, region::flush);
            assertEquals(List.of(0), region.dirtyChunkIndexes());

            region.flush();
            assertFalse(region.isDirty());
        }
        assertEquals(2, readValue(file, 0));
    }

    /// Reports committed indexes and leaves only uncommitted indexes dirty after a prefix save.
    @Test
    void reportsPartialMultiChunkCommit() throws Exception {
        Path file = initialTwoChunkRegion();
        NBTRegionFile.CommitHook failure = (stage, index) -> {
            if (index == 1 && stage == NBTRegionFile.CommitStage.PAYLOAD_WRITTEN) {
                throw new IOException("injected second-chunk failure");
            }
        };
        try (NBTRegionFile region = NBTRegionFile.open(file, ExternalChunkAccessor.of(file), failure)) {
            region.writeChunk(0, new Chunk(new CompoundTag().addInt("value", 10)));
            region.writeChunk(1, new Chunk(new CompoundTag().addInt("value", 11)));
            NBTPartialSaveException exception = assertThrows(NBTPartialSaveException.class, region::flush);
            assertEquals(List.of(0), exception.committedIndexes());
            assertEquals(1, exception.failedIndex());
            assertEquals(List.of(1), region.dirtyChunkIndexes());
        }
        assertEquals(10, readValue(file, 0));
        assertEquals(1, readValue(file, 1));
    }

    /// Distinguishes post-header failures from failures before the new chunk became visible.
    ///
    /// @param failedStageName injected committed stage
    @ParameterizedTest
    @ValueSource(strings = {"HEADER_FORCED", "CLEANUP"})
    void reportsPostCommitFailureAsCommitted(String failedStageName) throws Exception {
        Path file = initialTwoChunkRegion();
        NBTRegionFile.CommitStage failedStage = NBTRegionFile.CommitStage.valueOf(failedStageName);
        NBTRegionFile.CommitHook failure = (stage, index) -> {
            if (index == 0 && stage == failedStage) {
                throw new IOException("injected cleanup failure");
            }
        };
        try (NBTRegionFile region = NBTRegionFile.open(file, ExternalChunkAccessor.of(file), failure)) {
            region.writeChunk(0, new Chunk(new CompoundTag().addInt("value", 20)));
            NBTPartialSaveException exception = assertThrows(NBTPartialSaveException.class, region::flush);
            assertEquals(List.of(0), exception.committedIndexes());
            assertEquals(-1, exception.failedIndex());
            assertFalse(region.isDirty());
        }
        assertEquals(20, readValue(file, 0));
    }

    /// Preserves the committed inline payload after unchecked post-commit failures.
    ///
    /// @param failedStageName injected committed stage
    @ParameterizedTest
    @ValueSource(strings = {"HEADER_FORCED", "CLEANUP"})
    void reportsRuntimePostCommitFailureAsCommitted(String failedStageName) throws Exception {
        Path file = initialTwoChunkRegion();
        NBTRegionFile.CommitStage failedStage = NBTRegionFile.CommitStage.valueOf(failedStageName);
        NBTRegionFile.CommitHook failure = (stage, index) -> {
            if (index == 0 && stage == failedStage) {
                throw new IllegalStateException("injected runtime post-commit failure");
            }
        };
        try (NBTRegionFile region = NBTRegionFile.open(file, ExternalChunkAccessor.of(file), failure)) {
            region.writeChunk(0, new Chunk(new CompoundTag().addInt("value", 20)));
            NBTPartialSaveException exception = assertThrows(NBTPartialSaveException.class, region::flush);
            assertEquals(List.of(0), exception.committedIndexes());
            assertEquals(-1, exception.failedIndex());
            assertFalse(region.isDirty());
        }
        assertEquals(20, readValue(file, 0));
    }

    /// Fails the session closed when both a header switch and its rollback report failure.
    ///
    /// @param runtimeRollback whether rollback fails with an unchecked exception
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void reportsUncertainHeaderStateAndRequiresReopen(boolean runtimeRollback) throws Exception {
        Path file = initialTwoChunkRegion();
        NBTRegionFile.CommitHook failure = (stage, index) -> {
            if (index == 0 && stage == NBTRegionFile.CommitStage.HEADER_WRITTEN) {
                throw new IOException("injected header failure");
            }
            if (index == 0 && stage == NBTRegionFile.CommitStage.HEADER_ROLLBACK) {
                if (runtimeRollback) {
                    throw new IllegalStateException("injected runtime rollback failure");
                }
                throw new IOException("injected header rollback failure");
            }
        };
        try (NBTRegionFile region = NBTRegionFile.open(file, ExternalChunkAccessor.of(file), failure)) {
            region.writeChunk(0, new Chunk(new CompoundTag().addInt("value", 30)));
            assertThrows(NBTCommitUncertainException.class, region::flush);
            assertThrows(IOException.class, () -> region.readChunk(0));
        }
        try (NBTRegionFile reopened = NBTRegionFile.open(file)) {
            int visible = reopened.readChunk(0).getRootTag().getInt("value");
            assertTrue(visible == 1 || visible == 30);
        }
    }

    /// Refuses to overwrite an unrelated companion and to change compression in an external window.
    @Test
    void protectsExternalCompanionOwnershipAndCompression() throws Exception {
        Path unrelatedFile = temporaryDirectory.resolve("r.4.4.mca");
        Path unrelatedCompanion = temporaryDirectory.resolve("c.128.128.mcc");
        Files.write(unrelatedCompanion, new byte[]{1, 2, 3});
        try (NBTRegionFile region = NBTRegionFile.open(unrelatedFile)) {
            region.writeChunk(0, chunkWithPayload(payload(1_100_000)),
                    NBTRegionFile.CompressionType.UNCOMPRESSED);
            assertThrows(IOException.class, region::flush);
            assertArrayEquals(new byte[]{1, 2, 3}, Files.readAllBytes(unrelatedCompanion));
        }

        Path file = temporaryDirectory.resolve("r.0.0.mca");
        byte[] random = new byte[1_100_000];
        new Random(42L).nextBytes(random);
        try (NBTRegionFile region = NBTRegionFile.open(file)) {
            region.writeChunk(0, chunkWithPayload(random), NBTRegionFile.CompressionType.UNCOMPRESSED);
            region.flush();
            region.writeChunk(0, chunkWithPayload(random), NBTRegionFile.CompressionType.GZIP);
            assertDoesNotThrow(region::flush);
            assertEquals(List.of(), region.dirtyChunkIndexes());
        }
        assertEquals(NBTRegionFile.CompressionType.UNCOMPRESSED.id(), compressionMarker(file, 0) & 0x7F);
    }

    /// Restores an owned companion after every injected external failure before header commit.
    ///
    /// @param failedStageName injected external commit stage
    @ParameterizedTest
    @ValueSource(strings = {"COMPANION_PUBLISHED", "PAYLOAD_WRITTEN", "PAYLOAD_FORCED", "HEADER_WRITTEN"})
    void restoresExternalCompanionBeforeHeaderCommit(String failedStageName) throws Exception {
        Path file = temporaryDirectory.resolve("r.0.0.mca");
        Path companion = temporaryDirectory.resolve("c.0.0.mcc");
        byte[] original = payload(1_100_000);
        byte[] replacement = original.clone();
        replacement[0] ^= 1;
        try (NBTRegionFile initial = NBTRegionFile.open(file)) {
            initial.writeChunk(0, chunkWithPayload(original), NBTRegionFile.CompressionType.UNCOMPRESSED);
            initial.flush();
        }
        byte[] originalCompanion = Files.readAllBytes(companion);
        NBTRegionFile.CommitStage failedStage = NBTRegionFile.CommitStage.valueOf(failedStageName);
        NBTRegionFile.CommitHook failure = (stage, index) -> {
            if (index == 0 && stage == failedStage) {
                throw new IOException("injected external failure");
            }
        };

        try (NBTRegionFile region = NBTRegionFile.open(file, ExternalChunkAccessor.of(file), failure)) {
            region.writeChunk(0, chunkWithPayload(replacement), NBTRegionFile.CompressionType.UNCOMPRESSED);
            assertThrows(IOException.class, region::flush);
            assertEquals(List.of(0), region.dirtyChunkIndexes());
        }

        assertArrayEquals(originalCompanion, Files.readAllBytes(companion));
        try (NBTRegionFile reopened = NBTRegionFile.open(file)) {
            assertEquals(chunkWithPayload(original).getRootTag(), reopened.readChunk(0).getRootTag());
        }
    }

    /// Reads custom-accessor companions only through that accessor and refuses non-atomic writes.
    @Test
    void honorsCustomExternalAccessorBoundaries() throws Exception {
        Path file = temporaryDirectory.resolve("r.0.0.mca");
        Path standardCompanion = temporaryDirectory.resolve("c.0.0.mcc");
        byte[] original = randomPayload(1_100_000, 41L);
        try (NBTRegionFile initial = NBTRegionFile.open(file)) {
            initial.writeChunk(0, chunkWithPayload(original), NBTRegionFile.CompressionType.UNCOMPRESSED);
            initial.flush();
        }
        byte[] detachedCompanion = Files.readAllBytes(standardCompanion);
        byte[] unrelatedSibling = NBTCodec.of().writeTagToByteArray(
                new CompoundTag().addString("source", "unrelated sibling"));
        Files.write(standardCompanion, unrelatedSibling);
        AtomicInteger reads = new AtomicInteger();
        ExternalChunkAccessor accessor = new ExternalChunkAccessor() {
            /// Returns one detached in-memory companion stream.
            ///
            /// @param localX local chunk X coordinate
            /// @param localZ local chunk Z coordinate
            /// @return companion stream for slot zero, or `null`
            @Override
            public @Nullable InputStream openInputStream(int localX, int localZ) {
                if (localX != 0 || localZ != 0) {
                    return null;
                }
                reads.incrementAndGet();
                return new ByteArrayInputStream(detachedCompanion);
            }
        };

        try (NBTRegionFile region = NBTRegionFile.open(file, accessor)) {
            assertEquals(chunkWithPayload(original).getRootTag(), region.readChunk(0).getRootTag());
            region.writeChunk(0, chunkWithPayload(randomPayload(1_100_000, 42L)),
                    NBTRegionFile.CompressionType.UNCOMPRESSED);
            assertThrows(IOException.class, region::flush);
            assertEquals(List.of(0), region.dirtyChunkIndexes());
        }
        assertTrue(reads.get() >= 2);
        assertArrayEquals(unrelatedSibling, Files.readAllBytes(standardCompanion));
    }

    /// Locks the session when an external companion cannot be restored after a header failure.
    @Test
    void reportsUncertainCompanionRollbackAndRequiresReopen() throws Exception {
        Path file = temporaryDirectory.resolve("r.0.0.mca");
        byte[] original = randomPayload(1_100_000, 51L);
        byte[] replacement = randomPayload(1_100_000, 52L);
        try (NBTRegionFile initial = NBTRegionFile.open(file)) {
            initial.writeChunk(0, chunkWithPayload(original), NBTRegionFile.CompressionType.UNCOMPRESSED);
            initial.flush();
        }
        NBTRegionFile.CommitHook failure = (stage, index) -> {
            if (index == 0 && (stage == NBTRegionFile.CommitStage.HEADER_WRITTEN
                    || stage == NBTRegionFile.CommitStage.COMPANION_ROLLBACK)) {
                throw new IOException("injected companion rollback failure");
            }
        };

        try (NBTRegionFile region = NBTRegionFile.open(file, ExternalChunkAccessor.of(file), failure)) {
            region.writeChunk(0, chunkWithPayload(replacement), NBTRegionFile.CompressionType.UNCOMPRESSED);
            assertThrows(NBTCommitUncertainException.class, region::flush);
            assertThrows(IOException.class, () -> region.readChunk(0));
            assertEquals(List.of(0), region.dirtyChunkIndexes());
            assertThrows(IllegalStateException.class,
                    () -> region.writeChunk(1, new Chunk(new CompoundTag().addInt("value", 1))));
        }
        try (NBTRegionFile reopened = NBTRegionFile.open(file)) {
            assertEquals(chunkWithPayload(replacement).getRootTag(), reopened.readChunk(0).getRootTag());
        }
    }

    /// Locks the session when publishing an external backup and restoring its staged copy both fail.
    @Test
    void reportsUncertainCompanionBackupRollbackAndRequiresReopen() throws Exception {
        Path file = temporaryDirectory.resolve("r.0.0.mca");
        byte[] original = randomPayload(1_100_000, 53L);
        byte[] replacement = randomPayload(1_100_000, 54L);
        try (NBTRegionFile initial = NBTRegionFile.open(file)) {
            initial.writeChunk(0, chunkWithPayload(original), NBTRegionFile.CompressionType.UNCOMPRESSED);
            initial.flush();
        }
        NBTRegionFile.CommitHook failure = (stage, index) -> {
            if (index == 0 && (stage == NBTRegionFile.CommitStage.COMPANION_BACKUP_PUBLISH
                    || stage == NBTRegionFile.CommitStage.COMPANION_ROLLBACK)) {
                throw new IOException("injected companion backup rollback failure");
            }
        };

        try (NBTRegionFile region = NBTRegionFile.open(file, ExternalChunkAccessor.of(file), failure)) {
            region.writeChunk(0, chunkWithPayload(replacement), NBTRegionFile.CompressionType.UNCOMPRESSED);
            assertThrows(NBTCommitUncertainException.class, region::flush);
            assertThrows(IOException.class, () -> region.readChunk(0));
            assertEquals(List.of(0), region.dirtyChunkIndexes());
            assertThrows(IllegalStateException.class,
                    () -> region.writeChunk(1, new Chunk(new CompoundTag().addInt("value", 1))));
        }
    }

    /// Allows a source-header edit made by another writer; no source fingerprint conflict is used.
    @Test
    void allowsExternalHeaderEditDuringFlush() throws Exception {
        Path file = initialTwoChunkRegion();
        NBTRegionFile.CommitHook editHeader = (stage, index) -> {
            if (stage == NBTRegionFile.CommitStage.HEADER_WRITTEN && index == 0) {
                overwriteTimestampByte(file, 12, (byte) 0x55);
            }
        };
        try (NBTRegionFile region = NBTRegionFile.open(file, ExternalChunkAccessor.of(file), editHeader)) {
            region.writeChunk(0, new Chunk(new CompoundTag().addInt("value", 10)));
            region.writeChunk(1, new Chunk(new CompoundTag().addInt("value", 11)));
            region.flush();
            assertFalse(region.isDirty());
        }
        assertEquals(10, readValue(file, 0));
        assertEquals(11, readValue(file, 1));
    }

    /// Does not reject an external source change when no chunk is pending.
    @Test
    void emptyFlushIgnoresExternalSourceChange() throws Exception {
        Path file = initialTwoChunkRegion();
        try (NBTRegionFile region = NBTRegionFile.open(file)) {
            overwriteTimestampByte(file, 0, (byte) 0x55);

            region.flush();
            assertFalse(region.isDirty());
        }
    }

    /// Repairs an incomplete trailing sector during an explicit tolerant save.
    @Test
    void tolerantSaveRemovesIncompleteTrailingSector() throws Exception {
        Path file = initialTwoChunkRegion();
        Files.write(file, new byte[]{0x55}, StandardOpenOption.APPEND);

        try (NBTRegionFile region = NBTRegionFile.openTolerant(file)) {
            assertEquals(NBTReadReport.Severity.PARTIAL_DATA_LOSS, region.readReport().severity());
            region.flush();
            assertEquals(0L, Files.size(file) % ChunkUtils.SECTOR_BYTES);
            assertEquals(NBTReadReport.Severity.CLEAN, region.readReport().severity());
        }

        try (NBTRegionFile reopened = NBTRegionFile.open(file)) {
            assertEquals(1, reopened.readChunk(0).getRootTag().getInt("value"));
        }
    }

    /// Preserves a complete physical payload when only the inline frame length is damaged.
    @Test
    void preservesRecoverablePrefixFromTruncatedInlineFrame() throws Exception {
        Path file = initialTwoChunkRegion();
        byte[] bytes = Files.readAllBytes(file);
        int slotOffset = sectorOffset(bytes, 0);
        int frameOffset = slotOffset * ChunkUtils.SECTOR_BYTES;
        ByteBuffer.wrap(bytes, frameOffset, Integer.BYTES).putInt(Integer.MAX_VALUE);
        Files.write(file, bytes);

        try (NBTRegionFile region = NBTRegionFile.openTolerant(file)) {
            NBTReadResult<Chunk> result = region.readChunkTolerant(0);
            assertNotNull(result.root().getRootTag(), result.report().toString());
            assertEquals(1, result.root().getRootTag().getInt("value"));
            assertTrue(result.report().issues().stream()
                    .anyMatch(issue -> "REGION_FRAME_LENGTH_CLAMPED".equals(issue.code())
                            || "REGION_FRAME_INVALID".equals(issue.code())
                            || "REGION_SLOT_ISOLATED".equals(issue.code())),
                    result.report().issues().toString());

            ChunkRegion snapshot = new ChunkRegion();
            ChunkRegion baseline = new ChunkRegion();
            snapshot.setChunk(0, result.root().clone());
            baseline.setChunk(0, result.root().clone());
            region.synchronizePendingChanges(snapshot, baseline);
            assertTrue(region.isDirty());
            region.flush();
        }

        try (NBTRegionFile reopened = NBTRegionFile.open(file)) {
            assertEquals(1, reopened.readChunk(0).getRootTag().getInt("value"));
            assertEquals(1, reopened.readChunk(1).getRootTag().getInt("value"));
        }
    }

    /// Requires an explicit clear or replacement before publishing an unknown marker slot.
    @Test
    void tolerantSaveRequiresExplicitUnknownMarkerReplacement() throws Exception {
        Path file = initialTwoChunkRegion();
        byte[] bytes = Files.readAllBytes(file);
        int slotOffset = sectorOffset(bytes, 0);
        bytes[slotOffset * ChunkUtils.SECTOR_BYTES + Integer.BYTES] = 5;
        Files.write(file, bytes);

        try (NBTRegionFile region = NBTRegionFile.openTolerant(file)) {
            ChunkRegion snapshot = new ChunkRegion();
            ChunkRegion baseline = new ChunkRegion();
            Chunk valid = region.readChunkTolerant(1).root();
            snapshot.setChunk(1, valid.clone());
            baseline.setChunk(1, valid.clone());

            region.synchronizePendingChanges(snapshot, baseline);
            assertThrows(IOException.class, region::flush);
            region.clearChunk(0);
            assertDoesNotThrow(region::flush);
            assertEquals(NBTReadReport.Severity.CLEAN, region.readReport().severity());
        }

        try (NBTRegionFile reopened = NBTRegionFile.open(file)) {
            assertNull(reopened.readChunk(0).getRootTag());
            assertEquals(1, reopened.readChunk(1).getRootTag().getInt("value"));
        }
    }

    /// Rejects an opaque compression marker when a tolerant session has no explicit replacement.
    @Test
    void tolerantEmptyFlushRejectsUnknownMarker() throws Exception {
        Path file = initialTwoChunkRegion();
        byte[] bytes = Files.readAllBytes(file);
        int slotOffset = sectorOffset(bytes, 0);
        bytes[slotOffset * ChunkUtils.SECTOR_BYTES + Integer.BYTES] = 5;
        Files.write(file, bytes);

        try (NBTRegionFile region = NBTRegionFile.openTolerant(file)) {
            assertThrows(IOException.class, region::flush);
        }
    }

    /// Removes the reversible companion backup after a post-header external failure is reported.
    ///
    /// @param runtimeFailure whether the injected failure is unchecked
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void cleansExternalBackupAfterPostCommitFailure(boolean runtimeFailure) throws Exception {
        Path file = temporaryDirectory.resolve("r.0.0.mca");
        try (NBTRegionFile initial = NBTRegionFile.open(file)) {
            initial.writeChunk(0, chunkWithPayload(randomPayload(1_100_000, 61L)),
                    NBTRegionFile.CompressionType.UNCOMPRESSED);
            initial.flush();
        }
        NBTRegionFile.CommitHook failure = (stage, index) -> {
            if (index == 0 && stage == NBTRegionFile.CommitStage.HEADER_FORCED) {
                if (runtimeFailure) {
                    throw new IllegalStateException("injected runtime post-commit failure");
                }
                throw new IOException("injected post-commit failure");
            }
        };
        try (NBTRegionFile region = NBTRegionFile.open(file, ExternalChunkAccessor.of(file), failure)) {
            region.writeChunk(0, chunkWithPayload(randomPayload(1_100_000, 62L)),
                    NBTRegionFile.CompressionType.UNCOMPRESSED);
            NBTPartialSaveException exception = assertThrows(NBTPartialSaveException.class, region::flush);
            assertEquals(List.of(0), exception.committedIndexes());
            assertEquals(-1, exception.failedIndex());
        }
        try (var files = Files.list(temporaryDirectory)) {
            assertFalse(files.anyMatch(path -> path.getFileName().toString().endsWith(".old")));
        }
        try (NBTRegionFile reopened = NBTRegionFile.open(file)) {
            assertEquals(chunkWithPayload(randomPayload(1_100_000, 62L)).getRootTag(),
                    reopened.readChunk(0).getRootTag());
        }
    }

    /// Keeps an external replacement readable when unchecked cleanup fails after header commit.
    @Test
    void reportsRuntimeExternalCleanupAsCommitted() throws Exception {
        Path file = temporaryDirectory.resolve("r.0.0.mca");
        byte[] original = randomPayload(1_100_000, 71L);
        byte[] replacement = randomPayload(1_100_000, 72L);
        try (NBTRegionFile initial = NBTRegionFile.open(file)) {
            initial.writeChunk(0, chunkWithPayload(original), NBTRegionFile.CompressionType.UNCOMPRESSED);
            initial.flush();
        }
        NBTRegionFile.CommitHook failure = (stage, index) -> {
            if (index == 0 && stage == NBTRegionFile.CommitStage.CLEANUP) {
                throw new IllegalStateException("injected runtime cleanup failure");
            }
        };
        try (NBTRegionFile region = NBTRegionFile.open(file, ExternalChunkAccessor.of(file), failure)) {
            region.writeChunk(0, chunkWithPayload(replacement), NBTRegionFile.CompressionType.UNCOMPRESSED);
            NBTPartialSaveException exception = assertThrows(NBTPartialSaveException.class, region::flush);
            assertEquals(List.of(0), exception.committedIndexes());
            assertEquals(-1, exception.failedIndex());
            assertFalse(region.isDirty());
            assertDoesNotThrow(region::flush);
            try (var files = Files.list(temporaryDirectory)) {
                assertFalse(files.anyMatch(path -> path.getFileName().toString().endsWith(".old")));
            }
        }
        try (NBTRegionFile reopened = NBTRegionFile.open(file)) {
            assertEquals(chunkWithPayload(replacement).getRootTag(), reopened.readChunk(0).getRootTag());
        }
    }

    /// Never restores a new companion after the main path changes following a forced header write.
    @Test
    void locksWithoutRestoringCompanionWhenPathChangesAfterHeaderWrite() throws Exception {
        Assumptions.assumeFalse(
                System.getProperty("os.name", "").contains("Windows"),
                "Windows keeps an open region channel from being atomically replaced");
        Path file = temporaryDirectory.resolve("r.0.0.mca");
        Path companion = temporaryDirectory.resolve("c.0.0.mcc");
        byte[] original = randomPayload(1_100_000, 81L);
        byte[] replacement = randomPayload(1_100_000, 82L);
        try (NBTRegionFile initial = NBTRegionFile.open(file)) {
            initial.writeChunk(0, chunkWithPayload(original), NBTRegionFile.CompressionType.UNCOMPRESSED);
            initial.flush();
        }
        byte[] originalCompanion = Files.readAllBytes(companion);
        Path replacementFile = temporaryDirectory.resolve("replacement.mca");
        try (NBTRegionFile independent = NBTRegionFile.open(replacementFile)) {
            independent.writeChunk(0, new Chunk(new CompoundTag().addInt("value", 99)));
            independent.flush();
        }
        Path atomicProbeSource = temporaryDirectory.resolve("atomic-probe-source.mca");
        Path atomicProbeTarget = temporaryDirectory.resolve("atomic-probe-target.mca");
        Files.writeString(atomicProbeSource, "source");
        Files.writeString(atomicProbeTarget, "target");
        try {
            Files.move(
                    atomicProbeSource,
                    atomicProbeTarget,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException | AccessDeniedException unsupported) {
            Assumptions.assumeTrue(false, "atomic replacement is unavailable on this filesystem");
        } finally {
            Files.deleteIfExists(atomicProbeSource);
            Files.deleteIfExists(atomicProbeTarget);
        }
        NBTRegionFile.CommitHook replacementHook = (stage, index) -> {
            if (index == 0 && stage == NBTRegionFile.CommitStage.HEADER_WRITTEN) {
                Files.move(
                        replacementFile,
                        file,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            }
        };
        try (NBTRegionFile region = NBTRegionFile.open(file, ExternalChunkAccessor.of(file), replacementHook)) {
            region.writeChunk(0, chunkWithPayload(replacement), NBTRegionFile.CompressionType.UNCOMPRESSED);
            assertThrows(NBTCommitUncertainException.class, region::flush);
            assertEquals(List.of(0), region.dirtyChunkIndexes());
            assertThrows(IOException.class, () -> region.readChunk(0));
        }
        assertEquals(99, readValue(file, 0));
        assertFalse(Arrays.equals(originalCompanion, Files.readAllBytes(companion)));
    }

    /// Retains observable pending state after close without publishing it behind the caller's back.
    @Test
    void closeRetainsPendingStateWithoutPublishing() throws Exception {
        Path file = temporaryDirectory.resolve("r.0.0.mca");
        NBTRegionFile region = NBTRegionFile.open(file);
        region.writeChunk(0, new Chunk(new CompoundTag().addInt("value", 7)));
        region.close();
        assertTrue(region.isDirty());
        assertEquals(List.of(0), region.dirtyChunkIndexes());
        assertThrows(IOException.class, region::flush);
        try (NBTRegionFile reopened = NBTRegionFile.open(file)) {
            assertNull(reopened.readChunk(0).getRootTag());
        }
    }

    /// Rejects trailing bytes in external GZIP and LZ4 payloads during open validation.
    ///
    /// @param compression external compression format
    @ParameterizedTest
    @EnumSource(value = NBTRegionFile.CompressionType.class, names = {"GZIP", "LZ4"})
    void rejectsTrailingExternalCompressedPayload(NBTRegionFile.CompressionType compression) throws Exception {
        Path file = temporaryDirectory.resolve("r.0.0.mca");
        Path companion = temporaryDirectory.resolve("c.0.0.mcc");
        try (NBTRegionFile initial = NBTRegionFile.open(file)) {
            initial.writeChunk(0, chunkWithPayload(randomPayload(1_100_000, 71L)), compression);
            initial.flush();
        }
        Files.write(companion, new byte[]{1}, StandardOpenOption.APPEND);
        assertThrows(IOException.class, () -> {
            try (NBTRegionFile ignored = NBTRegionFile.open(file)) {
                // Validation must reject the trailing byte before exposing the session.
            }
        });
    }

    /// Rejects an oversized sparse companion before allocating its declared size.
    @Test
    void rejectsOversizedExternalCompanion() throws Exception {
        Path file = temporaryDirectory.resolve("r.0.0.mca");
        Path companion = temporaryDirectory.resolve("c.0.0.mcc");
        try (NBTRegionFile initial = NBTRegionFile.open(file)) {
            initial.writeChunk(0, chunkWithPayload(randomPayload(1_100_000, 81L)),
                    NBTRegionFile.CompressionType.UNCOMPRESSED);
            initial.flush();
        }
        try (FileChannel output = FileChannel.open(companion, StandardOpenOption.WRITE)) {
            output.write(ByteBuffer.wrap(new byte[]{0}), 65L * 1024L * 1024L);
        }
        assertThrows(IOException.class, () -> {
            try (NBTRegionFile ignored = NBTRegionFile.open(file)) {
                // Validation must enforce the input bound before exposing the session.
            }
        });
    }

    /// Accepts a valid chunk placed after a large unused sector gap.
    @Test
    void opensLogicallySparseRegion() throws Exception {
        Path file = temporaryDirectory.resolve("r.0.0.mca");
        int localIndex = 700;
        CompoundTag root = new CompoundTag().addString("layout", "sparse");
        try (NBTRegionFile initial = NBTRegionFile.open(file)) {
            initial.writeChunk(localIndex, new Chunk(root), NBTRegionFile.CompressionType.UNCOMPRESSED);
            initial.flush();
        }
        byte[] compact = Files.readAllBytes(file);
        int oldOffset = sectorOffset(compact, localIndex);
        int sparseOffset = 64;
        byte[] sparse = new byte[(sparseOffset + 1) * 4096];
        System.arraycopy(compact, 0, sparse, 0, 8192);
        System.arraycopy(compact, oldOffset * 4096, sparse, sparseOffset * 4096, 4096);
        setLocation(sparse, localIndex, sparseOffset, 1);
        Files.write(file, sparse);

        try (NBTRegionFile reopened = NBTRegionFile.open(file)) {
            assertEquals(root, reopened.readChunk(localIndex).getRootTag());
        }
    }

    /// Uses the safe path storage implementation instead of truncating an existing region first.
    @Test
    void pathCodecDelegatesToCopyOnWriteStorage() throws Exception {
        Path file = temporaryDirectory.resolve("r.0.0.mca");
        try (NBTRegionFile initial = NBTRegionFile.open(file)) {
            initial.writeChunk(0, new Chunk(new CompoundTag().addString("value", "old")),
                    NBTRegionFile.CompressionType.UNCOMPRESSED);
            initial.flush();
        }
        int oldOffset = sectorOffset(file, 0);
        ChunkRegion replacement = new ChunkRegion();
        replacement.setChunk(0, new Chunk(Instant.ofEpochSecond(99),
                new CompoundTag().addString("value", "new")));

        NBTCodec.of().writeRegion(file, replacement);

        assertNotEquals(oldOffset, sectorOffset(file, 0));
        assertEquals(replacement, NBTCodec.of().readRegion(file));
    }

    /// Creates a two-slot baseline used by failure-injection tests.
    ///
    /// @return initialized region path
    private Path initialTwoChunkRegion() throws Exception {
        Path file = temporaryDirectory.resolve("r.0.0.mca");
        try (NBTRegionFile region = NBTRegionFile.open(file)) {
            region.writeChunk(0, new Chunk(new CompoundTag().addInt("value", 1)));
            region.writeChunk(1, new Chunk(new CompoundTag().addInt("value", 1)));
            region.flush();
        }
        return file;
    }

    /// Creates three disjoint valid slots for tolerant-isolation tests.
    ///
    /// @return initialized region path
    private Path threeChunkRegion() throws Exception {
        Path file = temporaryDirectory.resolve("r.3.0.mca");
        try (NBTRegionFile region = NBTRegionFile.open(file)) {
            region.writeChunk(0, new Chunk(new CompoundTag().addInt("value", 0)));
            region.writeChunk(1, new Chunk(new CompoundTag().addInt("value", 1)));
            region.writeChunk(2, new Chunk(new CompoundTag().addInt("value", 9)));
            region.flush();
        }
        return file;
    }

    /// Reads one fixture value after opening through the strict region path.
    ///
    /// @param file region path
    /// @param localIndex local chunk slot
    /// @return stored integer value
    private static int readValue(Path file, int localIndex) throws Exception {
        try (NBTRegionFile region = NBTRegionFile.open(file)) {
            return region.readChunk(localIndex).getRootTag().getInt("value");
        }
    }

    /// Creates a chunk containing one primitive byte array.
    ///
    /// @param bytes payload bytes
    /// @return detached chunk
    private static Chunk chunkWithPayload(byte[] bytes) {
        return new Chunk(new CompoundTag().addTag("payload", new ByteArrayTag(bytes)));
    }

    /// Creates deterministic payload bytes which do not require a random source.
    ///
    /// @param length payload length
    /// @return payload bytes
    private static byte[] payload(int length) {
        byte[] result = new byte[length];
        for (int index = 0; index < result.length; index++) {
            result[index] = (byte) (index * 31);
        }
        return result;
    }

    /// Creates deterministic incompressible-looking payload bytes.
    ///
    /// @param length payload length
    /// @param seed random seed
    /// @return deterministic payload bytes
    private static byte[] randomPayload(int length, long seed) {
        byte[] result = new byte[length];
        new Random(seed).nextBytes(result);
        return result;
    }

    /// Simulates an external writer changing one otherwise harmless timestamp byte.
    ///
    /// @param file region path
    /// @param localIndex timestamp slot
    /// @param value replacement byte
    /// @throws IOException if the byte cannot be written
    private static void overwriteTimestampByte(Path file, int localIndex, byte value) throws IOException {
        try (FileChannel output = FileChannel.open(file, StandardOpenOption.WRITE)) {
            output.write(ByteBuffer.wrap(new byte[]{value}), 4096L + (long) localIndex * Integer.BYTES);
            output.force(true);
        }
    }

    /// Reads the compression marker byte for one occupied slot.
    ///
    /// @param file region path
    /// @param localIndex local chunk slot
    /// @return unsigned marker byte
    private static int compressionMarker(Path file, int localIndex) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        int offset = sectorOffset(bytes, localIndex);
        return Byte.toUnsignedInt(bytes[offset * 4096 + 4]);
    }

    /// Reads a location entry's sector offset.
    ///
    /// @param file region path
    /// @param localIndex local chunk slot
    /// @return sector offset
    private static int sectorOffset(Path file, int localIndex) throws IOException {
        return sectorOffset(Files.readAllBytes(file), localIndex);
    }

    /// Reads a location entry's sector offset from complete region bytes.
    ///
    /// @param bytes complete region bytes
    /// @param localIndex local chunk slot
    /// @return sector offset
    private static int sectorOffset(byte[] bytes, int localIndex) {
        int index = localIndex * 4;
        return (Byte.toUnsignedInt(bytes[index]) << 16)
                | (Byte.toUnsignedInt(bytes[index + 1]) << 8)
                | Byte.toUnsignedInt(bytes[index + 2]);
    }

    /// Writes one raw location header entry into a fixture byte array.
    ///
    /// @param bytes region fixture bytes
    /// @param localIndex local chunk slot
    /// @param offset sector offset
    /// @param length sector count
    private static void setLocation(byte[] bytes, int localIndex, int offset, int length) {
        int index = localIndex * 4;
        bytes[index] = (byte) (offset >>> 16);
        bytes[index + 1] = (byte) (offset >>> 8);
        bytes[index + 2] = (byte) offset;
        bytes[index + 3] = (byte) length;
    }
}
