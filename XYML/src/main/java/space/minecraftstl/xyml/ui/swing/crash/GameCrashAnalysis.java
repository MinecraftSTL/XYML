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
package space.minecraftstl.xyml.ui.swing.crash;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import space.minecraftstl.xyml.game.CrashReportAnalyzer;
import space.minecraftstl.xyml.game.analyzer.AnalyzeResult;
import space.minecraftstl.xyml.game.analyzer.LogAnalyzable;
import space.minecraftstl.xyml.game.analyzer.ResultID;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/// Immutable merged diagnosis produced from captured output and `logs/latest.log`.
@NotNullByDefault
final class GameCrashAnalysis {
    /// Detected rules in declaration order with at most one result per rule.
    private final @Unmodifiable List<CrashReportAnalyzer.Result> results;

    /// Detected limited log causes in analyzer order with at most one result per ID.
    private final @Unmodifiable List<AnalyzeResult<LogAnalyzable>> logResults;

    /// Sorted immutable stack-trace keywords used when no rule matches.
    private final @Unmodifiable Set<String> keywords;

    /// Established rules hidden by a more specific diagnosis, retained for evidence inspection.
    private final @Unmodifiable List<CrashReportAnalyzer.Result> suppressedResults;

    /// Physical sources that established each crash rule, in stable source order.
    private final @Unmodifiable Map<CrashReportAnalyzer.Rule, @Unmodifiable List<String>> evidenceSources;

    /// Physical sources that established each limited log diagnosis, in stable source order.
    private final @Unmodifiable Map<ResultID, @Unmodifiable List<String>> logEvidenceSources;

    /// Runtime candidates captured on the analysis worker for each limited diagnosis.
    private final @Unmodifiable Map<ResultID,
            @Unmodifiable List<LogAnalyzable.JavaRuntimeCandidate>> runtimeCandidates;

    /// Creates one merged diagnosis snapshot.
    ///
    /// @param results detected rules in declaration order
    /// @param logResults limited launch-log causes in analyzer order
    /// @param keywords stack-trace keywords for unknown crashes
    GameCrashAnalysis(
            List<CrashReportAnalyzer.Result> results,
            List<AnalyzeResult<LogAnalyzable>> logResults,
            Set<String> keywords) {
        this(results, logResults, keywords, List.of(), Map.of(), Map.of());
    }

    /// Creates a merged diagnosis with explicit suppressed evidence and source provenance.
    ///
    /// @param results displayed crash-report rules in stable order
    /// @param logResults displayed launch-log diagnoses in analyzer order
    /// @param keywords stack-trace keywords for unknown crashes
    /// @param suppressedResults rules hidden by a more specific diagnosis
    /// @param evidenceSources physical source names for each displayed rule
    GameCrashAnalysis(
            List<CrashReportAnalyzer.Result> results,
            List<AnalyzeResult<LogAnalyzable>> logResults,
            Set<String> keywords,
            List<CrashReportAnalyzer.Result> suppressedResults,
            Map<CrashReportAnalyzer.Rule, ? extends List<String>> evidenceSources) {
        this(results, logResults, keywords, suppressedResults, evidenceSources, Map.of());
    }

