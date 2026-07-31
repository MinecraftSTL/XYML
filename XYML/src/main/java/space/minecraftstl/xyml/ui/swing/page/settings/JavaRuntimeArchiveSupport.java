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
package space.minecraftstl.xyml.ui.swing.page.settings;

import kala.compress.archivers.ArchiveEntry;
import kala.compress.archivers.tar.TarArchiveEntry;
import kala.compress.archivers.zip.ZipArchiveEntry;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import space.minecraftstl.xyml.java.JavaInfo;
import space.minecraftstl.xyml.ui.swing.page.settings.JavaManagerRuntimeAcquisitionService.ArchiveLimits;
import space.minecraftstl.xyml.ui.swing.page.settings.JavaRuntimeAcquisitionBackend.CancellationCheck;
import space.minecraftstl.xyml.util.tree.ArchiveFileTree;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.zip.ZipEntry;

/// Holds bounded archive-stream accounting and immutable inspection values shared by the process backend.
///
/// Package visibility keeps these security helpers testable without exposing them as launcher API.
@NotNullByDefault
final class JavaRuntimeArchiveSupport {
    /// Prevents construction of the stateless support namespace.
    private JavaRuntimeArchiveSupport() {
    }

    /// Mutable budget for uncompressed bytes emitted into one normalized install archive.
    @NotNullByDefault
    static final class ArchiveWriteBudget {
        /// Immutable configured ceilings.
        private final ArchiveLimits limits;

        /// Uncompressed file and link bytes emitted so far.
        private long outputBytes;

        /// Creates an empty normalized-output budget.
        ///
        /// @param limits immutable resource ceilings
        ArchiveWriteBudget(ArchiveLimits limits) {
            this.limits = Objects.requireNonNull(limits, "limits");
        }

        /// Requires one declared source entry to fit both per-entry and remaining total ceilings.
        ///
        /// @param entryBytes declared entry bytes
        /// @param entryName entry name used in diagnostics
        /// @throws IOException when the entry cannot fit
        void requireEntryFits(long entryBytes, String entryName) throws IOException {
            if (entryBytes < 0L
                    || entryBytes > limits.maxEntryUncompressedBytes()
                    || entryBytes > limits.maxTotalUncompressedBytes() - outputBytes) {
                throw new IOException("Normalized Java entry exceeds its byte limit: " + entryName);
            }
        }

        /// Returns the remaining per-entry and total allowance for the next source stream.
        ///
        /// @return maximum bytes the next entry may emit
        long maximumRemainingEntryBytes() {
            return Math.min(
                    limits.maxEntryUncompressedBytes(),
                    limits.maxTotalUncompressedBytes() - outputBytes);
        }

        /// Records a regular source entry after bounded copying and verifies its declaration.
        ///
        /// @param entry source entry
        /// @param copiedBytes actual copied bytes
        /// @param entryName output entry name used in diagnostics
        /// @throws IOException when actual bytes differ or exceed a ceiling
        void recordSourceEntry(
                ArchiveEntry entry,
                long copiedBytes,
                String entryName) throws IOException {
            if (copiedBytes != entry.getSize()) {
                throw new IOException("Normalized Java entry size changed while copying: " + entryName);
            }
            recordOutputBytes(copiedBytes, entryName);
        }

        /// Records link or regular output bytes against per-entry and cumulative ceilings.
        ///
        /// @param entryBytes emitted bytes
        /// @param entryName output entry name used in diagnostics
        /// @throws IOException when a ceiling would be exceeded
        void recordOutputBytes(long entryBytes, String entryName) throws IOException {
            requireEntryFits(entryBytes, entryName);
            outputBytes += entryBytes;
        }
    }

    /// Output stream enforcing a hard written-byte ceiling and cooperative cancellation.
    @NotNullByDefault
    static final class LimitedOutputStream extends OutputStream {
        /// Underlying temporary-file stream.
        private final OutputStream output;

        /// Maximum bytes permitted through this wrapper.
        private final long maximumBytes;

