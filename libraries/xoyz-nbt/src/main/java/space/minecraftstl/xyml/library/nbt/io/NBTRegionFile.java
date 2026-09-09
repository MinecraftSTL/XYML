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
// Added by MinecraftSTL in 2026 for copy-on-write XoyzNBT region editing.
package space.minecraftstl.xyml.library.nbt.io;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import space.minecraftstl.xyml.library.nbt.chunk.Chunk;
import space.minecraftstl.xyml.library.nbt.chunk.ChunkRegion;
import space.minecraftstl.xyml.library.nbt.internal.ChunkUtils;
import space.minecraftstl.xyml.library.nbt.internal.ExternalChunkAccessors;
import space.minecraftstl.xyml.library.nbt.internal.input.InputSource;
import space.minecraftstl.xyml.library.nbt.internal.input.NBTInput;
import space.minecraftstl.xyml.library.nbt.internal.input.RawDataReader;
import space.minecraftstl.xyml.library.nbt.tag.CompoundTag;
import space.minecraftstl.xyml.library.nbt.tag.Tag;
import space.minecraftstl.xyml.library.nbt.validation.NBTStructureValidator;
import space.minecraftstl.xyml.library.nbt.validation.NBTValidationException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static space.minecraftstl.xyml.library.nbt.io.NBTRegionFileIO.asIOException;
import static space.minecraftstl.xyml.library.nbt.io.NBTRegionFileIO.checkIndex;
import static space.minecraftstl.xyml.library.nbt.io.NBTRegionFileIO.readIssue;
import static space.minecraftstl.xyml.library.nbt.io.NBTRegionFileIO.slotPath;
import static space.minecraftstl.xyml.library.nbt.io.NBTRegionFileIO.withSlotPath;

/// A safe, copy-on-write editor for one Java Anvil region file.
/// Changed chunks are serialized into unreferenced sectors before their locations are published.
/// Failed flushes retain readable old headers; already published chunks remain committed.
/// Region files expose 1024 slots and pass chunk values by deep copy.
@NotNullByDefault
public final class NBTRegionFile implements AutoCloseable {
    /// Commit milestones exposed to deterministic package-local tests.
    @NotNullByDefault
    enum CommitStage {
        /// Newly reserved sectors initialized without changing the header.
        ALLOCATION_WRITTEN,
        /// New inline or external-marker bytes written.
        PAYLOAD_WRITTEN,
        /// Legacy post-payload boundary retained for package-test compatibility.
        PAYLOAD_FORCED,
        /// New external companion replaced its temporary file.
        COMPANION_PUBLISHED,
        /// Existing companion backup is about to become the reversible published backup.
        COMPANION_BACKUP_PUBLISH,
        /// Replacement header bytes written.
        HEADER_WRITTEN,
        /// Legacy post-header boundary retained for package-test compatibility.
        HEADER_FORCED,
        /// Old storage is about to be cleaned up after commit.
        CLEANUP,
        /// Failed header publication is about to restore the old header.
        HEADER_ROLLBACK,
        /// Failed external publication is about to restore the old companion.
        COMPANION_ROLLBACK,
        /// Legacy boundary retained for package-test compatibility; no fingerprint is captured.
        FINGERPRINT_CAPTURE
    }

    /// Package-local deterministic failure boundary used by region transaction tests.
    @FunctionalInterface
    @NotNullByDefault
    interface CommitHook {
        /// Observes one commit stage and may inject an I/O failure.
        /// @param stage reached stage
        /// @param localIndex affected chunk slot
        /// @throws IOException to simulate a failure at this boundary
        void reach(CommitStage stage, int localIndex) throws IOException;
    }

    /// Production hook which never injects a failure.
    private static final CommitHook NO_COMMIT_HOOK = (stage, localIndex) -> {
    };

    /// Combined byte size of the location and timestamp header sectors.
    private static final int HEADER_BYTES = 2 * ChunkUtils.SECTOR_BYTES;
    /// Largest sector count representable by one region location entry.
    private static final int MAX_SECTOR_COUNT = 0xFF;
    /// Largest complete chunk frame which can be stored inside the region file.
    private static final int MAX_INLINE_BYTES = MAX_SECTOR_COUNT * ChunkUtils.SECTOR_BYTES;
    /// Shared default limit for one decompressed chunk payload.
    private static final int MAX_DECOMPRESSED_BYTES = Math.toIntExact(
            NBTReadLimits.defaults().maxDecompressedBytes());
    /// Shared default limit for one encoded chunk payload, including external companions.
    private static final int MAX_COMPRESSED_BYTES = Math.toIntExact(
            NBTReadLimits.defaults().maxEncodedBytes());

    /// Normalized path of the open region file.
    private final Path path;
    /// Channel owning all reads, copy-on-write payload writes, and header publication.
    private final FileChannel channel;
    /// Accessor used to locate or read external chunk companions.
    private final ExternalChunkAccessor accessor;
    /// Commit-stage observer used by tests and inert in production.
    private final CommitHook commitHook;
    /// Current sector offset for each local chunk slot.
    private final int[] sectorOffsets;
    /// Current allocated sector count for each local chunk slot.
    private final int[] sectorLengths;
    /// Current unsigned epoch-second timestamp bits for each local chunk slot.
    private final int[] timestamps;
    /// Current compression identifier for each occupied local chunk slot.
    private final byte[] compressionTypes;
    /// Whether each occupied local chunk slot resolves its payload from a companion file.
    private final boolean[] external;
    /// Slots whose header geometry cannot be safely associated with a payload.
    private final boolean[] isolatedSlots;
    /// Diagnostics observed while opening or recovering slots.
    private final List<NBTReadIssue> readIssues;
    /// Whether a tolerant open ignored bytes after the last complete sector.
    private boolean trailingTailNeedsRepair;
    /// Allocation bitmap including both header sectors and every currently reserved payload sector.
    private final BitSet usedSectors;
    /// Detached edits waiting to be published, keyed by local chunk index.
    private final Map<Integer, PendingChunk> pending = new HashMap<>();
    /// Owned publication sidecars which could not be removed after a committed write.
    private final NBTRegionFileIO.PendingCleanup pendingCleanup =
            new NBTRegionFileIO.PendingCleanup("Owned region sidecar");
    /// Whether this session has released its file channel.
    private boolean closed;
    /// Whether a publication outcome is uncertain and the session must not serve further reads or writes.
    private boolean commitLocked;

    /// Compression methods understood by the Anvil chunk format.
    @NotNullByDefault
    public enum CompressionType {
        /// GZip compression.
        GZIP(1),
        /// ZLIB compression (the default for newly written chunks).
        ZLIB(2),
        /// No compression.
        UNCOMPRESSED(3),
        /// LZ4 block compression.
        LZ4(4);

        /// Numeric compression identifier stored in the chunk frame marker.
        private final int id;

        /// Creates a compression type with its region-format identifier.
        /// @param id region-format compression identifier
        CompressionType(int id) {
            this.id = id;
        }

        /// Returns the Anvil compression identifier.
        public int id() {
            return id;
        }

        /// Resolves a supported region-format compression identifier.
        /// @param id unsigned compression identifier
        /// @return matching compression type
        /// @throws IOException if the identifier is unsupported
        private static CompressionType fromId(int id) throws IOException {
            for (CompressionType type : values()) {
                if (type.id == id) {
                    return type;
                }
            }
            throw new IOException("Unsupported region chunk compression type: " + id);
        }
    }

    /// Detached pending chunk together with the compression selected for its next publication.
    /// @param chunk detached chunk, or `null` for an explicit clear operation
    /// @param compression compression to use when a root payload is present
    /// @param explicitReplacement whether the caller explicitly replaced or cleared this slot
    @NotNullByDefault
    private record PendingChunk(
            @Nullable Chunk chunk,
            CompressionType compression,
            boolean explicitReplacement) {
    }

    /// Creates an initialized session around an already validated open channel.
    /// @param path normalized region path
    /// @param channel owned read-write file channel
    /// @param accessor external companion accessor
    /// @param commitHook commit-stage observer
    /// @param sectorOffsets validated location offsets
    /// @param sectorLengths validated location lengths
    /// @param timestamps raw timestamp values
    /// @param compressionTypes decoded compression identifiers
    /// @param external decoded external-payload flags
    /// @param usedSectors initial sector allocation bitmap
    private NBTRegionFile(Path path, FileChannel channel,
                          ExternalChunkAccessor accessor, CommitHook commitHook,
                          int[] sectorOffsets, int[] sectorLengths, int[] timestamps,
                          byte[] compressionTypes, boolean[] external, boolean[] isolatedSlots,
                          BitSet usedSectors, boolean trailingTailNeedsRepair,
                          List<NBTReadIssue> openingIssues) {
        this.path = path;
        this.channel = channel;
        this.accessor = accessor;
        this.commitHook = commitHook;
        this.sectorOffsets = sectorOffsets;
        this.sectorLengths = sectorLengths;
        this.timestamps = timestamps;
        this.compressionTypes = compressionTypes;
        this.external = external;
        this.isolatedSlots = isolatedSlots;
        this.usedSectors = usedSectors;
        this.trailingTailNeedsRepair = trailingTailNeedsRepair;
        this.readIssues = new ArrayList<>(Objects.requireNonNull(openingIssues, "openingIssues"));
    }

    /// Opens or creates a region file and validates its complete header and sector framing.
    /// A new file is initialized with two zero-filled header sectors. Existing files must have a
    /// sector-aligned length, non-overlapping chunk sectors, and valid chunk framing.
    /// @param path region file path
    /// @return an open region session
    /// @throws IOException if the file cannot be opened or fails structural validation
    @Contract("_ -> new")
    public static NBTRegionFile open(Path path) throws IOException {
        Objects.requireNonNull(path, "path");
        return open(path, ExternalChunkAccessor.of(path));
    }

    /// Opens or creates a region file with an explicit external-chunk accessor.
    /// The accessor is used for both validating existing external chunks and reading them. A
    /// copy-on-write write of an oversized chunk requires the accessor to identify a filesystem
    /// companion path (the standard [ExternalChunkAccessor#of(Path)] accessor does so).
    /// @param path region file path
    /// @param accessor external chunk locator
    /// @return an open region session
    /// @throws IOException if the file cannot be opened or fails structural validation
    @Contract("_, _ -> new")
    public static NBTRegionFile open(Path path, ExternalChunkAccessor accessor) throws IOException {
        return open(path, accessor, NO_COMMIT_HOOK);
    }

    /// Opens a region while isolating payload and slot errors for later tolerant reads.
    /// Header bytes remain structurally bounded. Invalid payloads are reported by
    /// [#readChunkTolerant(int, NBTReadLimits)] instead of preventing the other slots from opening.
    /// @param path region file path
    /// @return an open tolerant region session
    /// @throws IOException if the file cannot be opened or its length/header envelope is unusable
    @Contract("_ -> new")
    public static NBTRegionFile openTolerant(Path path) throws IOException {
        Objects.requireNonNull(path, "path");
        return openTolerant(path, ExternalChunkAccessor.of(path));
    }

