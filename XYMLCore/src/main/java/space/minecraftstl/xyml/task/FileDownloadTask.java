/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2020  huangyuhui <huanghongxun2008@126.com> and contributors
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
package space.minecraftstl.xyml.task;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import space.minecraftstl.xyml.util.DigestUtils;
import space.minecraftstl.xyml.util.io.ChecksumMismatchException;
import space.minecraftstl.xyml.util.io.CompressingUtils;
import space.minecraftstl.xyml.util.io.FileUtils;
import space.minecraftstl.xyml.util.io.NetworkUtils;
import space.minecraftstl.xyml.util.io.UrlResponseInfo;
import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.*;

import static java.util.Objects.requireNonNull;
import static space.minecraftstl.xyml.util.logging.Logger.LOG;

/// Downloads one file from ordered remote candidates into an exact target path.
///
/// The target is declared as a semantic task resource when the task is constructed, so independent executor chains
/// writing the same destination serialize while downloads to different files remain concurrent.
@NotNullByDefault
public class FileDownloadTask extends FetchTask<Void> {

    /// Immutable digest algorithm and expected checksum pair.
    @NotNullByDefault
    public record IntegrityCheck(String algorithm, String checksum) {
        /// Validates and stores one integrity-check pair.
        public IntegrityCheck(String algorithm, String checksum) {
            this.algorithm = requireNonNull(algorithm);
            this.checksum = requireNonNull(checksum);
        }

        /// Creates an integrity check when an expected checksum is available.
        ///
        /// @param algorithm digest algorithm
        /// @param checksum expected checksum, or null to disable integrity checking
        /// @return integrity check, or null when `checksum` is null
        public static @Nullable IntegrityCheck of(String algorithm, @Nullable String checksum) {
            if (checksum == null) return null;
            else return new IntegrityCheck(algorithm, checksum);
        }

        /// Returns a diagnostic representation of this integrity check.
        @Override
        public String toString() {
            return String.format("IntegrityCheck[algorithm='%s', checksum='%s']", algorithm, checksum);
        }
    }

    private final Path file;
    /// Optional digest validation applied before publishing the downloaded target.
    private final @Nullable IntegrityCheck integrityCheck;
    private boolean caching;
    /// Optional candidate file used to seed a content-addressed cache lookup.
    private @Nullable Path candidate;
    private final ArrayList<IntegrityCheckHandler> integrityCheckHandlers = new ArrayList<>();

    /**
     * @param uri  the URI of remote file.
     * @param path the location that download to.
     */
    public FileDownloadTask(String uri, Path path) {
        this(List.of(NetworkUtils.toURI(uri)), path, null);
    }

    /// Creates a download task from one textual URI and an optional integrity check.
    ///
    /// @param uri remote URI
    /// @param path download destination
    /// @param integrityCheck integrity check, or null to disable digest comparison
    public FileDownloadTask(String uri, Path path, @Nullable IntegrityCheck integrityCheck) {
        this(List.of(NetworkUtils.toURI(uri)), path, integrityCheck);
    }

    /**
     * @param uri  the URI of remote file.
     * @param path the location that download to.
     */
    public FileDownloadTask(URI uri, Path path) {
        this(uri, path, null);
    }

    /// Creates a download task from one URI and an optional integrity check.
    ///
    /// @param uri remote URI
    /// @param path download destination
    /// @param integrityCheck integrity check, or null to disable digest comparison
    public FileDownloadTask(URI uri, Path path, @Nullable IntegrityCheck integrityCheck) {
        this(List.of(uri), path, integrityCheck);
    }

    /**
     * Constructor.
     *
     * @param uris uris of remote file, will be attempted in order.
     * @param file the location that download to.
     */
    public FileDownloadTask(List<URI> uris, Path file) {
        this(uris, file, null);
    }

    /// Creates a download task and snapshots its exact destination as a semantic resource.
    ///
    /// @param uris remote candidates attempted in order
    /// @param path download destination
    /// @param integrityCheck integrity check, or null to accept the response without a digest comparison
    public FileDownloadTask(List<URI> uris, Path path, @Nullable IntegrityCheck integrityCheck) {
        super(uris);
        this.file = path;
        this.integrityCheck = integrityCheck;

        setName(path.getFileName().toString());
        setResources(TaskResource.downloadTarget(path));
    }

    public Path getPath() {
        return file;
    }

    /// Enables or disables content-addressed cache writes for this destination download.
    ///
    /// CacheRepository serializes its own shared cache transaction; this task therefore keeps only the exact target
    /// resource so downloads to different targets can proceed concurrently.
    ///
    /// @param caching whether successful downloads should be written to the content-addressed cache
    public void setCaching(boolean caching) {
        this.caching = caching;
    }

    public FileDownloadTask setCandidate(Path candidate) {
        this.candidate = candidate;
        return this;
    }

    public void addIntegrityCheckHandler(IntegrityCheckHandler handler) {
        integrityCheckHandlers.add(Objects.requireNonNull(handler));
    }