        /// Cooperative cancellation callback.
        private final CancellationCheck cancellationCheck;

        /// Bytes written so far.
        private long writtenBytes;

        /// Creates a bounded output stream.
        ///
        /// @param output underlying output stream
        /// @param maximumBytes maximum bytes permitted
        /// @param cancellationCheck cooperative cancellation callback
        LimitedOutputStream(
                OutputStream output,
                long maximumBytes,
                CancellationCheck cancellationCheck) {
            this.output = Objects.requireNonNull(output, "output");
            if (maximumBytes <= 0L) {
                throw new IllegalArgumentException("maximumBytes must be positive");
            }
            this.maximumBytes = maximumBytes;
            this.cancellationCheck = Objects.requireNonNull(cancellationCheck, "cancellationCheck");
        }

        /// Writes one byte after cancellation and ceiling checks.
        ///
        /// @param value byte value
        /// @throws IOException when writing fails or exceeds the ceiling
        @Override
        public void write(int value) throws IOException {
            cancellationCheck.checkCancelled();
            requireCapacity(1);
            output.write(value);
            writtenBytes++;
        }

        /// Writes one byte range after cancellation and ceiling checks.
        ///
        /// @param buffer source bytes
        /// @param offset first source index
        /// @param length bytes to write
        /// @throws IOException when writing fails or exceeds the ceiling
        @Override
        public void write(byte[] buffer, int offset, int length) throws IOException {
            Objects.checkFromIndexSize(offset, length, buffer.length);
            cancellationCheck.checkCancelled();
            requireCapacity(length);
            output.write(buffer, offset, length);
            writtenBytes += length;
        }

        /// Flushes the underlying stream.
        ///
        /// @throws IOException when flushing fails
        @Override
        public void flush() throws IOException {
            output.flush();
        }

        /// Closes the underlying stream.
        ///
        /// @throws IOException when closing fails
        @Override
        public void close() throws IOException {
            output.close();
        }

        /// Requires an additional byte count to fit the remaining output ceiling.
        ///
        /// @param additionalBytes additional bytes requested
        /// @throws IOException when the output ceiling would be exceeded
        private void requireCapacity(int additionalBytes) throws IOException {
            if (additionalBytes > maximumBytes - writtenBytes) {
                throw new IOException("Normalized Java archive exceeds its temporary byte limit");
            }
        }
    }

    /// Streaming JDK ZIP preflight budget applied before Kala constructs its in-memory archive tree.
    @NotNullByDefault
    static final class ZipPreflightBudget {
        /// Immutable configured ceilings.
        private final ArchiveLimits limits;

        /// Source ZIP byte length used for overall compression ratio.
        private final long sourceArchiveBytes;

        /// Entries observed in central-directory enumeration.
        private int entryCount;

        /// Total declared non-directory bytes.
        private long declaredBytes;

        /// Total actual streamed non-directory bytes.
        private long actualBytes;

        /// Creates an empty streaming ZIP budget.
        ///
        /// @param limits immutable resource ceilings
        /// @param sourceArchiveBytes source ZIP byte length
        ZipPreflightBudget(ArchiveLimits limits, long sourceArchiveBytes) {
            this.limits = Objects.requireNonNull(limits, "limits");
            if (sourceArchiveBytes <= 0L) {
                throw new IllegalArgumentException("sourceArchiveBytes must be positive");
            }
            this.sourceArchiveBytes = sourceArchiveBytes;
        }

        /// Registers one central-directory entry and validates its declaration and compressed size.
        ///
        /// @param entry ZIP entry
        /// @throws IOException when entry count, size, or ratio exceeds a ceiling
        void registerEntry(ZipEntry entry) throws IOException {
            entryCount++;
            if (entryCount > limits.maxEntries()) {
                throw new IOException("Java ZIP contains too many entries");
            }
            if (entry.isDirectory()) {
                return;
            }

            long declaredSize = entry.getSize();
            long compressedSize = entry.getCompressedSize();
            if (declaredSize < 0L
                    || compressedSize < 0L
                    || declaredSize > limits.maxEntryUncompressedBytes()
                    || declaredSize > limits.maxTotalUncompressedBytes() - declaredBytes) {
                throw new IOException("Java ZIP entry has an invalid declared size: " + entry.getName());
            }
            if (declaredSize > 0L
                    && (double) declaredSize / Math.max(1L, compressedSize)
                    > limits.maxCompressionRatio()) {
                throw new IOException(
                        "Java ZIP entry exceeds its compression-ratio limit: " + entry.getName());
            }
            declaredBytes += declaredSize;
        }

