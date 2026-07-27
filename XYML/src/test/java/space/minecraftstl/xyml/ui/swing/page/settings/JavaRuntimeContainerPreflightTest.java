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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Verifies bounded physical metadata checks run before TAR and ZIP object-model construction.
@NotNullByDefault
final class JavaRuntimeContainerPreflightTest {
    /// Per-test directory used for hostile physical archive fixtures.
    @TempDir
    private @Nullable Path temporaryDirectory;

    /// Rejects a GNU long-name payload before Kala allocates its complete metadata byte array.
    @Test
    void rejectsOversizedGnuLongNameBeforeTarParser() throws IOException {
        Path archive = temporaryDirectory().resolve("long-name.tar.gz");
        writeSingleMetadataTar(archive, 'L', new byte[2_048]);
        JavaManagerRuntimeAcquisitionService.ProcessBackend backend = backendWithMetadataLimits(1_024L);

        IOException failure = assertThrows(
                IOException.class,
                () -> backend.inspectLocalArchiveLayout(archive));

        assertEquals("Java TAR metadata exceeds its byte limit", failure.getMessage());
    }

    /// Rejects sparse PAX directives before Kala creates sparse maps or sparse input-stream lists.
    @Test
    void rejectsPaxSparseMetadataBeforeTarParser() throws IOException {
        Path archive = temporaryDirectory().resolve("pax-sparse.tar.gz");
        byte @Unmodifiable [] payload =
                "24 GNU.sparse.map=0,1\n".getBytes(StandardCharsets.US_ASCII);
        writeSingleMetadataTar(archive, 'x', payload);
        JavaManagerRuntimeAcquisitionService.ProcessBackend backend = backendWithMetadataLimits(4_096L);

        IOException failure = assertThrows(
                IOException.class,
                () -> backend.inspectLocalArchiveLayout(archive));

        assertEquals("GNU sparse Java TAR metadata is unsupported", failure.getMessage());
    }

    /// Rejects an oversized ZIP central directory before the JDK `ZipFile` constructor reads it into heap.
    @Test
    void rejectsOversizedCentralDirectoryBeforeZipFileConstruction() throws IOException {
        Path archive = temporaryDirectory().resolve("central-directory.zip");
        writeZipWithLongCentralMetadata(archive);
        JavaManagerRuntimeAcquisitionService.ProcessBackend backend = new JavaManagerRuntimeAcquisitionService.ProcessBackend(
                new JavaManagerRuntimeAcquisitionService.ArchiveLimits(
                        1024L * 1024L,
                        32,
                        1024L,
                        64L * 1024L,
                        1000.0,
                        512L));

        IOException failure = assertThrows(
                IOException.class,
                () -> backend.inspectLocalArchiveLayout(archive));

        assertEquals(
                "Java ZIP central directory exceeds its resource or offset limit",
                failure.getMessage());
    }

    /// Creates injectable limits that isolate the physical TAR metadata ceiling.
    ///
    /// @param maximumMetadataEntryBytes maximum bytes accepted from one metadata pseudo-entry
    /// @return acquisition backend using the requested ceiling
    private static JavaManagerRuntimeAcquisitionService.ProcessBackend backendWithMetadataLimits(
            long maximumMetadataEntryBytes) {
        return new JavaManagerRuntimeAcquisitionService.ProcessBackend(
                new JavaManagerRuntimeAcquisitionService.ArchiveLimits(
                        1024L * 1024L,
                        16,
                        maximumMetadataEntryBytes,
                        64L * 1024L,
                        1000.0,
                        1024L * 1024L));
    }

    /// Writes one gzip-compressed TAR containing a single parser metadata pseudo-entry.
    ///
    /// @param archive destination `.tar.gz`
    /// @param type TAR type flag
    /// @param payload metadata payload
    /// @throws IOException when the fixture cannot be written
    private static void writeSingleMetadataTar(
            Path archive,
            int type,
            byte @Unmodifiable [] payload) throws IOException {
        byte[] header = new byte[512];
        byte @Unmodifiable [] name = "metadata".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(name, 0, header, 0, name.length);
        byte @Unmodifiable [] size = String.format(Locale.ROOT, "%011o", payload.length)
                .getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(size, 0, header, 124, size.length);
        header[135] = 0;
        header[156] = (byte) type;

        try (OutputStream file = Files.newOutputStream(archive);
             GZIPOutputStream output = new GZIPOutputStream(file)) {
            output.write(header);
            output.write(payload);
            int padding = (512 - payload.length % 512) % 512;
            output.write(new byte[padding]);
            output.write(new byte[1024]);
        }
    }

    /// Writes a structurally valid ZIP whose bounded entry count carries more than 512 bytes of central metadata.
    ///
    /// @param archive destination ZIP
    /// @throws IOException when the fixture cannot be written
    private static void writeZipWithLongCentralMetadata(Path archive) throws IOException {
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(archive))) {
            for (int index = 0; index < 8; index++) {
                ZipEntry entry = new ZipEntry(
                        "runtime/" + Integer.toString(index) + "-" + "n".repeat(96));
                output.putNextEntry(entry);
                output.closeEntry();
            }
        }
    }

    /// Returns the non-null JUnit temporary directory after extension injection.
    ///
    /// @return temporary fixture directory
    private Path temporaryDirectory() {
        return java.util.Objects.requireNonNull(temporaryDirectory, "temporaryDirectory");
    }
}
