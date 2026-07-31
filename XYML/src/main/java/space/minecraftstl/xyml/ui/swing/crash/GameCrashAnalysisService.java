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
package space.minecraftstl.xyml.ui.swing.crash;

import org.jetbrains.annotations.NotNullByDefault;
import space.minecraftstl.xyml.game.Log;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletionStage;

/// Analyzes both in-memory game output and the instance's latest on-disk log without UI dependencies.
@NotNullByDefault
interface GameCrashAnalysisService {
    /// Starts both analysis sources and merges their rule and keyword results.
    ///
    /// @param capturedLogs immutable process-output snapshot
    /// @param latestLog on-disk `logs/latest.log` path
    /// @return asynchronous merged diagnosis
    CompletionStage<GameCrashAnalysis> analyze(
            List<Log> capturedLogs,
            Path latestLog);
}
