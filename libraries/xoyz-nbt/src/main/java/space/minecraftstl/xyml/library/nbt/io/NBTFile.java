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
// Modified by MinecraftSTL in 2026 for the XYML namespace and monorepo build.
package space.minecraftstl.xyml.library.nbt.io;

import net.jpountz.lz4.LZ4BlockOutputStream;
import space.minecraftstl.xyml.library.nbt.NBTElement;
import space.minecraftstl.xyml.library.nbt.chunk.ChunkRegion;
import space.minecraftstl.xyml.library.nbt.edit.NBTEditor;
import space.minecraftstl.xyml.library.nbt.edit.NBTSavepoint;
import space.minecraftstl.xyml.library.nbt.tag.Tag;
import space.minecraftstl.xyml.library.nbt.tag.TagType;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.GZIPOutputStream;

/// A synchronous, conflict-detecting editable session for one NBT file.
///
/// Standalone saves preserve the detected RAW, GZIP, ZLIB, or LZ4 envelope. They serialize to a
/// same-directory temporary file, force it, strictly parse it back, compare semantic content,
/// recheck the source fingerprint, and require an atomic replacement. Region sessions delegate
/// changed slots to [NBTRegionFile]'s copy-on-write publication.
///
/// @param <E> root element type
@NotNullByDefault
public final class NBTFile<E extends NBTElement> implements AutoCloseable {
    /// Absolute normalized source path.
    private final Path path;

    /// Codec used for standalone parsing and serialization.
    private final NBTCodec codec;

    /// Envelope detected when the session was opened.
    private final NBTFileEncoding encoding;

    /// Revision-aware editor which owns the mutable working tree.
    private final NBTEditor<E> editor;

    /// Open copy-on-write storage for a region session, or `null` for standalone tags.
    private final @Nullable NBTRegionFile regionFile;

    /// Expected standalone source fingerprint, or `null` for region sessions.
    private @Nullable SourceFingerprint sourceFingerprint;

    /// Last fully or partially published region baseline, or `null` for standalone tags.
    private @Nullable ChunkRegion regionBaseline;

    /// Whether this session has been closed.
    private boolean closed;

    /// Creates a file session from already validated state.
    ///
    /// @param path absolute normalized path
    /// @param codec standalone codec
    /// @param encoding preserved envelope
    /// @param editor detached editor
    /// @param sourceFingerprint standalone source fingerprint, or `null`
    /// @param regionFile open region storage, or `null`
    /// @param regionBaseline detached region baseline, or `null`
    private NBTFile(Path path, NBTCodec codec, NBTFileEncoding encoding, NBTEditor<E> editor,
                    @Nullable SourceFingerprint sourceFingerprint, @Nullable NBTRegionFile regionFile,
                    @Nullable ChunkRegion regionBaseline) {
        this.path = path;
        this.codec = codec;
        this.encoding = encoding;
        this.editor = editor;
        this.sourceFingerprint = sourceFingerprint;
        this.regionFile = regionFile;
        this.regionBaseline = regionBaseline;
    }

    /// Opens a standalone Java Edition tag and detects its complete outer envelope.
    ///
    /// @param path existing standalone NBT path
    /// @return editable tag session
    /// @throws IOException if the source changes during open or is not one strict complete tag
    @Contract("_ -> new")
    public static NBTFile<Tag> openTag(Path path) throws IOException {
        return openTag(path, Tag.class, NBTCodec.of());
    }

    /// Opens a standalone Java Edition tag of the requested type.
    ///
    /// @param path existing standalone NBT path
    /// @param tagType expected root tag type
    /// @param <T> root tag type
    /// @return editable typed tag session
    /// @throws IOException if the source is invalid, changes during open, or has another root type
    @Contract("_, _ -> new")
    public static <T extends Tag> NBTFile<T> openTag(Path path, TagType<T> tagType) throws IOException {
        Objects.requireNonNull(tagType, "tagType");
        return openTag(path, tagType.tagClass(), NBTCodec.of());
    }

