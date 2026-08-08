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
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import space.minecraftstl.xyml.game.CrashReportAnalyzer;
import space.minecraftstl.xyml.game.analyzer.AnalyzeResult;
import space.minecraftstl.xyml.game.analyzer.LogAnalyzable;
import space.minecraftstl.xyml.game.analyzer.LogAnalyzer;
import space.minecraftstl.xyml.game.analyzer.ResultID;
import space.minecraftstl.xyml.util.io.FileUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;

import static space.minecraftstl.xyml.util.logging.Logger.LOG;

/// Production crash analysis that evaluates captured and persisted logs concurrently.
@NotNullByDefault
final class DefaultGameCrashAnalysisService implements GameCrashAnalysisService {
    /// Executor used for filesystem reads and regular-expression analysis.
    private final Executor executor;

    /// Creates a production analysis service.
    ///
    /// @param executor executor with enough capacity for both independent sources
    DefaultGameCrashAnalysisService(Executor executor) {
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    /// Starts independent captured-log and latest-log analysis tasks before merging their results.
    ///
    /// The latest-log result intentionally wins when both sources match the same rule, preserving the old window's
    /// source order while keeping the final display ordered by rule declaration.
    ///
    /// @param logAnalyzable immutable launch context and captured-output snapshot
    /// @param latestLog on-disk `logs/latest.log` path
    /// @return asynchronous merged diagnosis
    @Override
    public CompletionStage<GameCrashAnalysis> analyze(
            LogAnalyzable logAnalyzable,
            Path latestLog) {
        LogAnalyzable copiedInput = Objects.requireNonNull(logAnalyzable, "logAnalyzable");
        Path copiedLatestLog = Objects.requireNonNull(latestLog, "latestLog");

        CompletableFuture<SourceAnalysis> captured =
                CompletableFuture.supplyAsync(() -> analyzeCapturedLogs(copiedInput), executor);
        CompletableFuture<SourceAnalysis> persisted =
                CompletableFuture.supplyAsync(() -> analyzeLatestLog(copiedInput, copiedLatestLog), executor);
        return captured.thenCombine(persisted, DefaultGameCrashAnalysisService::merge);
    }

    /// Analyzes the complete captured console output and any crash report referenced or embedded in it.
    ///
    /// @param input immutable launch context and captured-output snapshot
    /// @return established rules, limited log diagnoses, and crash-report stack keywords
    private static SourceAnalysis analyzeCapturedLogs(LogAnalyzable input) {
        String rawLog = input.logText();
        @Nullable String crashReport = null;
        try {
            crashReport = CrashReportAnalyzer.findCrashReport(rawLog);
        } catch (IOException | InvalidPathException exception) {
            LOG.warning("Failed to read crash report", exception);
        }
        if (crashReport == null) {
            crashReport = CrashReportAnalyzer.extractCrashReport(rawLog);
        }

        Set<String> keywords = crashReport == null
                ? Set.of()
                : CrashReportAnalyzer.findKeywordsFromCrashReport(crashReport);
        return new SourceAnalysis(
                CrashReportAnalyzer.analyze(rawLog),
                LogAnalyzer.analyze(input),
                keywords);
    }

    /// Reads and analyzes the instance's latest log when it is still available.
    ///
    /// @param context immutable launch context whose log lines will be replaced
    /// @param latestLog on-disk `logs/latest.log` path
    /// @return established rules, limited log diagnoses, and stack keywords, or an empty result after read failure
    private static SourceAnalysis analyzeLatestLog(LogAnalyzable context, Path latestLog) {
        if (!Files.isReadable(latestLog)) {
            return SourceAnalysis.empty();
        }

        String log;
        try {
            log = FileUtils.readTextMaybeNativeEncoding(latestLog);
        } catch (IOException exception) {
            LOG.warning("Failed to read logs/latest.log", exception);
            return SourceAnalysis.empty();
        }
        LogAnalyzable persistedInput = context.withLogLines(List.of(log));
        return new SourceAnalysis(
                CrashReportAnalyzer.analyze(log),
                LogAnalyzer.analyze(persistedInput),
                CrashReportAnalyzer.findKeywordsFromCrashReport(log));
    }

    /// Merges both sources with stable deduplication and removes established rules superseded by limited diagnoses.
    ///
    /// @param captured captured-output diagnosis
    /// @param persisted latest-log diagnosis
    /// @return immutable merged diagnosis
    private static GameCrashAnalysis merge(
            SourceAnalysis captured,
            SourceAnalysis persisted) {
        EnumMap<CrashReportAnalyzer.Rule, CrashReportAnalyzer.Result> crashResults =
                new EnumMap<>(CrashReportAnalyzer.Rule.class);
        for (CrashReportAnalyzer.Result result : captured.crashResults()) {
            crashResults.put(result.rule(), result);
        }
        for (CrashReportAnalyzer.Result result : persisted.crashResults()) {
            crashResults.put(result.rule(), result);
        }

        Map<ResultID, AnalyzeResult<LogAnalyzable>> logResults = new LinkedHashMap<>();
        for (AnalyzeResult<LogAnalyzable> result : captured.logResults()) {
            logResults.put(result.resultId(), result);
        }
        for (AnalyzeResult<LogAnalyzable> result : persisted.logResults()) {
            logResults.put(result.resultId(), result);
        }
        for (ResultID resultId : logResults.keySet()) {
            removeSupersededCrashRules(crashResults, resultId);
        }

        Set<String> keywords = new HashSet<>(captured.keywords());
        keywords.addAll(persisted.keywords());
        return new GameCrashAnalysis(
                new ArrayList<>(crashResults.values()),
                new ArrayList<>(logResults.values()),
                keywords);
    }

    /// Removes legacy results whose evidence and repair are represented by one limited log diagnosis.
    ///
    /// @param crashResults mutable established-rule map
    /// @param resultId limited diagnosis identifier
    private static void removeSupersededCrashRules(
            Map<CrashReportAnalyzer.Rule, CrashReportAnalyzer.Result> crashResults,
            ResultID resultId) {
        switch (resultId) {
            case CODE_PAGE -> crashResults.remove(CrashReportAnalyzer.Rule.UNSATISFIED_LINK_ERROR);
            case JRE_32BIT -> crashResults.remove(CrashReportAnalyzer.Rule.JVM_32BIT);
            case JRE_VERSION -> {
                crashResults.remove(CrashReportAnalyzer.Rule.NEED_JDK11);
                crashResults.remove(CrashReportAnalyzer.Rule.TOO_OLD_JAVA);
                crashResults.remove(CrashReportAnalyzer.Rule.JDK_9);
                crashResults.remove(CrashReportAnalyzer.Rule.JAVA_VERSION_IS_TOO_HIGH);
            }
            case VIRTUAL_MEMORY -> {
                crashResults.remove(CrashReportAnalyzer.Rule.MEMORY_EXCEEDED);
                crashResults.remove(CrashReportAnalyzer.Rule.OUT_OF_MEMORY);
            }
        }
    }

    /// Immutable diagnosis from one physical log source.
    ///
    /// @param crashResults established crash-report analyzer results
    /// @param logResults limited launch-log diagnoses
    /// @param keywords crash-report stack keywords
    @NotNullByDefault
    private record SourceAnalysis(
            @Unmodifiable Set<CrashReportAnalyzer.Result> crashResults,
            @Unmodifiable List<AnalyzeResult<LogAnalyzable>> logResults,
            @Unmodifiable Set<String> keywords) {
        /// Defensively copies every source result collection.
        private SourceAnalysis {
            crashResults = Set.copyOf(Objects.requireNonNull(crashResults, "crashResults"));
            logResults = List.copyOf(Objects.requireNonNull(logResults, "logResults"));
            keywords = Set.copyOf(Objects.requireNonNull(keywords, "keywords"));
        }

        /// Returns an immutable diagnosis containing no evidence.
        ///
        /// @return empty source diagnosis
        private static SourceAnalysis empty() {
            return new SourceAnalysis(Set.of(), List.of(), Set.of());
        }
    }
}
