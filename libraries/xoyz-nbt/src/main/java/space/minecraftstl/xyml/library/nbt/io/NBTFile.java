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
import space.minecraftstl.xyml.library.nbt.tag.CompoundTag;
import space.minecraftstl.xyml.library.nbt.tag.Tag;
import space.minecraftstl.xyml.library.nbt.tag.TagType;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.GZIPOutputStream;

/// A synchronous editable session for one NBT file.
///
/// Standalone saves preserve the detected RAW, GZIP, ZLIB, or LZ4 envelope. They serialize to a
/// deterministic `<source>.xyml_new` staging and an atomic replacement. A tolerant opening keeps
/// its diagnostics until the caller explicitly saves a strict repair. Region sessions delegate
/// changed slots to [NBTRegionFile]'s copy-on-write publication.
///
/// @param <E> root element type
@NotNullByDefault
public final class NBTFile<E extends NBTElement> implements AutoCloseable {
    /// Maximum strict standalone payload accepted for one save, matching the bounded read policy.
    private static final int MAX_STANDALONE_RAW_BYTES = Math.toIntExact(
            NBTReadLimits.defaults().maxDecompressedBytes());

    /// Maximum strict standalone envelope accepted for one save, matching the bounded read policy.
    private static final int MAX_STANDALONE_ENCODED_BYTES = Math.toIntExact(
            NBTReadLimits.defaults().maxEncodedBytes());

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

    /// Diagnostics captured while opening this session.
    private volatile NBTReadReport readReport;

    /// Last fully or partially published region baseline, or `null` for standalone tags.
    private @Nullable ChunkRegion regionBaseline;

    /// Whether this session has been closed.
    private boolean closed;

    /// Whether the source did not exist when this session was created and still needs its first publication.
    private boolean creationPending;

    /// Owned standalone publication stages which could not be removed after a failed save.
    private final NBTRegionFileIO.PendingCleanup pendingCleanup =
            new NBTRegionFileIO.PendingCleanup("Owned NBT stage");

    /// Creates a file session from already validated state.
    ///
    /// @param path absolute normalized path
    /// @param codec standalone codec
    /// @param encoding preserved envelope
    /// @param editor detached editor
    /// @param readReport opening diagnostics
    /// @param regionFile open region storage, or `null`
    /// @param regionBaseline detached region baseline, or `null`
    private NBTFile(Path path, NBTCodec codec, NBTFileEncoding encoding, NBTEditor<E> editor,
                    NBTReadReport readReport, @Nullable NBTRegionFile regionFile,
                    @Nullable ChunkRegion regionBaseline) {
        this(path, codec, encoding, editor, readReport, regionFile, regionBaseline, false);
    }

    /// Creates a file session with an explicit first-publication state.
    private NBTFile(Path path, NBTCodec codec, NBTFileEncoding encoding, NBTEditor<E> editor,
                    NBTReadReport readReport, @Nullable NBTRegionFile regionFile,
                    @Nullable ChunkRegion regionBaseline, boolean creationPending) {
        this.path = path;
        this.codec = codec;
        this.encoding = encoding;
        this.editor = editor;
        this.readReport = Objects.requireNonNull(readReport, "readReport");
        this.regionFile = regionFile;
        this.regionBaseline = regionBaseline;
        this.creationPending = creationPending;
    }

    /// Creates a new standalone Compound tag session using a filename-derived envelope.
    ///
    /// `.nbt` targets default to RAW; Minecraft standalone `.dat`, `.dat_old`, and `.xyml_old` targets
    /// default to GZIP. Other standalone names use the GZIP default as well. The target must not exist.
    /// The first [#save()] publishes a strictly encoded file through the normal deterministic staging path
    /// and does not create a backup for a source that was absent.
    ///
    /// @param path new standalone `.nbt`, `.dat`, `.dat_old`, or `.xyml_old` target
    /// @return editable new-file session
    /// @throws IOException if the target or its parent is unsafe, or the target already exists
    @Contract("_ -> new")
    public static NBTFile<CompoundTag> createTag(Path path) throws IOException {
        Path selectedPath = Objects.requireNonNull(path, "path");
        return createTag(selectedPath, defaultCreationEncoding(selectedPath));
    }

    /// Creates a new standalone Compound tag session with an explicit outer envelope.
    ///
    /// `REGION` is not a standalone envelope and is rejected. The target must not exist at creation and again at
    /// first publication, preventing an unrelated file from being silently overwritten after the session starts.
    ///
    /// @param path new standalone target
    /// @param encoding RAW, GZIP, ZLIB, or LZ4 envelope
    /// @return editable new-file session
    /// @throws IOException if the target or its parent is unsafe, already exists, or the encoding is REGION
    @Contract("_, _ -> new")
    public static NBTFile<CompoundTag> createTag(Path path, NBTFileEncoding encoding) throws IOException {
        return createTag(path, new CompoundTag(), encoding);
    }

