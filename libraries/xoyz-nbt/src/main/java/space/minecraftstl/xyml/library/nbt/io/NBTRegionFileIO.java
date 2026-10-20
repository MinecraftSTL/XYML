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
// Added by MinecraftSTL in 2026 for bounded region-file publication helpers.
package space.minecraftstl.xyml.library.nbt.io;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import space.minecraftstl.xyml.library.nbt.internal.ChunkUtils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/// Bounded filesystem and absolute-channel operations shared by region sessions.
@NotNullByDefault
final class NBTRegionFileIO {
    /// Creates an empty region source when necessary and rejects unsafe existing paths.
    ///
    /// @param file normalized region path
    /// @throws IOException if creation fails or the visible path is not a regular non-symbolic file
    static void ensureRegionFileExists(Path file) throws IOException {
        @Nullable Path parent = file.toAbsolutePath().normalize().getParent();
        if (parent == null) {
            throw new IOException("Region path has no parent directory: " + file);
        }
        createDirectoriesNoFollow(parent);
        try {
            Files.createFile(file);
        } catch (FileAlreadyExistsException existing) {
            // The complete no-follow attribute check below decides whether the occupant is safe.
        }
        requireRegularFile(file);
    }

    /// Requires a regular file without following or accepting a symbolic link.
    ///
    /// @param file candidate region source
    /// @throws IOException if the path is missing, symbolic, or not a regular file
    static void requireRegularFile(Path file) throws IOException {
        requireSafeParent(file);
        BasicFileAttributes attributes = Files.readAttributes(
                file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile() || Files.isSymbolicLink(file)) {
            throw new IOException("Region path is not a regular non-symbolic file: " + file);
        }
    }

