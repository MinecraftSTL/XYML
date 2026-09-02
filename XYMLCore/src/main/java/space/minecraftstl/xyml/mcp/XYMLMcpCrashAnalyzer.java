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
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import space.minecraftstl.xyml.game.CrashReportAnalyzer;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;

/// Adapts the existing crash analyzer to MCP-safe structured data.
@NotNullByDefault
public final class XYMLMcpCrashAnalyzer {

    /// Prevents instantiation of this stateless adapter.
    private XYMLMcpCrashAnalyzer() {
    }

    /// Applies XYML's crash rules to supplied log and crash-report text.
    ///
    /// @param logText raw game log text
    /// @param crashReport optional report content already resolved by the caller
    /// @return immutable structured rule matches, report text, and keywords
    public static @Unmodifiable Map<String, Object> analyze(
            String logText, @Nullable String crashReport) {
        String checkedLog = Objects.requireNonNull(logText, "logText");
        EnumMap<CrashReportAnalyzer.Rule, @Unmodifiable Map<String, Object>> matches =
                new EnumMap<>(CrashReportAnalyzer.Rule.class);
        addMatches(matches, checkedLog, "log");
        if (crashReport != null) {
            addMatches(matches, crashReport, "crash_report");
        }

        @Unmodifiable List<String> keywords = CrashReportAnalyzer.findKeywordsFromCrashReport(
                        crashReport == null ? "" : crashReport)
                .stream()
                .sorted()
                .toList();
        return Map.of(
                "matches", List.copyOf(matches.values()),
                "crash_report", crashReport == null ? "" : crashReport,
                "keywords", keywords);
    }

    /// Merges rule matches from one source, retaining later evidence while recording every matching source.
    ///
    /// @param matches accumulated matches indexed by rule
    /// @param text source text to analyze
    /// @param source stable source identifier
    private static void addMatches(
            EnumMap<CrashReportAnalyzer.Rule, @Unmodifiable Map<String, Object>> matches,
            String text,
            String source) {
        for (CrashReportAnalyzer.Result result : CrashReportAnalyzer.analyze(text)) {
            Map<String, Object> match = new LinkedHashMap<>();
            match.put("rule", result.rule().name());
            Matcher matcher = result.matcher();
            match.put("matched_text", Objects.requireNonNullElse(matcher.group(), ""));
            @Nullable @Unmodifiable Map<String, Object> previous = matches.get(result.rule());
            if (previous == null) {
                match.put("sources", List.of(source));
            } else {
                @SuppressWarnings("unchecked")
                @Unmodifiable List<String> previousSources = (List<String>) previous.get("sources");
                match.put("sources", appendSource(previousSources, source));
            }
            match.put("source", source);
            for (String group : result.rule().getGroupNames()) {
                match.put(group, groupValue(matcher, group));
            }
            matches.put(result.rule(), Map.copyOf(match));
        }
    }

    /// Appends a source identifier unless it was already recorded for the same rule.
    ///
    /// @param sources existing immutable source identifiers
    /// @param source source identifier to append
    /// @return immutable source identifiers
    private static @Unmodifiable List<String> appendSource(List<String> sources, String source) {
        return sources.contains(source)
                ? sources
                : java.util.stream.Stream.concat(sources.stream(), java.util.stream.Stream.of(source)).toList();
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