    /// Opens a standalone tag of the requested class using a supplied immutable codec.
    ///
    /// This overload allows callers to select Java or Bedrock Edition through the codec while
    /// retaining the same strict envelope and save guarantees.
    ///
    /// @param path existing standalone NBT path
    /// @param tagClass expected root tag class
    /// @param codec codec whose edition is used for parsing and serialization
    /// @param <T> root tag type
    /// @return editable typed tag session
    /// @throws IOException if the source is invalid, changes during open, or has another root type
    @Contract("_, _, _ -> new")
    public static <T extends Tag> NBTFile<T> openTag(Path path, Class<T> tagClass, NBTCodec codec)
            throws IOException {
        Path absolute = normalizeExistingPath(path);
        Class<T> expectedClass = Objects.requireNonNull(tagClass, "tagClass");
        NBTCodec selectedCodec = Objects.requireNonNull(codec, "codec");
        SourceSnapshot source = SourceSnapshot.read(absolute);
        NBTFileEncoding detected = NBTFileEncoding.detectStandalone(source.bytes());
        T root = selectedCodec.readTag(source.bytes(), expectedClass);
        return new NBTFile<>(absolute, selectedCodec, detected, NBTEditor.of(root), source.fingerprint(),
                null, null);
    }

    /// Opens and fully validates a Java Edition region as an editable 1024-slot tree.
    ///
    /// The returned session keeps its [NBTRegionFile] open so later saves can reject changes to
    /// either the main region file or a referenced external chunk companion.
    ///
    /// @param path existing or newly created region path
    /// @return editable region session
    /// @throws IOException if the region cannot be opened or any slot cannot be decoded
    @Contract("_ -> new")
    public static NBTFile<ChunkRegion> openRegion(Path path) throws IOException {
        return openRegion(NBTRegionFile.open(Objects.requireNonNull(path, "path")));
    }

    /// Opens an editable region session which takes ownership of an existing storage session.
    ///
    /// This package-local entry point lets deterministic transaction tests supply a commit hook.
    /// The storage is closed if loading fails and is otherwise owned by the returned file session.
    ///
    /// @param storage open region storage whose ownership is transferred
    /// @return editable region session
    /// @throws IOException if any slot cannot be decoded
    @Contract("_ -> new")
    static NBTFile<ChunkRegion> openRegion(NBTRegionFile storage) throws IOException {
        NBTRegionFile selectedStorage = Objects.requireNonNull(storage, "storage");
        boolean success = false;
        try {
            ChunkRegion root = readRegion(selectedStorage);
            ChunkRegion baseline = root.clone();
            NBTFile<ChunkRegion> result = new NBTFile<>(selectedStorage.path(), NBTCodec.of(),
                    NBTFileEncoding.REGION, NBTEditor.of(root), null, selectedStorage, baseline);
            success = true;
            return result;
        } finally {
            if (!success) {
                selectedStorage.close();
            }
        }
    }

    /// Returns the absolute normalized source path.
    ///
    /// @return source path
    @Contract(pure = true)
    public Path getPath() {
        return path;
    }

    /// Returns the revision-aware editor owned by this session.
    ///
    /// @return editable detached tree
    @Contract(pure = true)
    public NBTEditor<E> getEditor() {
        return editor;
    }

    /// Returns the on-disk envelope preserved by saves.
    ///
    /// @return detected file encoding
    @Contract(pure = true)
    public NBTFileEncoding getEncoding() {
        return encoding;
    }

    /// Saves the current editor snapshot without creating a backup.
    ///
    /// @throws IOException if validation, conflict detection, or publication fails
    public synchronized void save() throws IOException {
        save(NBTSaveOptions.defaults());
    }

    /// Saves the current editor snapshot using the supplied options.
    ///
    /// A successfully published older savepoint does not mark the editor clean if a newer edit
    /// arrived while I/O was in progress. Region saves reject backup options because a region and
    /// its external companions cannot be represented by one general backup path.
    ///
    /// @param options backup and publication options
    /// @throws IOException if validation, conflict detection, staging, or publication fails
    public synchronized void save(NBTSaveOptions options) throws IOException {
        ensureOpen();
        NBTSaveOptions selectedOptions = Objects.requireNonNull(options, "options");
        NBTSavepoint<E> savepoint = editor.saveSnapshot();
        if (encoding == NBTFileEncoding.REGION) {
            saveRegion(savepoint, selectedOptions);
        } else {
            saveStandalone(savepoint, selectedOptions);
        }
    }

    /// Closes this session and its region channel, if any, without publishing pending changes.
    ///
    /// Call [#save()] explicitly before closing. Changes which remain pending after a failed save
    /// stay in the editor, but a closed session cannot retry publication; reopening creates a fresh
    /// storage session which first rediscovers the visible disk state.
    ///
    /// @throws IOException if the region channel cannot close
    @Override
    public synchronized void close() throws IOException {
        if (closed) {
            return;
        }
        try {
            if (regionFile != null) {
                regionFile.close();
            }
        } finally {
            closed = true;
        }
    }

