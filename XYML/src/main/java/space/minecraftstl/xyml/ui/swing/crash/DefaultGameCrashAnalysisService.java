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
import space.minecraftstl.xyml.game.ExportedCrashBundle;
import space.minecraftstl.xyml.game.ExportedCrashBundleText;
import space.minecraftstl.xyml.game.analyzer.AnalyzeResult;
import space.minecraftstl.xyml.game.analyzer.LogAnalyzable;
import space.minecraftstl.xyml.game.analyzer.LogAnalyzer;
import space.minecraftstl.xyml.game.analyzer.ResultID;
import space.minecraftstl.xyml.game.analyzer.Solver;
import space.minecraftstl.xyml.launch.ProcessListener;
import space.minecraftstl.xyml.util.io.FileUtils;
import space.minecraftstl.xyml.util.platform.Bits;
import space.minecraftstl.xyml.util.platform.OperatingSystem;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
    /// Stable presentation order matching the limited analyzer registration order.
    private static final @Unmodifiable List<ResultID> LOG_RESULT_ORDER = List.of(
            ResultID.JRE_32BIT,
            ResultID.VIRTUAL_MEMORY,
            ResultID.C2_COMPILER,
            ResultID.JRE_VERSION,
            ResultID.LEGACY_JAVA_FIXER,
            ResultID.CLIENT_MOD_ON_SERVER,
            ResultID.RENDERER_MOD_COMPATIBILITY,
            ResultID.FORGE_MISSING_DEPENDENCY,
            ResultID.FABRIC_MISSING_DEPENDENCY,
            ResultID.CODE_PAGE);

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

        CompletableFuture<List<PhysicalText>> captured =
                CompletableFuture.supplyAsync(() -> capturedTexts(copiedInput), executor);
        CompletableFuture<List<PhysicalText>> persisted =
                CompletableFuture.supplyAsync(() -> latestLogTexts(copiedLatestLog), executor);
        return captured.thenCombine(persisted, (capturedTexts, latestLogTexts) -> {
            List<PhysicalText> texts = new ArrayList<>(capturedTexts.size() + latestLogTexts.size());
            texts.addAll(capturedTexts);
            texts.addAll(latestLogTexts);
            return merge(analyzeTexts(copiedInput, texts));
        });
    }

    /// Analyzes every unique text in one already validated exported crash bundle.
    ///
    /// @param bundle immutable validated crash-export contents
    /// @return asynchronous merged read-only diagnosis retaining archive entry provenance
    CompletionStage<GameCrashAnalysis> analyze(ExportedCrashBundle bundle) {
        ExportedCrashBundle copiedBundle = Objects.requireNonNull(bundle, "bundle");
        return CompletableFuture.supplyAsync(() -> {
            List<PhysicalText> texts = new ArrayList<>();
            for (ExportedCrashBundleText text : copiedBundle.texts()) {
                for (String source : text.sources()) {
                    texts.add(new PhysicalText(source, text.content()));
                }
            }
            LogAnalyzable input = new LogAnalyzable(
                    null,
                    null,
                    ProcessListener.ExitType.APPLICATION_ERROR,
                    OperatingSystem.UNKNOWN,
                    -1,
                    null,
                    null,
                    null,
                    null,
                    Bits.UNKNOWN,
                    null,
                    List.of());
            return merge(analyzeTexts(input, texts));
        }, executor);
    }

    /// Collects captured output and its referenced or embedded crash report.
    ///
    /// @param input immutable launch context and captured-output snapshot
    /// @return immutable physical text sources in stable order
    private static @Unmodifiable List<PhysicalText> capturedTexts(LogAnalyzable input) {
        String rawLog = input.logText();
        List<PhysicalText> texts = new ArrayList<>();
        texts.add(new PhysicalText("captured", rawLog));
        addCrashReport(texts, rawLog);
        return List.copyOf(texts);
    }

    /// Adds one referenced or embedded crash-report body after the source log.
    ///
    /// @param texts mutable physical text accumulator
    /// @param log source log that may contain a report marker
    private static void addCrashReport(List<PhysicalText> texts, String log) {
        @Nullable String crashReport = null;
        try {
            crashReport = CrashReportAnalyzer.findCrashReport(log);
        } catch (IOException | InvalidPathException exception) {
            LOG.warning("Failed to read crash report", exception);
        }
        if (crashReport == null) {
            crashReport = CrashReportAnalyzer.extractCrashReport(log);
        }
        if (crashReport != null) {
            texts.add(new PhysicalText("crash_report", crashReport));
        }
    }

    /// Reads and analyzes the instance's latest log when it is still available.
    ///
    /// @param latestLog on-disk `logs/latest.log` path
    /// @return immutable physical text sources, or an empty list after read failure
    private static @Unmodifiable List<PhysicalText> latestLogTexts(Path latestLog) {
        if (!Files.isReadable(latestLog)) {
            return List.of();
        }

        String log;
        try {
            log = FileUtils.readTextMaybeNativeEncoding(latestLog);
        } catch (IOException exception) {
            LOG.warning("Failed to read logs/latest.log", exception);
            return List.of();
        }
        List<PhysicalText> texts = new ArrayList<>();
        texts.add(new PhysicalText("latest_log", log));
        addCrashReport(texts, log);
        return List.copyOf(texts);
    }

    /// Analyzes unique contents once while retaining every physical source that supplied each content.
    ///
    /// @param context immutable launch context whose log lines are replaced for each unique text
    /// @param texts physical texts in stable source order
    /// @return immutable source diagnoses after content-based deduplication
    private static @Unmodifiable List<SourceAnalysis> analyzeTexts(
            LogAnalyzable context,
            List<PhysicalText> texts) {
        Map<String, LinkedHashSet<String>> sourcesByContent = new LinkedHashMap<>();
        for (PhysicalText text : texts) {
            sourcesByContent.computeIfAbsent(text.content(), ignored -> new LinkedHashSet<>()).add(text.source());
        }

        List<SourceAnalysis> analyses = new ArrayList<>();
        for (Map.Entry<String, LinkedHashSet<String>> entry : sourcesByContent.entrySet()) {
            String content = entry.getKey();
            LogAnalyzable input = context.withLogLines(List.of(content));
            analyses.add(new SourceAnalysis(
                    List.copyOf(entry.getValue()),
                    List.copyOf(CrashReportAnalyzer.analyze(content)),
                    LogAnalyzer.analyzeAll(input),
                    CrashReportAnalyzer.findKeywordsFromCrashReport(content)));
        }
        return List.copyOf(analyses);
    }

    /// Merges all unique sources with stable deduplication and removes superseded legacy diagnoses.
    ///
    /// @param sources unique-content diagnoses with physical provenance
    /// @return immutable merged diagnosis
    private static GameCrashAnalysis merge(@Unmodifiable List<SourceAnalysis> sources) {
        LinkedHashMap<CrashReportAnalyzer.Rule, CrashReportAnalyzer.Result> crashResults =
                new LinkedHashMap<>();
        LinkedHashMap<CrashReportAnalyzer.Rule, List<String>> evidenceSources =
                new LinkedHashMap<>();
        List<CrashReportAnalyzer.Result> suppressedResults = new ArrayList<>();

        Map<ResultID, AnalyzeResult<LogAnalyzable>> logResults = new LinkedHashMap<>();
        Map<ResultID, List<String>> logEvidenceSources = new LinkedHashMap<>();
        for (SourceAnalysis source : sources) {
            for (CrashReportAnalyzer.Result result : source.crashResults()) {
                for (String sourceName : source.sources()) {
                    addCrashResult(crashResults, evidenceSources, result, sourceName);
                }
            }
            for (AnalyzeResult<LogAnalyzable> result : source.logResults()) {
                for (String sourceName : source.sources()) {
                    addLogResult(logResults, logEvidenceSources, result, sourceName);
                }
            }
        }
        for (ResultID resultId : orderedLogResultIds(logResults)) {
            removeSupersededCrashRules(crashResults, suppressedResults, resultId);
        }
        if (!logResults.containsKey(ResultID.VIRTUAL_MEMORY)
                && crashResults.containsKey(CrashReportAnalyzer.Rule.MEMORY_EXCEEDED)) {
            suppress(crashResults, suppressedResults, CrashReportAnalyzer.Rule.OUT_OF_MEMORY);
        }

        Set<String> keywords = new HashSet<>();
        for (SourceAnalysis source : sources) {
            keywords.addAll(source.keywords());
        }
        return new GameCrashAnalysis(
                orderedCrashResults(crashResults),
                orderedLogResults(logResults),
                keywords,
                suppressedResults,
                evidenceSources,
                logEvidenceSources);
    }

    /// Orders established crash-report rules by their declaration order, retaining any future rule at its first-seen
    /// position after the known rules.
    ///
    /// @param crashResults deduplicated crash-report results
    /// @return stable ordered crash-report results
    private static List<CrashReportAnalyzer.Result> orderedCrashResults(
            Map<CrashReportAnalyzer.Rule, CrashReportAnalyzer.Result> crashResults) {
        List<CrashReportAnalyzer.Result> ordered = new ArrayList<>();
        Set<CrashReportAnalyzer.Rule> seen = new LinkedHashSet<>();
        for (CrashReportAnalyzer.Rule rule : CrashReportAnalyzer.Rule.values()) {
            CrashReportAnalyzer.Result result = crashResults.get(rule);
            if (result != null) {
                ordered.add(result);
                seen.add(rule);
            }
        }
        for (Map.Entry<CrashReportAnalyzer.Rule, CrashReportAnalyzer.Result> entry : crashResults.entrySet()) {
            if (seen.add(entry.getKey())) {
                ordered.add(entry.getValue());
            }
        }
        return List.copyOf(ordered);
    }

    /// Orders limited log diagnoses by the explicit analyzer registration order.
    ///
    /// @param logResults deduplicated limited diagnoses
    /// @return stable ordered limited diagnoses
    private static List<AnalyzeResult<LogAnalyzable>> orderedLogResults(
            Map<ResultID, AnalyzeResult<LogAnalyzable>> logResults) {
        List<AnalyzeResult<LogAnalyzable>> ordered = new ArrayList<>();
        Set<ResultID> seen = new LinkedHashSet<>();
        for (ResultID resultId : LOG_RESULT_ORDER) {
            AnalyzeResult<LogAnalyzable> result = logResults.get(resultId);
            if (result != null) {
                ordered.add(result);
                seen.add(resultId);
            }
        }
        for (Map.Entry<ResultID, AnalyzeResult<LogAnalyzable>> entry : logResults.entrySet()) {
            if (seen.add(entry.getKey())) {
                ordered.add(entry.getValue());
            }
        }
        return List.copyOf(ordered);
    }

    /// Returns limited diagnosis IDs in the same stable order used for presentation and supersession.
    ///
    /// @param logResults deduplicated limited diagnoses
    /// @return stable ordered diagnosis IDs
    private static List<ResultID> orderedLogResultIds(Map<ResultID, AnalyzeResult<LogAnalyzable>> logResults) {
        List<ResultID> ordered = new ArrayList<>();
        Set<ResultID> seen = new LinkedHashSet<>();
        for (ResultID resultId : LOG_RESULT_ORDER) {
            if (logResults.containsKey(resultId)) {
                ordered.add(resultId);
                seen.add(resultId);
            }
        }
        for (ResultID resultId : logResults.keySet()) {
            if (seen.add(resultId)) {
                ordered.add(resultId);
            }
        }
        return List.copyOf(ordered);
    }

    /// Adds one crash result while retaining first-seen order and all physical evidence sources.
    private static void addCrashResult(
            Map<CrashReportAnalyzer.Rule, CrashReportAnalyzer.Result> crashResults,
            Map<CrashReportAnalyzer.Rule, List<String>> evidenceSources,
            CrashReportAnalyzer.Result result,
            String source) {
        crashResults.put(result.rule(), result);
        List<String> sources = evidenceSources.computeIfAbsent(result.rule(), ignored -> new ArrayList<>());
        if (!sources.contains(source)) {
            sources.add(source);
        }
    }

    /// Adds one limited log diagnosis while retaining all physical source names and matched evidence fragments.
    ///
    /// The later source replaces ordinary diagnostic objects while matching dependency searches merge every stable ID.
    /// The linked map keeps the result at the position where it was first observed.
    private static void addLogResult(
            Map<ResultID, AnalyzeResult<LogAnalyzable>> logResults,
            Map<ResultID, List<String>> logEvidenceSources,
            AnalyzeResult<LogAnalyzable> result,
            String source) {
        AnalyzeResult<LogAnalyzable> mergedResult = result;
        @Nullable AnalyzeResult<LogAnalyzable> earlier = logResults.get(result.resultId());
        if (earlier != null) {
            Solver mergedSolver = isMissingDependencyResult(result.resultId())
                    ? Solver.mergeMissingDependencySearch(earlier.solver(), result.solver())
                    : result.solver();
            mergedResult = new AnalyzeResult<>(
                    result.analyzer(),
                    result.resultId(),
                    mergedSolver,
                    mergeEvidence(earlier.evidence(), result.evidence()));
        }
        logResults.put(result.resultId(), mergedResult);
        List<String> sources = logEvidenceSources.computeIfAbsent(result.resultId(), ignored -> new ArrayList<>());
        if (!sources.contains(source)) {
            sources.add(source);
        }
    }

    /// Merges matched evidence in physical-source order without repeating an identical fragment.
    ///
    /// @param earlier fragments captured from the first physical source
    /// @param later fragments captured from the later physical source
    /// @return immutable stable fragment union
    private static @Unmodifiable List<String> mergeEvidence(
            @Unmodifiable List<String> earlier,
            @Unmodifiable List<String> later) {
        Set<String> merged = new LinkedHashSet<>(earlier);
        merged.addAll(later);
        return List.copyOf(merged);
    }

    /// Returns whether one stable diagnosis carries a list of missing mod identifiers.
    ///
    /// @param resultId stable diagnosis identifier
    /// @return true for Forge or Fabric dependency failures
    private static boolean isMissingDependencyResult(ResultID resultId) {
        return resultId == ResultID.FORGE_MISSING_DEPENDENCY
                || resultId == ResultID.FABRIC_MISSING_DEPENDENCY
                || resultId == ResultID.RENDERER_MOD_COMPATIBILITY;
    }

    /// Removes legacy results whose evidence and repair are represented by one limited log diagnosis.
    ///
    /// @param crashResults mutable established-rule map
    /// @param resultId limited diagnosis identifier
    private static void removeSupersededCrashRules(
            Map<CrashReportAnalyzer.Rule, CrashReportAnalyzer.Result> crashResults,
            List<CrashReportAnalyzer.Result> suppressedResults,
            ResultID resultId) {
        switch (resultId) {
            case CODE_PAGE -> suppress(crashResults, suppressedResults,
                    CrashReportAnalyzer.Rule.UNSATISFIED_LINK_ERROR);
            case JRE_32BIT -> suppress(crashResults, suppressedResults,
                    CrashReportAnalyzer.Rule.JVM_32BIT);
            case JRE_VERSION -> {
                suppress(crashResults, suppressedResults, CrashReportAnalyzer.Rule.NEED_JDK11);
                suppress(crashResults, suppressedResults, CrashReportAnalyzer.Rule.TOO_OLD_JAVA);
                suppress(crashResults, suppressedResults, CrashReportAnalyzer.Rule.JDK_9);
                suppress(crashResults, suppressedResults, CrashReportAnalyzer.Rule.JAVA_VERSION_IS_TOO_HIGH);
            }
            case VIRTUAL_MEMORY -> {
                suppress(crashResults, suppressedResults, CrashReportAnalyzer.Rule.MEMORY_EXCEEDED);
                suppress(crashResults, suppressedResults, CrashReportAnalyzer.Rule.OUT_OF_MEMORY);
            }
            case FORGE_MISSING_DEPENDENCY -> suppress(crashResults, suppressedResults,
                    CrashReportAnalyzer.Rule.FORGEMOD_RESOLUTION);
            case FABRIC_MISSING_DEPENDENCY -> {
                suppress(crashResults, suppressedResults, CrashReportAnalyzer.Rule.MOD_RESOLUTION);
                suppress(crashResults, suppressedResults, CrashReportAnalyzer.Rule.MOD_RESOLUTION_MISSING);
                suppress(crashResults, suppressedResults, CrashReportAnalyzer.Rule.FABRIC_WARNINGS);
            }
            case RENDERER_MOD_COMPATIBILITY -> {
                suppress(crashResults, suppressedResults, CrashReportAnalyzer.Rule.MOD_RESOLUTION);
                suppress(crashResults, suppressedResults, CrashReportAnalyzer.Rule.MOD_RESOLUTION_CONFLICT);
                suppress(crashResults, suppressedResults, CrashReportAnalyzer.Rule.MOD_RESOLUTION_MISSING);
                suppress(crashResults, suppressedResults, CrashReportAnalyzer.Rule.FABRIC_WARNINGS);
            }
            case C2_COMPILER, CLIENT_MOD_ON_SERVER, LEGACY_JAVA_FIXER -> { }
        }
    }

    /// Moves one established rule to suppressed evidence when a more specific diagnosis owns it.
    private static void suppress(
            Map<CrashReportAnalyzer.Rule, CrashReportAnalyzer.Result> crashResults,
            List<CrashReportAnalyzer.Result> suppressedResults,
            CrashReportAnalyzer.Rule rule) {
        CrashReportAnalyzer.Result result = crashResults.remove(rule);
        if (result != null) {
            suppressedResults.add(result);
        }
    }

    /// Immutable diagnosis from one physical log source.
    ///
    /// @param crashResults established crash-report analyzer results
    /// @param logResults limited launch-log diagnoses
    /// @param keywords crash-report stack keywords
    @NotNullByDefault
    private record SourceAnalysis(
            @Unmodifiable List<String> sources,
            @Unmodifiable List<CrashReportAnalyzer.Result> crashResults,
            @Unmodifiable List<AnalyzeResult<LogAnalyzable>> logResults,
            @Unmodifiable Set<String> keywords) {
        /// Defensively copies every source result collection.
        private SourceAnalysis {
            sources = List.copyOf(Objects.requireNonNull(sources, "sources"));
            crashResults = List.copyOf(Objects.requireNonNull(crashResults, "crashResults"));
            logResults = List.copyOf(Objects.requireNonNull(logResults, "logResults"));
            keywords = Set.copyOf(Objects.requireNonNull(keywords, "keywords"));
        }

    }

    /// One physical log or crash-report text before content-based deduplication.
    @NotNullByDefault
    private record PhysicalText(String source, String content) {
        /// Validates one physical source and text snapshot.
        private PhysicalText {
            source = Objects.requireNonNull(source, "source");
            content = Objects.requireNonNull(content, "content");
        }
    }
}