    /// Opens a region with an explicit external-chunk accessor in tolerant mode.
    /// @param path region file path
    /// @param accessor external chunk locator
    /// @return an open tolerant region session
    /// @throws IOException if the file cannot be opened or its length/header envelope is unusable
    @Contract("_, _ -> new")
    public static NBTRegionFile openTolerant(Path path, ExternalChunkAccessor accessor) throws IOException {
        return open(path, accessor, NO_COMMIT_HOOK, true);
    }

    /// Opens a region session with a deterministic package-local commit hook.
    /// @param path region file path
    /// @param accessor external chunk locator
    /// @param commitHook commit-stage observer
    /// @return an open region session
    /// @throws IOException if the region cannot be opened and validated
    static NBTRegionFile open(Path path, ExternalChunkAccessor accessor, CommitHook commitHook) throws IOException {
        return open(path, accessor, commitHook, false);
    }

    /// Opens a region with an optional tolerant payload policy for package-local file sessions.
    private static NBTRegionFile open(Path path, ExternalChunkAccessor accessor, CommitHook commitHook,
                                      boolean tolerant) throws IOException {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(accessor, "accessor");
        Objects.requireNonNull(commitHook, "commitHook");
        Path absolute = path.toAbsolutePath().normalize();
        if (NBTRegionFileIO.isStagingPath(absolute)) {
            throw new IOException("NBT staging files are not valid region edit targets: " + absolute);
        }
        @Nullable Path parent = absolute.getParent();
        if (parent != null) {
            NBTRegionFileIO.createDirectoriesNoFollow(parent);
        }

        NBTRegionFileIO.ensureRegionFileExists(absolute);
        @Nullable FileChannel channel = null;
        boolean success = false;
        try {
            channel = FileChannel.open(absolute, StandardOpenOption.READ,
                    StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
            if (channel.size() == 0L) {
                NBTRegionFileIO.writeZeros(channel, 0L, HEADER_BYTES);
            }
            HeaderData header = readAndValidateHeader(absolute, channel, accessor, tolerant);
            NBTRegionFile result = new NBTRegionFile(absolute, channel, accessor, commitHook,
                    header.offsets, header.lengths,
                    header.timestamps, header.compressionTypes, header.external, header.isolatedSlots,
                    header.usedSectors, header.trailingBytes > 0L, header.issues);
            if (!tolerant) {
                result.validateExistingPayloads();
            }
            success = true;
            return result;
        } finally {
            if (!success) {
                if (channel != null) {
                    channel.close();
                }
            }
        }
    }

    /// Returns the path opened by this session.
    /// @return normalized region path
    public Path path() {
        return path;
    }

    /// Returns an immutable snapshot of the current per-slot storage markers.
    /// The snapshot includes empty slots, the low-seven-bit compression marker, the external
    /// companion flag, and the current occupancy bit. Pending writes are intentionally excluded
    /// until [#flush()] publishes their headers.
    /// @return immutable 1024-slot region storage profile
    public synchronized StorageProfile storageProfile() {
        byte[] markers = compressionTypes.clone();
        boolean[] externalFlags = external.clone();
        boolean[] occupied = new boolean[ChunkUtils.CHUNKS_PRE_REGION];
        for (int localIndex = 0; localIndex < occupied.length; localIndex++) {
            occupied[localIndex] = sectorLengths[localIndex] != 0;
        }
        return StorageProfile.region(markers, externalFlags, occupied);
    }

    /// Bean-style alias for [#storageProfile()].
    /// @return immutable 1024-slot region storage profile
    public synchronized StorageProfile getStorageProfile() {
        return storageProfile();
    }

    /// Returns whether this session has pending chunk changes.
    /// @return `true` when at least one chunk is pending
    public boolean isDirty() {
        return !pending.isEmpty();
    }

    /// Returns a stable snapshot of pending local indexes in ascending order.
    /// @return immutable ascending local-index snapshot
    public @Unmodifiable List<Integer> dirtyChunkIndexes() {
        List<Integer> indexes = new ArrayList<>(pending.keySet());
        Collections.sort(indexes);
        return List.copyOf(indexes);
    }

    /// Replaces every pending slot with the differences between a snapshot and its committed baseline.
    /// The complete replacement map is built before the current pending state changes. This lets an
    /// owning [NBTFile] cancel a failed pending write when a later editor snapshot returns that slot
    /// to its committed value, while preserving the existing or previously selected compression for
    /// slots which still differ.
    /// @param snapshot complete detached editor snapshot
    /// @param committedBaseline complete detached state known to be visible on disk
    void synchronizePendingChanges(ChunkRegion snapshot, ChunkRegion committedBaseline) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(committedBaseline, "committedBaseline");
        ensureOpenUnchecked();
        Map<Integer, PendingChunk> replacement = new HashMap<>();
        for (int localIndex = 0; localIndex < snapshot.size(); localIndex++) {
            Chunk changed = snapshot.getChunk(localIndex);
            if (!changed.equals(committedBaseline.getChunk(localIndex)) || slotNeedsRepair(localIndex)) {
                // A tolerant read may materialize a damaged known-format slot as an unchanged empty chunk. Treat its
                // confirmed repair publication as an explicit replacement. Unknown compression markers are excluded
                // by slotNeedsRepair and still require the caller to explicitly replace or clear that slot.
                boolean explicitReplacement = !changed.equals(committedBaseline.getChunk(localIndex))
                        || slotNeedsRepair(localIndex);
                replacement.put(localIndex,
                        new PendingChunk(changed.clone(), preferredCompression(localIndex), explicitReplacement));
            }
        }
        pending.clear();
        pending.putAll(replacement);
    }

    /// Reads a chunk as a detached deep copy.
    /// An empty slot is represented by a `Chunk` with a `null` root tag. A pending clear is
    /// visible immediately and returns a fresh empty chunk.
    /// @param localIndex local slot from 0 through 1023
    /// @return detached chunk copy
    /// @throws IOException if the chunk payload is malformed or cannot be decoded
    public Chunk readChunk(int localIndex) throws IOException {
        return readChunk(localIndex, NBTReadLimits.defaults().newDocumentBudget());
    }

    /// Reads a chunk while charging decompressed bytes to a caller-owned region budget.
    /// The package-local overload is used by strict region opens so all occupied slots share one
    /// cumulative limit. Public callers retain the historical per-call limit through
    /// [#readChunk(int)].
    /// @param localIndex local slot from 0 through 1023
    /// @param budget cumulative decompressed-byte budget for the containing read
    /// @return detached chunk copy
    /// @throws IOException if the chunk payload is malformed, cannot be decoded, or exceeds the budget
    Chunk readChunk(int localIndex, NBTReadLimits.Budget budget) throws IOException {
        checkIndex(localIndex);
        ensureOpen();
        NBTRegionFileIO.requireRegularFile(path);
        NBTReadLimits.Budget selectedBudget = Objects.requireNonNull(budget, "budget");
        @Nullable PendingChunk changed = pending.get(localIndex);
        if (changed != null) {
            return changed.chunk == null
                    ? new Chunk(NBTRegionFileIO.timestamp(timestamps, localIndex))
                    : changed.chunk.clone();
        }

        int offset = sectorOffsets[localIndex];
        int length = sectorLengths[localIndex];
        if (offset == 0 && length == 0) {
            return new Chunk(NBTRegionFileIO.timestamp(timestamps, localIndex));
        }

        byte[] sector = NBTRegionFileIO.readBytes(channel, (long) offset * ChunkUtils.SECTOR_BYTES,
                (long) length * ChunkUtils.SECTOR_BYTES);
        ChunkPayload payload = readPayload(localIndex, sector, length);
        int remainingLimit = Math.toIntExact(Math.min(
                (long) MAX_DECOMPRESSED_BYTES, selectedBudget.remaining()));
        byte[] decompressed = decompress(payload.compression, payload.compressed, remainingLimit);
        selectedBudget.consume(decompressed.length);
        CompoundTag root = parseCompound(decompressed, localIndex);
        if (payload.compression == CompressionType.LZ4) {
            rememberIssues(List.of(readIssue(NBTReadIssue.Severity.INFORMATIONAL,
                    "REGION_LZ4_EXTENSION", slotPath(localIndex),
                    "检测到扩展 LZ4 槽位标记，保存时将原样保留")));
        }
        return new Chunk(NBTRegionFileIO.timestamp(timestamps, localIndex), root);
    }

    /// Reads a chunk by local X/Z coordinates as a detached deep copy.
    /// @param localX local X coordinate from 0 through 31
    /// @param localZ local Z coordinate from 0 through 31
    /// @return detached chunk copy
    /// @throws IOException if the chunk payload is malformed or cannot be decoded
    public Chunk readChunk(int localX, int localZ) throws IOException {
        return readChunk(ChunkUtils.toLocalIndex(
                NBTRegionFileIO.checkedCoordinate(localX), NBTRegionFileIO.checkedCoordinate(localZ)));
    }

    /// Reads one chunk while isolating malformed payloads from the other region slots.
    /// A damaged or missing slot returns an empty chunk carrying its header timestamp and a
    /// report with `PARTIAL_DATA_LOSS`; callers can still inspect and edit every other slot. The
    /// original allocation remains reserved until that slot is explicitly replaced or cleared.
    /// @param localIndex local slot from 0 through 1023
    /// @return detached chunk and slot diagnostics
    /// @throws IOException if the session itself is closed or the read policy is invalid
    public NBTReadResult<Chunk> readChunkTolerant(int localIndex) throws IOException {
        return readChunkTolerant(localIndex, NBTReadLimits.defaults());
    }

    /// Reads one chunk with an explicit bounded tolerant-read policy.
    /// @param localIndex local slot from 0 through 1023
    /// @param limits defensive decompression and parser limits
    /// @return detached chunk and slot diagnostics
    /// @throws IOException if the session itself is closed or the read policy is invalid
    public NBTReadResult<Chunk> readChunkTolerant(int localIndex, NBTReadLimits limits) throws IOException {
        NBTReadLimits selectedLimits = Objects.requireNonNull(limits, "limits");
        return readChunkTolerant(localIndex, selectedLimits, selectedLimits.newDocumentBudget());
    }

