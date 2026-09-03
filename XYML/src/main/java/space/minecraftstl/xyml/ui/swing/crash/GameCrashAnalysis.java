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
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
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

    /// Creates one merged diagnosis snapshot.
    ///
    /// @param results detected rules in declaration order
    /// @param logResults limited launch-log causes in analyzer order
    /// @param keywords stack-trace keywords for unknown crashes
    GameCrashAnalysis(
            List<CrashReportAnalyzer.Result> results,
            List<AnalyzeResult<LogAnalyzable>> logResults,
            Set<String> keywords) {
        EnumMap<CrashReportAnalyzer.Rule, CrashReportAnalyzer.Result> byRule =
                new EnumMap<>(CrashReportAnalyzer.Rule.class);
        for (CrashReportAnalyzer.Result result : Objects.requireNonNull(results, "results")) {
            byRule.put(result.rule(), result);
        }
        this.results = List.copyOf(byRule.values());

        LinkedHashMap<ResultID, AnalyzeResult<LogAnalyzable>> byResultId = new LinkedHashMap<>();
        for (AnalyzeResult<LogAnalyzable> result : Objects.requireNonNull(logResults, "logResults")) {
            byResultId.put(result.resultId(), result);
        }
        this.logResults = List.copyOf(byResultId.values());
        this.keywords = Collections.unmodifiableSet(
                new LinkedHashSet<>(new TreeSet<>(Objects.requireNonNull(keywords, "keywords"))));
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
}
