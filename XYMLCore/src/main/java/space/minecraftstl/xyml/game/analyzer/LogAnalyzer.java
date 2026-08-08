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
package space.minecraftstl.xyml.game.analyzer;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import space.minecraftstl.xyml.launch.ProcessListener;

import java.util.ArrayList;
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

    /// Runs every registered analyzer until one requests an exclusive stop, then deduplicates by result ID.
    ///
    /// Normal and launcher-interrupted exits intentionally produce no diagnosis even if retained output contains an
    /// error-like line from an earlier recoverable operation.
    ///
    /// @param input immutable launch and log snapshot
    /// @return immutable ordered diagnoses with at most one result per ID
    public static @Unmodifiable List<AnalyzeResult<LogAnalyzable>> analyze(LogAnalyzable input) {
        Objects.requireNonNull(input, "input");
        if (input.exitType() == ProcessListener.ExitType.NORMAL
                || input.exitType() == ProcessListener.ExitType.INTERRUPTED) {
            return List.of();
        }

        List<AnalyzeResult<LogAnalyzable>> collected = new ArrayList<>();
        for (Analyzer<LogAnalyzable> analyzer : AnalyzableType.LOG.logAnalyzers()) {
            Analyzer.ControlFlow controlFlow = analyzer.analyze(input, collected);
            if (controlFlow == Analyzer.ControlFlow.BREAK_OTHER) {
                break;
            }
        }

        Map<ResultID, AnalyzeResult<LogAnalyzable>> uniqueResults = new LinkedHashMap<>();
        for (AnalyzeResult<LogAnalyzable> result : collected) {
            uniqueResults.putIfAbsent(result.resultId(), result);
        }
        return List.copyOf(uniqueResults.values());
    }
}