    /// Reads one chunk with a caller-owned cumulative document budget.
    /// This package-local overload lets an entire 1024-slot region share its cumulative output
    /// limit while retaining the public per-slot convenience method.
    /// @param localIndex local slot from 0 through 1023
    /// @param limits defensive decompression and parser limits
    /// @param budget cumulative document budget
    /// @return detached chunk and slot diagnostics
    /// @throws IOException if the session is closed or the policy is invalid
    NBTReadResult<Chunk> readChunkTolerant(int localIndex, NBTReadLimits limits,
                                           NBTReadLimits.Budget budget) throws IOException {
        checkIndex(localIndex);
        ensureOpen();
        NBTReadLimits selectedLimits = Objects.requireNonNull(limits, "limits");
        NBTReadLimits.Budget selectedBudget = Objects.requireNonNull(budget, "budget");
        @Nullable PendingChunk changed = pending.get(localIndex);
        if (changed != null) {
            Chunk result = changed.chunk == null
                    ? new Chunk(NBTRegionFileIO.timestamp(timestamps, localIndex)) : changed.chunk.clone();
            NBTReadReport report = slotReport(localIndex, List.of());
            rememberIssues(report.issues());
            return new NBTReadResult<>(result, report);
        }

        int offset = sectorOffsets[localIndex];
        int length = sectorLengths[localIndex];
        if (offset == 0 && length == 0) {
            NBTReadReport report = slotReport(localIndex, List.of());
            rememberIssues(report.issues());
            return new NBTReadResult<>(new Chunk(NBTRegionFileIO.timestamp(timestamps, localIndex)), report);
        }

        // A malformed location can overlap another slot or point outside the file. Keep its
        // original allocation reserved where possible, but never interpret those bytes as a
        // trustworthy payload during tolerant materialization.
        if (isolatedSlots[localIndex]) {
            List<NBTReadIssue> isolatedIssue = List.of(readIssue(
                    NBTReadIssue.Severity.PARTIAL_DATA_LOSS,
                    "REGION_SLOT_ISOLATED", slotPath(localIndex),
                    "槽位头部范围不安全，已隔离；请替换或清除该槽位后再保存"));
            NBTReadReport report = slotReport(localIndex, isolatedIssue);
            rememberIssues(report.issues());
            return new NBTReadResult<>(new Chunk(NBTRegionFileIO.timestamp(timestamps, localIndex)), report);
        }

        List<NBTReadIssue> issues = new ArrayList<>();
        try {
            byte[] sector = NBTRegionFileIO.readBytes(channel, (long) offset * ChunkUtils.SECTOR_BYTES,
                    (long) length * ChunkUtils.SECTOR_BYTES);
            @Nullable ChunkPayload payload = readPayloadTolerant(localIndex, sector, length, selectedLimits, issues);
            if (payload != null) {
                try {
                    NBTReadResult<CompoundTag> recovered = NBTRepairReader.readRegionPayload(
                            payload.compressed, payload.compression, selectedLimits, selectedBudget);
                    if (payload.compression == CompressionType.LZ4) {
                        issues.add(readIssue(NBTReadIssue.Severity.INFORMATIONAL,
                                "REGION_LZ4_EXTENSION", slotPath(localIndex),
                                "检测到扩展 LZ4 槽位标记，保存时将原样保留"));
                    }
                    for (NBTReadIssue issue : recovered.report().issues()) {
                        issues.add(withSlotPath(localIndex, issue));
                    }
                    Chunk result = new Chunk(NBTRegionFileIO.timestamp(timestamps, localIndex), recovered.root());
                    NBTReadReport report = slotReport(localIndex, issues);
                    rememberIssues(report.issues());
                    return new NBTReadResult<>(result, report);
                } catch (IOException | RuntimeException recoveryFailure) {
                    issues.add(readIssue(NBTReadIssue.Severity.PARTIAL_DATA_LOSS,
                            "REGION_SLOT_RECOVERY_FAILED", slotPath(localIndex),
                            "槽位内容无法可靠恢复：" + NBTRegionFileIO.failureMessage(recoveryFailure)));
                }
            }
        } catch (IOException | RuntimeException readFailure) {
            issues.add(readIssue(NBTReadIssue.Severity.PARTIAL_DATA_LOSS,
                    "REGION_SLOT_READ_FAILED", slotPath(localIndex),
                    "槽位扇区无法读取：" + NBTRegionFileIO.failureMessage(readFailure)));
        }

        Chunk empty = new Chunk(NBTRegionFileIO.timestamp(timestamps, localIndex));
        NBTReadReport report = slotReport(localIndex, issues);
        rememberIssues(report.issues());
        return new NBTReadResult<>(empty, report);
    }

    /// Reads one chunk by local coordinates using bounded tolerant recovery.
    /// @param localX local X coordinate from 0 through 31
    /// @param localZ local Z coordinate from 0 through 31
    /// @param limits defensive decompression and parser limits
    /// @return detached chunk and slot diagnostics
    /// @throws IOException if the session itself is closed or the read policy is invalid
    public NBTReadResult<Chunk> readChunkTolerant(int localX, int localZ, NBTReadLimits limits)
            throws IOException {
        return readChunkTolerant(ChunkUtils.toLocalIndex(
                NBTRegionFileIO.checkedCoordinate(localX), NBTRegionFileIO.checkedCoordinate(localZ)), limits);
    }

    /// Returns diagnostics collected by this region session.
    /// @return immutable aggregate report
    public synchronized NBTReadReport readReport() {
        if (readIssues.isEmpty()) {
            return NBTReadReport.clean(NBTFileEncoding.REGION);
        }
        return new NBTReadReport(NBTFileEncoding.REGION, false, readIssues);
    }

    /// Schedules a deep copy of a chunk for the next [#flush()] call.
    /// Newly written chunks use ZLIB compression. The existing chunk's sectors and compression
    /// method are never rewritten unless this method is called.
    /// @param localIndex local slot from 0 through 1023
    /// @param chunk chunk to copy; a `null` root means an empty slot with its timestamp retained
    public void writeChunk(int localIndex, Chunk chunk) {
        checkIndex(localIndex);
        ensureOpenUnchecked();
        writeChunk(localIndex, chunk, preferredCompression(localIndex));
    }

    /// Schedules a deep copy of a chunk using the selected compression method.
    /// @param localIndex local slot from 0 through 1023
    /// @param chunk chunk to copy
    /// @param compression compression method for the new payload
    public void writeChunk(int localIndex, Chunk chunk, CompressionType compression) {
        checkIndex(localIndex);
        ensureOpenUnchecked();
        Objects.requireNonNull(chunk, "chunk");
        Objects.requireNonNull(compression, "compression");
        pending.put(localIndex, new PendingChunk(chunk.clone(), compression, true));
    }

    /// Schedules a compound root for writing using the default ZLIB compression.
    /// @param localIndex local slot from 0 through 1023
    /// @param root compound root, or `null` to clear the slot
    public void writeChunk(int localIndex, @Nullable CompoundTag root) {
        checkIndex(localIndex);
        ensureOpenUnchecked();
        CompressionType compression = preferredCompression(localIndex);
        pending.put(localIndex, root == null
                ? new PendingChunk(new Chunk(NBTRegionFileIO.timestamp(timestamps, localIndex)), compression, true)
                : new PendingChunk(new Chunk(root.clone()), compression, true));
    }

    /// Schedules a detached chunk by local X/Z coordinates.
    /// @param localX local X coordinate from 0 through 31
    /// @param localZ local Z coordinate from 0 through 31
    /// @param chunk chunk to copy
    public void writeChunk(int localX, int localZ, Chunk chunk) {
        writeChunk(ChunkUtils.toLocalIndex(NBTRegionFileIO.checkedCoordinate(localX),
                NBTRegionFileIO.checkedCoordinate(localZ)), chunk);
    }

    /// Schedules a detached chunk by local X/Z coordinates using the selected compression.
    /// @param localX local X coordinate from 0 through 31
    /// @param localZ local Z coordinate from 0 through 31
    /// @param chunk chunk to copy
    /// @param compression compression method for the new payload
    public void writeChunk(int localX, int localZ, Chunk chunk, CompressionType compression) {
        writeChunk(ChunkUtils.toLocalIndex(NBTRegionFileIO.checkedCoordinate(localX),
                NBTRegionFileIO.checkedCoordinate(localZ)),
                chunk, compression);
    }

    /// Schedules a compound root by local X/Z coordinates.
    /// @param localX local X coordinate from 0 through 31
    /// @param localZ local Z coordinate from 0 through 31
    /// @param root compound root, or `null` to clear the slot
    public void writeChunk(int localX, int localZ, @Nullable CompoundTag root) {
        writeChunk(ChunkUtils.toLocalIndex(NBTRegionFileIO.checkedCoordinate(localX),
                NBTRegionFileIO.checkedCoordinate(localZ)), root);
    }

    /// Schedules a slot clear. The old sectors are released only after the new header entry is published.
    /// @param localIndex local slot from 0 through 1023
    public void clearChunk(int localIndex) {
        checkIndex(localIndex);
        ensureOpenUnchecked();
        pending.put(localIndex, new PendingChunk(
                new Chunk(NBTRegionFileIO.timestamp(timestamps, localIndex)),
                preferredCompression(localIndex), true));
    }

    /// Schedules a slot clear by local X/Z coordinates.
    /// @param localX local X coordinate from 0 through 31
    /// @param localZ local Z coordinate from 0 through 31
    public void clearChunk(int localX, int localZ) {
        clearChunk(ChunkUtils.toLocalIndex(NBTRegionFileIO.checkedCoordinate(localX),
                NBTRegionFileIO.checkedCoordinate(localZ)));
    }

    /// Flushes pending chunks in ascending local-index order using copy-on-write publication.
    /// If a later chunk fails, earlier chunks remain committed and an
    /// [NBTPartialSaveException] identifies the committed indexes. Failed and later chunks remain
    /// dirty in memory.
    /// @throws IOException if a chunk cannot be encoded, written, or published
    public void flush() throws IOException {
        ensureOpen();
        pendingCleanup.retry();
        boolean hadTrailingTail = trailingTailNeedsRepair;
        // Reject opaque extension markers before touching a repairable tail. A failed save must
        // leave the source bytes unchanged when no explicit replacement authorizes the marker.
        rejectUnknownCompressionMarkers();
        if (hadTrailingTail) {
            normalizeTrailingTail();
        }
        if (pending.isEmpty()) {
            if (hadTrailingTail) {
                clearTrailingTailIssue();
            }
            return;
        }
        List<Integer> indexes = new ArrayList<>(pending.keySet());
        Collections.sort(indexes);
        List<Integer> committed = new ArrayList<>();
        for (int localIndex : indexes) {
            try {
                @Nullable PendingChunk change = pending.get(localIndex);
                if (change == null) {
                    continue;
                }
                publish(localIndex, change);
                pending.remove(localIndex);
                committed.add(localIndex);
            } catch (ChunkCommittedException exception) {
                pending.remove(localIndex);
                committed.add(localIndex);
                clearRepairIssues(localIndex);
                throw new NBTPartialSaveException(committed, -1, exception);
            } catch (NBTCommitUncertainException exception) {
                lockAfterUncertainCommit(exception);
                throw exception;
            } catch (IOException exception) {
                if (!committed.isEmpty()) {
                    throw new NBTPartialSaveException(committed, localIndex, exception);
                }
                throw exception;
            } catch (RuntimeException exception) {
                IOException wrapped = new IOException("Failed to publish region chunk " + localIndex, exception);
                if (!committed.isEmpty()) {
                    throw new NBTPartialSaveException(committed, localIndex, wrapped);
                }
                throw wrapped;
            }
            clearRepairIssues(localIndex);
        }
        if (hadTrailingTail) {
            clearTrailingTailIssue();
        }
    }

