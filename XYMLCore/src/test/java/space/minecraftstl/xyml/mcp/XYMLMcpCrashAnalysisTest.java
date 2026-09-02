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
package space.minecraftstl.xyml.mcp;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

/// Verifies the MCP crash-analysis result shape using the existing XYML analyzer rules.
@NotNullByDefault
public final class XYMLMcpCrashAnalysisTest {

    /// Confirms an OOM line produces a structured rule match and a JSON-safe keyword list.
    @Test
    public void analyzesOutOfMemoryLog() {
        @Unmodifiable Map<String, Object> result = XYMLMcpCrashAnalyzer.analyze(
                "Exception in thread main java.lang.OutOfMemoryError: Java heap space", null);
        @SuppressWarnings("unchecked")
        @Unmodifiable List<@Unmodifiable Map<String, Object>> matches =
                (List<Map<String, Object>>) result.get("matches");
        assertFalse(matches.isEmpty());
        assertEquals("OUT_OF_MEMORY", matches.get(0).get("rule"));
        assertEquals("log", matches.get(0).get("source"));
        assertEquals(List.of("log"), matches.get(0).get("sources"));
        assertNull(matches.get(0).get("log"));
        assertEquals("", result.get("crash_report"));
    }

    /// Confirms rules found only in explicit crash-report text are included in the structured diagnosis.
    @Test
    public void analyzesExplicitCrashReportRules() {
        String report = "---- Minecraft Crash Report ----\n"
                + "java.lang.OutOfMemoryError: Java heap space";

        @Unmodifiable Map<String, Object> result = XYMLMcpCrashAnalyzer.analyze("[main/INFO]: stopped", report);
        @Unmodifiable Map<String, Object> match = matches(result).get(0);

        assertEquals(report, result.get("crash_report"));
        assertEquals("OUT_OF_MEMORY", match.get("rule"));
        assertEquals("crash_report", match.get("source"));
        assertEquals(List.of("crash_report"), match.get("sources"));
    }

    /// Confirms report evidence wins duplicate rules while retaining the complete stable source set.
    @Test
    public void mergesRulesWithReportPriorityAndNoDuplicates() {
        String log = "The driver does not appear to support OpenGL\n"
                + "java.nio.file.FileAlreadyExistsException: log.cfg";
        String report = "java.lang.OutOfMemoryError: Java heap space\n"
                + "java.nio.file.FileAlreadyExistsException: report.cfg";

        @Unmodifiable List<@Unmodifiable Map<String, Object>> matches =
                matches(XYMLMcpCrashAnalyzer.analyze(log, report));
        Set<String> rules = matches.stream()
                .map(match -> String.valueOf(match.get("rule")))
                .collect(Collectors.toSet());
        @Unmodifiable Map<String, Object> duplicate = matches.stream()
                .filter(match -> "FILE_ALREADY_EXISTS".equals(match.get("rule")))
                .findFirst()
                .orElseThrow();

        assertEquals(Set.of("OPENGL_NOT_SUPPORTED", "OUT_OF_MEMORY", "FILE_ALREADY_EXISTS"), rules);
        assertEquals(3, matches.size());
        assertEquals("crash_report", duplicate.get("source"));
        assertEquals(List.of("log", "crash_report"), duplicate.get("sources"));
        assertEquals("report.cfg", duplicate.get("file"));
        assertEquals("java.nio.file.FileAlreadyExistsException: report.cfg", duplicate.get("matched_text"));
    }

    /// Returns the typed structured match list from one analysis result.
    ///
    /// @param result structured analysis result
    /// @return structured rule matches
    @SuppressWarnings("unchecked")
    private static @Unmodifiable List<@Unmodifiable Map<String, Object>> matches(
            @Unmodifiable Map<String, Object> result) {
        return (List<Map<String, Object>>) result.get("matches");
    }
}
