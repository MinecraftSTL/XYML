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

import net.jpountz.lz4.LZ4BlockInputStream;
import net.jpountz.lz4.LZ4BlockOutputStream;
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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.GZIPOutputStream;
import java.util.zip.Inflater;

/// A safe, copy-on-write editor for one Java Anvil region file.
///
/// The class keeps the original header and sectors untouched until a pending chunk is flushed.
/// Every changed chunk is serialized and compressed before unreferenced sectors are written and
/// forced. Only then is its location published in the header. A failed flush therefore leaves the
/// old header readable; chunks already published remain committed and the rest stay dirty.
///
/// Region files always expose 1024 fixed local slots. Chunk values passed to and returned from
/// this class are deep copies, so callers cannot mutate the session without an explicit write.
@NotNullByDefault
public final class NBTRegionFile implements AutoCloseable {
    /// Commit milestones exposed only to deterministic package-local tests.
    @NotNullByDefault
    enum CommitStage {
        /// Newly reserved sectors have been initialized without changing the header.
        ALLOCATION_WRITTEN,
        /// New inline or external-marker bytes have been written.
        PAYLOAD_WRITTEN,
        /// New payload bytes have been forced before header publication.
        PAYLOAD_FORCED,
        /// A new external companion has replaced its temporary file.
        COMPANION_PUBLISHED,
        /// The replacement header bytes have been written.
        HEADER_WRITTEN,
        /// The replacement header has been forced and is committed.
        HEADER_FORCED,
        /// The old storage is about to be cleaned up after commit.
        CLEANUP,
        /// A failed header publication is about to restore the old header.
        HEADER_ROLLBACK,
        /// A failed external publication is about to restore the old companion.
        COMPANION_ROLLBACK,
        /// A complete source fingerprint is about to be captured.
        FINGERPRINT_CAPTURE
    }

    /// Package-local deterministic failure boundary used by region transaction tests.
    @FunctionalInterface
    @NotNullByDefault
    interface CommitHook {
        /// Observes one commit stage and may inject an I/O failure.
        ///
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
    /// Defensive limit for one decompressed chunk payload.
    private static final int MAX_DECOMPRESSED_BYTES = 64 * 1024 * 1024;
    /// Defensive limit for one external compressed payload, including compression overhead.
    private static final int MAX_COMPRESSED_BYTES = MAX_DECOMPRESSED_BYTES + 1024 * 1024;

    /// Normalized path of the open region file.
    private final Path path;
    /// Channel owning all reads, copy-on-write payload writes, and header publication.
    private final FileChannel channel;
    /// Session-owned hard link which binds [#channel] to a verifiable filesystem identity.
    private final Path identityLink;
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
    /// Allocation bitmap including both header sectors and every currently reserved payload sector.
    private final BitSet usedSectors;
    /// Detached edits waiting to be published, keyed by local chunk index.
    private final Map<Integer, PendingChunk> pending = new HashMap<>();
    /// Fingerprint captured after the most recent known-good disk state.
    private @Nullable RegionFingerprint fingerprint;
    /// Whether a failed header rollback made the visible disk state unknowable.
    private boolean commitStateUncertain;
    /// Whether this session has released its file channel.
    private boolean closed;

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
        ///
        /// @param id region-format compression identifier
        CompressionType(int id) {
            this.id = id;
        }

        /// Returns the Anvil compression identifier.
        public int id() {
            return id;
        }

        /// Resolves a supported region-format compression identifier.
        ///
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
    ///
    /// @param chunk detached chunk, or `null` for an explicit clear operation
    /// @param compression compression to use when a root payload is present
    @NotNullByDefault
    private record PendingChunk(@Nullable Chunk chunk, CompressionType compression) {
    }

    /// Creates an initialized session around an already validated open channel.
    ///
    /// @param path normalized region path
    /// @param channel owned read-write file channel
    /// @param identityLink session-owned hard link used to verify the visible path identity
    /// @param accessor external companion accessor
    /// @param commitHook commit-stage observer
    /// @param sectorOffsets validated location offsets
    /// @param sectorLengths validated location lengths
    /// @param timestamps raw timestamp values
    /// @param compressionTypes decoded compression identifiers
    /// @param external decoded external-payload flags
    /// @param usedSectors initial sector allocation bitmap
    private NBTRegionFile(Path path, FileChannel channel, Path identityLink,
                          ExternalChunkAccessor accessor, CommitHook commitHook,
                          int[] sectorOffsets, int[] sectorLengths, int[] timestamps,
                          byte[] compressionTypes, boolean[] external, BitSet usedSectors) {
        this.path = path;
        this.channel = channel;
        this.identityLink = identityLink;
        this.accessor = accessor;
        this.commitHook = commitHook;
        this.sectorOffsets = sectorOffsets;
        this.sectorLengths = sectorLengths;
        this.timestamps = timestamps;
        this.compressionTypes = compressionTypes;
        this.external = external;
        this.usedSectors = usedSectors;
    }

    /// Opens or creates a region file and validates its complete header and sector framing.
    ///
    /// A new file is initialized with two zero-filled header sectors. Existing files must have a
    /// sector-aligned length, non-overlapping chunk sectors, and valid chunk framing.
    ///
    /// @param path region file path
    /// @return an open region session
    /// @throws IOException if the file cannot be opened or fails structural validation
    @Contract("_ -> new")
    public static NBTRegionFile open(Path path) throws IOException {
        Objects.requireNonNull(path, "path");
        return open(path, ExternalChunkAccessor.of(path));
    }

    /// Opens or creates a region file with an explicit external-chunk accessor.
    ///
    /// The accessor is used for both validating existing external chunks and reading them. A
    /// copy-on-write write of an oversized chunk requires the accessor to identify a filesystem
    /// companion path (the standard [ExternalChunkAccessor#of(Path)] accessor does so).
    ///
    /// @param path region file path
    /// @param accessor external chunk locator
    /// @return an open region session
    /// @throws IOException if the file cannot be opened or fails structural validation
    @Contract("_, _ -> new")
    public static NBTRegionFile open(Path path, ExternalChunkAccessor accessor) throws IOException {
        return open(path, accessor, NO_COMMIT_HOOK);
    }

