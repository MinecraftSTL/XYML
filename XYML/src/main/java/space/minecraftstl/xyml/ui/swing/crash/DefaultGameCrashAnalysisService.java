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
import space.minecraftstl.xyml.game.Log;
import space.minecraftstl.xyml.util.Pair;
import space.minecraftstl.xyml.util.io.FileUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

import static space.minecraftstl.xyml.util.Pair.pair;
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
    /// @param capturedLogs immutable process-output snapshot
    /// @param latestLog on-disk `logs/latest.log` path
    /// @return asynchronous merged diagnosis
    @Override
    public CompletionStage<GameCrashAnalysis> analyze(
            List<Log> capturedLogs,
            Path latestLog) {
        @Unmodifiable List<Log> copiedLogs = List.copyOf(Objects.requireNonNull(capturedLogs, "capturedLogs"));
        Path copiedLatestLog = Objects.requireNonNull(latestLog, "latestLog");

        CompletableFuture<Pair<Set<CrashReportAnalyzer.Result>, Set<String>>> captured =
                CompletableFuture.supplyAsync(() -> analyzeCapturedLogs(copiedLogs), executor);
        CompletableFuture<Pair<Set<CrashReportAnalyzer.Result>, Set<String>>> persisted =
                CompletableFuture.supplyAsync(() -> analyzeLatestLog(copiedLatestLog), executor);
        return captured.thenCombine(persisted, DefaultGameCrashAnalysisService::merge);
    }

    /// Analyzes the complete captured console output and any crash report referenced or embedded in it.
    ///
    /// @param capturedLogs immutable process-output snapshot
    /// @return detected rules and crash-report stack keywords
    private static Pair<Set<CrashReportAnalyzer.Result>, Set<String>> analyzeCapturedLogs(
            @Unmodifiable List<Log> capturedLogs) {
        String rawLog = capturedLogs.stream().map(Log::getLog).collect(Collectors.joining("\n"));
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
        return pair(CrashReportAnalyzer.analyze(rawLog), keywords);
    }

    /// Reads and analyzes the instance's latest log when it is still available.
    ///
    /// @param latestLog on-disk `logs/latest.log` path
    /// @return detected rules and stack keywords, or empty sets when the file cannot be read
    private static Pair<Set<CrashReportAnalyzer.Result>, Set<String>> analyzeLatestLog(Path latestLog) {
        if (!Files.isReadable(latestLog)) {
            return pair(Set.of(), Set.of());
        }

        String log;
        try {
            log = FileUtils.readTextMaybeNativeEncoding(latestLog);
        } catch (IOException exception) {
            LOG.warning("Failed to read logs/latest.log", exception);
            return pair(Set.of(), Set.of());
        }
        return pair(CrashReportAnalyzer.analyze(log), CrashReportAnalyzer.findKeywordsFromCrashReport(log));
    }

    /// Merges two source results with one stable entry per rule and a union of keywords.
    ///
    /// @param captured captured-output diagnosis
    /// @param persisted latest-log diagnosis
    /// @return immutable merged diagnosis
    private static GameCrashAnalysis merge(
            Pair<Set<CrashReportAnalyzer.Result>, Set<String>> captured,
            Pair<Set<CrashReportAnalyzer.Result>, Set<String>> persisted) {
        EnumMap<CrashReportAnalyzer.Rule, CrashReportAnalyzer.Result> results =
                new EnumMap<>(CrashReportAnalyzer.Rule.class);
        for (CrashReportAnalyzer.Result result : captured.getKey()) {
            results.put(result.rule(), result);
        }
        for (CrashReportAnalyzer.Result result : persisted.getKey()) {
            results.put(result.rule(), result);
        }

        Set<String> keywords = new HashSet<>(captured.getValue());
        keywords.addAll(persisted.getValue());
        return new GameCrashAnalysis(new ArrayList<>(results.values()), keywords);
    }
}