    /// Closes the underlying channel without implicitly publishing pending changes.
    /// Call [#flush()] explicitly to publish edits. This fail-closed behavior prevents a close
    /// during error recovery from retrying a partial save behind the caller's back. Pending
    /// snapshots remain observable through [#isDirty()] and [#dirtyChunkIndexes()] after close,
    /// but the closed session cannot publish them.
    /// @throws IOException if the channel cannot be closed
    @Override
    public synchronized void close() throws IOException {
        if (closed) {
            return;
        }
        pendingCleanup.retry();
        // Keep the session retryable when the operating system refuses to close the channel. The
        // owning document lease must not be released while this physical handle is unresolved.
        channel.close();
        closed = true;
    }

    /// Closes this session after a publication and rollback both failed.
    /// The visible header and companion state must be rediscovered by a fresh open. Keeping this
    /// channel usable would allow reads to observe a mixture of the old and new publication.
    /// @param uncertain failure which caused the session lock
    private void lockAfterUncertainCommit(NBTCommitUncertainException uncertain) {
        commitLocked = true;
        if (closed) {
            return;
        }
        try {
            channel.close();
            closed = true;
        } catch (IOException closeFailure) {
            uncertain.addSuppressed(closeFailure);
        }
    }

    /// Serializes and publishes one pending chunk while retaining the previous visible storage.
    /// @param localIndex local chunk slot being updated
    /// @param change detached pending value and compression
    /// @throws IOException if validation, payload publication, or header publication fails
    private void publish(int localIndex, PendingChunk change) throws IOException {
        @Nullable Chunk chunk = change.chunk;
        int oldOffset = sectorOffsets[localIndex];
        int oldLength = sectorLengths[localIndex];
        boolean oldExternal = external[localIndex];
        @Nullable Path previousCompanion = external[localIndex] ? companionPath(localIndex) : null;
        if (chunk == null) {
            try {
                publishClear(localIndex, previousCompanion,
                        NBTRegionFileIO.epochSeconds(NBTRegionFileIO.timestamp(timestamps, localIndex)));
            } catch (ChunkCommittedException exception) {
                releaseSectors(oldOffset, oldLength);
                throw exception;
            } catch (NBTCommitUncertainException exception) {
                throw exception;
            }
            releaseSectors(oldOffset, oldLength);
            return;
        }

        @Nullable CompoundTag root = chunk.getRootTag();
        if (root == null) {
            try {
                publishHeader(localIndex, 0, 0, NBTRegionFileIO.epochSeconds(chunk.getTimestamp()), false, (byte) 0);
            } catch (ChunkCommittedException exception) {
                releaseSectors(oldOffset, oldLength);
                throw exception;
            } catch (NBTCommitUncertainException exception) {
                throw exception;
            }
            releaseSectors(oldOffset, oldLength);
            deleteCompanionAfterPublish(localIndex, previousCompanion);
            return;
        }

        try {
            NBTStructureValidator.validate(chunk);
        } catch (NBTValidationException exception) {
            throw new IOException("Invalid NBT tree for region chunk " + localIndex, exception);
        }
        CompressionType compression = compressionForPublication(localIndex, change.compression);
        byte[] nbt = NBTCodec.of().writeTagToByteArray(root.clone());
        byte[] compressed = compress(compression, nbt);
        if (compressed.length > MAX_COMPRESSED_BYTES) {
            throw new IOException("Compressed region chunk exceeds the encoded read limit: "
                    + compressed.length);
        }
        long framedBytes = compressed.length + 5L;
        // An existing external slot keeps its representation even when the replacement would fit
        // inline. This preserves the source marker/companion contract and avoids silently
        // deleting a companion merely because the payload became smaller.
        boolean keepExternal = oldExternal || framedBytes > MAX_INLINE_BYTES;
        if (!keepExternal) {
            int sectors = Math.toIntExact((framedBytes + ChunkUtils.SECTOR_BYTES - 1) / ChunkUtils.SECTOR_BYTES);
            Allocation allocation = allocateSectors(sectors, localIndex);
            try {
                writeInlineChunk(allocation.byteOffset(), sectors, compression, compressed);
                reach(CommitStage.PAYLOAD_WRITTEN, localIndex);
                reach(CommitStage.PAYLOAD_FORCED, localIndex);
                publishHeader(localIndex, allocation.sectorOffset, sectors,
                        NBTRegionFileIO.epochSeconds(chunk.getTimestamp()), false, (byte) compression.id());
            } catch (ChunkCommittedException exception) {
                releaseSectors(oldOffset, oldLength);
                throw exception;
            } catch (NBTCommitUncertainException exception) {
                throw exception;
            } catch (IOException | RuntimeException exception) {
                releaseSectors(allocation.sectorOffset, allocation.sectorCount);
                throw exception;
            }
            releaseSectors(oldOffset, oldLength);
            deleteCompanionAfterPublish(localIndex, previousCompanion);
        } else {
            Allocation allocation = allocateSectors(1, localIndex);
            @Nullable CompanionSwap companion = null;
            try {
                companion = writeCompanion(localIndex, compressed, oldExternal);
                reach(CommitStage.COMPANION_PUBLISHED, localIndex);
                writeExternalMarker(allocation.byteOffset(), compression);
                reach(CommitStage.PAYLOAD_WRITTEN, localIndex);
                reach(CommitStage.PAYLOAD_FORCED, localIndex);
                publishHeader(localIndex, allocation.sectorOffset, 1,
                        NBTRegionFileIO.epochSeconds(chunk.getTimestamp()), true, (byte) compression.id());
            } catch (ChunkCommittedException exception) {
                releaseSectors(oldOffset, oldLength);
                if (companion != null) {
                    try {
                        companion.commit(localIndex);
                    } catch (IOException cleanupFailure) {
                        exception.addSuppressed(cleanupFailure);
                    }
                }
                throw exception;
            } catch (NBTCommitUncertainException exception) {
                throw exception;
            } catch (IOException | RuntimeException exception) {
                if (companion != null) {
                    try {
                        companion.restore(localIndex);
                    } catch (IOException | RuntimeException restoreFailure) {
                        releaseSectors(allocation.sectorOffset, allocation.sectorCount);
                        IOException publicationFailure = asIOException(
                                "External chunk publication failed",
                                exception);
                        throw new NBTCommitUncertainException(
                                path,
                                localIndex,
                                publicationFailure,
                                asIOException("External chunk rollback failed", restoreFailure));
                    }
                }
                releaseSectors(allocation.sectorOffset, allocation.sectorCount);
                throw exception;
            }
            releaseSectors(oldOffset, oldLength);
            companion.commit(localIndex);
        }
    }

    /// Publishes an empty location and then removes the previously referenced companion.
    /// @param localIndex local chunk slot being cleared
    /// @param previousCompanion owned companion to remove after publication, if present
    /// @param timestamp raw timestamp bits retained for the empty slot
    /// @throws IOException if header publication or post-commit cleanup fails
    private void publishClear(int localIndex, @Nullable Path previousCompanion, int timestamp) throws IOException {
        publishHeader(localIndex, 0, 0, timestamp, false, (byte) 0);
        deleteCompanionAfterPublish(localIndex, previousCompanion);
    }

    /// Publishes one timestamp and location entry, restoring their prior values on pre-publication failure.
    /// @param localIndex local chunk slot being published
    /// @param offset replacement sector offset, or zero for an empty slot
    /// @param length replacement sector count, or zero for an empty slot
    /// @param timestamp raw unsigned epoch-second bits
    /// @param isExternal whether the replacement marker references a companion payload
    /// @param compression replacement compression identifier
    /// @throws IOException if publication or rollback fails
    private void publishHeader(int localIndex, int offset, int length, int timestamp,
                               boolean isExternal, byte compression) throws IOException {
        int oldOffset = sectorOffsets[localIndex];
        int oldLength = sectorLengths[localIndex];
        int oldTimestamp = timestamps[localIndex];
        ByteBuffer newTimestamp = ByteBuffer.allocate(Integer.BYTES).order(ByteOrder.BIG_ENDIAN)
                .putInt(timestamp);
        newTimestamp.flip();
        ByteBuffer newLocation = NBTRegionFileIO.encodeLocation(offset, length);
        boolean headerWritten = false;
        try {
            NBTRegionFileIO.requireRegularFile(path);
            // Publishing the timestamp first keeps the old location structurally readable until
            // the final four-byte location switch reaches disk.
            NBTRegionFileIO.writeFully(channel, newTimestamp,
                    ChunkUtils.SECTOR_BYTES + (long) localIndex * Integer.BYTES);
            NBTRegionFileIO.writeFully(channel, newLocation, (long) localIndex * Integer.BYTES);
            reach(CommitStage.HEADER_WRITTEN, localIndex);
            headerWritten = true;
            NBTRegionFileIO.requireRegularFile(path);
            reach(CommitStage.HEADER_FORCED, localIndex);
        } catch (IOException | RuntimeException failure) {
            IOException exception = asIOException("Region header publication failed", failure);
            if (headerWritten) {
                sectorOffsets[localIndex] = offset;
                sectorLengths[localIndex] = length;
                timestamps[localIndex] = timestamp;
                external[localIndex] = isExternal;
                compressionTypes[localIndex] = compression;
                isolatedSlots[localIndex] = false;
                throw new ChunkCommittedException(localIndex, exception);
            }
            try {
                reach(CommitStage.HEADER_ROLLBACK, localIndex);
                NBTRegionFileIO.writeFully(channel, NBTRegionFileIO.encodeLocation(oldOffset, oldLength),
                        (long) localIndex * Integer.BYTES);
                ByteBuffer rollbackTimestamp = ByteBuffer.allocate(Integer.BYTES).order(ByteOrder.BIG_ENDIAN)
                        .putInt(oldTimestamp);
                rollbackTimestamp.flip();
                NBTRegionFileIO.writeFully(channel, rollbackTimestamp,
                        ChunkUtils.SECTOR_BYTES + (long) localIndex * Integer.BYTES);
            } catch (IOException | RuntimeException rollbackFailure) {
                throw new NBTCommitUncertainException(
                        path,
                        localIndex,
                        exception,
                        asIOException("Region header rollback failed", rollbackFailure));
            }
            throw exception;
        }
        sectorOffsets[localIndex] = offset;
        sectorLengths[localIndex] = length;
        timestamps[localIndex] = timestamp;
        external[localIndex] = isExternal;
        compressionTypes[localIndex] = compression;
        isolatedSlots[localIndex] = false;
    }