    /// Opens a region session with a deterministic package-local commit hook.
    ///
    /// @param path region file path
    /// @param accessor external chunk locator
    /// @param commitHook commit-stage observer
    /// @return an open region session
    /// @throws IOException if the region cannot be opened and validated
    static NBTRegionFile open(Path path, ExternalChunkAccessor accessor, CommitHook commitHook) throws IOException {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(accessor, "accessor");
        Objects.requireNonNull(commitHook, "commitHook");
        Path absolute = path.toAbsolutePath().normalize();
        @Nullable Path parent = absolute.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        ensureRegionFileExists(absolute);
        Path identityLink = createIdentityLink(absolute);
        @Nullable FileChannel channel = null;
        boolean success = false;
        try {
            channel = FileChannel.open(identityLink, StandardOpenOption.READ,
                    StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
            if (channel.size() == 0L) {
                writeZeros(channel, 0L, HEADER_BYTES);
                channel.force(true);
            }
            HeaderData header = readAndValidateHeader(absolute, channel, accessor);
            NBTRegionFile result = new NBTRegionFile(absolute, channel, identityLink, accessor, commitHook,
                    header.offsets, header.lengths,
                    header.timestamps, header.compressionTypes, header.external, header.usedSectors);
            result.validateExistingPayloads();
            result.fingerprint = result.computeFingerprint();
            success = true;
            return result;
        } finally {
            if (!success) {
                if (channel != null) {
                    channel.close();
                }
                Files.deleteIfExists(identityLink);
            }
        }
    }

    /// Returns the path opened by this session.
    ///
    /// @return normalized region path
    public Path path() {
        return path;
    }

    /// Returns whether this session has pending chunk changes.
    ///
    /// @return `true` when at least one chunk is pending
    public boolean isDirty() {
        return !pending.isEmpty();
    }

    /// Returns a stable snapshot of pending local indexes in ascending order.
    ///
    /// @return immutable ascending local-index snapshot
    public @Unmodifiable List<Integer> dirtyChunkIndexes() {
        List<Integer> indexes = new ArrayList<>(pending.keySet());
        Collections.sort(indexes);
        return List.copyOf(indexes);
    }

    /// Replaces every pending slot with the differences between a snapshot and its committed baseline.
    ///
    /// The complete replacement map is built before the current pending state changes. This lets an
    /// owning [NBTFile] cancel a failed pending write when a later editor snapshot returns that slot
    /// to its committed value, while preserving the existing or previously selected compression for
    /// slots which still differ.
    ///
    /// @param snapshot complete detached editor snapshot
    /// @param committedBaseline complete detached state known to be visible on disk
    void synchronizePendingChanges(ChunkRegion snapshot, ChunkRegion committedBaseline) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(committedBaseline, "committedBaseline");
        ensureOpenUnchecked();
        Map<Integer, PendingChunk> replacement = new HashMap<>();
        for (int localIndex = 0; localIndex < snapshot.size(); localIndex++) {
            Chunk changed = snapshot.getChunk(localIndex);
            if (!changed.equals(committedBaseline.getChunk(localIndex))) {
                replacement.put(localIndex,
                        new PendingChunk(changed.clone(), preferredCompression(localIndex)));
            }
        }
        pending.clear();
        pending.putAll(replacement);
    }

    /// Reads a chunk as a detached deep copy.
    ///
    /// An empty slot is represented by a `Chunk` with a `null` root tag. A pending clear is
    /// visible immediately and returns a fresh empty chunk.
    ///
    /// @param localIndex local slot from 0 through 1023
    /// @return detached chunk copy
    /// @throws IOException if the chunk payload is malformed or cannot be decoded
    public Chunk readChunk(int localIndex) throws IOException {
        checkIndex(localIndex);
        ensureOpen();
        ensurePathIdentity();
        @Nullable PendingChunk changed = pending.get(localIndex);
        if (changed != null) {
            return changed.chunk == null ? new Chunk() : changed.chunk.clone();
        }

        int offset = sectorOffsets[localIndex];
        int length = sectorLengths[localIndex];
        if (offset == 0 && length == 0) {
            return new Chunk(timestamp(localIndex));
        }

        byte[] sector = readBytes((long) offset * ChunkUtils.SECTOR_BYTES,
                (long) length * ChunkUtils.SECTOR_BYTES);
        ChunkPayload payload = readPayload(localIndex, sector, length);
        CompoundTag root = parseCompound(decompress(payload.compression, payload.compressed), localIndex);
        return new Chunk(timestamp(localIndex), root);
    }

    /// Reads a chunk by local X/Z coordinates as a detached deep copy.
    ///
    /// @param localX local X coordinate from 0 through 31
    /// @param localZ local Z coordinate from 0 through 31
    /// @return detached chunk copy
    /// @throws IOException if the chunk payload is malformed or cannot be decoded
    public Chunk readChunk(int localX, int localZ) throws IOException {
        return readChunk(ChunkUtils.toLocalIndex(
                checkedCoordinate(localX), checkedCoordinate(localZ)));
    }

    /// Schedules a deep copy of a chunk for the next [#flush()] call.
    ///
    /// Newly written chunks use ZLIB compression. The existing chunk's sectors and compression
    /// method are never rewritten unless this method is called.
    ///
    /// @param localIndex local slot from 0 through 1023
    /// @param chunk chunk to copy; a `null` root means an empty slot with its timestamp retained
    public void writeChunk(int localIndex, Chunk chunk) {
        checkIndex(localIndex);
        ensureOpenUnchecked();
        writeChunk(localIndex, chunk, preferredCompression(localIndex));
    }

    /// Schedules a deep copy of a chunk using the selected compression method.
    ///
    /// @param localIndex local slot from 0 through 1023
    /// @param chunk chunk to copy
    /// @param compression compression method for the new payload
    public void writeChunk(int localIndex, Chunk chunk, CompressionType compression) {
        checkIndex(localIndex);
        ensureOpenUnchecked();
        Objects.requireNonNull(chunk, "chunk");
        Objects.requireNonNull(compression, "compression");
        pending.put(localIndex, new PendingChunk(chunk.clone(), compression));
    }

    /// Schedules a compound root for writing using the default ZLIB compression.
    ///
    /// @param localIndex local slot from 0 through 1023
    /// @param root compound root, or `null` to clear the slot
    public void writeChunk(int localIndex, @Nullable CompoundTag root) {
        checkIndex(localIndex);
        ensureOpenUnchecked();
        CompressionType compression = preferredCompression(localIndex);
        pending.put(localIndex, root == null
                ? new PendingChunk(new Chunk(), compression)
                : new PendingChunk(new Chunk(root.clone()), compression));
    }

    /// Schedules a detached chunk by local X/Z coordinates.
    ///
    /// @param localX local X coordinate from 0 through 31
    /// @param localZ local Z coordinate from 0 through 31
    /// @param chunk chunk to copy
    public void writeChunk(int localX, int localZ, Chunk chunk) {
        writeChunk(ChunkUtils.toLocalIndex(checkedCoordinate(localX), checkedCoordinate(localZ)), chunk);
    }

    /// Schedules a detached chunk by local X/Z coordinates using the selected compression.
    ///
    /// @param localX local X coordinate from 0 through 31
    /// @param localZ local Z coordinate from 0 through 31
    /// @param chunk chunk to copy
    /// @param compression compression method for the new payload
    public void writeChunk(int localX, int localZ, Chunk chunk, CompressionType compression) {
        writeChunk(ChunkUtils.toLocalIndex(checkedCoordinate(localX), checkedCoordinate(localZ)),
                chunk, compression);
    }

    /// Schedules a compound root by local X/Z coordinates.
    ///
    /// @param localX local X coordinate from 0 through 31
    /// @param localZ local Z coordinate from 0 through 31
    /// @param root compound root, or `null` to clear the slot
    public void writeChunk(int localX, int localZ, @Nullable CompoundTag root) {
        writeChunk(ChunkUtils.toLocalIndex(checkedCoordinate(localX), checkedCoordinate(localZ)), root);
    }

    /// Schedules a slot clear. The old sectors are released only after the new header entry is forced.
    ///
    /// @param localIndex local slot from 0 through 1023
    public void clearChunk(int localIndex) {
        checkIndex(localIndex);
        ensureOpenUnchecked();
        pending.put(localIndex, new PendingChunk(null, CompressionType.ZLIB));
    }

