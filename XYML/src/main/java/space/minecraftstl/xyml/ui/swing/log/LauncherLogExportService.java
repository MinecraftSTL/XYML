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
package space.minecraftstl.xyml.ui.swing.log;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import space.minecraftstl.xyml.task.Schedulers;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.tukaani.xz.XZInputStream;

/// Exports the retained launcher log and up to five historical log files without blocking the event dispatch thread.
///
/// Historical files are admitted only when they are regular non-symbolic-link children of the active log directory.
/// This keeps the launcher multi-file archive behavior while preventing a forged log listing from exporting unrelated
/// local files. Closing the service rejects new exports and cancels callback delivery for queued work.
@NotNullByDefault
public final class LauncherLogExportService implements AutoCloseable {
    /// Maximum historical log files bundled with the current in-memory log stream.
    private static final int RECENT_LOG_LIMIT = 5;

    /// Stable filename prefix for exported support archives.
    private static final String EXPORT_FILE_PREFIX = "xyml-exported-logs-";

    /// Stable archive entry name for the currently retained in-memory log events.
    private static final String CURRENT_LOG_ENTRY_NAME = "xyml-latest.log";

    /// Timestamp pattern used for collision-resistant export names.
    private static final DateTimeFormatter EXPORT_TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH-mm-ss");

    /// Production logger and output-directory access.
    private final LauncherLogExportAccess access;

    /// Executor dedicated to background file reads and archive writes.
    private final Executor executor;

    /// Time supplier isolated so collision and naming behavior can be tested deterministically.
    private final Supplier<LocalDateTime> clock;

    /// Export futures that may be cancelled when the hosting settings page closes.
    private final Set<CompletableFuture<Path>> activeExports = ConcurrentHashMap.newKeySet();

    /// Prevents new exports after the owning UI has been disposed.
    private final AtomicBoolean closed = new AtomicBoolean();

    /// Creates a production service using the shared launcher I/O scheduler.
    ///
    /// @return service backed by the active launcher launcher logger
    public static LauncherLogExportService createForCurrentLauncher() {
        return new LauncherLogExportService(LauncherLogExportAccessAdapter.createForCurrentLauncher(), Schedulers.io());
    }

    /// Creates a launcher-log exporter with the current wall clock.
    ///
    /// @param access source logger and output-directory access
    /// @param executor executor for file and archive work
    public LauncherLogExportService(LauncherLogExportAccess access, Executor executor) {
        this(access, executor, LocalDateTime::now);
    }

