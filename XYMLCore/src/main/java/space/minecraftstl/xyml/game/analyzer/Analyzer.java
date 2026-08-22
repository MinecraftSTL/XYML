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

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static space.minecraftstl.xyml.util.logging.Logger.LOG;

/// Evaluates one immutable input and appends only high-confidence diagnoses.
///
/// @param <T> analyzable input type
@FunctionalInterface
@NotNullByDefault
public interface Analyzer<T> {
    /// Evaluates the input and appends any diagnoses owned by this analyzer.
    ///
    /// @param input immutable analysis input
    /// @param results mutable result accumulator owned by the driver
    /// @return whether later analyzers should run
    ControlFlow analyze(T input, List<AnalyzeResult<T>> results) throws Exception;

    /// Runs an ordered analyzer snapshot while isolating failures from individual analyzers.
    ///
    /// One analyzer exception is logged and does not prevent later analyzers from running. An exclusive result still
    /// stops the driver immediately, matching the HMAT control-flow contract.
    ///
    /// @param analyzers ordered analyzers to invoke
    /// @param input immutable analysis input
    /// @param <T> analyzable input type
    /// @return immutable ordered results accumulated before normal completion or an exclusive stop
    static <T> @Unmodifiable List<AnalyzeResult<T>> analyze(
            List<? extends Analyzer<T>> analyzers,
            T input) {
        @Unmodifiable List<? extends Analyzer<T>> analyzerSnapshot =
                List.copyOf(Objects.requireNonNull(analyzers, "analyzers"));
        T checkedInput = Objects.requireNonNull(input, "input");
        List<AnalyzeResult<T>> results = new ArrayList<>();
        for (Analyzer<T> analyzer : analyzerSnapshot) {
            ControlFlow controlFlow;
            try {
                controlFlow = Objects.requireNonNull(
                        analyzer.analyze(checkedInput, results),
                        "analyzer control flow");
            } catch (Exception exception) {
                LOG.warning(
                        "Cannot invoke analyzer " + analyzer.getClass().getName()
                                + " for input type " + checkedInput.getClass().getName() + ".",
                        exception);
                continue;
            }
            if (controlFlow == ControlFlow.BREAK_OTHER) {
                break;
            }
        }
        return List.copyOf(results);
    }

    /// Controls whether the driver evaluates analyzers registered after the current analyzer.
    @NotNullByDefault
    enum ControlFlow {
        /// Stops evaluation because a sufficiently specific cause was found.
        BREAK_OTHER,

        /// Continues evaluation because this analyzer did not establish an exclusive cause.
        CONTINUE
    }
}
