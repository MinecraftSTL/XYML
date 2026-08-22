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
import space.minecraftstl.xyml.game.CrashReportAnalyzer;
import space.minecraftstl.xyml.util.platform.Bits;

import java.util.List;
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
        boolean legacyEvidence = CrashReportRuleEvidence.find(
                log,
                CrashReportAnalyzer.Rule.JVM_32BIT) != null;
        boolean supplementalEvidence = INVALID_INITIAL_HEAP.matcher(log).find()
                && log.contains(VM_CREATION_FAILURE);
        if (!legacyEvidence && !supplementalEvidence) {
            return ControlFlow.CONTINUE;
        }

        Solver solver = input.javaRuntimeRepair() == null
                ? new TextSolver(
                        "game.crash.reason.log.jre_32bit",
                        "Install and select a 64-bit Java runtime, or reduce the configured heap.")
                : Solver.ofUninstallJRE(
                        input,
                        "game.crash.reason.log.jre_32bit",
                        List.of(),
                        "Install and select a 64-bit Java runtime, or reduce the configured heap.");
        results.add(new AnalyzeResult<>(
                this,
                ResultID.JRE_32BIT,
                solver));
        return ControlFlow.BREAK_OTHER;
    }
}
