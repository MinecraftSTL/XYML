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

import java.util.List;
import java.util.Objects;

/// Immutable diagnosis produced by one analyzer.
///
/// @param analyzer analyzer that established the cause
/// @param resultId stable cause identifier used for ordering and deduplication
/// @param solver actionable repair proposal
/// @param evidence exact input fragments that established the cause, in stable encounter order
/// @param <T> analyzable input type
@NotNullByDefault
public record AnalyzeResult<T>(
        Analyzer<T> analyzer,
        ResultID resultId,
        Solver solver,
        @Unmodifiable List<String> evidence) {
    /// Validates every non-null diagnosis component.
    public AnalyzeResult {
        Objects.requireNonNull(analyzer, "analyzer");
        Objects.requireNonNull(resultId, "resultId");
        Objects.requireNonNull(solver, "solver");
        evidence = List.copyOf(Objects.requireNonNull(evidence, "evidence"));
    }

    /// Creates a diagnosis for a compatibility caller that does not yet provide evidence fragments.
    ///
    /// @param analyzer analyzer that established the cause
    /// @param resultId stable cause identifier used for ordering and deduplication
    /// @param solver actionable repair proposal
    public AnalyzeResult(Analyzer<T> analyzer, ResultID resultId, Solver solver) {
        this(analyzer, resultId, solver, List.of());
    }
}
