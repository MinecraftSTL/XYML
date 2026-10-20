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
package space.minecraftstl.xyml.game;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies bounded, allowlisted, read-only launcher crash-export ingestion.
@NotNullByDefault
final class ExportedCrashBundleReaderTest {
    /// Temporary directory containing test archives.
    @TempDir
    private Path temporaryDirectory;

    /// Canonical names are recognized without performing filesystem access.
    @Test
    void recognizesOnlyCanonicalGeneratedFileNames() {
        assertTrue(ExportedCrashBundleReader.hasSupportedFileName(
                Path.of("minecraft-exported-crash-info-2026-09-16_12-30-00.ZIP")));
        assertFalse(ExportedCrashBundleReader.hasSupportedFileName(Path.of("minecraft-exported-crash-info-.zip")));
        assertFalse(ExportedCrashBundleReader.hasSupportedFileName(Path.of("renamed.zip")));
        assertFalse(ExportedCrashBundleReader.hasSupportedFileName(Path.of("minecraft-exported-crash-info-a.txt")));
    }

    /// Supported texts are report-first, exact-content deduplicated, and retain every source name.
    @Test
    void readsSupportedTextsInPriorityOrderAndMergesSources() throws IOException {
        Path archive = archive("priority");
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(archive))) {
            writeText(output, "latest.log", "root");
            writeText(output, "launch.bat", "must not execute");
            writeText(output, "instance.json", "must not parse");
            writeText(output, "logs/latest.log", "same failure");
            writeText(output, "minecraft.log", "main");
            writeText(output, "crash-reports/crash.txt", "same failure");
            writeText(output, "liteconfig/loader.log", "loader");
            writeText(output, "crash-reports/second.txt", "report two");
        }

        ExportedCrashBundle result = new ExportedCrashBundleReader().read(archive);

        assertEquals(archive.toAbsolutePath().normalize(), result.archive());
        assertEquals(List.of("same failure", "report two", "main", "loader", "root"),
                result.texts().stream().map(ExportedCrashBundleText::content).toList());
        assertEquals(ExportedCrashBundleText.Kind.CRASH_REPORT, result.texts().get(0).kind());
        assertEquals(List.of("crash-reports/crash.txt", "logs/latest.log"), result.texts().get(0).sources());
        assertTrue(result.texts().stream().noneMatch(text -> text.content().contains("must not")));
        assertThrows(UnsupportedOperationException.class, () -> result.texts().add(result.texts().get(0)));
        assertThrows(UnsupportedOperationException.class, () -> result.texts().get(0).sources().add("other"));
    }

    /// Renamed ZIP files are rejected even when their content otherwise matches the export format.
    @Test
    void rejectsRenamedArchive() throws IOException {
        Path archive = temporaryDirectory.resolve("renamed.zip");
        writeArchive(archive, List.of(new TestEntry("minecraft.log", "failure")));

        assertThrows(IOException.class, () -> new ExportedCrashBundleReader().read(archive));
    }

    /// Traversal, absolute, drive-rooted, and empty-segment names fail the whole archive before text reads.
    @Test
    void rejectsUnsafeEntryNamesIncludingIgnoredEntries() throws IOException {
        for (String entryName : List.of("../launch.bat", "/launch.sh", "C:/launch.bat", "logs//latest.log")) {
            Path archive = archive("unsafe-" + Math.abs(entryName.hashCode()));
            writeArchive(archive, List.of(
                    new TestEntry("minecraft.log", "failure"),
                    new TestEntry(entryName, "ignored but unsafe")));

            assertThrows(IOException.class, () -> new ExportedCrashBundleReader().read(archive), entryName);
        }
    }

    /// Case and separator aliases cannot bypass duplicate supported-entry detection.
    @Test
    void rejectsDuplicateSupportedEntryPaths() throws IOException {
        Path archive = archive("duplicate");
        writeArchive(archive, List.of(
                new TestEntry("logs/latest.log", "first"),
                new TestEntry("LOGS\\LATEST.LOG", "second")));

        assertThrows(IOException.class, () -> new ExportedCrashBundleReader().read(archive));
    }

    /// All central-directory entries count toward the fixed resource ceiling, including ignored entries.
    @Test
    void rejectsTooManyEntries() throws IOException {
        Path archive = archive("entry-count");
        writeArchive(archive, List.of(
                new TestEntry("minecraft.log", "failure"),
                new TestEntry("one.json", "ignored"),
                new TestEntry("two.json", "ignored")));

        ExportedCrashBundleReader reader = new ExportedCrashBundleReader(2, 1024L, 2048L, 1000L);
        assertThrows(IOException.class, () -> reader.read(archive));
    }

    /// Declared and streamed single-entry bytes cannot exceed their configured ceiling.
    @Test
    void rejectsOversizedSingleText() throws IOException {
        Path archive = archive("single-limit");
        writeArchive(archive, List.of(new TestEntry("minecraft.log", "123456789")));

        ExportedCrashBundleReader reader = new ExportedCrashBundleReader(10, 8L, 16L, 1000L);
        assertThrows(IOException.class, () -> reader.read(archive));
    }

    /// Declared and streamed bytes are accumulated across all supported texts.
    @Test
    void rejectsOversizedTotalText() throws IOException {
        Path archive = archive("total-limit");
        writeArchive(archive, List.of(
                new TestEntry("minecraft.log", "12345678"),
                new TestEntry("latest.log", "abcdefgh")));

        ExportedCrashBundleReader reader = new ExportedCrashBundleReader(10, 8L, 15L, 1000L);
        assertThrows(IOException.class, () -> reader.read(archive));
    }

    /// Highly compressible diagnostic entries are rejected before decompression.
    @Test
    void rejectsExcessiveCompressionRatio() throws IOException {
        Path archive = archive("ratio-limit");
        writeArchive(archive, List.of(new TestEntry("minecraft.log", "A".repeat(32 * 1024))));

        ExportedCrashBundleReader reader = new ExportedCrashBundleReader(10, 64L * 1024L, 64L * 1024L, 2L);
        assertThrows(IOException.class, () -> reader.read(archive));
    }

    /// Corrupt ZIP bytes never produce a partial result.
    @Test
    void rejectsCorruptArchive() throws IOException {
        Path archive = archive("corrupt");
        Files.writeString(archive, "not a zip");

        assertThrows(IOException.class, () -> new ExportedCrashBundleReader().read(archive));
    }

    /// Archives with only ignored or blank supported entries contain no usable diagnostic report.
    @Test
    void rejectsArchiveWithoutDiagnosticText() throws IOException {
        Path ignored = archive("ignored-only");
        writeArchive(ignored, List.of(new TestEntry("launch.bat", "echo unsafe")));
        Path blank = archive("blank-only");
        writeArchive(blank, List.of(new TestEntry("minecraft.log", "  \n")));

        assertThrows(IOException.class, () -> new ExportedCrashBundleReader().read(ignored));
        assertThrows(IOException.class, () -> new ExportedCrashBundleReader().read(blank));
    }

    /// Builds one canonical archive path for the requested fixture name.
    ///
    /// @param suffix unique fixture suffix
    /// @return canonical crash-export path
    private Path archive(String suffix) {
        return temporaryDirectory.resolve("minecraft-exported-crash-info-" + suffix + ".zip");
    }

    /// Writes a complete ZIP fixture.
    ///
    /// @param archive destination archive
    /// @param entries ordered fixture entries
    private static void writeArchive(Path archive, List<TestEntry> entries) throws IOException {
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(archive))) {
            for (TestEntry entry : entries) {
                writeText(output, entry.name(), entry.content());
            }
        }
    }

    /// Writes one UTF-8 text ZIP entry.
    ///
    /// @param output open ZIP output
    /// @param name entry name
    /// @param content text content
    private static void writeText(ZipOutputStream output, String name, String content) throws IOException {
        output.putNextEntry(new ZipEntry(name));
        output.write(content.getBytes(StandardCharsets.UTF_8));
        output.closeEntry();
    }

    /// One ZIP fixture entry.
    ///
    /// @param name entry name
    /// @param content text content
    private record TestEntry(String name, String content) {
        /// Retains non-null fixture values.
        private TestEntry {
            java.util.Objects.requireNonNull(name, "name");
            java.util.Objects.requireNonNull(content, "content");
        }
    }
}
