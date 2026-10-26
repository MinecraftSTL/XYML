// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.mcp;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies crash-report discovery never escapes the selected instance report directory.
@NotNullByDefault
public final class XYMLMcpCrashReportResolverTest {
    /// Temporary filesystem used by report ownership tests.
    @TempDir
    private Path temporaryDirectory;

    /// Resolves a report referenced by a launcher-owned log when the file is directly under the owned root.
    @Test
    public void resolvesOwnedReferencedReport() throws IOException {
        Path root = Files.createDirectories(temporaryDirectory.resolve("crash-reports"));
        Path report = root.resolve("crash-1.txt");
        Files.writeString(report, "java.lang.OutOfMemoryError: Java heap space");
        String log = "#@!@# Game crashed! Crash report saved to: #@!@# " + root.resolve("missing.txt") + "\n"
                + "#@!@# Game crashed! Crash report saved to: #@!@# " + report;

        XYMLMcpCrashReportResolver.Resolution resolution =
                XYMLMcpCrashReportResolver.resolve(root, log, null, true);

        assertEquals(XYMLMcpCrashReportResolver.SOURCE_REFERENCED, resolution.source());
        assertEquals("java.lang.OutOfMemoryError: Java heap space", resolution.report());
        assertTrue(resolution.warnings().isEmpty());
        assertEquals("OUT_OF_MEMORY", matches(resolution).get(0).get("rule"));
    }

    /// Ignores an outside reference and analyzes an embedded report after recording a non-fatal warning.
    @Test
    public void rejectsOutsideReferenceAndFallsBackToEmbeddedReport() throws IOException {
        Path root = Files.createDirectories(temporaryDirectory.resolve("crash-reports"));
        Path outside = temporaryDirectory.resolve("outside.txt");
        Files.writeString(outside, "java.lang.OutOfMemoryError: secret");
        String log = "launcher prefix\n"
                + "---- Minecraft Crash Report ----\n"
                + "java.nio.file.FileAlreadyExistsException: embedded.cfg\n"
                + "#@!@# Game crashed! Crash report saved to: #@!@# " + outside;

        XYMLMcpCrashReportResolver.Resolution resolution =
                XYMLMcpCrashReportResolver.resolve(root, log, null, true);

        assertEquals(XYMLMcpCrashReportResolver.SOURCE_EMBEDDED, resolution.source());
        assertTrue(resolution.report() != null && resolution.report().contains("embedded.cfg"));
        assertFalse(resolution.report() != null && resolution.report().contains("secret"));
        assertEquals(1, resolution.warnings().size());
        assertFalse(matches(resolution).stream().anyMatch(match -> "OUT_OF_MEMORY".equals(match.get("rule"))));
    }

    /// Never follows a filesystem reference originating in caller-supplied `log_text`.
    @Test
    public void doesNotFollowCallerSuppliedLogReference() throws IOException {
        Path root = Files.createDirectories(temporaryDirectory.resolve("crash-reports"));
        Path outside = temporaryDirectory.resolve("secret.txt");
        Files.writeString(outside, "java.lang.OutOfMemoryError: secret");
        String log = "#@!@# Game crashed! Crash report saved to: #@!@# " + outside;

        XYMLMcpCrashReportResolver.Resolution resolution =
                XYMLMcpCrashReportResolver.resolve(root, log, null, false);

        assertEquals(XYMLMcpCrashReportResolver.SOURCE_NONE, resolution.source());
        assertNull(resolution.report());
        assertEquals(1, resolution.warnings().size());
    }

    /// Rejects relative escape, absolute outside, and nested explicit report paths.
    @Test
    public void rejectsExplicitReportPathEscapes() throws IOException {
        Path root = Files.createDirectories(temporaryDirectory.resolve("crash-reports"));
        Path outside = temporaryDirectory.resolve("outside.txt");
        Files.writeString(outside, "outside");
        Path nested = Files.createDirectories(root.resolve("nested")).resolve("report.txt");
        Files.writeString(nested, "nested");

        assertThrows(IOException.class,
                () -> XYMLMcpCrashReportResolver.resolve(root, "", "../outside.txt", false));
        assertThrows(IOException.class,
                () -> XYMLMcpCrashReportResolver.resolve(root, "", outside.toString(), false));
        assertThrows(IOException.class,
                () -> XYMLMcpCrashReportResolver.resolve(root, "", "nested/report.txt", false));
    }

    /// Analyzes the resolved report and returns its structured matches.
    ///
    /// @param resolution resolved report input
    /// @return structured matches
    @SuppressWarnings("unchecked")
    private static @Unmodifiable List<@Unmodifiable Map<String, Object>> matches(
            XYMLMcpCrashReportResolver.Resolution resolution) {
        @Unmodifiable Map<String, Object> analysis = XYMLMcpCrashAnalyzer.analyze("", resolution.report());
        return (List<Map<String, Object>>) analysis.get("matches");
    }
}
