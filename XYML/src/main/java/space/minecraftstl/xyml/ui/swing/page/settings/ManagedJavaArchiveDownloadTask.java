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

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import space.minecraftstl.xyml.task.FetchTask;
import space.minecraftstl.xyml.util.DigestUtils;
import space.minecraftstl.xyml.util.io.ChecksumMismatchException;
import space.minecraftstl.xyml.util.io.UrlResponseInfo;

import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/// Downloads one checksummed Java archive into a random managed temporary file under a hard byte ceiling.
///
/// The task never writes to a caller-chosen path and never publishes a partial file. A successful result still passes
/// through the separate archive container preflight, normalized repack, safe extractor, and atomic publisher.
@NotNullByDefault
final class ManagedJavaArchiveDownloadTask extends FetchTask<Path> {
    /// Parser-significant temporary-file suffix.
    private final String archiveSuffix;

    /// Normalized JCA checksum algorithm.
    private final String checksumAlgorithm;

    /// Expected lowercase hexadecimal checksum.
    private final String expectedChecksum;

    /// Maximum decoded bytes written by one response.
    private final long maximumBytes;

    /// Creates a stopped bounded download task over ordered provider candidates.
    ///
    /// @param uris immutable ordered download candidates
    /// @param archiveSuffix parser-significant `.zip` or `.tar.gz` suffix
    /// @param checksumAlgorithm normalized JCA checksum algorithm
    /// @param expectedChecksum expected lowercase hexadecimal checksum
    /// @param maximumBytes positive decoded-byte ceiling
    ManagedJavaArchiveDownloadTask(
            @Unmodifiable List<URI> uris,
            String archiveSuffix,
            String checksumAlgorithm,
            String expectedChecksum,
            long maximumBytes) {
        super(List.copyOf(Objects.requireNonNull(uris, "uris")));
        if (!(archiveSuffix.equals(".zip") || archiveSuffix.equals(".tar.gz"))) {
            throw new IllegalArgumentException("Unsupported Java archive suffix: " + archiveSuffix);
        }
        if (maximumBytes <= 0L) {
            throw new IllegalArgumentException("maximumBytes must be positive");
        }
        this.archiveSuffix = archiveSuffix;
        this.checksumAlgorithm = Objects.requireNonNull(checksumAlgorithm, "checksumAlgorithm");
        this.expectedChecksum = Objects.requireNonNull(expectedChecksum, "expectedChecksum");
        this.maximumBytes = maximumBytes;
        setName("Download verified Java archive");
    }

    /// Disables HTTP cache lookup so every successful result is a newly owned random temporary file.
    ///
    /// @return no-ETag download policy
    @Override
    protected EnumCheckETag shouldCheckETag() {
        return EnumCheckETag.NOT_CHECK_E_TAG;
    }

    /// Rejects the unreachable cache path because cached files are not owned by this acquisition chain.
    ///
    /// @param cachedFile cache path that must never be supplied
    /// @throws IOException always
    @Override
    protected void useCachedResult(Path cachedFile) throws IOException {
        throw new IOException("Managed Java archive download cannot consume cache path " + cachedFile);
    }

