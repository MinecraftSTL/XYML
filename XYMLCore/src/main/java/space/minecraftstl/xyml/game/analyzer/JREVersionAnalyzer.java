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