    /// Creates a launcher-log exporter with an injected clock for deterministic tests.
    ///
    /// @param access source logger and output-directory access
    /// @param executor executor for file and archive work
    /// @param clock supplier for export timestamp generation
    LauncherLogExportService(LauncherLogExportAccess access, Executor executor, Supplier<LocalDateTime> clock) {
        this.access = Objects.requireNonNull(access, "access");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /// Returns the active launcher-log directory when the launcher logger owns an on-disk file.
    ///
    /// @return active log directory, or an empty result for in-memory-only logging
    public Optional<Path> logDirectory() {
        @Nullable Path logFile = access.currentLogFile();
        if (logFile == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(logFile.toAbsolutePath().normalize().getParent());
    }

    /// Starts one background export of current and recent launcher logs.
    ///
    /// @return stage resolving to the newly created log or archive file
    public CompletionStage<Path> export() {
        if (closed.get()) {
            return CompletableFuture.failedFuture(new CancellationException("Launcher log export service is closed"));
        }

        CompletableFuture<Path> export = CompletableFuture.supplyAsync(() -> {
            ensureOpen();
            try {
                return writeExport();
            } catch (IOException exception) {
                throw new CompletionException(exception);
            }
        }, executor);
        activeExports.add(export);
        export.whenComplete((ignored, failure) -> activeExports.remove(export));
        if (closed.get() && activeExports.remove(export)) {
            export.cancel(true);
        }
        return export;
    }

    /// Cancels queued exports and prevents later callers from starting new archive work.
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        for (CompletableFuture<Path> export : activeExports) {
            export.cancel(true);
        }
        activeExports.clear();
    }

    /// Writes either the current in-memory log alone or a zip containing recent historical logs and the current log.
    ///
    /// @return created output file
    /// @throws IOException when output cannot be written
    private Path writeExport() throws IOException {
        @Unmodifiable List<Path> historicalLogs = trustedRecentLogFiles();
        if (historicalLogs.isEmpty()) {
            return writeReserved(".log", access::writeCurrentLogs);
        }
        return writeReserved(".zip", output -> {
            try (ZipOutputStream archive = new ZipOutputStream(output)) {
                writeArchive(archive, historicalLogs);
            }
        });
    }

    /// Filters launcher historical log paths to direct trusted files beneath the active launcher log directory.
    ///
    /// @return immutable safe historical file list
    private @Unmodifiable List<Path> trustedRecentLogFiles() {
        Optional<Path> directory = logDirectory();
        if (directory.isEmpty()) {
            return List.of();
        }
        Path trustedDirectory = directory.orElseThrow();
        List<Path> accepted = new ArrayList<>();
        Set<Path> seen = new HashSet<>();
        for (Path candidate : access.findRecentLogFiles(RECENT_LOG_LIMIT)) {
            Path normalized = candidate.toAbsolutePath().normalize();
            if (!normalized.startsWith(trustedDirectory)
                    || normalized.getParent() == null
                    || !normalized.getParent().equals(trustedDirectory)
                    || Files.isSymbolicLink(normalized)
                    || !Files.isRegularFile(normalized, LinkOption.NOFOLLOW_LINKS)) {
                continue;
            }
            if (seen.add(normalized)) {
                accepted.add(normalized);
            }
        }
        return List.copyOf(accepted);
    }

    /// Writes all accepted history files and then the current retained log stream into one archive.
    ///
    /// @param archive destination archive
    /// @param historicalLogs trusted historical files
    /// @throws IOException when archive output cannot be written
    private void writeArchive(ZipOutputStream archive, @Unmodifiable List<Path> historicalLogs) throws IOException {
        Set<String> entryNames = new HashSet<>();
        for (Path historicalLog : historicalLogs) {
            ensureOpen();
            String fileName = archiveFileName(historicalLog);
            @Nullable InputStream decompressed = openCompressedLog(historicalLog, fileName);
            if (decompressed != null) {
                String decompressedName = removeCompressedExtension(fileName);
                if (writeArchiveEntry(archive, entryNames, decompressedName, decompressed)) {
                    continue;
                }
            }

            @Nullable InputStream raw = openRawLog(historicalLog);
            if (raw == null) {
                // A rotated log can disappear while it is being exported; the current logger stream remains useful.
                continue;
            }
            writeArchiveEntry(archive, entryNames, fileName, raw);
        }

        String currentEntryName = uniqueEntryName(entryNames, CURRENT_LOG_ENTRY_NAME);
        archive.putNextEntry(new ZipEntry(currentEntryName));
        try {
            access.writeCurrentLogs(archive);
        } finally {
            archive.closeEntry();
        }
    }

    /// Opens a transparent decompression stream for a supported compressed log file.
    ///
    /// @param file source log file
    /// @param fileName source file name
    /// @return decompressed source stream, or null when the file is uncompressed or cannot be decompressed
    private static @Nullable InputStream openCompressedLog(Path file, String fileName) {
        if (!fileName.endsWith(".gz") && !fileName.endsWith(".xz")) {
            return null;
        }
        try {
            InputStream source = Files.newInputStream(file);
            try {
                return fileName.endsWith(".gz") ? new GZIPInputStream(source) : new XZInputStream(source);
            } catch (IOException exception) {
                source.close();
                return null;
            }
        } catch (IOException exception) {
            return null;
        }
    }

    /// Opens one raw historical log stream without treating a disappearing rotated file as an export failure.
    ///
    /// @param file historical log file
    /// @return raw stream, or null when the rotated file can no longer be opened
    private static @Nullable InputStream openRawLog(Path file) {
        try {
            return Files.newInputStream(file);
        } catch (IOException exception) {
            return null;
        }
    }

    /// Copies one input stream into a uniquely named archive entry.
    ///
    /// A source-read failure is reported through the return value so compressed input can fall back to a raw copy.
    /// Archive-write failures always propagate because a partially written archive is not a successful export.
    ///
    /// @param archive archive receiving the entry
    /// @param entryNames names already reserved in the archive
    /// @param requestedEntryName desired entry file name
    /// @param input source log content
    /// @return true when every source byte was copied, false when the source failed while reading
    /// @throws IOException when archive output cannot be written
    private static boolean writeArchiveEntry(
            ZipOutputStream archive,
            Set<String> entryNames,
            String requestedEntryName,
            InputStream input) throws IOException {
        Objects.requireNonNull(archive, "archive");
        Objects.requireNonNull(entryNames, "entryNames");
        Objects.requireNonNull(requestedEntryName, "requestedEntryName");
        Objects.requireNonNull(input, "input");
        archive.putNextEntry(new ZipEntry(uniqueEntryName(entryNames, requestedEntryName)));
        try (input) {
            byte[] buffer = new byte[8_192];
            while (true) {
                int read;
                try {
                    read = input.read(buffer);
                } catch (IOException exception) {
                    return false;
                }
                if (read < 0) {
                    return true;
                }
                archive.write(buffer, 0, read);
            }
        } finally {
            archive.closeEntry();
        }
    }

    /// Reserves a collision-free output file and writes the supplied data without replacing any existing export.
    ///
    /// @param extension required output-file extension
    /// @param writer archive or text writer
    /// @return created output file
    /// @throws IOException when no output file can be created or written
    private Path writeReserved(String extension, OutputWriter writer) throws IOException {
        Path directory = access.outputDirectory().toAbsolutePath().normalize();
        String baseName = EXPORT_FILE_PREFIX + EXPORT_TIMESTAMP.format(clock.get());
        for (int collision = 0; collision < 10_000; collision++) {
            ensureOpen();
            String suffix = collision == 0 ? "" : "-" + collision;
            Path output = directory.resolve(baseName + suffix + extension);
            try (OutputStream stream = Files.newOutputStream(
                    output,
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE)) {
                writer.write(stream);
                return output;
            } catch (FileAlreadyExistsException exception) {
                continue;
            } catch (IOException exception) {
                deleteIncompleteOutput(output, exception);
                throw exception;
            } catch (RuntimeException | Error failure) {
                deleteIncompleteOutput(output, failure);
                throw failure;
            }
        }
        throw new IOException("Could not reserve a launcher log export file in " + directory);
    }

    /// Deletes an incomplete user-visible export while preserving any cleanup failure on the original error.
    ///
    /// @param output incomplete output file
    /// @param failure write failure that triggered cleanup
    private static void deleteIncompleteOutput(Path output, Throwable failure) {
        try {
            Files.deleteIfExists(output);
        } catch (IOException cleanupFailure) {
            failure.addSuppressed(cleanupFailure);
        }
    }

    /// Converts a trusted path to one portable archive file name.
    ///
    /// @param file historical log path
    /// @return normalized archive file name
    private static String archiveFileName(Path file) {
        @Nullable Path fileName = file.getFileName();
        String source = fileName == null ? "launcher.log" : fileName.toString();
        String normalized = source.replace('\\', '_').replace('/', '_').trim();
        return normalized.isEmpty() ? "launcher.log" : normalized;
    }

    /// Removes one supported compression extension for the decompressed archive entry.
    ///
    /// @param fileName original compressed file name
    /// @return uncompressed archive entry name
    private static String removeCompressedExtension(String fileName) {
        if (fileName.endsWith(".gz") || fileName.endsWith(".xz")) {
            return fileName.substring(0, fileName.length() - 3);
        }
        return fileName;
    }

    /// Allocates an archive entry name that cannot collide with a previous entry.
    ///
    /// @param entryNames names already allocated in this archive
    /// @param requestedName base entry name
    /// @return unique archive entry name
    private static String uniqueEntryName(Set<String> entryNames, String requestedName) {
        String baseName = archiveFileName(Path.of(Objects.requireNonNull(requestedName, "requestedName")));
        if (entryNames.add(baseName)) {
            return baseName;
        }
        for (int suffix = 1; ; suffix++) {
            String candidate = baseName + "." + suffix;
            if (entryNames.add(candidate)) {
                return candidate;
            }
        }
    }

    /// Fails the active export promptly after its owning settings page has been closed.
    private void ensureOpen() {
        if (closed.get()) {
            throw new CancellationException("Launcher log export service is closed");
        }
    }

    /// Writes current logs or a complete archive payload to a newly reserved output stream.
    @FunctionalInterface
    @NotNullByDefault
    private interface OutputWriter {
        /// Writes one full export payload.
        ///
        /// @param output newly reserved destination stream
        /// @throws IOException when the payload cannot be written
        void write(OutputStream output) throws IOException;
    }
}