    /// Publishes one standalone savepoint through a validated atomic replacement.
    ///
    /// @param savepoint detached editor savepoint
    /// @param options save options
    /// @throws IOException if the source is stale or publication fails
    private void saveStandalone(NBTSavepoint<E> savepoint, NBTSaveOptions options) throws IOException {
        SourceFingerprint expected = Objects.requireNonNull(sourceFingerprint, "sourceFingerprint");
        verifyCurrentSource(expected);
        E root = savepoint.root();
        if (!(root instanceof Tag tag)) {
            throw new IOException("Standalone NBT session does not contain a tag root");
        }

        byte[] encoded = encodeTag(tag);
        Path staged = createTemporarySibling(path);
        IOException failure = null;
        try {
            writeAndForce(staged, encoded);
            SourceSnapshot stagedSource = SourceSnapshot.read(staged);
            validateStagedTag(stagedSource.bytes(), tag);
            verifyCurrentSource(expected);
            publishBackup(options, expected);
            verifyCurrentSource(expected);
            atomicReplace(staged, path, "NBT source");
            sourceFingerprint = stagedSource.fingerprint();
            editor.markSaved(savepoint);
        } catch (IOException exception) {
            failure = exception;
            throw exception;
        } finally {
            deleteStaged(staged, failure);
        }
    }

    /// Publishes changed slots from one detached region savepoint.
    ///
    /// @param savepoint detached editor savepoint
    /// @param options save options
    /// @throws IOException if staging or copy-on-write publication fails
    private void saveRegion(NBTSavepoint<E> savepoint, NBTSaveOptions options) throws IOException {
        if (options.backupPath() != null) {
            throw new IOException("A single backup path cannot represent a region and its companion files");
        }
        if (!(savepoint.root() instanceof ChunkRegion current)) {
            throw new IOException("Region NBT session does not contain a chunk-region root");
        }
        NBTRegionFile storage = Objects.requireNonNull(regionFile, "regionFile");
        ChunkRegion baseline = Objects.requireNonNull(regionBaseline, "regionBaseline");
        storage.synchronizePendingChanges(current, baseline);
        try {
            storage.flush();
        } catch (NBTPartialSaveException exception) {
            updateRegionBaseline(baseline, current, exception.committedIndexes());
            throw exception;
        }
        regionBaseline = current.clone();
        editor.markSaved(savepoint);
    }

    /// Encodes a standalone tag using the session's original envelope.
    ///
    /// @param tag detached tag to encode
    /// @return complete encoded bytes
    /// @throws IOException if validation or compression fails
    private byte[] encodeTag(Tag tag) throws IOException {
        ByteArrayOutputStream rawOutput = new ByteArrayOutputStream();
        codec.writeTag(rawOutput, tag);
        byte[] raw = rawOutput.toByteArray();
        if (encoding == NBTFileEncoding.RAW) {
            return raw;
        }
        ByteArrayOutputStream encodedOutput = new ByteArrayOutputStream(Math.max(128, raw.length / 2));
        try (OutputStream compressor = switch (encoding) {
                case GZIP -> new GZIPOutputStream(encodedOutput);
                case ZLIB -> new DeflaterOutputStream(encodedOutput);
                case LZ4 -> new LZ4BlockOutputStream(encodedOutput);
                case RAW, REGION -> throw new IOException("Invalid standalone NBT encoding: " + encoding);
            }) {
            compressor.write(raw);
        }
        return encodedOutput.toByteArray();
    }

    /// Strictly reads staged standalone bytes and verifies envelope and semantic equality.
    ///
    /// @param staged complete staged bytes
    /// @param expected expected detached semantic tag
    /// @throws IOException if the staged representation is invalid or differs from the savepoint
    private void validateStagedTag(byte[] staged, Tag expected) throws IOException {
        if (NBTFileEncoding.detectStandalone(staged) != encoding) {
            throw new IOException("Staged NBT data changed its storage encoding");
        }
        Tag actual = codec.readTag(staged);
        if (!actual.equals(expected)) {
            throw new IOException("Staged NBT data differs from the editor savepoint");
        }
    }

