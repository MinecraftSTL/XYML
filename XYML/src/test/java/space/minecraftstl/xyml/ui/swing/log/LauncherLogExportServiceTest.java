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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies secure historical-log filtering, stable archive entries, and collision-free output creation.
@NotNullByDefault
public final class LauncherLogExportServiceTest {
    /// Isolated output and logger roots for each export test.
    @TempDir
    private Path temporaryDirectory;

    /// Archives trusted plain and compressed history with the current retained logger stream.
    @Test
    public void archivesTrustedHistoryAndCurrentStream() throws Exception {
        Path logDirectory = Files.createDirectories(temporaryDirectory.resolve("logs"));
        Path outputDirectory = Files.createDirectories(temporaryDirectory.resolve("exports"));
        Path current = Files.writeString(logDirectory.resolve("current.log"), "current-file");
        Path plain = Files.writeString(logDirectory.resolve("older.log"), "older");
        Path compressed = logDirectory.resolve("older-two.log.gz");
        writeGzip(compressed, "older-two");
        Path unrelated = Files.writeString(temporaryDirectory.resolve("private.txt"), "do-not-export");
        RecordingAccess access = new RecordingAccess(current, List.of(plain, compressed, unrelated), outputDirectory);
        LauncherLogExportService service = service(access, LocalDateTime.of(2026, 7, 25, 11, 30, 45));

        Path output = service.export().toCompletableFuture().join();

        assertAll(
                () -> assertEquals("xyml-exported-logs-2026-07-25T11-30-45.zip", output.getFileName().toString()),
                () -> assertEquals(5, access.requestedHistoryCount.get()),
                () -> assertTrue(Files.isRegularFile(output)));
        try (ZipFile archive = new ZipFile(output.toFile())) {
            assertAll(
                    () -> assertEquals("older", readEntry(archive, "older.log")),
                    () -> assertEquals("older-two", readEntry(archive, "older-two.log")),
                    () -> assertEquals("current-stream", readEntry(archive, "xyml-latest.log")),
                    () -> assertFalse(archive.stream().anyMatch(entry -> entry.getName().contains("private"))));
        }
    }

    /// Ignores every historical path outside the active logger directory and still exports the current stream.
    @Test
    public void writesCurrentLogWhenNoTrustedHistoryExists() throws Exception {
        Path logDirectory = Files.createDirectories(temporaryDirectory.resolve("logs"));
        Path outputDirectory = Files.createDirectories(temporaryDirectory.resolve("exports"));
        Path current = Files.writeString(logDirectory.resolve("current.log"), "current-file");
        Path unrelated = Files.writeString(temporaryDirectory.resolve("private.txt"), "do-not-export");
        RecordingAccess access = new RecordingAccess(current, List.of(unrelated), outputDirectory);
        LauncherLogExportService service = service(access, LocalDateTime.of(2026, 7, 25, 11, 31, 0));

        Path output = service.export().toCompletableFuture().join();

        assertAll(
                () -> assertEquals("xyml-exported-logs-2026-07-25T11-31-00.log", output.getFileName().toString()),
                () -> assertEquals("current-stream", Files.readString(output)),
                () -> assertEquals(5, access.requestedHistoryCount.get()));
    }

    /// Preserves an existing timestamped export and reserves a deterministic suffixed filename for the new export.
    @Test
    public void doesNotReplaceExistingTimestampedExport() throws Exception {
        Path logDirectory = Files.createDirectories(temporaryDirectory.resolve("logs"));
        Path outputDirectory = Files.createDirectories(temporaryDirectory.resolve("exports"));
        Path current = Files.writeString(logDirectory.resolve("current.log"), "current-file");
        Path existing = outputDirectory.resolve("xyml-exported-logs-2026-07-25T11-32-00.log");
        Files.writeString(existing, "existing-export");
        RecordingAccess access = new RecordingAccess(current, List.of(), outputDirectory);
        LauncherLogExportService service = service(access, LocalDateTime.of(2026, 7, 25, 11, 32, 0));

        Path output = service.export().toCompletableFuture().join();

        assertAll(
                () -> assertEquals("existing-export", Files.readString(existing)),
                () -> assertEquals("xyml-exported-logs-2026-07-25T11-32-00-1.log", output.getFileName().toString()),
                () -> assertEquals("current-stream", Files.readString(output)));
    }

    /// Creates one direct-executor service with a fixed timestamp.
    ///
    /// @param access source logger fixture
    /// @param timestamp deterministic export timestamp
    /// @return synchronous export service
    private static LauncherLogExportService service(RecordingAccess access, LocalDateTime timestamp) {
        return new LauncherLogExportService(access, Runnable::run, () -> timestamp);
    }

    /// Writes one gzip-encoded rotated log fixture.
    ///
    /// @param output compressed output path
    /// @param content uncompressed fixture text
    /// @throws IOException when the fixture cannot be written
    private static void writeGzip(Path output, String content) throws IOException {
        try (OutputStream fileOutput = Files.newOutputStream(output);
             GZIPOutputStream gzipOutput = new GZIPOutputStream(fileOutput)) {
            gzipOutput.write(content.getBytes(StandardCharsets.UTF_8));
        }
    }

    /// Reads one UTF-8 archive entry for direct fixture assertions.
    ///
    /// @param archive archive containing the entry
    /// @param name entry name
    /// @return decoded entry text
    /// @throws IOException when the archive cannot be read
    private static String readEntry(ZipFile archive, String name) throws IOException {
        try (var input = archive.getInputStream(archive.getEntry(name))) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /// In-memory logger adapter backed by controlled paths and one stable current-log stream.
    @NotNullByDefault
    private static final class RecordingAccess implements LauncherLogExportAccess {
        /// Active logger path used to establish the trusted historical directory.
        private final Path currentLogFile;

        /// Immutable historical path list returned to the export service.
        private final @Unmodifiable List<Path> history;

        /// Target directory receiving generated exports.
        private final Path outputDirectory;

        /// Captures the requested history limit.
        private final AtomicInteger requestedHistoryCount = new AtomicInteger();

        /// Creates one deterministic logger fixture.
        ///
        /// @param currentLogFile active logger file
        /// @param history requested historical files
        /// @param outputDirectory export destination directory
        private RecordingAccess(Path currentLogFile, List<Path> history, Path outputDirectory) {
            this.currentLogFile = currentLogFile;
            this.history = List.copyOf(history);
            this.outputDirectory = outputDirectory;
        }

        /// Returns the configured active logger path.
        ///
        /// @return non-null active logger file
        @Override
        public @Nullable Path currentLogFile() {
            return currentLogFile;
        }

        /// Records the requested history size and returns the configured immutable history.
        ///
        /// @param maximumCount requested history bound
        /// @return configured historical paths
        @Override
        public @Unmodifiable List<Path> findRecentLogFiles(int maximumCount) {
            requestedHistoryCount.set(maximumCount);
            return history;
        }

        /// Writes stable current retained log text.
        ///
        /// @param output output receiving the retained stream
        /// @throws IOException when the output cannot be written
        @Override
        public void writeCurrentLogs(OutputStream output) throws IOException {
            output.write("current-stream".getBytes(StandardCharsets.UTF_8));
        }

        /// Returns the configured export destination.
        ///
        /// @return output directory
        @Override
        public Path outputDirectory() {
            return outputDirectory;
        }
    }
}
