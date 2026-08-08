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
    ControlFlow analyze(T input, List<AnalyzeResult<T>> results);

    /// Controls whether the driver evaluates analyzers registered after the current analyzer.
    @NotNullByDefault
    enum ControlFlow {
        /// Stops evaluation because a sufficiently specific cause was found.
        BREAK_OTHER,

        /// Continues evaluation because this analyzer did not establish an exclusive cause.
        CONTINUE
    }
}