    /// Reserves and zero-fills a contiguous free sector range without changing the header.
    /// @param count number of sectors to reserve
    /// @param localIndex local chunk slot receiving the allocation
    /// @return reserved sector range
    /// @throws IOException if the range cannot be represented or initialized
    private Allocation allocateSectors(int count, int localIndex) throws IOException {
        if (count < 1 || count > MAX_SECTOR_COUNT) {
            throw new IOException("Invalid sector allocation count: " + count);
        }
        NBTRegionFileIO.requireRegularFile(path);
        long size = channel.size();
        if ((size & (ChunkUtils.SECTOR_BYTES - 1L)) != 0L) {
            throw new IOException("Region file length is not sector-aligned");
        }
        long fileSectorsLong = size / ChunkUtils.SECTOR_BYTES;
        if (fileSectorsLong > 0x1_000000L) {
            throw new IOException("Region file exceeds the 24-bit sector address space");
        }
        int fileSectors = Math.toIntExact(fileSectorsLong);
        int sectorOffset = 2;
        while (true) {
            sectorOffset = usedSectors.nextClearBit(sectorOffset);
            int nextUsed = usedSectors.nextSetBit(sectorOffset);
            int freeEnd = nextUsed < 0 ? fileSectors : nextUsed;
            if ((long) sectorOffset + count <= freeEnd || nextUsed < 0) {
                break;
            }
            sectorOffset = nextUsed + 1;
        }
        long sectorEnd = (long) sectorOffset + count;
        if (sectorOffset > 0xFF_FFFF || sectorEnd > 0x1_000000L) {
            throw new IOException("Region sector offset exceeds the header limit");
        }
        long offset = (long) sectorOffset * ChunkUtils.SECTOR_BYTES;
        long end;
        try {
            end = Math.addExact(offset, Math.multiplyExact((long) count, ChunkUtils.SECTOR_BYTES));
        } catch (ArithmeticException exception) {
            throw new IOException("Region file is too large", exception);
        }
        usedSectors.set(sectorOffset, Math.toIntExact(sectorEnd));
        try {
            NBTRegionFileIO.writeZeros(channel, offset, end - offset);
            reach(CommitStage.ALLOCATION_WRITTEN, localIndex);
            return new Allocation(sectorOffset, count);
        } catch (IOException | RuntimeException exception) {
            usedSectors.clear(sectorOffset, Math.toIntExact(sectorEnd));
            throw exception;
        }
    }

    /// Marks a previously owned payload range available for later allocations.
    /// @param offset first sector in the range
    /// @param length number of sectors in the range
    private void releaseSectors(int offset, int length) {
        if (offset < 2 || length <= 0) {
            return;
        }
        long end = (long) offset + length;
        if (end > Integer.MAX_VALUE) {
            end = Integer.MAX_VALUE;
        }
        for (long sector = offset; sector < end; sector++) {
            int sectorIndex = (int) sector;
            if (!isSectorReferencedByAnotherSlot(sectorIndex)) {
                usedSectors.clear(sectorIndex);
            }
        }
    }

    /// Returns whether a still-published slot owns one sector.
    /// Tolerant region opening can retain overlapping or truncated header ranges as isolated
    /// reservations. A repaired slot must not release a sector while another slot still points
    /// at it, otherwise a later allocation could overwrite the other slot's only recoverable bytes.
    /// @param sector sector index
    /// @return whether any current slot references the sector
    private boolean isSectorReferencedByAnotherSlot(int sector) {
        for (int localIndex = 0; localIndex < sectorOffsets.length; localIndex++) {
            int start = sectorOffsets[localIndex];
            int count = sectorLengths[localIndex];
            if (count > 0 && sector >= start && (long) sector < (long) start + count) {
                return true;
            }
        }
        return false;
    }

    /// Writes a complete inline frame into an unreferenced sector range.
    /// @param offset byte offset of the reserved range
    /// @param sectors number of reserved sectors
    /// @param compression compression marker for the payload
    /// @param compressed compressed NBT payload bytes
    /// @throws IOException if the frame cannot be written completely
    private void writeInlineChunk(long offset, int sectors, CompressionType compression, byte[] compressed)
            throws IOException {
        ByteBuffer frame = ByteBuffer.allocate(sectors * ChunkUtils.SECTOR_BYTES).order(ByteOrder.BIG_ENDIAN);
        frame.putInt(compressed.length + 1);
        frame.put((byte) compression.id());
        frame.put(compressed);
        frame.flip();
        NBTRegionFileIO.writeFully(channel, frame, offset);
    }

    /// Writes an external-payload marker into one unreferenced sector.
    /// @param offset byte offset of the reserved sector
    /// @param compression compression marker for the companion payload
    /// @throws IOException if the marker cannot be written completely
    private void writeExternalMarker(long offset, CompressionType compression) throws IOException {
        ByteBuffer frame = ByteBuffer.allocate(ChunkUtils.SECTOR_BYTES).order(ByteOrder.BIG_ENDIAN);
        frame.putInt(1);
        frame.put((byte) (compression.id() | 0x80));
        frame.flip();
        NBTRegionFileIO.writeFully(channel, frame, offset);
    }

