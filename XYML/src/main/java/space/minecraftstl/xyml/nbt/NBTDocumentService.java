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
import space.minecraftstl.xyml.library.nbt.chunk.ChunkRegion;
import space.minecraftstl.xyml.library.nbt.io.NBTCodec;
import space.minecraftstl.xyml.library.nbt.tag.Tag;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.zip.GZIPOutputStream;

/// Loads and saves NBT documents exclusively on a caller-owned background executor.
///
/// Save writes a same-directory temporary file, closes and forces it, parses it back, compares the
/// semantic HelloNBT tree, rechecks the original fingerprint, and finally requires an atomic
/// replacement. There is deliberately no non-atomic fallback. Region files that require external
/// `.mcc` chunks fail while staging because multiple files cannot be published as one portable
/// atomic transaction; the original region remains untouched.
@NotNullByDefault
public final class NBTDocumentService {
    /// Full magic prefix emitted by lz4-java's block-stream output.
    private static final byte @Unmodifiable [] LZ4_MAGIC =
            "LZ4Block".getBytes(StandardCharsets.US_ASCII);

    /// Executor that owns all blocking NBT and filesystem operations.
    private final Executor ioExecutor;

    /// Immutable thread-safe HelloNBT codec.
    private final NBTCodec codec;

    /// Creates a service whose operations are dispatched to the supplied executor.
    ///
    /// The executor remains caller-owned and is never shut down by this service.
    ///
    /// @param ioExecutor executor for all blocking work
    public NBTDocumentService(Executor ioExecutor) {
        this.ioExecutor = Objects.requireNonNull(ioExecutor, "ioExecutor");
        codec = NBTCodec.of();
    }

    /// Opens and parses one supported NBT file on the background executor.
    ///
    /// The future fails with `IOException` as its completion cause for unsupported extensions,
    /// invalid NBT, symbolic links, non-regular paths, and sources changed during the read.
    ///
    /// @param file candidate source path
    /// @return future loaded document
    public CompletableFuture<NBTDocument> open(Path file) {
        Path normalized = Objects.requireNonNull(file, "file").toAbsolutePath().normalize();
        return CompletableFuture.supplyAsync(() -> {
            try {
                return openOnExecutor(normalized);
            } catch (IOException failure) {
                throw new CompletionException(failure);
            }
        }, ioExecutor);
    }

    /// Safely persists the current HelloNBT root on the background executor.
    ///
    /// The operation preserves RAW, GZIP, or LZ4 envelopes for standalone tags. It rejects a stale
    /// source and never falls back when the filesystem cannot atomically replace the target.
    ///
    /// @param document previously loaded document
    /// @return future completed after atomic publication
    public CompletableFuture<Void> save(NBTDocument document) {
        Objects.requireNonNull(document, "document");
        return CompletableFuture.runAsync(() -> {
            try {
                saveOnExecutor(document);
            } catch (IOException failure) {
                throw new CompletionException(failure);
            }
        }, ioExecutor);
    }

    /// Performs one complete open operation on the configured executor.
    ///
    /// @param file normalized absolute path
    /// @return loaded document
    /// @throws IOException when type detection, source stability, or parsing fails
    private NBTDocument openOnExecutor(Path file) throws IOException {
        @Nullable NBTFileType fileType = NBTFileType.detect(file);
        if (fileType == null) {
            throw new IOException("Unsupported NBT file extension: " + file);
        }
        NBTSourceFingerprint before = NBTSourceFingerprint.capture(file);
        NBTStorageEncoding encoding = detectStorageEncoding(file, fileType);
        NBTElement root = readElement(file, fileType);
        NBTSourceFingerprint after = NBTSourceFingerprint.capture(file);
        if (!before.sameAs(after)) {
            throw new IOException("NBT file changed while it was being parsed: " + file);
        }
        return new NBTDocument(file, fileType, encoding, root, after);
    }

