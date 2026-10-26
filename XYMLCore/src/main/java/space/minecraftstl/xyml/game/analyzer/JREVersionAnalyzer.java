// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.game.analyzer;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import space.minecraftstl.xyml.game.CrashReportAnalyzer;

import java.util.List;

/// Correlates selected and required Java versions with established incompatibility evidence.
@NotNullByDefault
public final class JREVersionAnalyzer implements Analyzer<LogAnalyzable> {
    /// Requires both a version mismatch and matching legacy evidence before attributing the crash.
    ///
    /// @param input immutable launch and log snapshot
    /// @param results mutable diagnosis accumulator
    /// @return `BREAK_OTHER` after a verified match, otherwise `CONTINUE`
    @Override
    public ControlFlow analyze(LogAnalyzable input, List<AnalyzeResult<LogAnalyzable>> results) {
        @Nullable Integer required = input.requiredJavaVersion();
        @Nullable Integer current = input.currentJavaVersion();
        if (required == null || current == null || required.equals(current)) {
            return ControlFlow.CONTINUE;
        }

        String log = input.logText();
        @Nullable CrashReportAnalyzer.Result evidence;
        if (current < required) {
            evidence = CrashReportRuleEvidence.find(
                    log,
                    CrashReportAnalyzer.Rule.TOO_OLD_JAVA,
                    CrashReportAnalyzer.Rule.NEED_JDK11);
        } else {
            evidence = CrashReportRuleEvidence.find(
                    log,
                    CrashReportAnalyzer.Rule.JAVA_VERSION_IS_TOO_HIGH,
                    CrashReportAnalyzer.Rule.JDK_9);
        }
        if (evidence == null) {
            return ControlFlow.CONTINUE;
        }

        Solver solver = input.javaRuntimeRepair() == null
                ? new TextSolver(
                        "game.crash.reason.log.jre_version",
                        List.of(required, current),
                        "Install or select Java " + required + " instead of Java " + current + ".",
                        RepairActionDescriptor.replaceJavaRuntime(false))
                : Solver.ofUninstallJRE(
                        input,
                        "game.crash.reason.log.jre_version",
                        List.of(required, current),
                        "Install or select Java " + required + " instead of Java " + current + ".");
        results.add(new AnalyzeResult<>(
                this,
                ResultID.JRE_VERSION,
                solver,
                List.of(evidence.matcher().group())));
        return ControlFlow.BREAK_OTHER;
    }
}