        /// Returns the smaller remaining per-entry and total streaming allowance.
        ///
        /// @return maximum bytes the next entry may produce
        long maximumRemainingEntryBytes() {
            return Math.min(
                    limits.maxEntryUncompressedBytes(),
                    limits.maxTotalUncompressedBytes() - actualBytes);
        }

        /// Records one fully streamed ZIP entry and verifies its exact declaration.
        ///
        /// @param entry ZIP entry
        /// @param entryBytes actual uncompressed bytes
        /// @throws IOException when actual bytes differ or exceed a ceiling
        void recordActualBytes(ZipEntry entry, long entryBytes) throws IOException {
            if (entryBytes != entry.getSize()
                    || entryBytes > limits.maxEntryUncompressedBytes()
                    || entryBytes > limits.maxTotalUncompressedBytes() - actualBytes) {
                throw new IOException("Java ZIP entry size changed while reading: " + entry.getName());
            }
            actualBytes += entryBytes;
        }

        /// Requires complete totals and an acceptable whole-ZIP compression ratio.
        ///
        /// @throws IOException when totals differ or the ratio is excessive
        void requireComplete() throws IOException {
            if (actualBytes != declaredBytes) {
                throw new IOException("Java ZIP declared and actual byte totals differ");
            }
            if ((double) actualBytes / sourceArchiveBytes > limits.maxCompressionRatio()) {
                throw new IOException("Java ZIP exceeds its overall compression-ratio limit");
            }
        }
    }

    /// Streaming TAR preflight budget applied before Kala constructs its in-memory archive tree.
    @NotNullByDefault
    static final class TarPreflightBudget {
        /// Immutable configured ceilings.
        private final ArchiveLimits limits;

        /// Compressed source byte length used for the overall compression ratio.
        private final long sourceArchiveBytes;

        /// Entries observed by streaming TAR enumeration.
        private int entryCount;

        /// Total declared non-directory bytes.
        private long declaredBytes;

        /// Total actual streamed non-directory bytes.
        private long actualBytes;

        /// Creates an empty streaming TAR budget.
        ///
        /// @param limits immutable resource ceilings
        /// @param sourceArchiveBytes compressed source byte length
        TarPreflightBudget(ArchiveLimits limits, long sourceArchiveBytes) {
            this.limits = Objects.requireNonNull(limits, "limits");
            if (sourceArchiveBytes <= 0L) {
                throw new IllegalArgumentException("sourceArchiveBytes must be positive");
            }
            this.sourceArchiveBytes = sourceArchiveBytes;
        }

        /// Registers one streamed TAR entry and validates its declared size.
        ///
        /// @param entry TAR entry
        /// @throws IOException when entry count or declared bytes exceed a ceiling
        void registerEntry(TarArchiveEntry entry) throws IOException {
            entryCount++;
            if (entryCount > limits.maxEntries()) {
                throw new IOException("Java TAR contains too many entries");
            }
            if (entry.isDirectory()) {
                if (entry.getSize() != 0L) {
                    throw new IOException("Java TAR directory has a non-zero declared size: " + entry.getName());
                }
                return;
            }

            long declaredSize = entry.getSize();
            if (declaredSize < 0L
                    || declaredSize > limits.maxEntryUncompressedBytes()
                    || declaredSize > limits.maxTotalUncompressedBytes() - declaredBytes) {
                throw new IOException("Java TAR entry has an invalid declared size: " + entry.getName());
            }
            declaredBytes += declaredSize;
        }