    /// Atomically publishes a compressed companion payload while retaining a restorable backup.
    /// @param localIndex local chunk slot whose companion is being published
    /// @param compressed compressed NBT payload without a region frame prefix
    /// @param replaceOwned whether an existing target is known to belong to this chunk
    /// @return companion swap which must be committed or restored
    /// @throws IOException if the companion path is unavailable or atomic publication fails
    private CompanionSwap writeCompanion(int localIndex, byte[] compressed, boolean replaceOwned)
            throws IOException {
        @Nullable Path target = companionPath(localIndex);
        if (target == null) {
            throw new IOException("External accessor does not expose a filesystem companion path");
        }
        @Nullable Path parent = target.getParent();
        if (parent != null) {
            NBTRegionFileIO.createDirectoriesNoFollow(parent);
        }
        Path temporary = NBTRegionFileIO.deterministicSibling(target, ".xyml_new");
        Path backupStage = NBTRegionFileIO.deterministicSibling(target, ".xyml_old.xyml_new");
        Path backup = NBTRegionFileIO.deterministicSibling(target, ".xyml_old");
        boolean targetReplaced = false;
        boolean backupPublished = false;
        try {
            NBTRegionFileIO.writeStage(temporary, compressed);
            boolean targetExists = Files.exists(target, LinkOption.NOFOLLOW_LINKS);
            if (Files.isSymbolicLink(target)) {
                throw new IOException("Refusing to replace a symbolic external chunk companion: " + target);
            }
            if (targetExists) {
                if (!replaceOwned) {
                    throw new IOException("Refusing to replace an unreferenced external chunk companion: " + target);
                }
                NBTRegionFileIO.requireRegularFile(target);
                NBTRegionFileIO.copyFileBounded(target, backupStage, MAX_COMPRESSED_BYTES);
            }
            NBTRegionFileIO.moveAtomically(temporary, target);
            targetReplaced = true;
            if (targetExists) {
                reach(CommitStage.COMPANION_BACKUP_PUBLISH, localIndex);
                NBTRegionFileIO.moveAtomically(backupStage, backup);
                backupPublished = true;
                return new CompanionSwap(temporary, target, backup);
            }
            return new CompanionSwap(temporary, target, null);
        } catch (IOException | RuntimeException exception) {
            if (targetReplaced) {
                try {
                    reach(CommitStage.COMPANION_ROLLBACK, localIndex);
                    if (backupPublished) {
                        NBTRegionFileIO.moveAtomically(backup, target);
                    } else if (Files.exists(backupStage, LinkOption.NOFOLLOW_LINKS)) {
                        NBTRegionFileIO.moveAtomically(backupStage, target);
                    } else {
                        Files.deleteIfExists(target);
                    }
                } catch (IOException | RuntimeException restoreFailure) {
                    throw new NBTCommitUncertainException(
                            path,
                            localIndex,
                            asIOException("External companion publication failed", exception),
                            asIOException("External companion rollback failed", restoreFailure));
                }
            }
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException | RuntimeException cleanupFailure) {
                pendingCleanup.remember(temporary);
                exception.addSuppressed(cleanupFailure);
            }
            try {
                Files.deleteIfExists(backupStage);
            } catch (IOException | RuntimeException cleanupFailure) {
                pendingCleanup.remember(backupStage);
                exception.addSuppressed(cleanupFailure);
            }
            throw exception;
        }
    }

    /// Removes an owned companion only after its header entry no longer references it.
    /// @param localIndex committed local chunk slot
    /// @param oldPath previously referenced companion, if present
    /// @throws IOException if post-commit cleanup fails
    private void deleteCompanionAfterPublish(int localIndex, @Nullable Path oldPath) throws IOException {
        try {
            reach(CommitStage.CLEANUP, localIndex);
            if (oldPath != null) {
                Files.deleteIfExists(oldPath);
            }
        } catch (IOException | RuntimeException exception) {
            if (oldPath != null) {
                pendingCleanup.remember(oldPath);
            }
            throw new ChunkCommittedException(
                    localIndex,
                    asIOException("Committed companion cleanup failed", exception));
        }
    }

    /// Tracks a published companion and the backup needed to undo it before header commit.
    @NotNullByDefault
    private final class CompanionSwap {
        /// Temporary path originally used to stage the replacement payload.
        private final Path temporary;
        /// Canonical companion path currently holding the replacement payload.
        private final Path target;
        /// Deterministic backup of the previous owned companion, if one existed.
        private final @Nullable Path backupPath;
        /// Whether header publication made this swap permanent.
        private boolean committed;

        /// Creates a reversible companion swap after its target was atomically replaced.
        /// @param temporary staging path, normally absent after the atomic move
        /// @param target canonical companion path
        /// @param backupPath previous companion backup, if one existed
        private CompanionSwap(Path temporary, Path target, @Nullable Path backupPath) {
            this.temporary = temporary;
            this.target = target;
            this.backupPath = backupPath;
        }

        /// Marks the replacement permanent and removes no-longer-needed temporary files.
        /// @param localIndex committed local chunk slot
        /// @throws IOException if post-commit cleanup fails
        private void commit(int localIndex) throws IOException {
            committed = true;
            try {
                reach(CommitStage.CLEANUP, localIndex);
                if (backupPath != null) {
                    Files.deleteIfExists(backupPath);
                }
                Files.deleteIfExists(temporary);
            } catch (IOException | RuntimeException exception) {
                if (backupPath != null) {
                    pendingCleanup.remember(backupPath);
                }
                pendingCleanup.remember(temporary);
                throw new ChunkCommittedException(
                        localIndex,
                        asIOException("Committed companion swap cleanup failed", exception));
            }
        }

        /// Restores the prior companion, or removes a newly created companion, before header commit.
        /// @param localIndex affected local chunk slot
        /// @throws IOException if the prior companion state cannot be restored atomically
        private void restore(int localIndex) throws IOException {
            if (committed) {
                return;
            }
            reach(CommitStage.COMPANION_ROLLBACK, localIndex);
            if (backupPath != null) {
                if (!NBTRegionFileIO.isRegularNonSymbolicFile(backupPath)) {
                    throw new IOException("External chunk backup disappeared before rollback: " + backupPath);
                }
                NBTRegionFileIO.moveAtomically(backupPath, target);
            } else {
                Files.deleteIfExists(target);
            }
            Files.deleteIfExists(temporary);
        }
    }

    /// Parses one region frame and resolves its inline or external compressed payload.
    /// @param localIndex local chunk slot used for diagnostics and companion lookup
    /// @param sector complete allocated sector bytes
    /// @param sectorLength allocated sector count
    /// @return decoded compression type and compressed payload
    /// @throws IOException if framing, compression identifiers, or companion data are invalid
    private ChunkPayload readPayload(int localIndex, byte[] sector, int sectorLength) throws IOException {
        ByteBuffer frame = ByteBuffer.wrap(sector).order(ByteOrder.BIG_ENDIAN);
        long length = Integer.toUnsignedLong(frame.getInt());
        if (length < 1L || length > (long) sectorLength * ChunkUtils.SECTOR_BYTES - 4L) {
            throw new IOException("Invalid chunk length at local index " + localIndex + ": " + length);
        }
        int marker = Byte.toUnsignedInt(frame.get());
        boolean isExternal = (marker & 0x80) != 0;
        int compressionId = marker & 0x7F;
        CompressionType compression = CompressionType.fromId(compressionId);
        if (isExternal) {
            if (length != 1L) {
                throw new IOException("External chunk marker has inline payload at local index " + localIndex);
            }
            byte[] companion = readCompanion(localIndex);
            if (companion.length == 0) {
                throw new IOException("External chunk companion is empty at local index " + localIndex);
            }
            return new ChunkPayload(compression, companion);
        }

        int payloadLength = Math.toIntExact(length - 1L);
        if (payloadLength > frame.remaining()) {
            throw new IOException("Chunk payload exceeds its sector at local index " + localIndex);
        }
        byte[] payload = new byte[payloadLength];
        frame.get(payload);
        return new ChunkPayload(compression, payload);
    }

    /// Parses one frame conservatively so a malformed slot can be isolated and recovered.
    /// Unlike [#readPayload(int, byte[], int)], this method clamps a damaged inline length to
    /// the bytes physically present in the allocated sectors and records the loss instead of
    /// aborting the whole region session.
    /// @param localIndex local chunk slot
    /// @param sector complete allocated sector bytes
    /// @param sectorLength allocated sector count
    /// @param limits bounded companion-input policy
    /// @param issues diagnostic sink
    /// @return recoverable payload, or `null` when its marker cannot be interpreted
    private @Nullable ChunkPayload readPayloadTolerant(int localIndex, byte[] sector, int sectorLength,
                                                        NBTReadLimits limits,
                                                        List<NBTReadIssue> issues) {
        if (sector.length < Integer.BYTES + 1) {
            issues.add(readIssue(NBTReadIssue.Severity.PARTIAL_DATA_LOSS,
                    "REGION_FRAME_TRUNCATED", slotPath(localIndex), "槽位帧缺少完整头部"));
            return null;
        }
        ByteBuffer frame = ByteBuffer.wrap(sector).order(ByteOrder.BIG_ENDIAN);
        long declaredLength = Integer.toUnsignedLong(frame.getInt());
        int marker = Byte.toUnsignedInt(frame.get());
        boolean isExternal = (marker & 0x80) != 0;
        int compressionId = marker & 0x7F;
        @Nullable CompressionType compression;
        try {
            compression = CompressionType.fromId(compressionId);
        } catch (IOException unsupported) {
            issues.add(readIssue(NBTReadIssue.Severity.PARTIAL_DATA_LOSS,
                    "REGION_COMPRESSION_UNSUPPORTED", slotPath(localIndex),
                    "槽位使用不受支持的压缩标记 " + compressionId));
            return null;
        }

        long maximumFrameLength = (long) sectorLength * ChunkUtils.SECTOR_BYTES - Integer.BYTES;
        if (declaredLength < 1L) {
            issues.add(readIssue(NBTReadIssue.Severity.PARTIAL_DATA_LOSS,
                    "REGION_FRAME_LENGTH_INVALID", slotPath(localIndex), "槽位帧长度小于 1"));
            return null;
        }
        if (declaredLength > maximumFrameLength) {
            issues.add(readIssue(NBTReadIssue.Severity.PARTIAL_DATA_LOSS,
                    "REGION_FRAME_LENGTH_CLAMPED", slotPath(localIndex),
                    "槽位帧长度超过已分配扇区，已按实际数据截断"));
            declaredLength = maximumFrameLength;
        }
        if (isExternal) {
            if (declaredLength != 1L) {
                issues.add(readIssue(NBTReadIssue.Severity.PARTIAL_DATA_LOSS,
                        "REGION_EXTERNAL_FRAME_INVALID", slotPath(localIndex),
                        "外部槽位帧长度不是 1，仍尝试读取伴随文件"));
            }
            try {
                byte[] companion = readCompanionTolerant(localIndex, limits, issues);
                if (companion.length == 0) {
                    return null;
                }
                return new ChunkPayload(compression, companion);
            } catch (IOException | RuntimeException failure) {
                issues.add(readIssue(NBTReadIssue.Severity.PARTIAL_DATA_LOSS,
                        "REGION_EXTERNAL_READ_FAILED", slotPath(localIndex),
                        "外部槽位伴随文件无法读取：" + NBTRegionFileIO.failureMessage(failure)));
                return null;
            }
        }

        long payloadLengthLong = declaredLength - 1L;
        int available = frame.remaining();
        int payloadLength = (int) Math.min(payloadLengthLong, available);
        if ((long) payloadLength < payloadLengthLong) {
            issues.add(readIssue(NBTReadIssue.Severity.PARTIAL_DATA_LOSS,
                    "REGION_INLINE_TRUNCATED", slotPath(localIndex),
                    "内联槽位数据在扇区末尾截断"));
        }
        byte[] payload = new byte[payloadLength];
        frame.get(payload);
        return new ChunkPayload(compression, payload);
    }

    /// Decompresses one validated chunk payload with a bounded output size.
    /// @param compression payload compression type
    /// @param payload compressed payload bytes
    /// @return detached uncompressed NBT bytes
    /// @throws IOException if the payload is malformed, truncated, trailing, or too large
    private byte[] decompress(CompressionType compression, byte[] payload, int maximumBytes) throws IOException {
        return NBTRegionCompression.decompress(compression, payload, maximumBytes);
    }

    /// Parses exactly one detached compound root and validates its complete NBT structure.
    /// @param bytes complete uncompressed NBT payload
    /// @param localIndex local chunk slot used for diagnostics
    /// @return validated detached compound root
    /// @throws IOException if parsing, full consumption, root type, or validation fails
    private CompoundTag parseCompound(byte[] bytes, int localIndex) throws IOException {
        try (RawDataReader reader = new RawDataReader(new InputSource.OfByteBuffer(bytes),
                MinecraftEdition.JAVA_EDITION)) {
            Tag tag = NBTInput.readTag(reader);
            if (!(tag instanceof CompoundTag compound)) {
                throw new IOException("Region chunk " + localIndex + " does not contain a compound root");
            }
            if (reader.position() != bytes.length) {
                throw new IOException("Trailing bytes after NBT payload for region chunk " + localIndex);
            }
            try {
                NBTStructureValidator.validate(compound);
            } catch (NBTValidationException exception) {
                throw new IOException("Invalid NBT tree for region chunk " + localIndex, exception);
            }
            return compound;
        }
    }

    /// Reads every occupied slot once so open fails before exposing malformed payloads.
    /// @throws IOException if any existing chunk cannot be read and validated
    private void validateExistingPayloads() throws IOException {
        NBTReadLimits.Budget budget = NBTReadLimits.defaults().newDocumentBudget();
        for (int localIndex = 0; localIndex < ChunkUtils.CHUNKS_PRE_REGION; localIndex++) {
            if (sectorLengths[localIndex] != 0) {
                readChunk(localIndex, budget);
            }
        }
    }

    /// Selects pending or existing compression, defaulting new chunks to zlib.
    /// @param localIndex local chunk slot
    /// @return compression to retain or use by default
    private CompressionType preferredCompression(int localIndex) {
        PendingChunk changed = pending.get(localIndex);
        if (changed != null) {
            return changed.compression;
        }
        if (sectorLengths[localIndex] != 0) {
            int id = Byte.toUnsignedInt(compressionTypes[localIndex]);
            for (CompressionType type : CompressionType.values()) {
                if (type.id == id) {
                    return type;
                }
            }
        }
        return CompressionType.ZLIB;
    }

    /// Chooses the marker for a publication while preserving an existing slot's profile.
    /// An explicit compression argument is meaningful for a new slot. Once a slot is occupied,
    /// its low-seven-bit marker is part of the captured storage profile and must remain stable;
    /// this rule applies equally to inline and external slots.
    /// @param localIndex local chunk slot
    /// @param requested caller-requested compression
    /// @return existing known compression, or the requested compression for a new/unknown slot
    private CompressionType compressionForPublication(int localIndex, CompressionType requested) {
        if (sectorLengths[localIndex] != 0) {
            @Nullable CompressionType existing = compressionType(Byte.toUnsignedInt(compressionTypes[localIndex]));
            if (existing != null) {
                return existing;
            }
        }
        return requested;
    }

    /// Rejects an untouched slot whose marker is outside the known compression set.
    /// Tolerant opening intentionally keeps such a slot visible as an isolated diagnostic, but
    /// silently rewriting it as ZLIB would destroy an extension that this library cannot decode.
    /// An explicit pending replacement is allowed because the caller has selected a new known
    /// compression profile for that slot.
    /// @throws IOException if an occupied slot has an unknown marker and no explicit replacement
    private void rejectUnknownCompressionMarkers() throws IOException {
        for (int localIndex = 0; localIndex < compressionTypes.length; localIndex++) {
            if (sectorLengths[localIndex] == 0) {
                continue;
            }
            int marker = Byte.toUnsignedInt(compressionTypes[localIndex]);
            @Nullable PendingChunk change = pending.get(localIndex);
            if (compressionType(marker) == null && (change == null || !change.explicitReplacement())) {
                throw new IOException("Unknown region compression marker at local index " + localIndex
                        + "; replace or clear the slot explicitly before saving");
            }
        }
    }

    /// Resolves one stored compression identifier without silently selecting a replacement.
    /// @param id low-seven-bit compression identifier
    /// @return known compression type, or null for an extension marker
    private static @Nullable CompressionType compressionType(int id) {
        for (CompressionType type : CompressionType.values()) {
            if (type.id == id) {
                return type;
            }
        }
        return null;
    }

    /// Compresses serialized NBT bytes with the selected region compression method.
    /// @param compression output compression type
    /// @param input complete serialized NBT bytes
    /// @return compressed payload bytes without a region frame prefix
    /// @throws IOException if compression fails
    private byte[] compress(CompressionType compression, byte[] input) throws IOException {
        return NBTRegionCompression.compress(compression, input);
    }

    /// Reads the complete region header and validates all location ranges and chunk markers.
    /// @param path region path used for diagnostics and companion discovery
    /// @param channel open region channel
    /// @param accessor external companion accessor
    /// @return validated header arrays and occupied-sector bitmap
    /// @throws IOException if header structure, ranges, markers, or companions are invalid
    private static HeaderData readAndValidateHeader(Path path, FileChannel channel,
                                                     ExternalChunkAccessor accessor,
                                                     boolean tolerant) throws IOException {
        long size = channel.size();
        if (size < HEADER_BYTES || (!tolerant && (size & (ChunkUtils.SECTOR_BYTES - 1L)) != 0L)) {
            throw new IOException("Region file must be at least two sector-aligned header sectors: " + path);
        }
        long usableSize = size - (size & (ChunkUtils.SECTOR_BYTES - 1L));
        ByteBuffer buffer = ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.BIG_ENDIAN);
        NBTRegionFileIO.readFully(channel, buffer, 0L);
        buffer.flip();
        int[] offsets = new int[ChunkUtils.CHUNKS_PRE_REGION];
        int[] lengths = new int[ChunkUtils.CHUNKS_PRE_REGION];
        int[] timestamps = new int[ChunkUtils.CHUNKS_PRE_REGION];
        byte[] compression = new byte[ChunkUtils.CHUNKS_PRE_REGION];
        boolean[] external = new boolean[ChunkUtils.CHUNKS_PRE_REGION];
        boolean[] isolatedSlots = new boolean[ChunkUtils.CHUNKS_PRE_REGION];
        List<NBTReadIssue> issues = new ArrayList<>();
        if (usableSize != size) {
            issues.add(readIssue(NBTReadIssue.Severity.PARTIAL_DATA_LOSS,
                    "REGION_FILE_TRAILING_TRUNCATION", "region",
                    "文件末尾不是完整扇区，已忽略不完整尾部并隔离受影响槽位"));
        }
        long sectorCount = usableSize / ChunkUtils.SECTOR_BYTES;
        if (sectorCount > 0x1_000000L) {
            throw new IOException("Region file exceeds the 24-bit sector address space: " + path);
        }
        BitSet usedSectors = new BitSet(Math.toIntExact(sectorCount));
        usedSectors.set(0, 2);
        for (int i = 0; i < offsets.length; i++) {
            int offset = ((Byte.toUnsignedInt(buffer.get()) << 16)
                    | (Byte.toUnsignedInt(buffer.get()) << 8)
                    | Byte.toUnsignedInt(buffer.get()));
            int length = Byte.toUnsignedInt(buffer.get());
            if ((offset == 0) != (length == 0)) {
                if (!tolerant) {
                    throw new IOException("Region header has a half-empty entry at local index " + i);
                }
                issues.add(readIssue(NBTReadIssue.Severity.PARTIAL_DATA_LOSS,
                        "REGION_HEADER_SLOT_INVALID", slotPath(i),
                        "槽位头部的扇区偏移和长度不一致，已隔离该槽位"));
                isolatedSlots[i] = true;
                if (offset >= 2 && (long) offset < sectorCount) {
                    // A non-zero offset with a missing length may still own one sector. Keep a
                    // conservative reservation so a repair of another slot cannot overwrite it.
                    length = Math.max(length, 1);
                } else {
                    // An offset of zero (or one beyond EOF) has no safely attributable bytes.
                    offset = 0;
                    length = 0;
                }
            }
            long declaredEnd = 0L;
            boolean declaredRangeOverflow = false;
            if (length > 0 && offset >= 2) {
                try {
                    declaredEnd = Math.addExact((long) offset, (long) length);
                } catch (ArithmeticException overflow) {
                    declaredRangeOverflow = true;
                }
            }
            if (length > 0 && (offset < 2 || declaredRangeOverflow || declaredEnd > sectorCount)) {
                if (!tolerant) {
                    throw new IOException("Region header points outside the file at local index " + i);
                }
                issues.add(readIssue(NBTReadIssue.Severity.PARTIAL_DATA_LOSS,
                        "REGION_HEADER_SLOT_OUT_OF_RANGE", slotPath(i),
                        "槽位头部指向文件范围之外，已隔离该槽位"));
                isolatedSlots[i] = true;
                if (offset < 2 || (long) offset >= sectorCount) {
                    offset = 0;
                    length = 0;
                }
                // When the start is inside the file, retain the declared range. The slot is
                // isolated from reads, while the in-file prefix remains reserved for safety.
            }
            offsets[i] = offset;
            lengths[i] = length;
        }
        for (int i = 0; i < timestamps.length; i++) {
            timestamps[i] = buffer.getInt();
        }

        List<SectorRange> ranges = new ArrayList<>();
        for (int i = 0; i < offsets.length; i++) {
            if (lengths[i] != 0) {
                long start = Integer.toUnsignedLong(offsets[i]);
                long end;
                try {
                    end = Math.addExact(start, Integer.toUnsignedLong(lengths[i]));
                } catch (ArithmeticException overflow) {
                    if (!tolerant) {
                        throw new IOException("Region sector range overflows at local index " + i, overflow);
                    }
                    isolatedSlots[i] = true;
                    issues.add(readIssue(NBTReadIssue.Severity.PARTIAL_DATA_LOSS,
                            "REGION_HEADER_RANGE_OVERFLOW", slotPath(i),
                            "槽位扇区范围发生整数溢出，已隔离该槽位"));
                    continue;
                }
                ranges.add(new SectorRange(start, end, i));
                long reservedEnd = Math.min(end, sectorCount);
                if (reservedEnd > start) {
                    usedSectors.set(offsets[i], Math.toIntExact(reservedEnd));
                }
            }
        }
        ranges.sort((a, b) -> Long.compare(a.start(), b.start()));
        boolean[] overlapReported = new boolean[offsets.length];
        for (int currentIndex = 0; currentIndex < ranges.size(); currentIndex++) {
            SectorRange current = ranges.get(currentIndex);
            for (int previousIndex = 0; previousIndex < currentIndex; previousIndex++) {
                SectorRange previous = ranges.get(previousIndex);
                if (previous.end() <= current.start()) {
                    continue;
                }
                if (!tolerant) {
                    throw new IOException("Overlapping region sectors at local index " + current.localIndex());
                }
                isolatedSlots[current.localIndex()] = true;
                isolatedSlots[previous.localIndex()] = true;
                if (!overlapReported[current.localIndex()]) {
                    issues.add(readIssue(NBTReadIssue.Severity.PARTIAL_DATA_LOSS,
                            "REGION_HEADER_OVERLAP", slotPath(current.localIndex()),
                            "槽位扇区与其他槽位重叠，已隔离该槽位"));
                    overlapReported[current.localIndex()] = true;
                }
                if (!overlapReported[previous.localIndex()]) {
                    issues.add(readIssue(NBTReadIssue.Severity.PARTIAL_DATA_LOSS,
                            "REGION_HEADER_OVERLAP", slotPath(previous.localIndex()),
                            "槽位扇区与其他槽位重叠，已隔离该槽位"));
                    overlapReported[previous.localIndex()] = true;
                }
                // Keep the original ranges reserved. This prevents a later repair of one
                // slot from overwriting bytes which may still be useful to another slot.
            }
        }

        for (int i = 0; i < offsets.length; i++) {
            if (lengths[i] == 0) {
                continue;
            }
            long framePosition = (long) offsets[i] * ChunkUtils.SECTOR_BYTES;
            long frameHeaderEnd;
            try {
                frameHeaderEnd = Math.addExact(framePosition, Integer.BYTES + 1L);
            } catch (ArithmeticException overflow) {
                frameHeaderEnd = Long.MAX_VALUE;
            }
            if (frameHeaderEnd > usableSize) {
                if (!tolerant) {
                    throw new IOException("Region chunk frame is truncated at local index " + i);
                }
                issues.add(readIssue(NBTReadIssue.Severity.PARTIAL_DATA_LOSS,
                        "REGION_FRAME_TRUNCATED", slotPath(i),
                        "槽位帧头不完整，已隔离该槽位"));
                compression[i] = 0;
                external[i] = false;
                isolatedSlots[i] = true;
                continue;
            }
            ByteBuffer frame = ByteBuffer.allocate(5).order(ByteOrder.BIG_ENDIAN);
            try {
                NBTRegionFileIO.readFully(channel, frame, framePosition);
            } catch (IOException failure) {
                if (!tolerant) {
                    throw failure;
                }
                issues.add(readIssue(NBTReadIssue.Severity.PARTIAL_DATA_LOSS,
                        "REGION_FRAME_TRUNCATED", slotPath(i),
                        "槽位帧头无法完整读取，已隔离该槽位"));
                compression[i] = 0;
                external[i] = false;
                isolatedSlots[i] = true;
                continue;
            }
            frame.flip();
            long length = Integer.toUnsignedLong(frame.getInt());
            if (length < 1L || length > (long) lengths[i] * ChunkUtils.SECTOR_BYTES - 4L) {
                if (!tolerant) {
                    throw new IOException("Invalid chunk frame length at local index " + i);
                }
                issues.add(readIssue(NBTReadIssue.Severity.PARTIAL_DATA_LOSS,
                        "REGION_FRAME_INVALID", slotPath(i),
                        "槽位帧长度无效，已保留槽位并延迟恢复"));
                compression[i] = 0;
                external[i] = false;
                isolatedSlots[i] = true;
                continue;
            }
            int marker = Byte.toUnsignedInt(frame.get());
            int compressionId = marker & 0x7F;
            try {
                CompressionType.fromId(compressionId);
            } catch (IOException unsupported) {
                if (!tolerant) {
                    throw unsupported;
                }
                issues.add(readIssue(NBTReadIssue.Severity.PARTIAL_DATA_LOSS,
                        "REGION_COMPRESSION_UNSUPPORTED", slotPath(i),
                        "槽位使用不受支持的压缩标记 " + compressionId + "，已隔离读取"));
                isolatedSlots[i] = true;
                compression[i] = (byte) compressionId;
                external[i] = (marker & 0x80) != 0;
                continue;
            }
            boolean isExternal = (marker & 0x80) != 0;
            if (isExternal && length != 1L) {
                if (!tolerant) {
                    throw new IOException("External chunk has inline bytes at local index " + i);
                }
                issues.add(readIssue(NBTReadIssue.Severity.PARTIAL_DATA_LOSS,
                        "REGION_EXTERNAL_FRAME_INVALID", slotPath(i),
                        "外部槽位仍包含内联数据，已延迟恢复"));
                isolatedSlots[i] = true;
            }
            compression[i] = (byte) compressionId;
            external[i] = isExternal;
            if (isExternal) {
                if (!companionExists(path, accessor, i)) {
                    if (!tolerant) {
                        throw new IOException("Missing external chunk companion for local index " + i);
                    }
                    issues.add(readIssue(NBTReadIssue.Severity.PARTIAL_DATA_LOSS,
                            "REGION_EXTERNAL_COMPANION_MISSING", slotPath(i),
                            "外部槽位伴随文件缺失，已隔离该槽位"));
                    isolatedSlots[i] = true;
                }
            }
        }
        return new HeaderData(offsets, lengths, timestamps, compression, external, isolatedSlots,
                usedSectors, size - usableSize, issues);
    }

    /// Resolves a writable filesystem path for one external chunk companion when available.
    /// @param localIndex local chunk slot
    /// @return companion path, or `null` when the accessor exposes only streams
    /// @throws IOException if standard coordinate parsing fails
    private @Nullable Path companionPath(int localIndex) throws IOException {
        if (accessor instanceof ExternalChunkAccessors.FileExternalChunkAccessor fileAccessor) {
            return fileAccessor.locate(path, ChunkUtils.getLocalX(localIndex), ChunkUtils.getLocalZ(localIndex));
        }
        return null;
    }

    /// Reads all compressed payload bytes from one referenced companion.
    /// @param localIndex local chunk slot
    /// @return complete companion payload bytes
    /// @throws IOException if the companion is unavailable or cannot be read
    private byte[] readCompanion(int localIndex) throws IOException {
        @Nullable Path knownPath = companionPath(localIndex);
        if (knownPath != null) {
            if (!NBTRegionFileIO.isRegularNonSymbolicFile(knownPath)) {
                throw new IOException("External chunk companion is not a regular file");
            }
            if (Files.size(knownPath) > MAX_COMPRESSED_BYTES) {
                throw new IOException("External chunk companion is too large");
            }
            try (InputStream input = Files.newInputStream(knownPath, LinkOption.NOFOLLOW_LINKS)) {
                return NBTRegionFileIO.readBounded(input, MAX_COMPRESSED_BYTES,
                        "External chunk companion is too large");
            }
        }
        try (@Nullable InputStream input = accessor.openInputStream(
                ChunkUtils.getLocalX(localIndex), ChunkUtils.getLocalZ(localIndex))) {
            if (input == null) {
                throw new IOException("External accessor cannot read local index " + localIndex);
            }
            return NBTRegionFileIO.readBounded(input, MAX_COMPRESSED_BYTES,
                    "External chunk companion is too large");
        }
    }

    /// Reads an external companion with the caller's encoded-byte limit.
    /// A tolerant read keeps the bounded prefix when the stream is longer than the policy and
    /// reports the truncation through the supplied issue list.
    /// @param localIndex local chunk slot
    /// @param limits bounded input policy
    /// @param issues diagnostic sink
    /// @return bounded companion bytes, possibly empty when unavailable
    private byte[] readCompanionTolerant(int localIndex, NBTReadLimits limits,
                                         List<NBTReadIssue> issues) throws IOException {
        long configuredMaximum = Math.min(limits.maxEncodedBytes(), MAX_COMPRESSED_BYTES);
        int maximum = Math.toIntExact(Math.min(configuredMaximum, Integer.MAX_VALUE));
        @Nullable Path knownPath = companionPath(localIndex);
        @Nullable InputStream input;
        if (knownPath != null) {
            if (!NBTRegionFileIO.isRegularNonSymbolicFile(knownPath)) {
                issues.add(readIssue(NBTReadIssue.Severity.PARTIAL_DATA_LOSS,
                        "REGION_EXTERNAL_COMPANION_MISSING", slotPath(localIndex),
                        "外部槽位伴随文件不存在"));
                return new byte[0];
            }
            input = Files.newInputStream(knownPath, LinkOption.NOFOLLOW_LINKS);
        } else {
            input = accessor.openInputStream(ChunkUtils.getLocalX(localIndex), ChunkUtils.getLocalZ(localIndex));
            if (input == null) {
                issues.add(readIssue(NBTReadIssue.Severity.PARTIAL_DATA_LOSS,
                        "REGION_EXTERNAL_COMPANION_MISSING", slotPath(localIndex),
                        "外部槽位伴随文件不可用"));
                return new byte[0];
            }
        }
        try (InputStream source = input) {
            ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(maximum, 8192));
            byte[] buffer = new byte[8192];
            int count;
            while ((count = source.read(buffer)) >= 0) {
                if (count == 0) {
                    int single = source.read();
                    if (single < 0) {
                        break;
                    }
                    if (output.size() >= maximum) {
                        issues.add(readIssue(NBTReadIssue.Severity.PARTIAL_DATA_LOSS,
                                "REGION_EXTERNAL_COMPANION_LIMIT", slotPath(localIndex),
                                "外部槽位伴随文件超过读取限额，已截断"));
                        break;
                    }
                    output.write(single);
                    continue;
                }
                if (output.size() > maximum - count) {
                    issues.add(readIssue(NBTReadIssue.Severity.PARTIAL_DATA_LOSS,
                            "REGION_EXTERNAL_COMPANION_LIMIT", slotPath(localIndex),
                            "外部槽位伴随文件超过读取限额，已截断"));
                    break;
                }
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        }
    }

    /// Checks that a referenced companion exists and contains at least one byte.
    /// @param source region path
    /// @param accessor companion accessor
    /// @param localIndex local chunk slot
    /// @return `true` when a nonempty companion can be opened
    /// @throws IOException if companion discovery or probing fails
    private static boolean companionExists(Path source, ExternalChunkAccessor accessor, int localIndex)
            throws IOException {
        @Nullable Path knownPath;
        if (accessor instanceof ExternalChunkAccessors.FileExternalChunkAccessor fileAccessor) {
            knownPath = fileAccessor.locate(source, ChunkUtils.getLocalX(localIndex), ChunkUtils.getLocalZ(localIndex));
        } else {
            knownPath = null;
        }
        if (knownPath != null) {
            return NBTRegionFileIO.isRegularNonSymbolicFile(knownPath) && Files.size(knownPath) > 0L;
        }
        try (@Nullable InputStream input = accessor.openInputStream(
                ChunkUtils.getLocalX(localIndex), ChunkUtils.getLocalZ(localIndex))) {
            return input != null && input.read() >= 0;
        }
    }

    /// Builds the current report for one slot, including header diagnostics discovered at open.
    private synchronized NBTReadReport slotReport(int localIndex, List<NBTReadIssue> extra) {
        List<NBTReadIssue> issues = new ArrayList<>();
        String prefix = slotPath(localIndex);
        for (NBTReadIssue issue : readIssues) {
            if (issue.path().equals(prefix) || issue.path().startsWith(prefix + ".")) {
                issues.add(issue);
            }
        }
        for (NBTReadIssue issue : extra) {
            if (!issues.contains(issue)) {
                issues.add(issue);
            }
        }
        return issues.isEmpty()
                ? NBTReadReport.clean(NBTFileEncoding.REGION)
                : new NBTReadReport(NBTFileEncoding.REGION, false, issues);
    }

    /// Returns whether a slot still needs a strict repair publication.
    /// LZ4 is an intentional extension marker which remains valid for this library and is only
    /// reported as an informational warning. Every other opening/recovery diagnostic represents
    /// bytes which should be rewritten once the user confirms repair.
    /// @param localIndex local chunk slot
    /// @return whether the current in-memory slot must be published even when unchanged
    private synchronized boolean slotNeedsRepair(int localIndex) {
        String prefix = slotPath(localIndex);
        boolean unknownCompression = readIssues.stream()
                .filter(issue -> issue.path().equals(prefix) || issue.path().startsWith(prefix + "."))
                .anyMatch(issue -> "REGION_COMPRESSION_UNSUPPORTED".equals(issue.code()));
        if (unknownCompression) {
            return false;
        }
        if (isolatedSlots[localIndex]) {
            return true;
        }
        return readIssues.stream()
                .filter(issue -> issue.path().equals(prefix) || issue.path().startsWith(prefix + "."))
                .anyMatch(issue -> !"REGION_LZ4_EXTENSION".equals(issue.code()));
    }

    /// Removes completed repair diagnostics while retaining informational extension warnings.
    /// @param localIndex successfully published local chunk slot
    private synchronized void clearRepairIssues(int localIndex) {
        isolatedSlots[localIndex] = false;
        String prefix = slotPath(localIndex);
        boolean keepLz4Warning = sectorLengths[localIndex] != 0
                && Byte.toUnsignedInt(compressionTypes[localIndex]) == CompressionType.LZ4.id;
        readIssues.removeIf(issue -> (issue.path().equals(prefix) || issue.path().startsWith(prefix + "."))
                && (!"REGION_LZ4_EXTENSION".equals(issue.code()) || !keepLz4Warning));
    }

    /// Truncates an ignored partial tail once a tolerant session is explicitly saved.
    /// The tail is outside every complete region sector and therefore cannot be attributed to a
    /// slot. Removing it is the only deterministic repair; no force call is made, matching the
    /// editor's ordinary publication durability contract.
    /// @throws IOException if the source is no longer a regular file or cannot be truncated
    private void normalizeTrailingTail() throws IOException {
        if (!trailingTailNeedsRepair) {
            return;
        }
        NBTRegionFileIO.requireRegularFile(path);
        long size = channel.size();
        long aligned = size - (size & (ChunkUtils.SECTOR_BYTES - 1L));
        if (aligned < HEADER_BYTES) {
            throw new IOException("Region file no longer contains a complete header");
        }
        channel.truncate(aligned);
    }

    /// Clears the aggregate diagnostic after the ignored tail has been normalized.
    private synchronized void clearTrailingTailIssue() {
        trailingTailNeedsRepair = false;
        readIssues.removeIf(issue -> "REGION_FILE_TRAILING_TRUNCATION".equals(issue.code())
                && "region".equals(issue.path()));
    }

    /// Remembers newly observed issues without exposing mutable state to callers.
    private synchronized void rememberIssues(List<NBTReadIssue> issues) {
        for (NBTReadIssue issue : issues) {
            if (!readIssues.contains(issue)) {
                readIssues.add(issue);
            }
        }
    }

    /// Requires a usable session for checked I/O operations.
    /// @throws IOException if the channel is closed or a prior publication left its state uncertain
    private void ensureOpen() throws IOException {
        if (closed || commitLocked) {
            throw new IOException(commitLocked
                    ? "Region file publication state is uncertain; reopen it"
                    : "Region file is closed");
        }
    }

    /// Requires a usable session for mutation methods which do not declare checked exceptions.
    /// @throws IllegalStateException if the channel is closed or a prior publication left its state uncertain
    private void ensureOpenUnchecked() {
        if (closed || commitLocked) {
            throw new IllegalStateException(commitLocked
                    ? "Region file publication state is uncertain; reopen it"
                    : "Region file is closed");
        }
    }

    /// Notifies the configured deterministic commit-stage observer.
    /// @param stage stage just reached
    /// @param localIndex affected local chunk slot
    /// @throws IOException when the test hook injects a failure
    private void reach(CommitStage stage, int localIndex) throws IOException {
        commitHook.reach(stage, localIndex);
    }

    /// Validated header arrays and allocation bitmap transferred into a new session.
    @NotNullByDefault
    private record HeaderData(int[] offsets, int[] lengths, int[] timestamps,
                              byte[] compressionTypes, boolean[] external, boolean[] isolatedSlots,
                              BitSet usedSectors, long trailingBytes,
                              List<NBTReadIssue> issues) {
    }

    /// One validated half-open sector interval in a region file.
    @NotNullByDefault
    private record SectorRange(long start, long end, int localIndex) {
    }

    /// One newly reserved contiguous sector range.
    @NotNullByDefault
    private record Allocation(int sectorOffset, int sectorCount) {
        /// Returns the absolute byte offset of the first reserved sector.
        private long byteOffset() {
            return (long) sectorOffset * ChunkUtils.SECTOR_BYTES;
        }
    }

    /// Signals that header publication succeeded before a later step failed.
    @NotNullByDefault
    private static final class ChunkCommittedException extends IOException {
        /// Creates a committed-state signal with the original post-commit failure.
        private ChunkCommittedException(int localIndex, IOException cause) {
            super("Region chunk " + localIndex + " was committed, but post-commit work failed", cause);
        }
    }

    /// Parsed chunk-frame metadata and detached compressed payload.
    @NotNullByDefault
    private record ChunkPayload(CompressionType compression, byte[] compressed) {
    }

}
