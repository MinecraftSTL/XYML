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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/// Identifies a HotSpot fatal error that occurred inside the C2 optimizing compiler.
@NotNullByDefault
public final class C2CompilerAnalyzer implements Analyzer<LogAnalyzable> {
    /// Stable `hs_err` fatal-error heading.
    private static final Pattern FATAL_ERROR = Pattern.compile(
            "(?m)^# A fatal error has been detected by the Java Runtime Environment:[ \\t]*$");

    /// `hs_err` current-thread line that explicitly owns the crash to C2.
    private static final Pattern C2_THREAD = Pattern.compile(
            "(?m)^(?:# )?Current thread \\([^\\r\\n]+\\): JavaThread \\\"C2 CompilerThread\\d*\\\"[^\\r\\n]*$");

    /// `hs_err` section written only when a compiler task was active.
    private static final Pattern CURRENT_COMPILE_TASK = Pattern.compile(
            "(?m)^(?:# )?Current CompileTask:[ \\t]*$");

    /// Requires the fatal heading, current C2 thread, and active compile-task section as independent evidence.
    ///
    /// @param input immutable launch and log snapshot
    /// @param results mutable diagnosis accumulator
    /// @return `BREAK_OTHER` after a verified match, otherwise `CONTINUE`
    @Override
    public ControlFlow analyze(LogAnalyzable input, List<AnalyzeResult<LogAnalyzable>> results) {
        Matcher fatalError = FATAL_ERROR.matcher(input.logText());
        Matcher c2Thread = C2_THREAD.matcher(input.logText());
        Matcher compileTask = CURRENT_COMPILE_TASK.matcher(input.logText());
        if (!fatalError.find() || !c2Thread.find() || !compileTask.find()) {
            return ControlFlow.CONTINUE;
        }

        String fallback = "Select a different compatible Java runtime; the HotSpot C2 compiler terminated the VM.";
        Solver solver = input.javaRuntimeRepair() == null
                ? new TextSolver(
                        "game.crash.reason.log.c2_compiler",
                        List.of(),
                        fallback,
                        RepairActionDescriptor.replaceJavaRuntime(false))
                : Solver.ofUninstallJRE(
                        input,
                        "game.crash.reason.log.c2_compiler",
                        List.of(),
                        fallback);
        results.add(new AnalyzeResult<>(
                this,
                ResultID.C2_COMPILER,
                solver,
                List.of(fatalError.group(), c2Thread.group(), compileTask.group())));
        return ControlFlow.BREAK_OTHER;
    }
}