    /// Executes one synchronized stale-check, stage, validation, and atomic publication transaction.
    ///
    /// @param document loaded document
    /// @throws IOException when the source is stale, staging fails, validation fails, or atomic move is unsupported
    private void saveOnExecutor(NBTDocument document) throws IOException {
        synchronized (document) {
            Path target = document.file();
            NBTSourceFingerprint expectedFingerprint = document.sourceFingerprint();
            NBTSourceFingerprint currentFingerprint = NBTSourceFingerprint.capture(target);
            if (!expectedFingerprint.sameAs(currentFingerprint)) {
                throw new IOException("NBT file changed since it was opened: " + target);
            }

            NBTElement currentElement = readElement(target, document.fileType());
            NBTSourceFingerprint parsedFingerprint = NBTSourceFingerprint.capture(target);
            if (!currentFingerprint.sameAs(parsedFingerprint)
                    || !currentElement.equals(document.baselineElement())) {
                throw new IOException("NBT file changed since it was opened: " + target);
            }

            NBTElement elementToSave = document.snapshotElementForSave();
            Path temporary = createTemporarySibling(target);
            try {
                writeElement(temporary, document.fileType(), document.storageEncoding(), elementToSave);
                forceFile(temporary);
                validateStagedElement(temporary, document.fileType(), elementToSave);
                NBTSourceFingerprint stagedFingerprint = NBTSourceFingerprint.capture(temporary);

                NBTSourceFingerprint beforeMove = NBTSourceFingerprint.capture(target);
                if (!parsedFingerprint.sameAs(beforeMove)) {
                    throw new IOException("NBT file changed while a save was being staged: " + target);
                }
                atomicReplace(temporary, target);
                document.markSaved(elementToSave, stagedFingerprint);
            } finally {
                Files.deleteIfExists(temporary);
            }
        }
    }

    /// Parses one source using the HelloNBT operation proven for its file family.
    ///
    /// @param file source path
    /// @param fileType expected family
    /// @return parsed Tag or ChunkRegion
    /// @throws IOException when parsing fails
    private NBTElement readElement(Path file, NBTFileType fileType) throws IOException {
        return switch (fileType) {
            case TAG -> codec.readTag(file);
            case ANVIL, REGION -> codec.readRegion(file);
        };
    }

    /// Detects the outer envelope that must be preserved during save.
    ///
    /// @param file source path
    /// @param fileType detected family
    /// @return exact storage envelope
    /// @throws IOException when the source prefix cannot be read
    private static NBTStorageEncoding detectStorageEncoding(Path file, NBTFileType fileType) throws IOException {
        if (fileType != NBTFileType.TAG) {
            return NBTStorageEncoding.REGION;
        }
        byte[] prefix = new byte[LZ4_MAGIC.length];
        int length = 0;
        try (var input = Files.newInputStream(file)) {
            while (length < prefix.length) {
                int count = input.read(prefix, length, prefix.length - length);
                if (count < 0) {
                    break;
                }
                length += count;
            }
        }
        if (length >= 3
                && Byte.toUnsignedInt(prefix[0]) == 0x1f
                && Byte.toUnsignedInt(prefix[1]) == 0x8b
                && Byte.toUnsignedInt(prefix[2]) == 0x08) {
            return NBTStorageEncoding.GZIP;
        }
        if (length == LZ4_MAGIC.length) {
            boolean lz4 = true;
            for (int index = 0; index < LZ4_MAGIC.length; index++) {
                if (prefix[index] != LZ4_MAGIC[index]) {
                    lz4 = false;
                    break;
                }
            }
            if (lz4) {
                return NBTStorageEncoding.LZ4;
            }
        }
        return NBTStorageEncoding.RAW;
    }

