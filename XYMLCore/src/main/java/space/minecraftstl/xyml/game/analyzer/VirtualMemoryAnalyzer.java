// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.game.analyzer;

import org.jetbrains.annotations.NotNullByDefault;

import java.util.List;
import java.util.regex.Matcher;
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
        Matcher evidence = JVM_NATIVE_MEMORY_FAILURE.matcher(input.logText());
        if (!evidence.find()) {
            return ControlFlow.CONTINUE;
        }

        results.add(new AnalyzeResult<>(
                this,
                ResultID.VIRTUAL_MEMORY,
                new TextSolver(
                        "game.crash.reason.log.virtual_memory",
                        "Increase the system page file or free physical memory, then launch the game again."),
                List.of(evidence.group())));
        return ControlFlow.BREAK_OTHER;
    }
}