    /// Checks a companion path without following symbolic links.
    ///
    /// @param candidate candidate companion path
    /// @return whether the path is a regular non-symbolic file
    static boolean isRegularNonSymbolicFile(Path candidate) {
        try {
            requireSafeParent(candidate);
            BasicFileAttributes attributes = Files.readAttributes(
                    candidate, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            return attributes.isRegularFile() && !attributes.isSymbolicLink();
        } catch (IOException ignored) {
            return false;
        }
    }

    /// Returns whether a path names the deterministic standalone or region staging suffix.
    ///
    /// @param file normalized or absolute candidate path
    /// @return whether the final name belongs to the reserved staging namespace
    static boolean isStagingPath(Path file) {
        @Nullable Path fileName = file.getFileName();
        return fileName != null && fileName.toString().toLowerCase(Locale.ROOT).endsWith(".xyml_new");
    }

    /// Fills a buffer from an absolute channel position without changing shared channel position.
    ///
    /// @param channel source channel
    /// @param buffer destination buffer
    /// @param position absolute starting byte position
    /// @throws IOException if EOF is reached or the channel makes no progress
    static void readFully(FileChannel channel, ByteBuffer buffer, long position) throws IOException {
        if (position < 0L) {
            throw new IOException("Negative region file position: " + position);
        }
        while (buffer.hasRemaining()) {
            int read = channel.read(buffer, position);
            if (read < 0) {
                throw new IOException("Unexpected end of region file");
            }
            if (read == 0) {
                throw new IOException("Region channel made no progress");
            }
            try {
                position = Math.addExact(position, read);
            } catch (ArithmeticException overflow) {
                throw new IOException("Region file position overflows", overflow);
            }
        }
    }

    /// Reads one bounded absolute byte range from the region channel.
    ///
    /// @param channel source channel
    /// @param position absolute starting byte position
    /// @param length number of bytes to read
    /// @return exact range bytes
    /// @throws IOException if the length is unsupported or the range cannot be read completely
    static byte[] readBytes(FileChannel channel, long position, long length) throws IOException {
        if (position < 0L || length < 0L || length > Integer.MAX_VALUE
                || length > Long.MAX_VALUE - position) {
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
    static void writeFully(FileChannel channel, ByteBuffer buffer, long position) throws IOException {
        if (position < 0L) {
            throw new IOException("Negative region file position: " + position);
        }
        while (buffer.hasRemaining()) {
            int written = channel.write(buffer, position);
            if (written <= 0) {
                throw new IOException("Region channel made no progress while writing");
            }
            try {
                position = Math.addExact(position, written);
            } catch (ArithmeticException overflow) {
                throw new IOException("Region file position overflows", overflow);
            }
        }
    }

    /// Creates and writes a deterministic sibling stage without replacing foreign content.
    ///
    /// @param target stage path
    /// @param bytes complete staged bytes
    /// @throws IOException if the stage already exists or the write fails
    static void writeStage(Path target, byte[] bytes) throws IOException {
        requireSafeParent(target);
        boolean created = false;
        try (FileChannel output = FileChannel.open(target,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
            created = true;
            writeFully(output, ByteBuffer.wrap(bytes), 0L);
        } catch (IOException | RuntimeException failure) {
            if (created) {
                try {
                    Files.deleteIfExists(target);
                } catch (IOException cleanup) {
                    failure.addSuppressed(cleanup);
                }
            }
            throw failure;
        }
    }

    /// Copies a regular source into a newly-created stage while enforcing an encoded-byte bound.
    ///
    /// The source is checked both by its current attributes and while it is streamed. This keeps
    /// a source which grows after the initial size probe from turning a rolling-backup operation
    /// into an unbounded copy.
    ///
    /// @param source regular non-symbolic source file
    /// @param target stage path which must not already exist
    /// @param maximumBytes inclusive copy limit
    /// @throws IOException if either path is unsafe, the source grows beyond the limit, or copying fails
    static void copyFileBounded(Path source, Path target, long maximumBytes) throws IOException {
        if (maximumBytes < 0L) {
            throw new IllegalArgumentException("maximumBytes must not be negative");
        }
        requireRegularFile(source);
        requireSafeParent(target);
        if (Files.size(source) > maximumBytes) {
            throw new IOException("Source exceeds the bounded copy limit: " + source);
        }
        boolean created = false;
        try (InputStream input = Files.newInputStream(source, LinkOption.NOFOLLOW_LINKS);
             FileChannel output = FileChannel.open(target,
                     StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
            created = true;
            byte[] buffer = new byte[8192];
            long total = 0L;
            while (true) {
                int count = input.read(buffer);
                if (count < 0) {
                    break;
                }
                if (count == 0) {
                    int single = input.read();
                    if (single < 0) {
                        break;
                    }
                    if (total >= maximumBytes) {
                        throw new IOException("Source exceeds the bounded copy limit: " + source);
                    }
                    writeFully(output, ByteBuffer.wrap(new byte[]{(byte) single}), total);
                    total++;
                    continue;
                }
                if ((long) count > maximumBytes - total) {
                    throw new IOException("Source exceeds the bounded copy limit: " + source);
                }
                writeFully(output, ByteBuffer.wrap(buffer, 0, count), total);
                total += count;
            }
        } catch (IOException | RuntimeException failure) {
            if (created) {
                try {
                    Files.deleteIfExists(target);
                } catch (IOException cleanup) {
                    failure.addSuppressed(cleanup);
                }
            }
            throw failure;
        }
        requireRegularFile(target);
    }

    /// Requires every existing parent component to be a real directory.
    ///
    /// Missing descendants are allowed because callers may create them immediately afterwards;
    /// once created, [#createDirectoriesNoFollow(Path)] performs the same check again. A symbolic
    /// link or special file anywhere in the existing parent chain is rejected before any write.
    ///
    /// @param file file whose parent chain is being validated
    /// @throws IOException if an existing component is symbolic, special, or inaccessible
    static void requireSafeParent(Path file) throws IOException {
        Path absolute = Objects.requireNonNull(file, "file").toAbsolutePath().normalize();
        @Nullable Path parent = absolute.getParent();
        if (parent == null) {
            throw new IOException("Path has no parent directory: " + file);
        }
        validateExistingDirectoryChain(parent);
    }

    /// Creates a directory chain without accepting a pre-existing symbolic-link component.
    ///
    /// @param directory directory to create
    /// @throws IOException if a component is not a real directory or creation fails
    static void createDirectoriesNoFollow(Path directory) throws IOException {
        Path absolute = Objects.requireNonNull(directory, "directory").toAbsolutePath().normalize();
        validateExistingDirectoryChain(absolute);
        Files.createDirectories(absolute);
        validateExistingDirectoryChain(absolute);
    }

    /// Checks all existing components in a path from the leaf toward the filesystem root.
    ///
    /// @param leaf deepest component to inspect
    /// @throws IOException if an existing component is not a non-symbolic directory
    private static void validateExistingDirectoryChain(Path leaf) throws IOException {
        @Nullable Path current = leaf;
        while (current != null) {
            try {
                BasicFileAttributes attributes = Files.readAttributes(
                        current, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (!attributes.isDirectory() || attributes.isSymbolicLink() || Files.isSymbolicLink(current)) {
                    throw new IOException("Path component is not a regular non-symbolic directory: " + current);
                }
                rejectAliasedDirectoryEntry(current);
            } catch (NoSuchFileException missing) {
                // A missing component is safe to create; continue checking its ancestors.
            }
            current = current.getParent();
        }
    }

    /// Rejects a directory entry whose real target differs from its lexical parent entry.
    ///
    /// Windows junctions are directory reparse points and are not consistently reported as symbolic
    /// links by every NIO provider. Comparing the resolved entry with the followed real path catches
    /// those aliases while ordinary case normalization remains owned by the provider's Path type.
    ///
    /// @param directory existing directory component
    /// @throws IOException if the component redirects traversal or cannot be resolved
    private static void rejectAliasedDirectoryEntry(Path directory) throws IOException {
        @Nullable Path parent = directory.getParent();
        @Nullable Path name = directory.getFileName();
        if (parent == null || name == null) {
            return;
        }
        Path lexicalEntry = parent.toRealPath().resolve(name).toAbsolutePath().normalize();
        Path realEntry = directory.toRealPath().toAbsolutePath().normalize();
        if (!lexicalEntry.equals(realEntry)) {
            throw new IOException("Path component redirects through a symbolic link or junction: " + directory);
        }
    }

    /// Resolves a deterministic stage sibling while requiring a normal file name.
    ///
    /// @param target canonical target path
    /// @param suffix stage suffix
    /// @return sibling path
    /// @throws IOException if the target has no file name or parent
    static Path deterministicSibling(Path target, String suffix) throws IOException {
        @Nullable Path parent = target.getParent();
        @Nullable Path fileName = target.getFileName();
        if (parent == null || fileName == null) {
            throw new IOException("External companion has no parent directory: " + target);
        }
        return parent.resolve(fileName + suffix);
    }

    /// Writes a zero-filled absolute range, extending the file when needed.
    ///
    /// @param channel destination channel
    /// @param position absolute starting byte position
    /// @param bytes number of zero bytes to write
    /// @throws IOException if the range cannot be written completely
    static void writeZeros(FileChannel channel, long position, long bytes) throws IOException {
        if (position < 0L || bytes < 0L || bytes > Long.MAX_VALUE - position) {
            throw new IOException("Region zero-fill range overflows");
        }
        ByteBuffer zeros = ByteBuffer.allocate(8192);
        long remaining = bytes;
        while (remaining > 0L) {
            zeros.clear();
            zeros.limit((int) Math.min(remaining, zeros.capacity()));
            writeFully(channel, zeros, position);
            int written = zeros.limit();
            try {
                position = Math.addExact(position, written);
            } catch (ArithmeticException overflow) {
                throw new IOException("Region file position overflows", overflow);
            }
            remaining -= written;
        }
    }

    /// Encodes one four-byte region location entry.
    ///
    /// @param offset 24-bit sector offset
    /// @param length unsigned eight-bit sector count
    /// @return flipped buffer containing exactly one location entry
    static ByteBuffer encodeLocation(int offset, int length) {
        ByteBuffer location = ByteBuffer.allocate(Integer.BYTES).order(ByteOrder.BIG_ENDIAN);
        location.put((byte) (offset >>> 16));
        location.put((byte) (offset >>> 8));
        location.put((byte) offset);
        location.put((byte) length);
        location.flip();
        return location;
    }

    /// Atomically replaces a companion path and fails closed when the filesystem lacks support.
    ///
    /// @param source fully written staging path
    /// @param target canonical companion path
    /// @throws IOException if an atomic replacement cannot be completed
    static void moveAtomically(Path source, Path target) throws IOException {
        requireSafeParent(source);
        requireSafeParent(target);
        requireRegularFile(source);
        requireReplaceTarget(target);
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException exception) {
            throw new IOException("Atomic companion publication is not supported", exception);
        }
    }

    /// Rejects an existing replacement target that is not an ordinary non-symbolic file.
    ///
    /// @param target replacement destination
    /// @throws IOException if an existing target is symbolic, special, or a directory
    private static void requireReplaceTarget(Path target) throws IOException {
        requireSafeParent(target);
        try {
            BasicFileAttributes attributes = Files.readAttributes(
                    target, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isRegularFile() || attributes.isSymbolicLink()) {
                throw new IOException("Region replacement target is not a regular non-symbolic file: " + target);
            }
        } catch (NoSuchFileException ignored) {
            // A new companion destination is valid.
        }
    }

    /// Reads a stream into memory while rejecting input beyond a strict byte limit.
    ///
    /// @param input source stream
    /// @param maximumBytes largest accepted byte count
    /// @param limitMessage diagnostic message for oversized input
    /// @return complete bounded bytes
    /// @throws IOException if the stream cannot be read or exceeds the limit
    static byte @Unmodifiable [] readBounded(InputStream input, int maximumBytes, String limitMessage)
            throws IOException {
        if (maximumBytes < 0) {
            throw new IllegalArgumentException("maximumBytes must not be negative");
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(maximumBytes, 8192));
        byte[] buffer = new byte[8192];
        int count;
        while ((count = input.read(buffer)) >= 0) {
            if (count == 0) {
                int single = input.read();
                if (single < 0) {
                    break;
                }
                if (output.size() >= maximumBytes) {
                    throw new IOException(limitMessage);
                }
                output.write(single);
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
    /// @param timestamps raw region timestamp values
    /// @param localIndex local chunk slot
    /// @return timestamp instant
    static Instant timestamp(int[] timestamps, int localIndex) {
        return Instant.ofEpochSecond(Integer.toUnsignedLong(timestamps[localIndex]));
    }

    /// Clamps an instant to the unsigned 32-bit timestamp representation used by region headers.
    ///
    /// @param instant timestamp to encode
    /// @return raw unsigned epoch-second bits
    static int epochSeconds(Instant instant) {
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
    static int checkedCoordinate(int coordinate) {
        return Objects.checkIndex(coordinate, ChunkUtils.CHUNKS_PER_REGION_SIDE);
    }

    /// Returns a stable message for checked and unchecked parser failures.
    ///
    /// @param failure parser failure
    /// @return non-empty failure message
    static String failureMessage(Throwable failure) {
        String message = failure.getMessage();
        return message == null ? failure.getClass().getSimpleName() : message;
    }

    /// Converts one publication failure to checked I/O context.
    ///
    /// @param message context for an unchecked failure
    /// @param failure original checked or unchecked failure
    /// @return original I/O failure or a checked wrapper
    static IOException asIOException(String message, Throwable failure) {
        return failure instanceof IOException ioException
                ? ioException
                : new IOException(Objects.requireNonNull(message, "message"), failure);
    }

    /// Validates one fixed region slot index.
    ///
    /// @param localIndex local chunk slot
    /// @throws IndexOutOfBoundsException if the index is outside 0 through 1023
    static void checkIndex(int localIndex) {
        Objects.checkIndex(localIndex, ChunkUtils.CHUNKS_PRE_REGION);
    }

    /// Returns the stable diagnostic path used for one fixed region slot.
    ///
    /// @param localIndex local chunk slot
    /// @return diagnostic path
    static String slotPath(int localIndex) {
        return "slot[" + localIndex + "]";
    }

    /// Creates one immutable read diagnostic.
    ///
    /// @param severity issue severity
    /// @param code stable issue code
    /// @param path NBT or region path
    /// @param message human-readable detail
    /// @return immutable issue
    static NBTReadIssue readIssue(NBTReadIssue.Severity severity, String code,
                                  String path, String message) {
        return new NBTReadIssue(severity, code, path, message);
    }

    /// Prefixes a recovery issue with its owning slot path.
    ///
    /// @param localIndex local chunk slot
    /// @param issue issue from the payload parser
    /// @return issue with a region-slot path
    static NBTReadIssue withSlotPath(int localIndex, NBTReadIssue issue) {
        String prefix = slotPath(localIndex);
        String issuePath = issue.path();
        String path = issuePath.isEmpty() ? prefix : prefix + "." + issuePath;
        return new NBTReadIssue(issue.severity(), issue.code(), path, issue.message());
    }

    /// Tracks session-owned publication stages which could not be removed immediately.
    ///
    /// Missing paths are treated as already cleaned. Existing symbolic links, directories, and
    /// special files fail closed and remain pending for a later retry.
    @NotNullByDefault
    static final class PendingCleanup {
        /// Human-readable context used when a retry still cannot remove a path.
        private final String description;
        /// Paths created by the owning session and awaiting removal.
        private final Set<Path> paths = new LinkedHashSet<>();

        /// Creates an empty pending-cleanup tracker.
        ///
        /// @param description operation context for retry failures
        PendingCleanup(String description) {
            this.description = Objects.requireNonNull(description, "description");
        }

        /// Records one path owned by the session.
        ///
        /// @param path owned temporary or obsolete sidecar path
        synchronized void remember(Path path) {
            paths.add(Objects.requireNonNull(path, "path").toAbsolutePath().normalize());
        }

        /// Retries removal of every recorded path while retaining unresolved paths.
        ///
        /// @throws IOException if one or more paths cannot yet be removed
        synchronized void retry() throws IOException {
            if (paths.isEmpty()) {
                return;
            }
            @Nullable IOException firstFailure = null;
            for (Path path : List.copyOf(paths)) {
                try {
                    requireRegularFile(path);
                    Files.deleteIfExists(path);
                    paths.remove(path);
                } catch (NoSuchFileException ignored) {
                    paths.remove(path);
                } catch (IOException | RuntimeException failure) {
                    IOException checked = failure instanceof IOException
                            ? (IOException) failure
                            : new IOException(description + " cleanup failed: " + path, failure);
                    if (firstFailure == null) {
                        firstFailure = checked;
                    } else if (firstFailure != checked) {
                        firstFailure.addSuppressed(checked);
                    }
                }
            }
            if (firstFailure != null) {
                throw firstFailure;
            }
        }
    }

    /// Prevents construction of this stateless helper.
    private NBTRegionFileIO() {
    }
}