        /// Returns the smaller remaining per-entry and total streaming allowance.
        ///
        /// @return maximum bytes the next entry may produce
        long maximumRemainingEntryBytes() {
            return Math.min(
                    limits.maxEntryUncompressedBytes(),
                    limits.maxTotalUncompressedBytes() - actualBytes);
        }

        /// Records one fully streamed TAR entry and verifies its exact declaration.
        ///
        /// @param entry TAR entry
        /// @param entryBytes actual uncompressed bytes
        /// @throws IOException when actual bytes differ or exceed a ceiling
        void recordActualBytes(TarArchiveEntry entry, long entryBytes) throws IOException {
            if (entryBytes != entry.getSize()
                    || entryBytes > limits.maxEntryUncompressedBytes()
                    || entryBytes > limits.maxTotalUncompressedBytes() - actualBytes) {
                throw new IOException("Java TAR entry size changed while reading: " + entry.getName());
            }
            actualBytes += entryBytes;
        }

        /// Requires complete totals and an acceptable whole-archive compression ratio.
        ///
        /// @throws IOException when totals differ or the ratio is excessive
        void requireComplete() throws IOException {
            if (actualBytes != declaredBytes) {
                throw new IOException("Java TAR declared and actual byte totals differ");
            }
            if ((double) actualBytes / sourceArchiveBytes > limits.maxCompressionRatio()) {
                throw new IOException("Java TAR exceeds its overall compression-ratio limit");
            }
        }
    }

    /// Mutable per-inspection accounting that enforces declared and actually read archive resources.
    @NotNullByDefault
    static final class ArchiveResourceBudget {
        /// Immutable configured ceilings.
        private final ArchiveLimits limits;

        /// Compressed or source archive byte length used for overall ratio checks.
        private final long sourceArchiveBytes;

        /// Explicit entries observed so far.
        private int entryCount;

        /// Sum of declared non-directory entry bytes.
        private long declaredBytes;

        /// Sum of actual non-directory bytes read so far.
        private long actualBytes;

        /// Creates an empty accounting budget for one non-empty source archive.
        ///
        /// @param limits immutable resource ceilings
        /// @param sourceArchiveBytes source archive byte length
        ArchiveResourceBudget(ArchiveLimits limits, long sourceArchiveBytes) {
            this.limits = Objects.requireNonNull(limits, "limits");
            if (sourceArchiveBytes <= 0L) {
                throw new IllegalArgumentException("sourceArchiveBytes must be positive");
            }
            this.sourceArchiveBytes = sourceArchiveBytes;
        }

        /// Registers one explicit entry and checks declared size and ZIP compression ratio.
        ///
        /// @param entry archive entry
        /// @throws IOException when a declared resource limit is exceeded
        void registerEntry(ArchiveEntry entry) throws IOException {
            entryCount++;
            if (entryCount > limits.maxEntries()) {
                throw new IOException("Java archive contains too many entries");
            }
            if (entry.isDirectory()) {
                return;
            }

            long declaredSize = entry.getSize();
            if (declaredSize < 0L || declaredSize > limits.maxEntryUncompressedBytes()) {
                throw new IOException(
                        "Java archive entry has an invalid declared size: " + entry.getName());
            }
            if (declaredSize > limits.maxTotalUncompressedBytes() - declaredBytes) {
                throw new IOException("Java archive exceeds its declared uncompressed byte limit");
            }
            declaredBytes += declaredSize;

            if (entry instanceof ZipArchiveEntry zipEntry && declaredSize > 0L) {
                long compressedSize = zipEntry.getCompressedSize();
                if (compressedSize < 0L
                        || (double) declaredSize / Math.max(1L, compressedSize)
                        > limits.maxCompressionRatio()) {
                    throw new IOException(
                            "Java archive entry exceeds its compression-ratio limit: " + entry.getName());
                }
            }
        }

