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
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import space.minecraftstl.xyml.game.CrashReportAnalyzer;

import java.io.IOException;
import java.nio.file.InvalidPathException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;

/// Adapts the existing crash analyzer to MCP-safe structured data.
@NotNullByDefault
public final class XYMLMcpCrashAnalyzer {

    /// Prevents instantiation of this stateless adapter.
    private XYMLMcpCrashAnalyzer() {
    }

    /// Applies XYML's crash rules and report extraction to supplied text.
    ///
    /// @param logText raw game log text
    /// @param explicitReport optional report content supplied by the caller
    /// @return immutable structured rule matches, report text, and keywords
    public static @Unmodifiable Map<String, Object> analyze(
            String logText, @Nullable String explicitReport) {
        @Nullable String report = explicitReport;
        if (report == null) {
            try {
                report = CrashReportAnalyzer.findCrashReport(logText);
            } catch (IOException | InvalidPathException ignored) {
                report = null;
            }
            if (report == null) {
                report = CrashReportAnalyzer.extractCrashReport(logText);
            }
        }

        List<Map<String, Object>> matches = new ArrayList<>();
        for (CrashReportAnalyzer.Result result : CrashReportAnalyzer.analyze(logText)) {
            Map<String, Object> match = new LinkedHashMap<>();
            match.put("rule", result.rule().name());
            match.put("log", result.log());
            Matcher matcher = result.matcher();
            for (String group : result.rule().getGroupNames()) {
                match.put(group, groupValue(matcher, group));
            }
            matches.add(Map.copyOf(match));
        }
        matches.sort(Comparator.comparing(value -> String.valueOf(value.get("rule"))));

        return Map.of(
                "matches", List.copyOf(matches),
                "crash_report", report == null ? "" : report,
                "keywords", List.copyOf(CrashReportAnalyzer.findKeywordsFromCrashReport(
                        report == null ? "" : report)));
    }

    /// Safely obtains a named regex group from a crash rule.
    ///
    /// @param matcher populated rule matcher
    /// @param name named group
    /// @return group value, or an empty string when absent
    private static String groupValue(Matcher matcher, String name) {
        try {
            @Nullable String value = matcher.group(name);
            return value == null ? "" : value;
        } catch (IllegalArgumentException ignored) {
            return "";
        }
    }
}