    /// Creates a new standalone session with any valid named tag as its root.
    ///
    /// This overload is the generic counterpart to the empty-Compound convenience methods. The supplied root is
    /// detached by [NBTEditor] and is validated before the first strict publication, so scalar and array roots remain
    /// available without weakening the Java Edition NBT wire rules.
    ///
    /// @param path new standalone target
    /// @param root initial named root tag
    /// @param encoding RAW, GZIP, ZLIB, or LZ4 envelope
    /// @param <T> root tag type
    /// @return editable new-file session
    /// @throws IOException if the target or its parent is unsafe, already exists, or the encoding is REGION
    @Contract("_, _, _ -> new")
    public static <T extends Tag> NBTFile<T> createTag(Path path, T root, NBTFileEncoding encoding)
            throws IOException {
        Path absolute = normalizeCreationPath(path);
        NBTFileEncoding selectedEncoding = Objects.requireNonNull(encoding, "encoding");
        if (selectedEncoding == NBTFileEncoding.REGION) {
            throw new IOException("REGION is not a standalone NBT envelope");
        }
        return new NBTFile<>(absolute, NBTCodec.of(), selectedEncoding,
                NBTEditor.of(Objects.requireNonNull(root, "root")),
                standaloneReadReport(selectedEncoding, true, List.of()),
                null, null, true);
    }

    /// Creates a new standalone session with an arbitrary named root and the filename-derived envelope.
    ///
    /// @param path new standalone target
    /// @param root initial named root tag
    /// @param <T> root tag type
    /// @return editable new-file session
    /// @throws IOException if the target or its parent is unsafe, or the target already exists
    @Contract("_, _ -> new")
    public static <T extends Tag> NBTFile<T> createTag(Path path, T root) throws IOException {
        Path selectedPath = Objects.requireNonNull(path, "path");
        return createTag(selectedPath, root, defaultCreationEncoding(selectedPath));
    }

    /// Opens a standalone Java Edition tag and detects its complete outer envelope.
    ///
    /// @param path existing standalone NBT path
    /// @return editable tag session
    /// @throws IOException if the source is oversized, unsafe, or is not one strict complete tag
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
    /// @throws IOException if the source is invalid, oversized, unsafe, or has another root type
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
    /// @throws IOException if the source is invalid, exceeds a read limit, or has another root type
    @Contract("_, _, _ -> new")
    public static <T extends Tag> NBTFile<T> openTag(Path path, Class<T> tagClass, NBTCodec codec)
            throws IOException {
        Path absolute = normalizeExistingPath(path);
        Class<T> expectedClass = Objects.requireNonNull(tagClass, "tagClass");
        NBTCodec selectedCodec = Objects.requireNonNull(codec, "codec");
        SourceSnapshot source = SourceSnapshot.read(absolute);
        NBTFileEncoding detected = NBTFileEncoding.detectStandalone(source.bytes());
        T root = selectedCodec.readTag(source.bytes(), expectedClass);
        return new NBTFile<>(absolute, selectedCodec, detected, NBTEditor.of(root),
                standaloneReadReport(detected, true, List.of()), null, null);
    }

    /// Opens a standalone tag with bounded tolerant recovery when strict parsing fails.
    ///
    /// @param path existing standalone NBT path
    /// @return editable tag session carrying a recovery report
    /// @throws IOException if no root can be recovered or a limit is exceeded
    @Contract("_ -> new")
    public static NBTFile<Tag> openTagTolerant(Path path) throws IOException {
        return openTagTolerant(path, Tag.class, NBTCodec.of(), NBTReadLimits.defaults());
    }

    /// Opens a typed standalone tag with bounded tolerant recovery.
    ///
    /// @param path existing standalone NBT path
    /// @param tagType expected root type
    /// @param limits defensive read limits
    /// @param <T> root type
    /// @return editable tag session carrying a recovery report
    /// @throws IOException if no root can be recovered or a limit is exceeded
    @Contract("_, _, _ -> new")
    public static <T extends Tag> NBTFile<T> openTagTolerant(Path path, TagType<T> tagType,
                                                               NBTReadLimits limits) throws IOException {
        Objects.requireNonNull(tagType, "tagType");
        return openTagTolerant(path, tagType.tagClass(), NBTCodec.of(), limits);
    }