    /// Atomically publishes an optional rolling backup of the current source.
    ///
    /// @param options save options
    /// @param expected expected current source fingerprint
    /// @throws IOException if the backup cannot be staged, verified, or atomically published
    private void publishBackup(NBTSaveOptions options, SourceFingerprint expected) throws IOException {
        Path configured = options.backupPath();
        if (configured == null) {
            return;
        }
        Path backup = configured.toAbsolutePath().normalize();
        if (backup.equals(path)) {
            throw new IOException("The NBT backup path must differ from the source path");
        }
        Path stagedBackup = createTemporarySibling(backup);
        IOException failure = null;
        try {
            copyAndForce(path, stagedBackup);
            SourceSnapshot backupSource = SourceSnapshot.read(stagedBackup);
            if (!expected.equals(backupSource.fingerprint())) {
                throw new IOException("Staged NBT backup differs from the current source");
            }
            verifyCurrentSource(expected);
            atomicReplace(stagedBackup, backup, "NBT backup");
        } catch (IOException exception) {
            failure = exception;
            throw exception;
        } finally {
            deleteStaged(stagedBackup, failure);
        }
    }

    /// Verifies that the current standalone source still matches the expected content.
    ///
    /// @param expected expected source fingerprint
    /// @throws IOException if the source changed or can no longer be read safely
    private void verifyCurrentSource(SourceFingerprint expected) throws IOException {
        SourceFingerprint actual = SourceSnapshot.read(path).fingerprint();
        if (!expected.equals(actual)) {
            throw new IOException("NBT source changed since it was opened: " + path);
        }
    }

    /// Reads all fixed slots from an open region storage session.
    ///
    /// @param storage validated region storage
    /// @return detached complete region tree
    /// @throws IOException if any chunk cannot be decoded
    private static ChunkRegion readRegion(NBTRegionFile storage) throws IOException {
        ChunkRegion region = new ChunkRegion();
        for (int localIndex = 0; localIndex < region.size(); localIndex++) {
            region.setChunk(localIndex, storage.readChunk(localIndex));
        }
        return region;
    }

    /// Updates only the region slots known to have been published before a partial failure.
    ///
    /// @param baseline mutable saved baseline
    /// @param current detached savepoint root
    /// @param committedIndexes successfully published local indexes
    private static void updateRegionBaseline(ChunkRegion baseline, ChunkRegion current,
                                             List<Integer> committedIndexes) {
        for (int localIndex : committedIndexes) {
            baseline.setChunk(localIndex, current.getChunk(localIndex).clone());
        }
    }

    /// Normalizes and validates an existing standalone source path.
    ///
    /// @param path source path
    /// @return absolute normalized path
    /// @throws IOException if the path is not a regular non-symbolic-link file
    private static Path normalizeExistingPath(Path path) throws IOException {
        Path absolute = Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
        BasicFileAttributes attributes = Files.readAttributes(
                absolute, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile() || attributes.isSymbolicLink()) {
            throw new IOException("NBT source is not a regular file: " + absolute);
        }
        return absolute;
    }

    /// Creates a temporary sibling so the later move stays on one filesystem provider.
    ///
    /// @param target eventual target
    /// @return newly created temporary sibling
    /// @throws IOException if the target has no parent or staging fails
    private static Path createTemporarySibling(Path target) throws IOException {
        @Nullable Path parent = target.getParent();
        @Nullable Path fileName = target.getFileName();
        if (parent == null || fileName == null) {
            throw new IOException("NBT target has no parent directory: " + target);
        }
        return Files.createTempFile(parent, "." + fileName + ".", ".tmp");
    }

    /// Writes complete bytes to an existing stage and forces content and metadata.
    ///
    /// @param target staged file
    /// @param bytes complete encoded bytes
    /// @throws IOException if writing or forcing fails
    private static void writeAndForce(Path target, byte[] bytes) throws IOException {
        try (FileChannel channel = FileChannel.open(
                target, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) {
                int written = channel.write(buffer);
                if (written <= 0) {
                    throw new IOException("NBT staging channel made no progress");
                }
            }
            channel.force(true);
        }
    }

