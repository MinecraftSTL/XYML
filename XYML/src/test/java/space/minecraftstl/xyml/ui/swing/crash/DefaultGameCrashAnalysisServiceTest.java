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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import space.minecraftstl.xyml.game.CrashReportAnalyzer;
import space.minecraftstl.xyml.game.Log;

import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies independent source analysis, stable rule merging, and unreadable latest-log handling.
@NotNullByDefault
class DefaultGameCrashAnalysisServiceTest {
    /// Temporary directory used as a deterministic instance log root.
    @TempDir
    private Path temporaryDirectory;

    /// Lets the latest-log match replace the same captured-log rule while retaining other captured rules.
    ///
    /// @throws Exception when temporary I/O or bounded asynchronous completion fails
    @Test
    void latestLogWinsDuplicateRuleAndRetainsCapturedRules() throws Exception {
        Path latestLog = temporaryDirectory.resolve("latest.log");
        String persistedMarker = "持久日志标记：游戏实例加载失败，需要检查内存配置和启动参数。".repeat(20);
        Files.write(
                latestLog,
                (persistedMarker + " java.lang.OutOfMemoryError").getBytes(Charset.forName("GB18030")));
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            DefaultGameCrashAnalysisService service = new DefaultGameCrashAnalysisService(executor);
            GameCrashAnalysis analysis = service.analyze(
                            List.of(new Log(
                                    "captured marker java.lang.OutOfMemoryError\n"
                                            + "The driver does not appear to support OpenGL")),
                            latestLog)
                    .toCompletableFuture()
                    .get(5, TimeUnit.SECONDS);

            assertEquals(2, analysis.results().size());
            CrashReportAnalyzer.Result outOfMemory = analysis.results().stream()
                    .filter(result -> result.rule() == CrashReportAnalyzer.Rule.OUT_OF_MEMORY)
                    .findFirst()
                    .orElseThrow();
            assertTrue(outOfMemory.log().contains(persistedMarker));
            assertTrue(analysis.results().stream()
                    .anyMatch(result -> result.rule() == CrashReportAnalyzer.Rule.OPENGL_NOT_SUPPORTED));
        } finally {
            executor.shutdownNow();
        }
    }

    /// Produces captured-log results normally when `logs/latest.log` does not exist.
    ///
    /// @throws Exception when bounded asynchronous completion fails
    @Test
    void unreadableLatestLogDoesNotFailCapturedAnalysis() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            DefaultGameCrashAnalysisService service = new DefaultGameCrashAnalysisService(executor);
            GameCrashAnalysis analysis = service.analyze(
                            List.of(new Log("Open J9 is not supported")),
                            temporaryDirectory.resolve("missing.log"))
                    .toCompletableFuture()
                    .get(5, TimeUnit.SECONDS);

            assertEquals(List.of(CrashReportAnalyzer.Rule.OPENJ9), analysis.results().stream()
                    .map(CrashReportAnalyzer.Result::rule)
                    .toList());
        } finally {
            executor.shutdownNow();
        }
    }

}
