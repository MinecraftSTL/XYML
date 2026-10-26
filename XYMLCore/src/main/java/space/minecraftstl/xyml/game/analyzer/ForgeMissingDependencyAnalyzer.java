// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.game.analyzer;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import space.minecraftstl.xyml.game.CrashReportAnalyzer;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/// Identifies Forge's explicit missing-mod dependency report.
@NotNullByDefault
public final class ForgeMissingDependencyAnalyzer implements Analyzer<LogAnalyzable> {
    /// Forge's stable heading for dependency entries.
    private static final String DEPENDENCY_HEADING = "Missing or unsupported mandatory dependencies:";

    /// Modern Forge dependency entry whose actual version is explicitly absent.
    private static final Pattern MISSING_DEPENDENCY = Pattern.compile(
            "(?m)^[ \\t]*Mod ID:\\s*'(?<dependency>[A-Za-z0-9][A-Za-z0-9_.-]*)'\\s*,\\s*"
                    + "Requested by:\\s*'(?<requester>[A-Za-z0-9][A-Za-z0-9_.-]*)'\\s*,\\s*"
                    + "Expected range:\\s*'[^'\\r\\n]*'\\s*,\\s*"
                    + "Actual version:\\s*'\\[MISSING\\]'[ \\t]*$");

    /// Produces a diagnosis only from an established Forge dependency result and explicit missing entries.
    ///
    /// @param input immutable launch and log snapshot
    /// @param results mutable diagnosis accumulator
    /// @return `BREAK_OTHER` after a verified match, otherwise `CONTINUE`
    @Override
    public ControlFlow analyze(LogAnalyzable input, List<AnalyzeResult<LogAnalyzable>> results) {
        String log = input.logText();
        if (!log.contains(DEPENDENCY_HEADING)) {
            return ControlFlow.CONTINUE;
        }

        @Nullable CrashReportAnalyzer.Result evidence = CrashReportRuleEvidence.find(
                log,
                CrashReportAnalyzer.Rule.FORGEMOD_RESOLUTION);
        if (evidence == null) {
            return ControlFlow.CONTINUE;
        }

        Set<String> dependencies = new LinkedHashSet<>();
        Set<String> dependencyIds = new LinkedHashSet<>();
        Set<String> evidenceFragments = new LinkedHashSet<>();
        Matcher matcher = MISSING_DEPENDENCY.matcher(evidence.matcher().group("reason"));
        while (matcher.find()) {
            dependencyIds.add(matcher.group("dependency"));
            dependencies.add(formatDependency(matcher));
            evidenceFragments.add(matcher.group());
        }
        if (dependencies.isEmpty()) {
            return ControlFlow.CONTINUE;
        }

        String summary = String.join(", ", dependencies);
        results.add(new AnalyzeResult<>(
                this,
                ResultID.FORGE_MISSING_DEPENDENCY,
                Solver.ofMissingDependencySearch(
                        input,
                        List.copyOf(dependencyIds),
                        "game.crash.reason.log.forge_missing_dependency",
                        List.of(summary),
                        "Forge reported missing required mod dependencies: " + summary),
                List.copyOf(evidenceFragments)));
        return ControlFlow.BREAK_OTHER;
    }

    /// Formats one validated dependency entry for localized display.
    ///
    /// @param matcher matched Forge dependency entry
    /// @return safe dependency summary
    private static String formatDependency(Matcher matcher) {
        return matcher.group("dependency") + " (required by " + matcher.group("requester") + ")";
    }
}
