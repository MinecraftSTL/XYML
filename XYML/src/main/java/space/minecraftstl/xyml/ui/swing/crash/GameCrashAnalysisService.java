// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.crash;

import org.jetbrains.annotations.NotNullByDefault;
import space.minecraftstl.xyml.game.analyzer.LogAnalyzable;

import java.nio.file.Path;
import java.util.concurrent.CompletionStage;

/// Analyzes both in-memory game output and the instance's latest on-disk log without UI dependencies.
@NotNullByDefault
interface GameCrashAnalysisService {
    /// Starts both analysis sources and merges their rule and keyword results.
    ///
    /// @param logAnalyzable immutable launch context and captured-output snapshot
    /// @param latestLog on-disk `logs/latest.log` path
    /// @return asynchronous merged diagnosis
    CompletionStage<GameCrashAnalysis> analyze(
            LogAnalyzable logAnalyzable,
            Path latestLog);
}
