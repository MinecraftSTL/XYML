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
package space.minecraftstl.xyml.ai;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/// Verifies the MCP crash-analysis result shape using the existing XYML analyzer rules.
@NotNullByDefault
public final class XYMLMcpCrashAnalysisTest {

    /// Confirms an OOM line produces a structured rule match and a JSON-safe keyword list.
    @Test
    public void analyzesOutOfMemoryLog() {
        Map<String, Object> result = XYMLMcpCrashAnalyzer.analyze(
                "Exception in thread main java.lang.OutOfMemoryError: Java heap space", null);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> matches = (List<Map<String, Object>>) result.get("matches");
        assertFalse(matches.isEmpty());
        assertEquals("OUT_OF_MEMORY", matches.get(0).get("rule"));
        assertEquals("", result.get("crash_report"));
    }
}