    /// Opens a typed standalone tag with the default bounded tolerant-read policy.
    ///
    /// @param path existing standalone NBT path
    /// @param tagType expected root type
    /// @param <T> root type
    /// @return editable tag session carrying a recovery report
    /// @throws IOException if no root can be recovered or a limit is exceeded
    @Contract("_, _ -> new")
    public static <T extends Tag> NBTFile<T> openTagTolerant(Path path, TagType<T> tagType) throws IOException {
        return openTagTolerant(path, tagType, NBTReadLimits.defaults());
    }

    /// Opens a standalone tag with a supplied codec and bounded tolerant recovery.
    ///
    /// @param path existing standalone NBT path
    /// @param tagClass expected root type
    /// @param codec codec controlling edition and byte order
    /// @param limits defensive read limits
    /// @param <T> root type
    /// @return editable tag session carrying a recovery report
    /// @throws IOException if no root can be recovered or a limit is exceeded
    @Contract("_, _, _, _ -> new")
    public static <T extends Tag> NBTFile<T> openTagTolerant(Path path, Class<T> tagClass,
                                                               NBTCodec codec, NBTReadLimits limits)
            throws IOException {
        Path absolute = normalizeExistingPath(path);
        Class<T> expectedClass = Objects.requireNonNull(tagClass, "tagClass");
        NBTCodec selectedCodec = Objects.requireNonNull(codec, "codec");
        NBTReadLimits selectedLimits = Objects.requireNonNull(limits, "limits");
        SourceSnapshot source = SourceSnapshot.read(absolute, selectedLimits.maxEncodedBytes());
        NBTReadResult<T> result = NBTRepairReader.read(source.bytes(), expectedClass, selectedCodec, selectedLimits);
        NBTReadReport report = standaloneReadReport(result.report().encoding(), result.report().strictValid(),
                result.report().issues());
        return new NBTFile<>(absolute, selectedCodec, report.encoding(), NBTEditor.of(result.root()),
                report, null, null);
    }

    /// Opens and fully validates a Java Edition region as an editable 1024-slot tree.
    ///
    /// The returned session keeps its [NBTRegionFile] open so later saves can publish changed slots
    /// through the same copy-on-write storage handle.
    ///
    /// @param path existing or newly created region path
    /// @return editable region session
    /// @throws IOException if the region cannot be opened or any slot cannot be decoded
    @Contract("_ -> new")
    public static NBTFile<ChunkRegion> openRegion(Path path) throws IOException {
        return openRegion(NBTRegionFile.open(Objects.requireNonNull(path, "path")));
    }

    /// Opens a Java Edition region while isolating malformed chunk slots.
    ///
    /// Each slot is read with bounded tolerant recovery. A bad slot becomes an empty editable
    /// chunk with a `PARTIAL_DATA_LOSS` report; all unaffected slots remain available. The report
    /// stays attached to the session until the caller explicitly saves or reopens it.
    ///
    /// @param path existing or newly created region path
    /// @return editable tolerant region session
    /// @throws IOException if the region envelope cannot be opened or the default limits reject it
    @Contract("_ -> new")
    public static NBTFile<ChunkRegion> openRegionTolerant(Path path) throws IOException {
        return openRegionTolerant(Objects.requireNonNull(path, "path"), NBTReadLimits.defaults());
    }

    /// Opens a Java Edition region with explicit bounded tolerant recovery.
    ///
    /// @param path existing or newly created region path
    /// @param limits defensive decompression and parser limits
    /// @return editable tolerant region session
    /// @throws IOException if the region envelope cannot be opened or the limits are invalid
    @Contract("_, _ -> new")
    public static NBTFile<ChunkRegion> openRegionTolerant(Path path, NBTReadLimits limits) throws IOException {
        return openRegionTolerant(NBTRegionFile.openTolerant(Objects.requireNonNull(path, "path")), limits);
    }

    /// Opens a Java Edition region with an explicit companion accessor and default limits.
    ///
    /// @param path existing or newly created region path
    /// @param accessor external chunk locator
    /// @return editable tolerant region session
    /// @throws IOException if the region envelope cannot be opened
    @Contract("_, _ -> new")
    public static NBTFile<ChunkRegion> openRegionTolerant(Path path, ExternalChunkAccessor accessor)
            throws IOException {
        return openRegionTolerant(path, accessor, NBTReadLimits.defaults());
    }