    /// Creates a temporary file in the target directory so a later atomic move stays on one provider.
    ///
    /// @param target normalized target file
    /// @return newly created temporary sibling
    /// @throws IOException when a sibling cannot be created
    private static Path createTemporarySibling(Path target) throws IOException {
        @Nullable Path parent = target.getParent();
        @Nullable Path fileName = target.getFileName();
        if (parent == null || fileName == null) {
            throw new IOException("NBT target has no writable parent directory: " + target);
        }
        return Files.createTempFile(parent, "." + fileName + ".", ".tmp");
    }

    /// Serializes one semantic root to a staged file using its original envelope.
    ///
    /// @param target temporary target path
    /// @param fileType destination family
    /// @param encoding preserved storage envelope
    /// @param element detached root snapshot
    /// @throws IOException when the root type is incompatible or HelloNBT serialization fails
    private void writeElement(
            Path target,
            NBTFileType fileType,
            NBTStorageEncoding encoding,
            NBTElement element) throws IOException {
        if (fileType == NBTFileType.TAG) {
            if (!(element instanceof Tag tag)) {
                throw new IOException("Standalone NBT file does not contain a Tag root");
            }
            writeTag(target, encoding, tag);
            return;
        }
        if (!(element instanceof ChunkRegion region)) {
            throw new IOException("Region NBT file does not contain a ChunkRegion root");
        }
        if (encoding != NBTStorageEncoding.REGION) {
            throw new IOException("Region NBT file has an incompatible storage encoding: " + encoding);
        }
        try (OutputStream output = new BufferedOutputStream(Files.newOutputStream(
                target,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING))) {
            // The stream overload deliberately uses an empty external accessor. Oversized chunks
            // therefore fail in staging instead of partially publishing companion .mcc files.
            codec.writeRegion(output, region);
        }
    }

    /// Serializes one standalone tag while preserving its detected outer compression.
    ///
    /// @param target temporary target path
    /// @param encoding RAW, GZIP, or LZ4 envelope
    /// @param tag detached tag root
    /// @throws IOException when compression or HelloNBT serialization fails
    private void writeTag(Path target, NBTStorageEncoding encoding, Tag tag) throws IOException {
        try (OutputStream rawOutput = new BufferedOutputStream(Files.newOutputStream(
                target,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING))) {
            switch (encoding) {
                case RAW -> codec.writeTag(rawOutput, tag);
                case GZIP -> {
                    try (GZIPOutputStream gzipOutput = new GZIPOutputStream(rawOutput)) {
                        codec.writeTag(gzipOutput, tag);
                    }
                }
                case LZ4 -> {
                    try (LZ4BlockOutputStream lz4Output = new LZ4BlockOutputStream(rawOutput)) {
                        codec.writeTag(lz4Output, tag);
                    }
                }
                case REGION -> throw new IOException("Standalone NBT tag has a region storage encoding");
            }
        }
    }

    /// Forces staged bytes and metadata to the filesystem before publication.
    ///
    /// @param file staged regular file
    /// @throws IOException when the file cannot be forced
    private static void forceFile(Path file) throws IOException {
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE)) {
            channel.force(true);
        }
    }

    /// Parses staged bytes and rejects any semantic difference before publication.
    ///
    /// @param staged staged file
    /// @param fileType expected family
    /// @param expected detached root that was serialized
    /// @throws IOException when staged bytes cannot be parsed or differ semantically
    private void validateStagedElement(
            Path staged,
            NBTFileType fileType,
            NBTElement expected) throws IOException {
        NBTElement actual = readElement(staged, fileType);
        if (!actual.equals(expected)) {
            throw new IOException("Staged NBT data differs from the in-memory document");
        }
    }

    /// Atomically replaces the target without a lossy fallback.
    ///
    /// @param staged fully written and validated sibling
    /// @param target existing source file
    /// @throws IOException when atomic replacement is unavailable or fails
    private static void atomicReplace(Path staged, Path target) throws IOException {
        try {
            Files.move(
                    staged,
                    target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException failure) {
            throw new IOException("Filesystem does not support atomic NBT replacement: " + target, failure);
        }
    }
}