    /// Schedules a slot clear by local X/Z coordinates.
    ///
    /// @param localX local X coordinate from 0 through 31
    /// @param localZ local Z coordinate from 0 through 31
    public void clearChunk(int localX, int localZ) {
        clearChunk(ChunkUtils.toLocalIndex(checkedCoordinate(localX), checkedCoordinate(localZ)));
    }

    /// Flushes pending chunks in ascending local-index order using copy-on-write publication.
    ///
    /// If a later chunk fails, earlier chunks remain committed and an
    /// [NBTPartialSaveException] identifies the committed indexes. Failed and later chunks remain
    /// dirty in memory.
    ///
    /// @throws IOException if a chunk cannot be encoded, written, or published
    public void flush() throws IOException {
        ensureOpen();
        checkFingerprint();
        if (pending.isEmpty()) {
            channel.force(true);
            return;
        }

        List<Integer> indexes = new ArrayList<>(pending.keySet());
        Collections.sort(indexes);
        List<Integer> committed = new ArrayList<>();
        for (int localIndex : indexes) {
            try {
                checkFingerprint();
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
                try {
                    fingerprint = computeFingerprint();
                } catch (IOException | RuntimeException fingerprintFailure) {
                    commitStateUncertain = true;
                    IOException wrapped = asIOException(
                            "Failed to fingerprint committed region chunk " + localIndex,
                            fingerprintFailure);
                    NBTCommitUncertainException uncertain = new NBTCommitUncertainException(
                            path, localIndex, wrapped);
                    uncertain.addSuppressed(exception);
                    throw new NBTPartialSaveException(committed, -1, uncertain);
                }
                throw new NBTPartialSaveException(committed, -1, exception);
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
            try {
                fingerprint = computeFingerprint();
            } catch (IOException | RuntimeException exception) {
                commitStateUncertain = true;
                IOException wrapped = asIOException(
                        "Failed to fingerprint committed region chunk " + localIndex,
                        exception);
                throw new NBTPartialSaveException(
                        committed,
                        -1,
                        new NBTCommitUncertainException(path, localIndex, wrapped));
            }
        }
    }

    /// Closes the underlying channel without implicitly publishing pending changes.
    ///
    /// Call [#flush()] explicitly to publish edits. This fail-closed behavior prevents a close
    /// during error recovery from retrying a partial save behind the caller's back. Pending
    /// snapshots remain observable through [#isDirty()] and [#dirtyChunkIndexes()] after close,
    /// but the closed session cannot publish them.
    ///
    /// @throws IOException if the channel cannot be closed
    @Override
    public void close() throws IOException {
        if (closed) {
            Files.deleteIfExists(identityLink);
            return;
        }
        @Nullable IOException failure = null;
        try {
            channel.close();
        } catch (IOException closeFailure) {
            failure = closeFailure;
        }
        try {
            Files.deleteIfExists(identityLink);
        } catch (IOException cleanupFailure) {
            if (failure == null) {
                failure = cleanupFailure;
            } else {
                failure.addSuppressed(cleanupFailure);
            }
        } finally {
            closed = true;
        }
        if (failure != null) {
            throw failure;
        }
    }

    /// Serializes and publishes one pending chunk while retaining the previous visible storage.
    ///
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
                publishClear(localIndex, previousCompanion);
            } catch (ChunkCommittedException exception) {
                releaseSectors(oldOffset, oldLength);
                throw exception;
            } catch (NBTCommitUncertainException exception) {
                commitStateUncertain = true;
                throw exception;
            }
            releaseSectors(oldOffset, oldLength);
            return;
        }