    /// Copies a source to an existing stage and forces the completed copy.
    ///
    /// @param source source file
    /// @param target staged backup file
    /// @throws IOException if copying or forcing fails
    private static void copyAndForce(Path source, Path target) throws IOException {
        try (FileChannel input = FileChannel.open(source, StandardOpenOption.READ);
             FileChannel output = FileChannel.open(
                     target, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
            long position = 0L;
            long size = input.size();
            while (position < size) {
                long transferred = input.transferTo(position, size - position, output);
                if (transferred <= 0L) {
                    throw new IOException("NBT backup channel made no progress");
                }
                position += transferred;
            }
            output.force(true);
        }
    }

    /// Atomically replaces one target without a non-atomic fallback.
    ///
    /// @param source fully validated staged source
    /// @param target publication target
    /// @param description target description for diagnostics
    /// @throws IOException if atomic replacement is unsupported or fails
    private static void atomicReplace(Path source, Path target, String description) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException exception) {
            throw new IOException("Filesystem does not support atomic " + description + " replacement: " + target,
                    exception);
        }
    }

    /// Deletes a leftover staging file while preserving the primary failure when one exists.
    ///
    /// @param staged staging path
    /// @param primary primary operation failure, or `null`
    /// @throws IOException if cleanup alone fails
    private static void deleteStaged(Path staged, @Nullable IOException primary) throws IOException {
        try {
            Files.deleteIfExists(staged);
        } catch (IOException cleanup) {
            if (primary != null) {
                primary.addSuppressed(cleanup);
            } else {
                throw cleanup;
            }
        }
    }

    /// Throws when an operation is attempted after close.
    ///
    /// @throws IOException if this session is closed
    private void ensureOpen() throws IOException {
        if (closed) {
            throw new IOException("NBT file session is closed");
        }
    }

    /// Complete stable read of a regular source and its content fingerprint.
    ///
    /// @param bytes complete source bytes
    /// @param fingerprint content fingerprint
    @NotNullByDefault
    private static final class SourceSnapshot {
        /// Complete immutable encoded source bytes.
        private final byte @Unmodifiable [] bytes;

        /// Immutable content fingerprint for the encoded source.
        private final SourceFingerprint fingerprint;

        /// Creates a detached immutable source snapshot.
        ///
        /// @param bytes complete encoded source bytes
        /// @param fingerprint content fingerprint
        private SourceSnapshot(byte @Unmodifiable [] bytes, SourceFingerprint fingerprint) {
            this.bytes = bytes.clone();
            this.fingerprint = fingerprint;
        }

        /// Returns a defensive copy of the complete encoded source bytes.
        ///
        /// @return complete encoded source bytes
        private byte @Unmodifiable [] bytes() {
            return bytes.clone();
        }

        /// Returns the immutable source fingerprint.
        ///
        /// @return source fingerprint
        private SourceFingerprint fingerprint() {
            return fingerprint;
        }

        /// Reads a source while checking that its basic attributes remain stable.
        ///
        /// @param path source path
        /// @return complete stable source snapshot
        /// @throws IOException if the source changes during the read or is not a regular file
        private static SourceSnapshot read(Path path) throws IOException {
            BasicFileAttributes before = readAttributes(path);
            byte @Unmodifiable [] bytes = Files.readAllBytes(path);
            BasicFileAttributes after = readAttributes(path);
            if (before.size() != after.size()
                    || !before.lastModifiedTime().equals(after.lastModifiedTime())
                    || !Objects.equals(before.fileKey(), after.fileKey())
                    || bytes.length != after.size()) {
                throw new IOException("NBT source changed while it was being read: " + path);
            }
            return new SourceSnapshot(bytes, new SourceFingerprint(bytes.length, digest(bytes)));
        }

        /// Reads non-following basic attributes and rejects non-regular sources.
        ///
        /// @param path source path
        /// @return stable basic attributes
        /// @throws IOException if the source is not a regular non-symbolic-link file
        private static BasicFileAttributes readAttributes(Path path) throws IOException {
            BasicFileAttributes attributes = Files.readAttributes(
                    path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isRegularFile() || attributes.isSymbolicLink()) {
                throw new IOException("NBT source is not a regular file: " + path);
            }
            return attributes;
        }
    }

    /// Content fingerprint used to reject stale standalone saves.
    @NotNullByDefault
    private static final class SourceFingerprint {
        /// Encoded byte length.
        private final long size;

        /// SHA-256 digest of the complete encoded bytes.
        private final byte @Unmodifiable [] digest;

        /// Creates a content fingerprint.
        ///
        /// @param size encoded byte length
        /// @param digest SHA-256 digest
        private SourceFingerprint(long size, byte @Unmodifiable [] digest) {
            this.size = size;
            this.digest = digest.clone();
        }

        /// Returns whether another object describes identical encoded bytes.
        ///
        /// @param object candidate object
        /// @return whether the fingerprints are equal
        @Override
        public boolean equals(Object object) {
            return this == object
                    || object instanceof SourceFingerprint other
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

    /// Computes SHA-256 without exposing a mutable digest object.
    ///
    /// @param bytes complete encoded bytes
    /// @return SHA-256 digest
    private static byte @Unmodifiable [] digest(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (NoSuchAlgorithmException exception) {
            throw new AssertionError("SHA-256 is required by the Java platform", exception);
        }
    }
}
