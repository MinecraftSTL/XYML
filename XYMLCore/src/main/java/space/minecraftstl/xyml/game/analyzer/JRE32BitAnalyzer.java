// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.game.analyzer;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import space.minecraftstl.xyml.game.CrashReportAnalyzer;
import space.minecraftstl.xyml.util.platform.Bits;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/// Identifies heap reservation failures that are verified to come from a 32-bit Java runtime.
@NotNullByDefault
public final class JRE32BitAnalyzer implements Analyzer<LogAnalyzable> {
    /// Exact JVM initialization variant missing from the legacy `JVM_32BIT` rule.
    private static final Pattern INVALID_INITIAL_HEAP = Pattern.compile(
            "(?m)^Invalid initial heap size: -Xm[sn]\\S+$");

    /// Requires the fatal VM-creation footer before accepting the supplemental variant.
    private static final String VM_CREATION_FAILURE = "Could not create the Java Virtual Machine.";

    /// Reuses the established 32-bit rule and accepts one narrowly bounded missing variant.
    ///
    /// @param input immutable launch and log snapshot
    /// @param results mutable diagnosis accumulator
    /// @return `BREAK_OTHER` after a verified match, otherwise `CONTINUE`
    @Override
    public ControlFlow analyze(LogAnalyzable input, List<AnalyzeResult<LogAnalyzable>> results) {
        if (input.javaBits() != Bits.BIT_32) {
            return ControlFlow.CONTINUE;
        }

        String log = input.logText();
        @Nullable CrashReportAnalyzer.Result legacyEvidence = CrashReportRuleEvidence.find(
                log,
                CrashReportAnalyzer.Rule.JVM_32BIT);
        Matcher supplementalEvidence = INVALID_INITIAL_HEAP.matcher(log);
        boolean hasSupplementalEvidence = supplementalEvidence.find() && log.contains(VM_CREATION_FAILURE);
        if (legacyEvidence == null && !hasSupplementalEvidence) {
            return ControlFlow.CONTINUE;
        }
        @Unmodifiable List<String> evidence = legacyEvidence == null
                ? List.of(supplementalEvidence.group(), VM_CREATION_FAILURE)
                : List.of(legacyEvidence.matcher().group());

        Solver solver = input.javaRuntimeRepair() == null
                ? new TextSolver(
                        "game.crash.reason.log.jre_32bit",
                        List.of(),
                        "Install and select a 64-bit Java runtime, or reduce the configured heap.",
                        RepairActionDescriptor.replaceJavaRuntime(false))
                : Solver.ofUninstallJRE(
                        input,
                        "game.crash.reason.log.jre_32bit",
                        List.of(),
                        "Install and select a 64-bit Java runtime, or reduce the configured heap.");
        results.add(new AnalyzeResult<>(
                this,
                ResultID.JRE_32BIT,
                solver,
                evidence));
        return ControlFlow.BREAK_OTHER;
    }
}