        @Nullable CompoundTag root = chunk.getRootTag();
        if (root == null) {
            try {
                publishHeader(localIndex, 0, 0, epochSeconds(chunk.getTimestamp()), false, (byte) 0);
            } catch (ChunkCommittedException exception) {
                releaseSectors(oldOffset, oldLength);
                throw exception;
            } catch (NBTCommitUncertainException exception) {
                commitStateUncertain = true;
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
        byte[] nbt = NBTCodec.of().writeTagToByteArray(root.clone());
        byte[] compressed = compress(change.compression, nbt);
        long framedBytes = compressed.length + 5L;
        if (framedBytes <= MAX_INLINE_BYTES) {
            int sectors = Math.toIntExact((framedBytes + ChunkUtils.SECTOR_BYTES - 1) / ChunkUtils.SECTOR_BYTES);
            Allocation allocation = allocateSectors(sectors, localIndex);
            try {
                writeInlineChunk(allocation.byteOffset(), sectors, change.compression, compressed);
                reach(CommitStage.PAYLOAD_WRITTEN, localIndex);
                channel.force(true);
                reach(CommitStage.PAYLOAD_FORCED, localIndex);
                publishHeader(localIndex, allocation.sectorOffset, sectors,
                        epochSeconds(chunk.getTimestamp()), false, (byte) change.compression.id());
            } catch (ChunkCommittedException exception) {
                releaseSectors(oldOffset, oldLength);
                throw exception;
            } catch (NBTCommitUncertainException exception) {
                commitStateUncertain = true;
                throw exception;
            } catch (IOException | RuntimeException exception) {
                releaseSectors(allocation.sectorOffset, allocation.sectorCount);
                refreshFingerprintAfterFailedWrite(localIndex, exception);
                throw exception;
            }
            releaseSectors(oldOffset, oldLength);
            deleteCompanionAfterPublish(localIndex, previousCompanion);
        } else {
            if (oldExternal && Byte.toUnsignedInt(compressionTypes[localIndex]) != change.compression.id()) {
                throw new IOException("Changing compression for an already external chunk cannot be published "
                        + "atomically with the standard companion-file format");
            }
            Allocation allocation = allocateSectors(1, localIndex);
            @Nullable CompanionSwap companion = null;
            try {
                companion = writeCompanion(localIndex, compressed, oldExternal);
                reach(CommitStage.COMPANION_PUBLISHED, localIndex);
                writeExternalMarker(allocation.byteOffset(), change.compression);
                reach(CommitStage.PAYLOAD_WRITTEN, localIndex);
                channel.force(true);
                reach(CommitStage.PAYLOAD_FORCED, localIndex);
                publishHeader(localIndex, allocation.sectorOffset, 1,
                        epochSeconds(chunk.getTimestamp()), true, (byte) change.compression.id());
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
                commitStateUncertain = true;
                throw exception;
            } catch (IOException | RuntimeException exception) {
                if (companion != null) {
                    try {
                        companion.restore(localIndex);
                    } catch (IOException | RuntimeException restoreFailure) {
                        releaseSectors(allocation.sectorOffset, allocation.sectorCount);
                        commitStateUncertain = true;
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
                refreshFingerprintAfterFailedWrite(localIndex, exception);
                throw exception;
            }
            releaseSectors(oldOffset, oldLength);
            companion.commit(localIndex);
        }
    }

    /// Publishes an empty location and then removes the previously referenced companion.
    ///
    /// @param localIndex local chunk slot being cleared
    /// @param previousCompanion owned companion to remove after publication, if present
    /// @throws IOException if header publication or post-commit cleanup fails
    private void publishClear(int localIndex, @Nullable Path previousCompanion) throws IOException {
        publishHeader(localIndex, 0, 0, 0, false, (byte) 0);
        deleteCompanionAfterPublish(localIndex, previousCompanion);
    }

    /// Publishes one timestamp and location entry, restoring their prior values on pre-force failure.
    ///
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
        ByteBuffer newLocation = encodeLocation(offset, length);
        boolean headerForced = false;
        try {
            ensurePathIdentity();
            // Publishing the timestamp first keeps the old location structurally readable until
            // the final four-byte location switch reaches disk.
            writeFully(channel, newTimestamp,
                    ChunkUtils.SECTOR_BYTES + (long) localIndex * Integer.BYTES);
            channel.force(true);
            writeFully(channel, newLocation, (long) localIndex * Integer.BYTES);
            reach(CommitStage.HEADER_WRITTEN, localIndex);
            channel.force(true);
            headerForced = true;
            ensurePathIdentity();
            reach(CommitStage.HEADER_FORCED, localIndex);
        } catch (RegionPathChangedException exception) {
            commitStateUncertain = true;
            if (headerForced) {
                throw new NBTCommitUncertainException(path, localIndex, exception);
            }
            throw exception;
        } catch (IOException | RuntimeException failure) {
            IOException exception = asIOException("Region header publication failed", failure);
            if (headerForced) {
                sectorOffsets[localIndex] = offset;
                sectorLengths[localIndex] = length;
                timestamps[localIndex] = timestamp;
                external[localIndex] = isExternal;
                compressionTypes[localIndex] = compression;
                throw new ChunkCommittedException(localIndex, exception);
            }
            try {
                reach(CommitStage.HEADER_ROLLBACK, localIndex);
                writeFully(channel, encodeLocation(oldOffset, oldLength),
                        (long) localIndex * Integer.BYTES);
                ByteBuffer rollbackTimestamp = ByteBuffer.allocate(Integer.BYTES).order(ByteOrder.BIG_ENDIAN)
                        .putInt(oldTimestamp);
                rollbackTimestamp.flip();
                writeFully(channel, rollbackTimestamp,
                        ChunkUtils.SECTOR_BYTES + (long) localIndex * Integer.BYTES);
                channel.force(true);
            } catch (IOException | RuntimeException rollbackFailure) {
                commitStateUncertain = true;
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
    }

    /// Reserves and zero-fills a contiguous free sector range without changing the header.
    ///
    /// @param count number of sectors to reserve
    /// @param localIndex local chunk slot receiving the allocation
    /// @return reserved sector range
    /// @throws IOException if the range cannot be represented or initialized
    private Allocation allocateSectors(int count, int localIndex) throws IOException {
        if (count < 1 || count > MAX_SECTOR_COUNT) {
            throw new IOException("Invalid sector allocation count: " + count);
        }
        ensurePathIdentity();
        long size = channel.size();
        if ((size & (ChunkUtils.SECTOR_BYTES - 1L)) != 0L) {
            throw new IOException("Region file length is not sector-aligned");
        }
        int fileSectors = Math.toIntExact(size / ChunkUtils.SECTOR_BYTES);
        int sectorOffset = 2;
        while (true) {
            sectorOffset = usedSectors.nextClearBit(sectorOffset);
            int nextUsed = usedSectors.nextSetBit(sectorOffset);
            int freeEnd = nextUsed < 0 ? fileSectors : nextUsed;
            if (sectorOffset + count <= freeEnd || nextUsed < 0) {
                break;
            }
            sectorOffset = nextUsed + 1;
        }
        if (sectorOffset > 0xFF_FFFF || sectorOffset + count > 0x1_000000) {
            throw new IOException("Region sector offset exceeds the header limit");
        }
        long offset = (long) sectorOffset * ChunkUtils.SECTOR_BYTES;
        long end;
        try {
            end = Math.addExact(offset, Math.multiplyExact((long) count, ChunkUtils.SECTOR_BYTES));
        } catch (ArithmeticException exception) {
            throw new IOException("Region file is too large", exception);
        }
        usedSectors.set(sectorOffset, sectorOffset + count);
        try {
            writeZeros(channel, offset, end - offset);
            reach(CommitStage.ALLOCATION_WRITTEN, localIndex);
            return new Allocation(sectorOffset, count);
        } catch (IOException | RuntimeException exception) {
            usedSectors.clear(sectorOffset, sectorOffset + count);
            refreshFingerprintAfterFailedWrite(localIndex, exception);
            throw exception;
        }
    }

    /// Marks a previously owned payload range available for later allocations.
    ///
    /// @param offset first sector in the range
    /// @param length number of sectors in the range
    private void releaseSectors(int offset, int length) {
        if (offset >= 2 && length > 0) {
            usedSectors.clear(offset, offset + length);
        }
    }

    /// Refreshes the known fingerprint after an unpublished write extended or changed free space.
    ///
    /// @param localIndex local chunk slot whose unpublished payload write failed
    /// @param failure original unpublished-write failure retained as suppressed context
    /// @throws NBTCommitUncertainException if the current source fingerprint cannot be established
    private void refreshFingerprintAfterFailedWrite(int localIndex, Throwable failure)
            throws NBTCommitUncertainException {
        try {
            fingerprint = computeFingerprint();
        } catch (IOException | RuntimeException fingerprintFailure) {
            commitStateUncertain = true;
            IOException wrapped = asIOException(
                    "Failed to refresh region fingerprint after a failed chunk write",
                    fingerprintFailure);
            NBTCommitUncertainException uncertain = new NBTCommitUncertainException(path, localIndex, wrapped);
            uncertain.addSuppressed(failure);
            throw uncertain;
        }
    }

    /// Preserves checked I/O failures and gives unchecked filesystem failures checked context.
    ///
    /// @param message context for an unchecked failure
    /// @param failure original checked or unchecked failure
    /// @return original I/O failure or a checked wrapper
    private static IOException asIOException(String message, Throwable failure) {
        return failure instanceof IOException ioException
                ? ioException
                : new IOException(Objects.requireNonNull(message, "message"), failure);
    }

    /// Writes a complete inline frame into an unreferenced sector range.
    ///
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
        writeFully(channel, frame, offset);
    }

    /// Writes an external-payload marker into one unreferenced sector.
    ///
    /// @param offset byte offset of the reserved sector
    /// @param compression compression marker for the companion payload
    /// @throws IOException if the marker cannot be written completely
    private void writeExternalMarker(long offset, CompressionType compression) throws IOException {
        ByteBuffer frame = ByteBuffer.allocate(ChunkUtils.SECTOR_BYTES).order(ByteOrder.BIG_ENDIAN);
        frame.putInt(1);
        frame.put((byte) (compression.id() | 0x80));
        frame.flip();
        writeFully(channel, frame, offset);
    }

    /// Atomically publishes a compressed companion payload while retaining a restorable backup.
    ///
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
            Files.createDirectories(parent);
        }
        Path temporary = Files.createTempFile(parent == null ? path.toAbsolutePath().getParent() : parent,
                target.getFileName().toString(), ".tmp");
        @Nullable Path backup = null;
        try {
            try (FileChannel output = FileChannel.open(temporary, StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING)) {
                writeFully(output, ByteBuffer.wrap(compressed), 0L);
                output.force(true);
            }
            if (Files.exists(target)) {
                if (!replaceOwned) {
                    throw new IOException("Refusing to replace an unreferenced external chunk companion: " + target);
                }
                backup = Files.createTempFile(parent == null ? path.toAbsolutePath().getParent() : parent,
                        target.getFileName().toString(), ".old");
                Files.copy(target, backup, StandardCopyOption.REPLACE_EXISTING);
                forceFile(backup);
            }
            moveAtomically(temporary, target);
            return new CompanionSwap(temporary, target, backup);
        } catch (IOException | RuntimeException exception) {
            Files.deleteIfExists(temporary);
            if (backup != null) {
                Files.deleteIfExists(backup);
            }
            throw exception;
        }
    }

    /// Removes an owned companion only after its header entry no longer references it.
    ///
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
        /// Forced backup of the previous owned companion, if one existed.
        private final @Nullable Path backupPath;
        /// Whether header publication made this swap permanent.
        private boolean committed;

        /// Creates a reversible companion swap after its target was atomically replaced.
        ///
        /// @param temporary staging path, normally absent after the atomic move
        /// @param target canonical companion path
        /// @param backupPath previous companion backup, if one existed
        private CompanionSwap(Path temporary, Path target, @Nullable Path backupPath) {
            this.temporary = temporary;
            this.target = target;
            this.backupPath = backupPath;
        }

        /// Marks the replacement permanent and removes no-longer-needed temporary files.
        ///
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
                throw new ChunkCommittedException(
                        localIndex,
                        asIOException("Committed companion swap cleanup failed", exception));
            }
        }

        /// Restores the prior companion, or removes a newly created companion, before header commit.
        ///
        /// @param localIndex affected local chunk slot
        /// @throws IOException if the prior companion state cannot be restored atomically
        private void restore(int localIndex) throws IOException {
            if (committed) {
                return;
            }
            reach(CommitStage.COMPANION_ROLLBACK, localIndex);
            if (backupPath != null) {
                if (!Files.isRegularFile(backupPath)) {
                    throw new IOException("External chunk backup disappeared before rollback: " + backupPath);
                }
                moveAtomically(backupPath, target);
            } else {
                Files.deleteIfExists(target);
            }
            Files.deleteIfExists(temporary);
        }
    }

    /// Parses one region frame and resolves its inline or external compressed payload.
    ///
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

    /// Decompresses one validated chunk payload with a bounded output size.
    ///
    /// @param compression payload compression type
    /// @param payload compressed payload bytes
    /// @return detached uncompressed NBT bytes
    /// @throws IOException if the payload is malformed, truncated, trailing, or too large
    private byte[] decompress(CompressionType compression, byte[] payload) throws IOException {
        return switch (compression) {
            case UNCOMPRESSED -> {
                if (payload.length > MAX_DECOMPRESSED_BYTES) {
                    throw new IOException("Uncompressed chunk payload exceeds the size limit");
                }
                yield payload.clone();
            }
            case GZIP -> readGzip(payload);
            case LZ4 -> readLz4(payload);
            case ZLIB -> inflate(payload);
        };
    }

    /// Strictly expands one GZIP payload.
    ///
    /// @param payload complete compressed bytes
    /// @return uncompressed bytes
    /// @throws IOException if GZIP validation or bounded reading fails
    private byte[] readGzip(byte[] payload) throws IOException {
        return NBTCodec.decodeGzipStrict(payload, MAX_DECOMPRESSED_BYTES);
    }

    /// Strictly expands one LZ4 block-stream payload.
    ///
    /// @param payload complete compressed bytes
    /// @return uncompressed bytes
    /// @throws IOException if LZ4 validation or bounded reading fails
    private byte[] readLz4(byte[] payload) throws IOException {
        ByteArrayInputStream source = new ByteArrayInputStream(payload);
        return readCompressedStream(new LZ4BlockInputStream(source), source, payload);
    }

    /// Strictly inflates one zlib payload and rejects unused trailing input.
    ///
    /// @param payload complete compressed bytes
    /// @return uncompressed bytes
    /// @throws IOException if the payload is malformed, truncated, trailing, or too large
    private byte[] inflate(byte[] payload) throws IOException {
        Inflater inflater = new Inflater();
        try {
            inflater.setInput(payload);
            ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(payload.length * 2, 8192));
            byte[] buffer = new byte[8192];
            while (!inflater.finished()) {
                int count;
                try {
                    count = inflater.inflate(buffer);
                } catch (java.util.zip.DataFormatException exception) {
                    throw new IOException("Invalid ZLIB chunk payload", exception);
                }
                if (count > 0) {
                    if (output.size() > MAX_DECOMPRESSED_BYTES - count) {
                        throw new IOException("Chunk payload is too large after decompression");
                    }
                    output.write(buffer, 0, count);
                } else if (inflater.needsDictionary() || inflater.needsInput()) {
                    throw new IOException("Truncated ZLIB chunk payload");
                }
            }
            if (inflater.getRemaining() != 0) {
                throw new IOException("Trailing bytes after ZLIB chunk payload");
            }
            return output.toByteArray();
        } finally {
            inflater.end();
        }
    }

    /// Reads a stream decompressor to EOF while enforcing the chunk output and input boundaries.
    ///
    /// @param input decompressor layered over `source`
    /// @param source compressed byte source used to detect trailing data
    /// @param compressed original compressed bytes used to size the output buffer
    /// @return complete uncompressed bytes
    /// @throws IOException if decompression fails, input remains, or output exceeds the limit
    private byte[] readCompressedStream(InputStream input, ByteArrayInputStream source,
                                        byte[] compressed) throws IOException {
        try (InputStream stream = input) {
            ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(compressed.length * 2, 8192));
            byte[] buffer = new byte[8192];
            int count;
            while ((count = stream.read(buffer)) >= 0) {
                if (count == 0) {
                    continue;
                }
                if (output.size() > MAX_DECOMPRESSED_BYTES - count) {
                    throw new IOException("Chunk payload is too large after decompression");
                }
                output.write(buffer, 0, count);
            }
            if (source.available() != 0) {
                throw new IOException("Trailing bytes after compressed chunk payload");
            }
            return output.toByteArray();
        }
    }

    /// Parses exactly one detached compound root and validates its complete NBT structure.
    ///
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
    ///
    /// @throws IOException if any existing chunk cannot be read and validated
    private void validateExistingPayloads() throws IOException {
        for (int localIndex = 0; localIndex < ChunkUtils.CHUNKS_PRE_REGION; localIndex++) {
            if (sectorLengths[localIndex] != 0) {
                readChunk(localIndex);
            }
        }
    }

    /// Selects pending or existing compression, defaulting new chunks to zlib.
    ///
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

    /// Compresses serialized NBT bytes with the selected region compression method.
    ///
    /// @param compression output compression type
    /// @param input complete serialized NBT bytes
    /// @return compressed payload bytes without a region frame prefix
    /// @throws IOException if compression fails
    private byte[] compress(CompressionType compression, byte[] input) throws IOException {
        if (compression == CompressionType.UNCOMPRESSED) {
            return input.clone();
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(input.length, 8192));
        OutputStream compressor = switch (compression) {
            case GZIP -> new GZIPOutputStream(output);
            case ZLIB -> new DeflaterOutputStream(output);
            case LZ4 -> new LZ4BlockOutputStream(output);
            case UNCOMPRESSED -> throw new AssertionError();
        };
        try (compressor) {
            compressor.write(input);
        }
        return output.toByteArray();
    }

    /// Reads the complete region header and validates all location ranges and chunk markers.
    ///
    /// @param path region path used for diagnostics and companion discovery
    /// @param channel open region channel
    /// @param accessor external companion accessor
    /// @return validated header arrays and occupied-sector bitmap
    /// @throws IOException if header structure, ranges, markers, or companions are invalid
    private static HeaderData readAndValidateHeader(Path path, FileChannel channel,
                                                     ExternalChunkAccessor accessor) throws IOException {
        long size = channel.size();
        if (size < HEADER_BYTES || (size & (ChunkUtils.SECTOR_BYTES - 1L)) != 0L) {
            throw new IOException("Region file must be at least two sector-aligned header sectors: " + path);
        }
        ByteBuffer buffer = ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.BIG_ENDIAN);
        readFully(channel, buffer, 0L);
        buffer.flip();
        int[] offsets = new int[ChunkUtils.CHUNKS_PRE_REGION];
        int[] lengths = new int[ChunkUtils.CHUNKS_PRE_REGION];
        int[] timestamps = new int[ChunkUtils.CHUNKS_PRE_REGION];
        byte[] compression = new byte[ChunkUtils.CHUNKS_PRE_REGION];
        boolean[] external = new boolean[ChunkUtils.CHUNKS_PRE_REGION];
        BitSet usedSectors = new BitSet(Math.toIntExact(size / ChunkUtils.SECTOR_BYTES));
        usedSectors.set(0, 2);
        for (int i = 0; i < offsets.length; i++) {
            int offset = ((Byte.toUnsignedInt(buffer.get()) << 16)
                    | (Byte.toUnsignedInt(buffer.get()) << 8)
                    | Byte.toUnsignedInt(buffer.get()));
            int length = Byte.toUnsignedInt(buffer.get());
            if ((offset == 0) != (length == 0)) {
                throw new IOException("Region header has a half-empty entry at local index " + i);
            }
            if (length > 0 && (offset < 2 || (long) offset + length > size / ChunkUtils.SECTOR_BYTES)) {
                throw new IOException("Region header points outside the file at local index " + i);
            }
            offsets[i] = offset;
            lengths[i] = length;
        }
        for (int i = 0; i < timestamps.length; i++) {
            timestamps[i] = buffer.getInt();
        }

        List<int[]> ranges = new ArrayList<>();
        for (int i = 0; i < offsets.length; i++) {
            if (lengths[i] != 0) {
                ranges.add(new int[]{offsets[i], offsets[i] + lengths[i], i});
                usedSectors.set(offsets[i], offsets[i] + lengths[i]);
            }
        }
        ranges.sort((a, b) -> Integer.compare(a[0], b[0]));
        int previousEnd = 2;
        for (int[] range : ranges) {
            if (range[0] < previousEnd) {
                throw new IOException("Overlapping region sectors at local index " + range[2]);
            }
            previousEnd = range[1];
        }

        for (int i = 0; i < offsets.length; i++) {
            if (lengths[i] == 0) {
                continue;
            }
            ByteBuffer frame = ByteBuffer.allocate(5).order(ByteOrder.BIG_ENDIAN);
            readFully(channel, frame, (long) offsets[i] * ChunkUtils.SECTOR_BYTES);
            frame.flip();
            long length = Integer.toUnsignedLong(frame.getInt());
            if (length < 1L || length > (long) lengths[i] * ChunkUtils.SECTOR_BYTES - 4L) {
                throw new IOException("Invalid chunk frame length at local index " + i);
            }
            int marker = Byte.toUnsignedInt(frame.get());
            int compressionId = marker & 0x7F;
            CompressionType.fromId(compressionId);
            boolean isExternal = (marker & 0x80) != 0;
            if (isExternal && length != 1L) {
                throw new IOException("External chunk has inline bytes at local index " + i);
            }
            compression[i] = (byte) compressionId;
            external[i] = isExternal;
            if (isExternal) {
                if (!companionExists(path, accessor, i)) {
                    throw new IOException("Missing external chunk companion for local index " + i);
                }
            }
        }
        return new HeaderData(offsets, lengths, timestamps, compression, external, usedSectors);
    }

    /// Resolves a writable filesystem path for one external chunk companion when available.
    ///
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
    ///
    /// @param localIndex local chunk slot
    /// @return complete companion payload bytes
    /// @throws IOException if the companion is unavailable or cannot be read
    private byte[] readCompanion(int localIndex) throws IOException {
        @Nullable Path knownPath = companionPath(localIndex);
        if (knownPath != null) {
            if (Files.size(knownPath) > MAX_COMPRESSED_BYTES) {
                throw new IOException("External chunk companion is too large");
            }
            try (InputStream input = Files.newInputStream(knownPath)) {
                return readBounded(input, MAX_COMPRESSED_BYTES, "External chunk companion is too large");
            }
        }
        try (@Nullable InputStream input = accessor.openInputStream(
                ChunkUtils.getLocalX(localIndex), ChunkUtils.getLocalZ(localIndex))) {
            if (input == null) {
                throw new IOException("External accessor cannot read local index " + localIndex);
            }
            return readBounded(input, MAX_COMPRESSED_BYTES, "External chunk companion is too large");
        }
    }

    /// Checks that a referenced companion exists and contains at least one byte.
    ///
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
            return Files.isRegularFile(knownPath) && Files.size(knownPath) > 0L;
        }
        try (@Nullable InputStream input = accessor.openInputStream(
                ChunkUtils.getLocalX(localIndex), ChunkUtils.getLocalZ(localIndex))) {
            return input != null && input.read() >= 0;
        }
    }

    /// Reads a stream into memory while rejecting input beyond a strict byte limit.
    ///
    /// @param input source stream
    /// @param maximumBytes largest accepted byte count
    /// @param limitMessage diagnostic message for oversized input
    /// @return complete bounded bytes
    /// @throws IOException if the stream cannot be read or exceeds the limit
    private static byte @Unmodifiable [] readBounded(InputStream input, int maximumBytes, String limitMessage)
            throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(maximumBytes, 8192));
        byte[] buffer = new byte[8192];
        int count;
        while ((count = input.read(buffer)) >= 0) {
            if (count == 0) {
                continue;
            }
            if (output.size() > maximumBytes - count) {
                throw new IOException(limitMessage);
            }
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }

    /// Converts one stored unsigned timestamp into an instant.
    ///
    /// @param localIndex local chunk slot
    /// @return timestamp instant
    private Instant timestamp(int localIndex) {
        return Instant.ofEpochSecond(Integer.toUnsignedLong(timestamps[localIndex]));
    }

    /// Clamps an instant to the unsigned 32-bit timestamp representation used by region headers.
    ///
    /// @param instant timestamp to encode
    /// @return raw unsigned epoch-second bits
    private static int epochSeconds(Instant instant) {
        long seconds = instant.getEpochSecond();
        if (seconds <= 0L) {
            return 0;
        }
        return seconds >= 0xFFFF_FFFFL ? -1 : (int) seconds;
    }

    /// Validates one local chunk coordinate.
    ///
    /// @param coordinate local X or Z coordinate
    /// @return the validated coordinate
    /// @throws IndexOutOfBoundsException if the coordinate is outside 0 through 31
    private static int checkedCoordinate(int coordinate) {
        return Objects.checkIndex(coordinate, ChunkUtils.CHUNKS_PER_REGION_SIDE);
    }

    /// Validates one fixed region slot index.
    ///
    /// @param localIndex local chunk slot
    /// @throws IndexOutOfBoundsException if the index is outside 0 through 1023
    private static void checkIndex(int localIndex) {
        Objects.checkIndex(localIndex, ChunkUtils.CHUNKS_PRE_REGION);
    }

    /// Requires a usable session for checked I/O operations.
    ///
    /// @throws IOException if the channel is closed or commit state requires reopening
    private void ensureOpen() throws IOException {
        if (closed) {
            throw new IOException("Region file is closed");
        }
        if (commitStateUncertain) {
            throw new IOException("Region commit state is uncertain; close and reopen the file before continuing");
        }
    }

    /// Requires a usable session for mutation methods which do not declare checked exceptions.
    ///
    /// @throws IllegalStateException if the channel is closed or commit state requires reopening
    private void ensureOpenUnchecked() {
        if (closed) {
            throw new IllegalStateException("Region file is closed");
        }
        if (commitStateUncertain) {
            throw new IllegalStateException(
                    "Region commit state is uncertain; close and reopen the file before continuing");
        }
    }

    /// Notifies the configured deterministic commit-stage observer.
    ///
    /// @param stage stage just reached
    /// @param localIndex affected local chunk slot
    /// @throws IOException when the test hook injects a failure
    private void reach(CommitStage stage, int localIndex) throws IOException {
        commitHook.reach(stage, localIndex);
    }

    /// Computes a fingerprint over the entire region and every currently referenced companion.
    ///
    /// @return current source fingerprint
    /// @throws IOException if any owned source byte cannot be read
    private RegionFingerprint computeFingerprint() throws IOException {
        reach(CommitStage.FINGERPRINT_CAPTURE, -1);
        ensurePathIdentity();
        MessageDigest digest = newSha256();
        ByteBuffer buffer = ByteBuffer.allocate(8192);
        long size = channel.size();
        updateLong(digest, size);
        long position = 0L;
        while (position < size) {
            buffer.clear();
            buffer.limit((int) Math.min(buffer.capacity(), size - position));
            readFully(channel, buffer, position);
            digest.update(buffer.array(), 0, buffer.limit());
            position += buffer.limit();
        }
        for (int localIndex = 0; localIndex < ChunkUtils.CHUNKS_PRE_REGION; localIndex++) {
            if (!external[localIndex]) {
                continue;
            }
            updateInt(digest, localIndex);
            @Nullable Path companionPath = companionPath(localIndex);
            byte[] identity = companionPath == null
                    ? ("accessor:" + accessor.getClass().getName()).getBytes(java.nio.charset.StandardCharsets.UTF_8)
                    : companionPath.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            updateInt(digest, identity.length);
            digest.update(identity);
            byte[] companion = readCompanion(localIndex);
            updateInt(digest, companion.length);
            digest.update(companion);
        }
        ensurePathIdentity();
        return new RegionFingerprint(size, digest.digest());
    }

    /// Creates an empty region source when necessary and rejects unsafe existing paths.
    ///
    /// @param file normalized region path
    /// @throws IOException if creation fails or the visible path is not a regular non-symbolic file
    private static void ensureRegionFileExists(Path file) throws IOException {
        try {
            Files.createFile(file);
        } catch (FileAlreadyExistsException existing) {
            // The complete no-follow attribute check below decides whether the occupant is safe.
        }
        requireRegularFile(file);
    }

    /// Creates a session-owned hard link which pins the region channel to one file identity.
    ///
    /// The link lives beside the source so it necessarily belongs to the same filesystem. Its
    /// unpredictable temporary name is not a valid region or companion name and is removed when
    /// the session closes. Filesystems without hard-link support fail closed here.
    ///
    /// @param file regular region source
    /// @return newly created hard-link path
    /// @throws IOException if a stable same-filesystem identity cannot be created
    private static Path createIdentityLink(Path file) throws IOException {
        Path parent = Objects.requireNonNull(file.getParent(), "Absolute region path has no parent");
        for (int attempt = 0; attempt < 32; attempt++) {
            Path candidate = parent.resolve(".xoyz-nbt-session-" + UUID.randomUUID() + ".identity");
            try {
                Files.createLink(candidate, file);
                return candidate;
            } catch (FileAlreadyExistsException collision) {
                // A collided path is foreign content and must never be removed by this session.
                continue;
            }
        }
        throw new IOException("Could not reserve a unique region identity link beside: " + file);
    }

    /// Requires a regular file without following or accepting a symbolic link.
    ///
    /// @param file candidate source or identity link
    /// @throws IOException if the path is missing, symbolic, or not a regular file
    private static void requireRegularFile(Path file) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(
                file,
                BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile() || Files.isSymbolicLink(file)) {
            throw new IOException("Region path is not a regular non-symbolic file: " + file);
        }
    }

    /// Confirms that the visible path still identifies the file owned by this session.
    ///
    /// @throws RegionPathChangedException if the path was deleted, replaced, or made unsafe
    private void ensurePathIdentity() throws RegionPathChangedException {
        try {
            requireRegularFile(path);
            requireRegularFile(identityLink);
            if (!Files.isSameFile(path, identityLink)) {
                throw new RegionPathChangedException(path, null);
            }
        } catch (RegionPathChangedException failure) {
            commitStateUncertain = true;
            throw failure;
        } catch (IOException | RuntimeException failure) {
            commitStateUncertain = true;
            throw new RegionPathChangedException(
                    path,
                    asIOException("Region path identity check failed", failure));
        }
    }

    /// Creates the SHA-256 digest required by every supported Java runtime.
    ///
    /// @return fresh SHA-256 digest
    private static MessageDigest newSha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new AssertionError("SHA-256 is required by the Java platform", exception);
        }
    }

    /// Adds one big-endian integer boundary to a source fingerprint.
    ///
    /// @param digest destination digest
    /// @param value integer value
    private static void updateInt(MessageDigest digest, int value) {
        digest.update((byte) (value >>> 24));
        digest.update((byte) (value >>> 16));
        digest.update((byte) (value >>> 8));
        digest.update((byte) value);
    }

    /// Adds one big-endian long boundary to a source fingerprint.
    ///
    /// @param digest destination digest
    /// @param value long value
    private static void updateLong(MessageDigest digest, long value) {
        updateInt(digest, (int) (value >>> 32));
        updateInt(digest, (int) value);
    }

    /// Rejects publication when the source changed since the last known-good state.
    ///
    /// @throws IOException if the current source fingerprint differs or cannot be computed
    private void checkFingerprint() throws IOException {
        if (fingerprint != null && !fingerprint.equals(computeFingerprint())) {
            throw new IOException("Region file or an external chunk changed after opening");
        }
    }

    /// Reports that the path no longer names the file bound to the persistent region channel.
    @NotNullByDefault
    private static final class RegionPathChangedException extends IOException {
        /// Creates a path-identity conflict with optional filesystem context.
        ///
        /// @param path replaced or inaccessible path
        /// @param cause underlying attribute failure, or `null` for a key mismatch
        private RegionPathChangedException(Path path, @Nullable IOException cause) {
            super("Region path was replaced or became inaccessible: " + path, cause);
        }
    }

    /// Fills a buffer from an absolute channel position without changing shared channel position.
    ///
    /// @param channel source channel
    /// @param buffer destination buffer
    /// @param position absolute starting byte position
    /// @throws IOException if EOF is reached or the channel makes no progress
    private static void readFully(FileChannel channel, ByteBuffer buffer, long position) throws IOException {
        while (buffer.hasRemaining()) {
            int read = channel.read(buffer, position);
            if (read < 0) {
                throw new IOException("Unexpected end of region file");
            }
            if (read == 0) {
                throw new IOException("Region channel made no progress");
            }
            position += read;
        }
    }

    /// Reads one bounded absolute byte range from the region channel.
    ///
    /// @param position absolute starting byte position
    /// @param length number of bytes to read
    /// @return exact range bytes
    /// @throws IOException if the length is unsupported or the range cannot be read completely
    private byte[] readBytes(long position, long length) throws IOException {
        if (length < 0L || length > Integer.MAX_VALUE) {
            throw new IOException("Region sector range is too large: " + length);
        }
        ByteBuffer buffer = ByteBuffer.allocate((int) length);
        readFully(channel, buffer, position);
        return buffer.array();
    }

    /// Drains a buffer to an absolute channel position without changing shared channel position.
    ///
    /// @param channel destination channel
    /// @param buffer source buffer
    /// @param position absolute starting byte position
    /// @throws IOException if the channel makes no write progress
    private static void writeFully(FileChannel channel, ByteBuffer buffer, long position) throws IOException {
        while (buffer.hasRemaining()) {
            int written = channel.write(buffer, position);
            if (written <= 0) {
                throw new IOException("Region channel made no progress while writing");
            }
            position += written;
        }
    }

    /// Writes a zero-filled absolute range, extending the file when needed.
    ///
    /// @param channel destination channel
    /// @param position absolute starting byte position
    /// @param bytes number of zero bytes to write
    /// @throws IOException if the range cannot be written completely
    private static void writeZeros(FileChannel channel, long position, long bytes) throws IOException {
        ByteBuffer zeros = ByteBuffer.allocate(8192);
        long remaining = bytes;
        while (remaining > 0L) {
            zeros.clear();
            zeros.limit((int) Math.min(remaining, zeros.capacity()));
            writeFully(channel, zeros, position);
            int written = zeros.limit();
            position += written;
            remaining -= written;
        }
    }

    /// Encodes one four-byte region location entry.
    ///
    /// @param offset 24-bit sector offset
    /// @param length unsigned eight-bit sector count
    /// @return flipped buffer containing exactly one location entry
    private static ByteBuffer encodeLocation(int offset, int length) {
        ByteBuffer location = ByteBuffer.allocate(Integer.BYTES).order(ByteOrder.BIG_ENDIAN);
        location.put((byte) (offset >>> 16));
        location.put((byte) (offset >>> 8));
        location.put((byte) offset);
        location.put((byte) length);
        location.flip();
        return location;
    }

    /// Forces a staged companion or backup file to stable storage.
    ///
    /// @param file file to force
    /// @throws IOException if the file cannot be opened or forced
    private static void forceFile(Path file) throws IOException {
        try (FileChannel output = FileChannel.open(file, StandardOpenOption.WRITE)) {
            output.force(true);
        }
    }

    /// Atomically replaces a companion path and fails closed when the filesystem lacks support.
    ///
    /// @param source fully written staging path
    /// @param target canonical companion path
    /// @throws IOException if an atomic replacement cannot be completed
    private static void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException exception) {
            throw new IOException("Atomic companion publication is not supported", exception);
        }
    }

    /// Validated header arrays and the allocation bitmap transferred into a new session.
    ///
    /// @param offsets sector offsets by local chunk slot
    /// @param lengths sector counts by local chunk slot
    /// @param timestamps raw timestamp values by local chunk slot
    /// @param compressionTypes compression identifiers by local chunk slot
    /// @param external external-payload flags by local chunk slot
    /// @param usedSectors bitmap of reserved region sectors
    @NotNullByDefault
    private record HeaderData(int[] offsets, int[] lengths, int[] timestamps,
                              byte[] compressionTypes, boolean[] external, BitSet usedSectors) {
    }

    /// One newly reserved contiguous sector range.
    ///
    /// @param sectorOffset first reserved sector
    /// @param sectorCount number of reserved sectors
    @NotNullByDefault
    private record Allocation(int sectorOffset, int sectorCount) {
        /// Returns the absolute byte offset of the first reserved sector.
        ///
        /// @return absolute byte offset
        private long byteOffset() {
            return (long) sectorOffset * ChunkUtils.SECTOR_BYTES;
        }
    }

    /// Internal signal that header publication succeeded before a later step failed.
    @NotNullByDefault
    private static final class ChunkCommittedException extends IOException {
        /// Creates a committed-state signal with the original post-commit failure.
        ///
        /// @param localIndex committed local chunk slot
        /// @param cause post-commit failure
        private ChunkCommittedException(int localIndex, IOException cause) {
            super("Region chunk " + localIndex + " was committed, but post-commit work failed", cause);
        }
    }

    /// Parsed chunk-frame metadata and its detached compressed payload.
    ///
    /// @param compression payload compression type
    /// @param compressed compressed payload bytes without a frame prefix
    @NotNullByDefault
    private record ChunkPayload(CompressionType compression, byte[] compressed) {
    }

    /// Strong framed fingerprint of a region file and all currently referenced companions.
    ///
    /// @param size current region byte size
    /// @param digest SHA-256 over framed region and referenced companion identities and bytes
    @NotNullByDefault
    private static final class RegionFingerprint {
        /// Current region byte size.
        private final long size;
        /// SHA-256 over framed region and referenced companion identities and bytes.
        private final byte @Unmodifiable [] digest;

        /// Creates a defensive immutable source fingerprint.
        ///
        /// @param size current region byte size
        /// @param digest SHA-256 digest
        private RegionFingerprint(long size, byte @Unmodifiable [] digest) {
            this.size = size;
            this.digest = digest.clone();
        }

        /// Returns whether another fingerprint describes identical framed source bytes.
        ///
        /// @param object candidate fingerprint
        /// @return whether source size and digest match
        @Override
        public boolean equals(Object object) {
            return this == object
                    || object instanceof RegionFingerprint other
                    && size == other.size
                    && Arrays.equals(digest, other.digest);
        }

        /// Returns a hash code consistent with [#equals(Object)].
        ///
        /// @return fingerprint hash code
        @Override
        public int hashCode() {
            return 31 * Long.hashCode(size) + Arrays.hashCode(digest);
        }
    }
}
