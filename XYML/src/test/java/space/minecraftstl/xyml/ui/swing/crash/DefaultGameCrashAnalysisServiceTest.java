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
import space.minecraftstl.xyml.game.analyzer.LogAnalyzable;
import space.minecraftstl.xyml.game.analyzer.ResultID;
import space.minecraftstl.xyml.launch.ProcessListener;
import space.minecraftstl.xyml.util.platform.Bits;
import space.minecraftstl.xyml.util.platform.OperatingSystem;

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
                            input(List.of(new Log(
                                    "captured marker java.lang.OutOfMemoryError\n"
                                            + "The driver does not appear to support OpenGL")), Bits.BIT_64),
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
                            input(List.of(new Log("Open J9 is not supported")), Bits.BIT_64),
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

    /// Deduplicates the same limited diagnosis from both sources and replaces its established 32-bit rule.
    ///
    /// @throws Exception when bounded asynchronous completion fails
    @Test
    void limitedLogDiagnosisReplacesEquivalentCrashRule() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            DefaultGameCrashAnalysisService service = new DefaultGameCrashAnalysisService(executor);
            String failure = "Error occurred during initialization of VM\n"
                    + "Could not reserve enough space for 3571712KB object heap";
            Path latestLog = temporaryDirectory.resolve("latest-32-bit.log");
            Files.writeString(latestLog, failure);
            GameCrashAnalysis analysis = service.analyze(
                            input(List.of(new Log(failure)), Bits.BIT_32),
                            latestLog)
                    .toCompletableFuture()
                    .get(5, TimeUnit.SECONDS);

            assertEquals(1, analysis.resultCount());
            assertTrue(analysis.results().stream()
                    .noneMatch(result -> result.rule() == CrashReportAnalyzer.Rule.JVM_32BIT));
            assertEquals(List.of(ResultID.JRE_32BIT), analysis.logResults().stream()
                    .map(result -> result.resultId())
                    .toList());
        } finally {
            executor.shutdownNow();
        }
    }

    /// Replaces Forge's broad dependency rule with the structured missing-dependency diagnosis.
    ///
    /// @throws Exception when temporary I/O or bounded asynchronous completion fails
    @Test
    void forgeDependencyDiagnosisReplacesLegacyRule() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            String failure = "Missing or unsupported mandatory dependencies:\n"
                    + "\tMod ID: 'vampirism', Requested by: 'werewolves', Expected range: '[1.9.0-beta.1,)', "
                    + "Actual version: '[MISSING]'";
            Path latestLog = temporaryDirectory.resolve("latest-forge-dependency.log");
            Files.writeString(latestLog, failure);
            GameCrashAnalysis analysis = new DefaultGameCrashAnalysisService(executor)
                    .analyze(input(List.of(new Log(failure)), Bits.BIT_64), latestLog)
                    .toCompletableFuture()
                    .get(5, TimeUnit.SECONDS);

            assertEquals(1, analysis.resultCount());
            assertTrue(analysis.results().stream()
                    .noneMatch(result -> result.rule() == CrashReportAnalyzer.Rule.FORGEMOD_RESOLUTION));
            assertEquals(List.of(ResultID.FORGE_MISSING_DEPENDENCY), analysis.logResults().stream()
                    .map(result -> result.resultId())
                    .toList());
        } finally {
            executor.shutdownNow();
        }
    }

    /// Replaces Fabric's broad dependency rule with the structured missing-dependency diagnosis.
    ///
    /// @throws Exception when temporary I/O or bounded asynchronous completion fails
    @Test
    void fabricDependencyDiagnosisReplacesLegacyRule() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            String failure = "net.fabricmc.loader.discovery.ModResolutionException: "
                    + "Could not find required mod: pca requires {fabric @ [>=0.39.2]}";
            Path latestLog = temporaryDirectory.resolve("latest-fabric-dependency.log");
            Files.writeString(latestLog, failure);
            GameCrashAnalysis analysis = new DefaultGameCrashAnalysisService(executor)
                    .analyze(input(List.of(new Log(failure)), Bits.BIT_64), latestLog)
                    .toCompletableFuture()
                    .get(5, TimeUnit.SECONDS);

            assertEquals(1, analysis.resultCount());
            assertTrue(analysis.results().stream()
                    .noneMatch(result -> result.rule() == CrashReportAnalyzer.Rule.MOD_RESOLUTION_MISSING));
            assertEquals(List.of(ResultID.FABRIC_MISSING_DEPENDENCY), analysis.logResults().stream()
                    .map(result -> result.resultId())
                    .toList());
        } finally {
            executor.shutdownNow();
        }
    }

    /// Keeps the explicit native-memory diagnosis when the broad out-of-memory rule matches the same source.
    ///
    /// @throws Exception when temporary I/O or bounded asynchronous completion fails
    @Test
    void memoryExceededSupersedesOutOfMemoryWithoutVirtualMemoryDiagnosis() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            DefaultGameCrashAnalysisService service = new DefaultGameCrashAnalysisService(executor);
            String failure = "Native memory allocation failed to reserve 671088640 bytes\n"
                    + "Out of Memory Error";
            GameCrashAnalysis analysis = service.analyze(
                            input(List.of(new Log(failure)), Bits.BIT_64),
                            temporaryDirectory.resolve("missing-memory-log.log"))
                    .toCompletableFuture()
                    .get(5, TimeUnit.SECONDS);

            assertEquals(List.of(CrashReportAnalyzer.Rule.MEMORY_EXCEEDED), analysis.results().stream()
                    .map(CrashReportAnalyzer.Result::rule)
                    .toList());
            assertEquals(List.of(CrashReportAnalyzer.Rule.OUT_OF_MEMORY), analysis.suppressedResults().stream()
                    .map(CrashReportAnalyzer.Result::rule)
                    .toList());
            assertTrue(analysis.logResults().isEmpty());
        } finally {
            executor.shutdownNow();
        }
    }

    /// Adapts the Swing `Log` model into one deterministic immutable Core input.
    ///
    /// @param logs captured process-output entries
    /// @param javaBits selected Java bitness
    /// @return immutable launch-log analysis input
    private static LogAnalyzable input(List<Log> logs, Bits javaBits) {
        return new LogAnalyzable(
                "1.20.4",
                "net.minecraft.client.main.Main",
                ProcessListener.ExitType.APPLICATION_ERROR,
                OperatingSystem.WINDOWS,
                936,
                Path.of("C:/Games/Minecraft/.minecraft"),
                Path.of("C:/Java/bin/javaw.exe"),
                17,
                17,
                javaBits,
                4096,
                logs.stream().map(Log::getLog).toList());
    }

}