    /// Creates one random temporary destination and rejects an oversized advertised response before reading it.
    ///
    /// @param response response metadata, or null for a non-HTTP candidate
    /// @param checkETag ignored ETag policy
    /// @param bmclapiHash ignored mirror checksum because Disco metadata supplies the selected checksum
    /// @return fresh bounded transfer context
    /// @throws IOException when the response is too large or a safe temporary file cannot be opened
    @Override
    protected Context getContext(
            @Nullable UrlResponseInfo response,
            boolean checkETag,
            @Nullable String bmclapiHash) throws IOException {
        if (response != null) {
            long advertisedLength = response.headers()
                    .firstValueAsLong("content-length")
                    .orElse(-1L);
            if (advertisedLength > maximumBytes) {
                throw new IOException("Downloaded Java archive exceeds its byte limit");
            }
        }

        Path temporaryArchive = Files.createTempFile("xyml-disco-java-", archiveSuffix)
                .toAbsolutePath()
                .normalize();
        BasicFileAttributes initialAttributes = Files.readAttributes(
                temporaryArchive,
                BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS);
        if (!initialAttributes.isRegularFile() || initialAttributes.isSymbolicLink()) {
            Files.deleteIfExists(temporaryArchive);
            throw new IOException("Managed Java download target is not a regular file");
        }

        FileChannel output;
        try {
            output = FileChannel.open(
                    temporaryArchive,
                    StandardOpenOption.WRITE,
                    LinkOption.NOFOLLOW_LINKS);
        } catch (IOException | RuntimeException failure) {
            Files.deleteIfExists(temporaryArchive);
            throw failure;
        }
        MessageDigest digest = DigestUtils.getDigest(checksumAlgorithm);
        return new Context() {
            /// Number of decoded bytes currently stored in the owned temporary file.
            private long writtenBytes;

            /// Whether the file channel has already been closed.
            private boolean closed;

            /// Truncates only the random task-owned path before a full retry and resets its digest.
            @Override
            public void reset() throws IOException {
                output.truncate(0L);
                output.position(0L);
                digest.reset();
                writtenBytes = 0L;
            }

            /// Writes one decoded range after checking overflow and the hard archive byte ceiling.
            ///
            /// @param buffer source buffer
            /// @param offset first source byte
            /// @param length number of bytes to write
            /// @throws IOException when the response exceeds the configured ceiling
            @Override
            public void write(byte[] buffer, int offset, int length) throws IOException {
                if (length > maximumBytes - writtenBytes) {
                    throw new IOException("Downloaded Java archive exceeds its byte limit");
                }
                digest.update(buffer, offset, length);
                ByteBuffer bytes = ByteBuffer.wrap(buffer, offset, length);
                while (bytes.hasRemaining()) {
                    output.write(bytes);
                }
                writtenBytes += length;
            }

            /// Closes the channel, verifies stable regular-file state and checksum, and publishes only full results.
            ///
            /// @throws IOException when final file identity, size, or checksum validation fails
            @Override
            public void close() throws IOException {
                if (closed) {
                    return;
                }
                closed = true;
                boolean keep = false;
                try {
                    output.close();
                    if (!isSuccess()) {
                        return;
                    }
                    BasicFileAttributes finalAttributes = Files.readAttributes(
                            temporaryArchive,
                            BasicFileAttributes.class,
                            LinkOption.NOFOLLOW_LINKS);
                    if (!finalAttributes.isRegularFile()
                            || finalAttributes.isSymbolicLink()
                            || finalAttributes.size() != writtenBytes
                            || writtenBytes <= 0L
                            || !sameFileIdentity(initialAttributes, finalAttributes)) {
                        throw new IOException("Managed Java download target changed during transfer");
                    }
                    String actualChecksum = HexFormat.of().formatHex(digest.digest());
                    if (!expectedChecksum.equalsIgnoreCase(actualChecksum)) {
                        throw new ChecksumMismatchException(
                                checksumAlgorithm,
                                expectedChecksum,
                                actualChecksum);
                    }
                    ManagedJavaArchiveDownloadTask.this.setResult(temporaryArchive);
                    keep = true;
                } finally {
                    if (!keep) {
                        Files.deleteIfExists(temporaryArchive);
                    }
                }
            }
        };
    }

    /// Compares stable file keys when available and falls back to creation time on providers without file keys.
    ///
    /// @param expected attributes captured immediately after random file creation
    /// @param actual attributes captured after transfer completion
    /// @return whether both snapshots identify the same regular file
    private static boolean sameFileIdentity(
            BasicFileAttributes expected,
            BasicFileAttributes actual) {
        @Nullable Object expectedKey = expected.fileKey();
        @Nullable Object actualKey = actual.fileKey();
        return expectedKey != null && actualKey != null
                ? expectedKey.equals(actualKey)
                : expected.creationTime().equals(actual.creationTime());
    }
}
