// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

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
