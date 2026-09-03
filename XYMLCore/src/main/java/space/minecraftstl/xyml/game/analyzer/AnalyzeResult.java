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

import java.util.Objects;

/// Immutable diagnosis produced by one analyzer.
///
/// @param analyzer analyzer that established the cause
/// @param resultId stable cause identifier used for ordering and deduplication
/// @param solver actionable repair proposal
/// @param <T> analyzable input type
@NotNullByDefault
public record AnalyzeResult<T>(Analyzer<T> analyzer, ResultID resultId, Solver solver) {
    /// Validates every non-null diagnosis component.
    public AnalyzeResult {
        Objects.requireNonNull(analyzer, "analyzer");
        Objects.requireNonNull(resultId, "resultId");
        Objects.requireNonNull(solver, "solver");
    }
}