    /// Opens a Java Edition region with an explicit companion accessor and read policy.
    ///
    /// @param path existing or newly created region path
    /// @param accessor external chunk locator
    /// @param limits defensive decompression and parser limits
    /// @return editable tolerant region session
    /// @throws IOException if the region envelope cannot be opened or the limits are invalid
    @Contract("_, _, _ -> new")
    public static NBTFile<ChunkRegion> openRegionTolerant(Path path, ExternalChunkAccessor accessor,
                                                           NBTReadLimits limits) throws IOException {
        return openRegionTolerant(NBTRegionFile.openTolerant(
                Objects.requireNonNull(path, "path"), Objects.requireNonNull(accessor, "accessor")), limits);
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
            NBTReadReport report = selectedStorage.readReport();
            NBTFile<ChunkRegion> result = new NBTFile<>(selectedStorage.path(), NBTCodec.of(),
                    NBTFileEncoding.REGION, NBTEditor.of(root),
                    report, selectedStorage, baseline);
            success = true;
            return result;
        } finally {
            if (!success) {
                selectedStorage.close();
            }
        }
    }

    /// Opens a tolerant region session from an already-open storage owner.
    ///
    /// @param storage open tolerant region storage whose ownership is transferred
    /// @param limits defensive decompression and parser limits
    /// @return editable tolerant region session
    /// @throws IOException if a session slot cannot be materialized
    static NBTFile<ChunkRegion> openRegionTolerant(NBTRegionFile storage, NBTReadLimits limits) throws IOException {
        NBTRegionFile selectedStorage = Objects.requireNonNull(storage, "storage");
        NBTReadLimits selectedLimits = Objects.requireNonNull(limits, "limits");
        boolean success = false;
        try {
            List<NBTReadIssue> issues = new java.util.ArrayList<>(selectedStorage.readReport().issues());
            ChunkRegion root = new ChunkRegion();
            NBTReadLimits.Budget budget = selectedLimits.newDocumentBudget();
            for (int localIndex = 0; localIndex < root.size(); localIndex++) {
                NBTReadResult<space.minecraftstl.xyml.library.nbt.chunk.Chunk> result =
                        selectedStorage.readChunkTolerant(localIndex, selectedLimits, budget);
                root.setChunk(localIndex, result.root());
                for (NBTReadIssue issue : result.report().issues()) {
                    if (!issues.contains(issue)) {
                        issues.add(issue);
                    }
                }
            }
            ChunkRegion baseline = root.clone();
            NBTReadReport report = issues.isEmpty()
                    ? NBTReadReport.clean(NBTFileEncoding.REGION)
                    : new NBTReadReport(NBTFileEncoding.REGION, false, issues);
            NBTFile<ChunkRegion> result = new NBTFile<>(selectedStorage.path(), NBTCodec.of(),
                    NBTFileEncoding.REGION, NBTEditor.of(root), report, selectedStorage, baseline);
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

    /// Returns an immutable snapshot of the storage algorithm used by this session.
    ///
    /// Standalone sessions return their detected outer encoding. Region sessions obtain a fresh
    /// per-slot snapshot so markers reflect headers already published by copy-on-write saves;
    /// pending edits are not included until the next successful flush.
    ///
    /// @return immutable standalone or region storage profile
    public synchronized StorageProfile storageProfile() {
        return regionFile == null ? StorageProfile.standalone(encoding) : regionFile.storageProfile();
    }

    /// Bean-style alias for [#storageProfile()].
    ///
    /// @return immutable standalone or region storage profile
    public synchronized StorageProfile getStorageProfile() {
        return storageProfile();
    }

    /// Returns immutable diagnostics captured while opening this session.
    ///
    /// @return opening report
    @Contract(pure = true)
    public NBTReadReport readReport() {
        return readReport;
    }

    /// Returns whether an explicit repair save is required before normal writes.
    ///
    /// @return `true` when strict opening found a defect requiring a repair publication
    @Contract(pure = true)
    public boolean requiresRepair() {
        return readReport.requiresRepair();
    }

    /// Returns whether the editor has unpublished changes or this is a newly created source awaiting first save.
    ///
    /// @return `true` when a publication is required
    @Contract(pure = true)
    public synchronized boolean isDirty() {
        return creationPending || editor.isDirty();
    }

    /// Saves the current editor snapshot and keeps the previous standalone bytes in `.xyml_old`.
    ///
    /// @throws IOException if validation or publication fails
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
    /// @throws IOException if validation, staging, or publication fails
    public synchronized void save(NBTSaveOptions options) throws IOException {
        ensureOpen();
        pendingCleanup.retry();
        NBTSaveOptions selectedOptions = Objects.requireNonNull(options, "options");
        if (encoding != NBTFileEncoding.REGION && selectedOptions.usesDefaultBackup()) {
            selectedOptions = NBTSaveOptions.withBackup(defaultBackupPath(path));
        }
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
        // Mark the logical session closed only after every physical region handle has closed. A
        // failed close remains retryable and therefore keeps its owning document lease alive.
        pendingCleanup.retry();
        if (regionFile != null) {
            regionFile.close();
        }
        closed = true;
    }

    /// Publishes one standalone savepoint through deterministic staging and atomic replacement.
    ///
    /// @param savepoint detached editor savepoint
    /// @param options save options
    /// @throws IOException if strict serialization or publication fails
    private void saveStandalone(NBTSavepoint<E> savepoint, NBTSaveOptions options) throws IOException {
        E root = savepoint.root();
        if (!(root instanceof Tag tag)) {
            throw new IOException("Standalone NBT session does not contain a tag root");
        }

        byte[] encoded = encodeTag(tag);
        if (creationPending) {
            requireCreationTarget(path);
        } else {
            requireRegularSource(path);
        }
        Path staged = deterministicStage(path);
        boolean stageCreated = false;
        IOException failure = null;
        try {
            writeStage(staged, encoded);
            stageCreated = true;
            verifyStage(staged, encoded);
            if (!creationPending) {
                publishBackup(options);
                requireRegularSource(path);
            } else {
                // A creator must never replace a file that appeared after the session was opened.
                requireCreationTarget(path);
            }
            if (creationPending) {
                atomicCreate(staged, path, "NBT source");
            } else {
                atomicReplace(staged, path, "NBT source");
            }
            editor.markSaved(savepoint);
            readReport = standaloneReadReport(encoding, true, List.of());
            creationPending = false;
        } catch (IOException exception) {
            failure = exception;
            throw exception;
        } finally {
            if (stageCreated) {
                deleteStaged(staged, failure);
            }
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
            readReport = storage.readReport();
        } catch (NBTPartialSaveException exception) {
            updateRegionBaseline(baseline, current, exception.committedIndexes());
            readReport = storage.readReport();
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
        if (raw.length > MAX_STANDALONE_RAW_BYTES) {
            throw new IOException("Uncompressed NBT payload exceeds the write limit: " + raw.length);
        }
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
        byte[] encoded = encodedOutput.toByteArray();
        if (encoded.length > MAX_STANDALONE_ENCODED_BYTES) {
            throw new IOException("Encoded NBT payload exceeds the write limit: " + encoded.length);
        }
        return encoded;
    }

    /// Atomically publishes an optional rolling backup of the current source.
    ///
    /// @param options save options
    /// @throws IOException if the backup cannot be staged or atomically published
    private void publishBackup(NBTSaveOptions options) throws IOException {
        Path configured = options.backupPath();
        if (configured == null) {
            return;
        }
        Path backup = configured.toAbsolutePath().normalize();
        if (isStagingPath(backup)) {
            throw new IOException("NBT staging files cannot be used as backup destinations: " + backup);
        }
        if (backup.equals(path)) {
            throw new IOException("The NBT backup path must differ from the source path");
        }
        Path stagedBackup = deterministicStage(backup);
        boolean stageCreated = false;
        IOException failure = null;
        try {
            requireRegularSource(path);
            copyStage(path, stagedBackup);
            stageCreated = true;
            requireRegularFile(stagedBackup, "NBT backup stage");
            atomicReplace(stagedBackup, backup, "NBT backup");
        } catch (IOException exception) {
            failure = exception;
            throw exception;
        } finally {
            if (stageCreated) {
                deleteStaged(stagedBackup, failure);
            }
        }
    }

    /// Resolves the deterministic standalone rolling-backup sibling.
    ///
    /// @param source standalone source path
    /// @return source-relative `.xyml_old` path
    /// @throws IOException if the source has no ordinary file name
    private static Path defaultBackupPath(Path source) throws IOException {
        @Nullable Path fileName = source.getFileName();
        if (fileName == null) {
            throw new IOException("NBT source has no file name: " + source);
        }
        return source.resolveSibling(fileName + ".xyml_old");
    }

    /// Selects the default standalone envelope for a newly created filename.
    ///
    /// The editor uses raw bytes for ordinary `.nbt` documents and GZIP for the Minecraft
    /// standalone data-file family. Keeping the fallback compressed makes extensionless or
    /// application-specific data files safe to create without changing the explicit overload.
    ///
    /// @param path requested target path
    /// @return default standalone envelope
    private static NBTFileEncoding defaultCreationEncoding(Path path) {
        @Nullable Path fileName = path.getFileName();
        String name = fileName == null ? "" : fileName.toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".nbt") ? NBTFileEncoding.RAW : NBTFileEncoding.GZIP;
    }

    /// Builds a standalone report while retaining the supported LZ4 extension diagnostic.
    ///
    /// LZ4 is intentionally available as a library extension, but it is not a format the
    /// official Java Edition reader is required to understand. The warning remains visible after
    /// a strict save so a later editor session does not silently lose that compatibility context.
    ///
    /// @param encoding detected standalone envelope
    /// @param strictValid whether the source was strictly parsed
    /// @param existing existing diagnostics in detection order
    /// @return immutable report with the LZ4 extension warning when applicable
    private static NBTReadReport standaloneReadReport(NBTFileEncoding encoding, boolean strictValid,
                                                       List<NBTReadIssue> existing) {
        if (encoding != NBTFileEncoding.LZ4
                || existing.stream().anyMatch(issue -> "LZ4_EXTENSION".equals(issue.code()))) {
            return new NBTReadReport(encoding, strictValid, existing);
        }
        List<NBTReadIssue> issues = new ArrayList<>(existing);
        issues.add(new NBTReadIssue(NBTReadIssue.Severity.INFORMATIONAL, "LZ4_EXTENSION", "",
                "检测到扩展 LZ4 独立文件，保存时将保留该算法；官方原版可能无法直接读取"));
        return new NBTReadReport(encoding, strictValid, issues);
    }

    /// Reads all fixed slots from an open region storage session.
    ///
    /// @param storage validated region storage
    /// @return detached complete region tree
    /// @throws IOException if any chunk cannot be decoded
    private static ChunkRegion readRegion(NBTRegionFile storage) throws IOException {
        ChunkRegion region = new ChunkRegion();
        NBTReadLimits.Budget budget = NBTReadLimits.defaults().newDocumentBudget();
        for (int localIndex = 0; localIndex < region.size(); localIndex++) {
            region.setChunk(localIndex, storage.readChunk(localIndex, budget));
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
        if (isStagingPath(absolute)) {
            throw new IOException("NBT staging files are not valid edit targets: " + absolute);
        }
        NBTRegionFileIO.requireSafeParent(absolute);
        BasicFileAttributes attributes = Files.readAttributes(
                absolute, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile() || attributes.isSymbolicLink()) {
            throw new IOException("NBT source is not a regular file: " + absolute);
        }
        return absolute;
    }

    /// Normalizes a new-file target and proves that no filesystem object currently occupies it.
    ///
    /// @param path requested target
    /// @return absolute normalized target
    /// @throws IOException if the parent or target is unsafe, or the target already exists
    private static Path normalizeCreationPath(Path path) throws IOException {
        Path absolute = Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
        if (isStagingPath(absolute)) {
            throw new IOException("NBT staging files are not valid edit targets: " + absolute);
        }
        NBTRegionFileIO.requireSafeParent(absolute);
        requireCreationTarget(absolute);
        return absolute;
    }

    /// Returns whether a path names the deterministic staging-file suffix.
    ///
    /// The comparison is case-insensitive because the editor must keep the temporary namespace
    /// reserved on case-insensitive file systems as well.
    ///
    /// @param path normalized or absolute path
    /// @return whether the final name ends in `.xyml_new`
    private static boolean isStagingPath(Path path) {
        @Nullable Path fileName = path.getFileName();
        return fileName != null && fileName.toString().toLowerCase(Locale.ROOT).endsWith(".xyml_new");
    }

    /// Returns the deterministic staging sibling used by one save operation.
    ///
    /// @param target eventual target
    /// @return deterministic stage path
    /// @throws IOException if the target has no parent or file name
    private static Path deterministicStage(Path target) throws IOException {
        @Nullable Path parent = target.getParent();
        @Nullable Path fileName = target.getFileName();
        if (parent == null || fileName == null) {
            throw new IOException("NBT target has no parent directory: " + target);
        }
        return parent.resolve(fileName + ".xyml_new");
    }

    /// Creates and writes a complete stage without forcing it to stable storage.
    ///
    /// @param target staged file
    /// @param bytes complete encoded bytes
    /// @throws IOException if the stage already exists or writing fails
    private void writeStage(Path target, byte[] bytes) throws IOException {
        NBTRegionFileIO.requireSafeParent(target);
        boolean created = false;
        try (FileChannel output = FileChannel.open(target,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
            created = true;
            writeFileChannelFully(output, ByteBuffer.wrap(bytes));
        } catch (IOException | RuntimeException failure) {
            if (created) {
                try {
                    Files.deleteIfExists(target);
                } catch (IOException | RuntimeException cleanup) {
                    pendingCleanup.remember(target);
                    failure.addSuppressed(cleanup);
                }
            }
            throw failure;
        }
    }

    /// Verifies that a staged publication contains exactly the bytes produced by strict encoding.
    ///
    /// This is deliberately a bounded byte comparison rather than a second semantic NBT parse:
    /// serialization already validated the object graph, while a full read-back would add latency
    /// and could turn a successful write into an unrelated parser compatibility test.
    ///
    /// @param target staged path
    /// @param expected complete encoded bytes
    /// @throws IOException if the stage is missing, oversized, truncated, or differs from `expected`
    private static void verifyStage(Path target, byte[] expected) throws IOException {
        requireRegularFile(target, "NBT source stage");
        long size = Files.size(target);
        if (size != expected.length) {
            throw new IOException("NBT source stage length differs from strict encoding: " + size);
        }
        byte[] actual = SourceSnapshot.read(target, expected.length).bytes();
        if (!Arrays.equals(actual, expected)) {
            throw new IOException("NBT source stage differs from strict encoding");
        }
    }

    /// Copies a source to a newly created deterministic stage.
    ///
    /// @param source source file
    /// @param target staged backup file
    /// @throws IOException if copying or stage creation fails
    private void copyStage(Path source, Path target) throws IOException {
        boolean absentBefore = !Files.exists(target, LinkOption.NOFOLLOW_LINKS);
        try {
            NBTRegionFileIO.copyFileBounded(source, target, NBTReadLimits.defaults().maxEncodedBytes());
        } catch (IOException | RuntimeException failure) {
            if (absentBefore) {
                try {
                    if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                        pendingCleanup.remember(target);
                    }
                } catch (RuntimeException existenceFailure) {
                    pendingCleanup.remember(target);
                    failure.addSuppressed(existenceFailure);
                }
            }
            throw failure;
        }
    }

    /// Writes one complete buffer to a file channel without relying on a single write call.
    private static void writeFileChannelFully(FileChannel output, ByteBuffer buffer) throws IOException {
        while (buffer.hasRemaining()) {
            int written = output.write(buffer);
            if (written <= 0) {
                throw new IOException("NBT stage channel made no progress");
            }
        }
    }

    /// Atomically replaces one target without a non-atomic fallback.
    ///
    /// @param source fully validated staged source
    /// @param target publication target
    /// @param description target description for diagnostics
    /// @throws IOException if atomic replacement is unsupported or fails
    private static void atomicReplace(Path source, Path target, String description) throws IOException {
        NBTRegionFileIO.requireSafeParent(source);
        NBTRegionFileIO.requireSafeParent(target);
        requireRegularFile(source, description + " stage");
        requireReplaceTarget(target, description);
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException exception) {
            throw new IOException("Filesystem does not support atomic " + description + " replacement: " + target,
                    exception);
        }
    }

    /// Atomically publishes a first-generation source without replacing a concurrent target.
    ///
    /// @param source fully validated staged source
    /// @param target absent publication target
    /// @param description target description for diagnostics
    /// @throws IOException if atomic creation is unsupported or the target appeared
    private static void atomicCreate(Path source, Path target, String description) throws IOException {
        NBTRegionFileIO.requireSafeParent(source);
        NBTRegionFileIO.requireSafeParent(target);
        requireRegularFile(source, description + " stage");
        requireCreationTarget(target);
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            throw new IOException("Filesystem does not support atomic " + description + " creation: " + target,
                    exception);
        }
    }

    /// Deletes a leftover staging file while preserving the primary failure when one exists.
    ///
    /// @param staged staging path
    /// @param primary primary operation failure, or `null`
    /// @throws IOException if cleanup alone fails
    private void deleteStaged(Path staged, @Nullable IOException primary) throws IOException {
        try {
            Files.deleteIfExists(staged);
        } catch (IOException | RuntimeException cleanup) {
            pendingCleanup.remember(staged);
            if (primary != null) {
                primary.addSuppressed(cleanup);
            } else if (cleanup instanceof IOException ioException) {
                throw ioException;
            } else {
                throw (RuntimeException) cleanup;
            }
        }
    }

    /// Requires a source path to remain an ordinary non-symbolic file immediately before publication.
    ///
    /// This check is intentionally repeated during a save because the editor does not retain a source
    /// file descriptor for standalone sessions. It prevents a replaced symlink, directory, or special
    /// file from being copied into a rolling backup or treated as the publication target.
    ///
    /// @param source source path
    /// @throws IOException if the source is missing, symbolic, or not a regular file
    private static void requireRegularSource(Path source) throws IOException {
        requireRegularFile(source, "NBT source");
    }

    /// Requires a path to be a regular non-symbolic file without following links.
    ///
    /// @param candidate candidate file path
    /// @param description path description for diagnostics
    /// @throws IOException if the candidate is missing, symbolic, or not a regular file
    private static void requireRegularFile(Path candidate, String description) throws IOException {
        NBTRegionFileIO.requireSafeParent(candidate);
        BasicFileAttributes attributes = Files.readAttributes(
                candidate, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile() || attributes.isSymbolicLink()) {
            throw new IOException(description + " is not a regular non-symbolic file: " + candidate);
        }
    }

    /// Rejects an existing replacement target that is not an ordinary non-symbolic file.
    ///
    /// An absent target is valid for a first backup publication. Reading attributes without
    /// following links prevents a symlink from being treated as a safe replacement destination.
    ///
    /// @param target replacement destination
    /// @param description path description for diagnostics
    /// @throws IOException if an existing target is symbolic, special, or a directory
    private static void requireReplaceTarget(Path target, String description) throws IOException {
        NBTRegionFileIO.requireSafeParent(target);
        try {
            BasicFileAttributes attributes = Files.readAttributes(
                    target, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isRegularFile() || attributes.isSymbolicLink()) {
                throw new IOException(description + " target is not a regular non-symbolic file: " + target);
            }
        } catch (NoSuchFileException ignored) {
            // A new backup destination is allowed; CREATE_NEW staging still guards its sibling.
        }
    }

    /// Requires a target to remain absent for a new-file publication.
    ///
    /// @param target candidate target
    /// @throws IOException if any filesystem object is present or its state cannot be inspected
    private static void requireCreationTarget(Path target) throws IOException {
        NBTRegionFileIO.requireSafeParent(target);
        try {
            BasicFileAttributes attributes = Files.readAttributes(
                    target, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            throw new IOException("NBT creation target already exists: " + target);
        } catch (NoSuchFileException ignored) {
            // The target is absent. CREATE_NEW staging and the final atomic move provide the remaining race guard.
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

    /// Complete bounded read of a regular source.
    ///
    /// @param bytes complete source bytes
    @NotNullByDefault
    private static final class SourceSnapshot {
        /// Complete immutable encoded source bytes.
        private final byte @Unmodifiable [] bytes;

        /// Creates a detached immutable source snapshot.
        ///
        /// @param bytes complete encoded source bytes
        private SourceSnapshot(byte @Unmodifiable [] bytes) {
            this.bytes = bytes.clone();
        }

        /// Returns a defensive copy of the complete encoded source bytes.
        ///
        /// @return complete encoded source bytes
        private byte @Unmodifiable [] bytes() {
            return bytes.clone();
        }

        /// Reads a bounded source after checking that it is a regular non-symbolic file.
        ///
        /// @param path source path
        /// @return complete bounded source snapshot
        /// @throws IOException if the source exceeds the bound or is not a regular file
        private static SourceSnapshot read(Path path) throws IOException {
            return read(path, NBTReadLimits.defaults().maxEncodedBytes());
        }

        /// Reads a source with an explicit encoded-input bound.
        private static SourceSnapshot read(Path path, long maximum) throws IOException {
            BasicFileAttributes attributes = readAttributes(path);
            long size = attributes.size();
            if (size > maximum) {
                throw new IOException("Encoded NBT input exceeds the read limit: " + size);
            }
            return new SourceSnapshot(readBounded(path, maximum));
        }

        /// Reads at most `maximum` bytes without using an unbounded convenience method.
        private static byte @Unmodifiable [] readBounded(Path path, long maximum) throws IOException {
            ByteArrayOutputStream output = new ByteArrayOutputStream(
                    (int) Math.min(maximum, 8192L));
            try (InputStream input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
                byte[] buffer = new byte[8192];
                long total = 0L;
                while (true) {
                    int count = input.read(buffer);
                    if (count < 0) {
                        return output.toByteArray();
                    }
                    if (count == 0) {
                        int single = input.read();
                        if (single < 0) {
                            return output.toByteArray();
                        }
                        if (total >= maximum) {
                            throw new IOException("Encoded NBT input exceeds the read limit");
                        }
                        output.write(single);
                        total++;
                        continue;
                    }
                    total += count;
                    if (total > maximum) {
                        throw new IOException("Encoded NBT input exceeds the read limit");
                    }
                    output.write(buffer, 0, count);
                }
            }
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

}
