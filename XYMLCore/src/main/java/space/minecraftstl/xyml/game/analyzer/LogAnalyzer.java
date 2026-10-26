// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.game.analyzer;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import space.minecraftstl.xyml.game.Log;
import space.minecraftstl.xyml.launch.ProcessListener;
import space.minecraftstl.xyml.util.platform.Bits;
import space.minecraftstl.xyml.util.platform.OperatingSystem;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/// Pure entry point for the ordered, limited launch-log analyzer registry.
@NotNullByDefault
public final class LogAnalyzer {
    /// Prevents construction of this static entry-point class.
    private LogAnalyzer() {
    }

    /// Analyzes a standalone immutable game-log snapshot without inventing unavailable launch context.
    ///
    /// Context-dependent analyzers such as code-page, Java-bitness, and Java-version checks remain conservative because
    /// this convenience entry has no game path or selected-runtime metadata. Call [#analyze(LogAnalyzable)] when that
    /// context is available.
    ///
    /// @param logs game-log entries in source order
    /// @return immutable ordered diagnoses with at most one result per ID
    public static @Unmodifiable List<AnalyzeResult<LogAnalyzable>> analyze(List<Log> logs) {
        @Unmodifiable List<Log> logSnapshot = List.copyOf(Objects.requireNonNull(logs, "logs"));
        return analyze(new LogAnalyzable(
                null,
                null,
                ProcessListener.ExitType.APPLICATION_ERROR,
                OperatingSystem.CURRENT_OS,
                OperatingSystem.CODE_PAGE,
                null,
                null,
                null,
                null,
                Bits.UNKNOWN,
                null,
                logSnapshot.stream().map(Log::getLog).toList()));
    }

    /// Runs registered analyzers until one requests an exclusive stop, then deduplicates by result ID.
    ///
    /// Normal and launcher-interrupted exits intentionally produce no diagnosis even if retained output contains an
    /// error-like line from an earlier recoverable operation.
    ///
    /// @param input immutable launch and log snapshot
    /// @return immutable ordered diagnoses with at most one result per ID
    public static @Unmodifiable List<AnalyzeResult<LogAnalyzable>> analyze(LogAnalyzable input) {
        return analyzeInternal(input, false);
    }

    /// Runs every registered analyzer and returns all independently established causes in registration order.
    ///
    /// Unlike [#analyze(LogAnalyzable)], this method deliberately ignores `BREAK_OTHER` so a crash window can expose
    /// multiple repair rows. A result ID is retained only once, at the position of its first established match.
    ///
    /// @param input immutable launch and log snapshot
    /// @return immutable ordered diagnoses with at most one result per ID
    public static @Unmodifiable List<AnalyzeResult<LogAnalyzable>> analyzeAll(LogAnalyzable input) {
        return analyzeInternal(input, true);
    }

    /// Executes one of the two analyzer control-flow modes.
    ///
    /// @param input immutable launch and log snapshot
    /// @param collectAll whether later analyzers must run after `BREAK_OTHER`
    /// @return immutable ordered diagnoses
    private static @Unmodifiable List<AnalyzeResult<LogAnalyzable>> analyzeInternal(
            LogAnalyzable input,
            boolean collectAll) {
        Objects.requireNonNull(input, "input");
        if (input.exitType() == ProcessListener.ExitType.NORMAL
                || input.exitType() == ProcessListener.ExitType.INTERRUPTED) {
            return List.of();
        }

        @Unmodifiable List<AnalyzeResult<LogAnalyzable>> collected =
                collectAll
                        ? Analyzer.analyzeAll(AnalyzableType.LOG.logAnalyzers(), input)
                        : Analyzer.analyze(AnalyzableType.LOG.logAnalyzers(), input);

        Map<ResultID, AnalyzeResult<LogAnalyzable>> uniqueResults = new LinkedHashMap<>();
        for (AnalyzeResult<LogAnalyzable> result : collected) {
            uniqueResults.putIfAbsent(result.resultId(), result);
        }
        return List.copyOf(uniqueResults.values());
    }
}
