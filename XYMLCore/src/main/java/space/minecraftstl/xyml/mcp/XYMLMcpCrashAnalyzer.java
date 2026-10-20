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

import java.util.ArrayList;
import java.util.Collections;
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
        LinkedHashMap<CrashReportAnalyzer.Rule, @Unmodifiable Map<String, Object>> matches = new LinkedHashMap<>();
        addMatches(matches, checkedLog, "log");
        if (crashReport != null) {
            addMatches(matches, crashReport, "crash_report");
        }

        List<@Unmodifiable Map<String, Object>> suppressedMatches = new ArrayList<>();
        applySupersession(matches, suppressedMatches);

        @Unmodifiable List<String> keywords = CrashReportAnalyzer.findKeywordsFromCrashReport(
                        crashReport == null ? "" : crashReport)
                .stream()
                .sorted()
                .toList();
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("matches", List.copyOf(matches.values()));
        response.put("suppressed_matches", List.copyOf(suppressedMatches));
        response.put("crash_report", crashReport == null ? "" : crashReport);
        response.put("keywords", keywords);
        return Collections.unmodifiableMap(new LinkedHashMap<>(response));
    }

    /// Applies one explicit supersession relation while retaining the hidden evidence for inspection.
    ///
    /// The narrower native-memory reservation diagnosis is authoritative whenever it is present. The broader
    /// `OUT_OF_MEMORY` rule remains useful as a fallback, but exposing both would present two repair causes for one
    /// failure and would make remote callers choose an arbitrary order.
    ///
    /// @param matches mutable displayed matches
    /// @param suppressedMatches mutable hidden evidence list
    /// @param supersedingRule rule that owns the diagnosis
    /// @param supersededRule broader rule to hide
    private static void suppress(
            Map<CrashReportAnalyzer.Rule, @Unmodifiable Map<String, Object>> matches,
            List<@Unmodifiable Map<String, Object>> suppressedMatches,
            CrashReportAnalyzer.Rule supersedingRule,
            CrashReportAnalyzer.Rule supersededRule) {
        if (!matches.containsKey(supersedingRule)) {
            return;
        }
        @Nullable Map<String, Object> suppressed = matches.remove(supersededRule);
        if (suppressed == null) {
            return;
        }
        Map<String, Object> evidence = new LinkedHashMap<>(suppressed);
        evidence.put("suppressed_by", supersedingRule.name());
        suppressedMatches.add(Collections.unmodifiableMap(new LinkedHashMap<>(evidence)));
    }

    /// Merges rule matches from one source, retaining later evidence while recording every matching source.
    ///
    /// @param matches accumulated matches indexed by rule
    /// @param text source text to analyze
    /// @param source stable source identifier
    private static void addMatches(
            Map<CrashReportAnalyzer.Rule, @Unmodifiable Map<String, Object>> matches,
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
            matches.put(result.rule(), Collections.unmodifiableMap(new LinkedHashMap<>(match)));
        }
    }

    /// Applies the same specific-over-broad policy used by the desktop crash analysis.
    ///
    /// The order is deliberate: a later relation may only remove evidence that is still present, while every
    /// removed match remains available in the suppressed evidence list for diagnostics.
    private static void applySupersession(
            Map<CrashReportAnalyzer.Rule, @Unmodifiable Map<String, Object>> matches,
            List<@Unmodifiable Map<String, Object>> suppressedMatches) {
        // The raw crash-report registry has no VIRTUAL_MEMORY result ID. MEMORY_EXCEEDED is the more specific
        // native-memory rule available at this layer, so it suppresses the broad OUT_OF_MEMORY fallback.
        suppress(matches, suppressedMatches, CrashReportAnalyzer.Rule.MEMORY_EXCEEDED,
                CrashReportAnalyzer.Rule.OUT_OF_MEMORY);
        // Forge/Fabric loader-specific supersession is applied by the combined LogAnalyzer result, where the
        // dedicated FORGE_MISSING_DEPENDENCY and FABRIC_MISSING_DEPENDENCY IDs are available. This adapter only
        // receives raw crash-report rules and therefore must not guess which loader owns a generic resolution line.
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
