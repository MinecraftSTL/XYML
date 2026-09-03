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
import org.jetbrains.annotations.Nullable;
import space.minecraftstl.xyml.game.CrashReportAnalyzer;

import java.util.List;

/// Identifies operating-system commit failures without treating Java heap exhaustion as virtual-memory failure.
@NotNullByDefault
public final class VirtualMemoryAnalyzer implements Analyzer<LogAnalyzable> {
    /// Exact physical-or-swap evidence accepted from the broader legacy out-of-memory rule.
    private static final String OUT_OF_PHYSICAL_OR_SWAP = "The system is out of physical RAM or swap space";

    /// Reuses established memory rules while excluding ordinary `OutOfMemoryError` evidence.
    ///
    /// @param input immutable launch and log snapshot
    /// @param results mutable diagnosis accumulator
    /// @return `BREAK_OTHER` after a verified match, otherwise `CONTINUE`
    @Override
    public ControlFlow analyze(LogAnalyzable input, List<AnalyzeResult<LogAnalyzable>> results) {
        String log = input.logText();
        @Nullable CrashReportAnalyzer.Result evidence = CrashReportRuleEvidence.find(
                log,
                CrashReportAnalyzer.Rule.MEMORY_EXCEEDED,
                CrashReportAnalyzer.Rule.OUT_OF_MEMORY);
        if (evidence == null || evidence.rule() == CrashReportAnalyzer.Rule.OUT_OF_MEMORY
                && !OUT_OF_PHYSICAL_OR_SWAP.equals(evidence.matcher().group())) {
            return ControlFlow.CONTINUE;
        }

        results.add(new AnalyzeResult<>(
                this,
                ResultID.VIRTUAL_MEMORY,
                new TextSolver(
                        "game.crash.reason.log.virtual_memory",
                        "Increase the system page file or free physical memory, then launch the game again.")));
        return ControlFlow.BREAK_OTHER;
    }
}