    /// Creates a merged diagnosis with rule and log-source provenance.
    ///
    /// @param results displayed crash-report rules in stable order
    /// @param logResults displayed launch-log diagnoses in analyzer order
    /// @param keywords stack-trace keywords for unknown crashes
    /// @param suppressedResults rules hidden by a more specific diagnosis
    /// @param evidenceSources physical source names for each displayed rule
    /// @param logEvidenceSources physical source names for each displayed log diagnosis
    GameCrashAnalysis(
            List<CrashReportAnalyzer.Result> results,
            List<AnalyzeResult<LogAnalyzable>> logResults,
            Set<String> keywords,
            List<CrashReportAnalyzer.Result> suppressedResults,
            Map<CrashReportAnalyzer.Rule, ? extends List<String>> evidenceSources,
            Map<ResultID, ? extends List<String>> logEvidenceSources) {
        LinkedHashMap<CrashReportAnalyzer.Rule, CrashReportAnalyzer.Result> byRule = new LinkedHashMap<>();
        for (CrashReportAnalyzer.Result result : Objects.requireNonNull(results, "results")) {
            // Keep the first encounter position while allowing the later physical source to
            // supply the authoritative solver/evidence snapshot.
            byRule.put(result.rule(), result);
        }
        this.results = List.copyOf(byRule.values());

        LinkedHashMap<ResultID, AnalyzeResult<LogAnalyzable>> byResultId = new LinkedHashMap<>();
        for (AnalyzeResult<LogAnalyzable> result : Objects.requireNonNull(logResults, "logResults")) {
            // LinkedHashMap.put replaces the value without moving its original insertion slot.
            byResultId.put(result.resultId(), result);
        }
        this.logResults = List.copyOf(byResultId.values());
        this.keywords = Collections.unmodifiableSet(
                new LinkedHashSet<>(new TreeSet<>(Objects.requireNonNull(keywords, "keywords"))));
        this.suppressedResults = List.copyOf(Objects.requireNonNull(suppressedResults, "suppressedResults"));
        LinkedHashMap<CrashReportAnalyzer.Rule, @Unmodifiable List<String>> copiedSources = new LinkedHashMap<>();
        for (Map.Entry<CrashReportAnalyzer.Rule, ? extends List<String>> entry
                : Objects.requireNonNull(evidenceSources, "evidenceSources").entrySet()) {
            copiedSources.put(entry.getKey(), List.copyOf(entry.getValue()));
        }
        this.evidenceSources = Collections.unmodifiableMap(copiedSources);
        LinkedHashMap<ResultID, @Unmodifiable List<String>> copiedLogSources = new LinkedHashMap<>();
        for (Map.Entry<ResultID, ? extends List<String>> entry
                : Objects.requireNonNull(logEvidenceSources, "logEvidenceSources").entrySet()) {
            copiedLogSources.put(entry.getKey(), List.copyOf(entry.getValue()));
        }
        this.logEvidenceSources = Collections.unmodifiableMap(copiedLogSources);
        LinkedHashMap<ResultID, @Unmodifiable List<LogAnalyzable.JavaRuntimeCandidate>> copiedCandidates =
                new LinkedHashMap<>();
        for (AnalyzeResult<LogAnalyzable> result : this.logResults) {
            copiedCandidates.put(result.resultId(), List.copyOf(result.solver().candidates()));
        }
        this.runtimeCandidates = Collections.unmodifiableMap(copiedCandidates);
    }

    /// Creates a legacy-only diagnosis snapshot.
    ///
    /// @param results detected crash-report rules in declaration order
    /// @param keywords stack-trace keywords for unknown crashes
    GameCrashAnalysis(
            List<CrashReportAnalyzer.Result> results,
            Set<String> keywords) {
        this(results, List.of(), keywords);
    }

    /// Returns detected rules in stable declaration order.
    ///
    /// @return immutable detected-rule snapshot
    @Unmodifiable List<CrashReportAnalyzer.Result> results() {
        return results;
    }

    /// Returns limited launch-log diagnoses in stable analyzer order.
    ///
    /// @return immutable limited log-diagnosis snapshot
    @Unmodifiable List<AnalyzeResult<LogAnalyzable>> logResults() {
        return logResults;
    }

    /// Returns the combined number of established and limited diagnoses.
    ///
    /// @return combined diagnosis count
    int resultCount() {
        return results.size() + logResults.size();
    }

    /// Returns immutable stack-trace keywords.
    ///
    /// @return immutable keyword snapshot
    @Unmodifiable Set<String> keywords() {
        return keywords;
    }

    /// Returns crash-report rules suppressed by explicit supersession policy.
    ///
    /// @return immutable suppressed-evidence snapshot
    @Unmodifiable List<CrashReportAnalyzer.Result> suppressedResults() {
        return suppressedResults;
    }

    /// Returns physical source names for each displayed crash rule.
    ///
    /// @return immutable rule-to-source snapshot
    @Unmodifiable Map<CrashReportAnalyzer.Rule, @Unmodifiable List<String>> evidenceSources() {
        return evidenceSources;
    }

    /// Returns physical source names for each displayed limited log diagnosis.
    ///
    /// @return immutable result-to-source snapshot
    @Unmodifiable Map<ResultID, @Unmodifiable List<String>> logEvidenceSources() {
        return logEvidenceSources;
    }

    /// Returns the Java-runtime candidates captured for one diagnosis.
    ///
    /// @param resultId stable diagnosis identifier
    /// @return immutable candidate list, or an empty list when this diagnosis has no internal choice
    @Unmodifiable List<LogAnalyzable.JavaRuntimeCandidate> runtimeCandidates(ResultID resultId) {
        return runtimeCandidates.getOrDefault(Objects.requireNonNull(resultId, "resultId"), List.of());
    }
}
