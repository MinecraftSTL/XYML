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

import java.util.List;
import java.util.regex.Pattern;

/// Identifies operating-system commit failures without treating Java heap exhaustion as virtual-memory failure.
@NotNullByDefault
public final class VirtualMemoryAnalyzer implements Analyzer<LogAnalyzable> {
    /// JVM-native allocation diagnostics that explicitly identify a failed map, commit, or reservation.
    ///
    /// The line boundary is intentional: a generic memory sentence elsewhere in the log must not become evidence.
    private static final Pattern JVM_NATIVE_MEMORY_FAILURE = Pattern.compile(
            "(?im)(?:\\bNative memory allocation\\s*\\(\\s*mmap\\s*\\)\\s+failed\\s+to\\s+(?:commit|map|reserve)\\b|"
                    + "\\bos::commit_memory\\s*\\([^\\r\\n]*\\)\\s+failed\\b)");

    /// Accepts only JVM-native allocation evidence with an explicit commit, map, or reservation failure.
    ///
    /// @param input immutable launch and log snapshot
    /// @param results mutable diagnosis accumulator
    /// @return `BREAK_OTHER` after a verified match, otherwise `CONTINUE`
    @Override
    public ControlFlow analyze(LogAnalyzable input, List<AnalyzeResult<LogAnalyzable>> results) {
        if (!JVM_NATIVE_MEMORY_FAILURE.matcher(input.logText()).find()) {
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