        /// Returns the smaller remaining per-entry and total actual-byte allowance.
        ///
        /// @return maximum bytes the next entry may produce
        long maximumRemainingEntryBytes() {
            return Math.min(
                    limits.maxEntryUncompressedBytes(),
                    limits.maxTotalUncompressedBytes() - actualBytes);
        }

        /// Records one fully read entry and requires its actual bytes to equal its declaration.
        ///
        /// @param entry archive entry
        /// @param entryBytes actual bytes read
        /// @throws IOException when actual size or cumulative size is invalid
        void recordActualBytes(ArchiveEntry entry, long entryBytes) throws IOException {
            if (entryBytes < 0L
                    || entryBytes != entry.getSize()
                    || entryBytes > limits.maxEntryUncompressedBytes()
                    || entryBytes > limits.maxTotalUncompressedBytes() - actualBytes) {
                throw new IOException(
                        "Java archive entry size does not match its bounded contents: " + entry.getName());
            }
            actualBytes += entryBytes;
        }

        /// Requires complete actual accounting and an acceptable overall compression ratio.
        ///
        /// @throws IOException when declared and actual totals differ or the ratio is excessive
        void requireComplete() throws IOException {
            if (actualBytes != declaredBytes) {
                throw new IOException("Java archive declared and actual byte totals differ");
            }
            if ((double) actualBytes / sourceArchiveBytes > limits.maxCompressionRatio()) {
                throw new IOException("Java archive exceeds its overall compression-ratio limit");
            }
        }
    }

    /// Immutable exact archive byte length and SHA-256 captured by one complete read.
    ///
    /// @param size exact bytes read
    /// @param sha256 lowercase SHA-256 hexadecimal digest
    @NotNullByDefault
    record ArchiveFingerprint(long size, String sha256) {
        /// Rejects invalid sizes and absent digests.
        ArchiveFingerprint {
            if (size < 0L) {
                throw new IllegalArgumentException("size must not be negative");
            }
            sha256 = Objects.requireNonNull(sha256, "sha256");
        }
    }

    /// Opened archive tree plus an optional bounded expanded TAR owned by this backend.
    ///
    /// @param tree opened archive tree
    /// @param expandedTemporaryFile owned expanded TAR, or null for direct ZIP access
    @NotNullByDefault
    record OpenedArchive(
            ArchiveFileTree<?, ?> tree,
            @Nullable Path expandedTemporaryFile) implements AutoCloseable {
        /// Rejects an absent archive tree.
        OpenedArchive {
            tree = Objects.requireNonNull(tree, "tree");
        }

        /// Closes the archive tree and then deletes its owned expanded TAR.
        ///
        /// @throws IOException when closing or deletion fails
        @Override
        public void close() throws IOException {
            @Nullable IOException failure = null;
            try {
                tree.close();
            } catch (IOException closeFailure) {
                failure = closeFailure;
            }
            if (expandedTemporaryFile != null) {
                try {
                    Files.deleteIfExists(expandedTemporaryFile);
                } catch (IOException deleteFailure) {
                    if (failure == null) {
                        failure = deleteFailure;
                    } else {
                        failure.addSuppressed(deleteFailure);
                    }
                }
            }
            if (failure != null) {
                throw failure;
            }
        }
    }

    /// Located Java Home subtree and parsed release metadata inside a validated archive.
    ///
    /// @param directory archive-tree directory representing Java Home
    /// @param path immutable full path from the archive root to Java Home
    /// @param javaInfo parsed Java metadata
    @NotNullByDefault
    record JavaHomeLocation<E extends ArchiveEntry>(
            ArchiveFileTree.Dir<E> directory,
            @Unmodifiable List<String> path,
            JavaInfo javaInfo) {
        /// Defensively snapshots path segments and rejects absent location data.
        JavaHomeLocation {
            directory = Objects.requireNonNull(directory, "directory");
            path = List.copyOf(Objects.requireNonNull(path, "path"));
            if (path.isEmpty()) {
                throw new IllegalArgumentException("path must not be empty");
            }
            javaInfo = Objects.requireNonNull(javaInfo, "javaInfo");
        }
    }
}