    @Override
    protected EnumCheckETag shouldCheckETag() {
        // Check cache
        if (integrityCheck != null && caching) {
            Optional<Path> cache = repository.checkExistentFile(candidate, integrityCheck.algorithm(), integrityCheck.checksum());
            if (cache.isPresent()) {
                try {
                    FileUtils.copyFile(cache.get(), file);
                    LOG.trace("Successfully verified file " + file + " from " + uris.get(0));
                    return EnumCheckETag.CACHED;
                } catch (IOException e) {
                    LOG.warning("Failed to copy cache files", e);
                }
            }
            return EnumCheckETag.NOT_CHECK_E_TAG;
        } else {
            return EnumCheckETag.CHECK_E_TAG;
        }
    }

    @Override
    protected void beforeDownload(URI uri) {
        LOG.trace("Downloading " + uri + " to " + file);
    }

    @Override
    protected void useCachedResult(Path cache) throws IOException {
        FileUtils.copyFile(cache, file);
    }

    @Override
    protected Context getContext(@Nullable UrlResponseInfo response, boolean checkETag, @Nullable String bmclapiHash) throws IOException {
        Path temp = Files.createTempFile(null, null);

        @Nullable String algorithm;
        @Nullable String checksum;
        if (integrityCheck != null) {
            algorithm = integrityCheck.algorithm();
            checksum = integrityCheck.checksum();
        } else if (bmclapiHash != null && DigestUtils.isSha1Digest(bmclapiHash)) {
            algorithm = "SHA-1";
            checksum = bmclapiHash;
        } else {
            algorithm = null;
            checksum = null;
        }

        @Nullable MessageDigest digest = algorithm != null ? DigestUtils.getDigest(algorithm) : null;

        FileChannel fileOutput = FileChannel.open(temp,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.CREATE);
        return new Context() {
            @Override
            public void reset() throws IOException {
                if (digest != null) {
                    digest.reset();
                }

                fileOutput.truncate(0L);
                fileOutput.position(0L);
            }

            @Override
            public void write(byte[] buffer, int offset, int len) throws IOException {
                if (digest != null) {
                    digest.update(buffer, offset, len);
                }

                ByteBuffer byteBuffer = ByteBuffer.wrap(buffer, offset, len);
                while (byteBuffer.hasRemaining()) {
                    //noinspection ResultOfMethodCallIgnored
                    fileOutput.write(byteBuffer);
                }
            }

            @Override
            public void close() throws IOException {
                try {
                    fileOutput.close();
                } catch (IOException e) {
                    LOG.warning("Failed to close file: " + temp, e);
                    deleteTempFile();
                    throw e;
                }

                if (!isSuccess()) {
                    deleteTempFile();
                    return;
                }

                boolean moved = false;
                try {
                    for (IntegrityCheckHandler handler : integrityCheckHandlers) {
                        handler.checkIntegrity(temp, file);
                    }

                    if (checksum != null && !checksum.isEmpty()) {
                        String actualChecksum = HexFormat.of().formatHex(digest.digest());
                        if (!checksum.equalsIgnoreCase(actualChecksum)) {
                            throw new ChecksumMismatchException(algorithm, checksum, actualChecksum);
                        }
                    }

                    Files.createDirectories(file.toAbsolutePath().getParent());

                    try {
                        Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
                        moved = true;
                    } catch (Exception e) {
                        throw new IOException("Unable to move temp file from " + temp + " to " + file, e);
                    }

                    if (caching && algorithm != null) {
                        try {
                            repository.cacheFile(file, algorithm, checksum);
                        } catch (IOException e) {
                            LOG.warning("Failed to cache file", e);
                        }
                    }

                    if (checkETag) {
                        repository.cacheRemoteFile(response, file);
                    }
                } finally {
                    if (!moved) {
                        deleteTempFile();
                    }
                }
            }

            private void deleteTempFile() {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException e) {
                    LOG.warning("Failed to delete file: " + temp, e);
                }
            }
        };
    }

    /// Validates a downloaded temporary file against destination-specific format requirements.
    @NotNullByDefault
    public interface IntegrityCheckHandler {
        /// Checks whether a downloaded file is structurally valid.
        ///
        /// @param filePath downloaded file, usually in a temporary directory
        /// @param destinationPath final destination used to infer the expected format
        /// @throws IOException if the downloaded file is corrupted
        void checkIntegrity(Path filePath, Path destinationPath) throws IOException;
    }

    /// Integrity handler that verifies ZIP and JAR targets can be opened as read-only ZIP filesystems.
    public static final IntegrityCheckHandler ZIP_INTEGRITY_CHECK_HANDLER = (filePath, destinationPath) -> {
        String ext = FileUtils.getExtension(destinationPath).toLowerCase(Locale.ROOT);
        if (ext.equals("zip") || ext.equals("jar")) {
            try (FileSystem ignored = CompressingUtils.createReadOnlyZipFileSystem(filePath)) {
                // test for zip format
            }
        }
    };
}
