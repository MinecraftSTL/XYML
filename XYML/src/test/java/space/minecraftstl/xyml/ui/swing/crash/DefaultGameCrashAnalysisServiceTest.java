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
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import space.minecraftstl.xyml.game.CrashReportAnalyzer;
import space.minecraftstl.xyml.game.ExportedCrashBundle;
import space.minecraftstl.xyml.game.ExportedCrashBundleText;
import space.minecraftstl.xyml.game.Log;
import space.minecraftstl.xyml.game.analyzer.LogAnalyzable;
import space.minecraftstl.xyml.game.analyzer.ResultID;
import space.minecraftstl.xyml.launch.ProcessListener;
import space.minecraftstl.xyml.task.Task;
import space.minecraftstl.xyml.util.platform.Bits;
import space.minecraftstl.xyml.util.platform.OperatingSystem;

import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
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
            assertEquals(
                    List.of("captured", "latest_log"),
                    analysis.evidenceSources().get(CrashReportAnalyzer.Rule.OUT_OF_MEMORY));
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
            assertEquals(1, analysis.logResults().get(0).evidence().size());
            assertTrue(failure.contains(analysis.logResults().get(0).evidence().get(0)));
            assertEquals(
                    List.of("captured", "latest_log"),
                    analysis.logEvidenceSources().get(ResultID.JRE_32BIT));
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

    /// Retains every first-seen dependency ID when analysis has no application search boundary.
    ///
    /// @throws Exception when temporary I/O or bounded asynchronous completion fails
    @Test
    void mergesMissingDependencyIdsAcrossSourcesWithoutSearchBoundary() throws Exception {
        String capturedFailure = forgeDependencyFailure(
                "captured_dep",
                "captured_mod",
                "shared_dep",
                "captured_mod");
        String persistedFailure = forgeDependencyFailure(
                "shared_dep",
                "persisted_mod",
                "persisted_dep",
                "persisted_mod");
        Path latestLog = temporaryDirectory.resolve("latest-forge-merged-information.log");
        Files.writeString(latestLog, persistedFailure);

        GameCrashAnalysis analysis = analyze(
                input(List.of(new Log(capturedFailure)), Bits.BIT_64),
                latestLog);

        assertEquals(1, analysis.logResults().size());
        var solver = analysis.logResults().get(0).solver();
        assertEquals(
                List.of("captured_dep", "shared_dep", "persisted_dep"),
                solver.repairAction().dependencyIds());
        assertFalse(solver.repairAction().executable());
        assertNull(solver.createTask());
        assertEquals(List.of("captured", "latest_log"),
                analysis.logEvidenceSources().get(ResultID.FORGE_MISSING_DEPENDENCY));
        assertEquals(4, analysis.logResults().get(0).evidence().size());
        assertTrue(analysis.logResults().get(0).evidence().get(0).contains("captured_dep"));
        assertTrue(analysis.logResults().get(0).evidence().get(1).contains("shared_dep"));
        assertTrue(analysis.logResults().get(0).evidence().get(2).contains("shared_dep"));
        assertTrue(analysis.logResults().get(0).evidence().get(3).contains("persisted_dep"));
    }

    /// Executes a fresh search task with the stable dependency union from captured and persisted logs.
    ///
    /// @throws Exception when temporary I/O, task execution, or bounded asynchronous completion fails
    @Test
    void mergedMissingDependencySearchExecutesCompleteStableUnion() throws Exception {
        String capturedFailure = forgeDependencyFailure(
                "captured_dep",
                "captured_mod",
                "shared_dep",
                "captured_mod");
        String persistedFailure = forgeDependencyFailure(
                "shared_dep",
                "persisted_mod",
                "persisted_dep",
                "persisted_mod");
        Path latestLog = temporaryDirectory.resolve("latest-forge-merged-search.log");
        Files.writeString(latestLog, persistedFailure);
        AtomicInteger taskCreations = new AtomicInteger();
        AtomicReference<@Unmodifiable List<String>> executedIds = new AtomicReference<>(List.of());
        LogAnalyzable searchableInput = input(List.of(new Log(capturedFailure)), Bits.BIT_64)
                .withMissingDependencySearch(ids -> {
                    @Unmodifiable List<String> snapshot = List.copyOf(ids);
                    taskCreations.incrementAndGet();
                    return Task.runAsync(Runnable::run, () -> executedIds.set(snapshot));
                });

        GameCrashAnalysis analysis = analyze(searchableInput, latestLog);
        var solver = analysis.logResults().get(0).solver();

        assertTrue(solver.repairAction().executable());
        assertEquals(
                List.of("captured_dep", "shared_dep", "persisted_dep"),
                solver.repairAction().dependencyIds());
        Task<?> firstTask = solver.createTask();
        Task<?> secondTask = solver.createTask();
        assertNotSame(firstTask, secondTask);
        assertEquals(Task.TaskState.READY, firstTask.getState());
        assertEquals(Task.TaskState.READY, secondTask.getState());
        firstTask.execute();
        assertEquals(2, taskCreations.get());
        assertEquals(List.of("captured_dep", "shared_dep", "persisted_dep"), executedIds.get());
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

    /// Preserves the analyzer registry order when all six limited causes are established across both sources.
    ///
    /// The Java-version evidence remains in the captured source so the independent persisted-source code-page
    /// diagnosis is not conservatively rejected as a Java-version failure.
    ///
    /// @throws Exception when temporary I/O or bounded asynchronous completion fails
    @Test
    void ordersAllLimitedCausesByAnalyzerRegistration() throws Exception {
        String capturedFailure = "Error occurred during initialization of VM\n"
                + "Could not reserve enough space for 3571712KB object heap\n"
                + "Native memory allocation (mmap) failed to commit 65536 bytes\n"
                + "java.lang.UnsupportedClassVersionError: example/Mod has been compiled by a more recent "
                + "version of the Java Runtime (class file version 61.0), this version only recognizes "
                + "class file versions up to 52.0\n"
                + "Missing or unsupported mandatory dependencies:\n"
                + "\tMod ID: 'forge_dep', Requested by: 'forge_mod', Expected range: '[1,)', "
                + "Actual version: '[MISSING]'\n"
                + "net.fabricmc.loader.discovery.ModResolutionException: Could not find required mod: "
                + "fabric_mod requires {fabric_dep @ [>=1.0]}";
        Path latestLog = temporaryDirectory.resolve("all-limited-causes.log");
        Files.writeString(latestLog, "Error: Could not find or load main class net.minecraft.client.main.Main");

        GameCrashAnalysis analysis = analyze(
                inputWithRuntimeMismatch(
                        List.of(new Log(capturedFailure)),
                        Bits.BIT_32,
                        Path.of("C:/Games/\uD83D\uDE80/.minecraft")),
                latestLog);

        assertEquals(List.of(
                ResultID.JRE_32BIT,
                ResultID.VIRTUAL_MEMORY,
                ResultID.JRE_VERSION,
                ResultID.FORGE_MISSING_DEPENDENCY,
                ResultID.FABRIC_MISSING_DEPENDENCY,
                ResultID.CODE_PAGE), analysis.logResults().stream()
                .map(result -> result.resultId())
                .toList());
    }

    /// Moves the broad native-library rule to suppressed evidence when the code-page analyzer establishes the cause.
    ///
    /// @throws Exception when bounded asynchronous completion fails
    @Test
    void codePageDiagnosisSupersedesUnsatisfiedLinkRule() throws Exception {
        String failure = "[LWJGL] Failed to load a library\n"
                + "java.lang.UnsatisfiedLinkError: Failed to locate library: lwjgl";

        GameCrashAnalysis analysis = analyze(
                inputWithPaths(
                        List.of(new Log(failure)),
                        Bits.BIT_64,
                        17,
                        17,
                        Path.of("C:/Games/\uD83D\uDE80/.minecraft")),
                temporaryDirectory.resolve("missing-code-page.log"));

        assertEquals(List.of(ResultID.CODE_PAGE), analysis.logResults().stream()
                .map(result -> result.resultId())
                .toList());
        assertEquals(List.of(CrashReportAnalyzer.Rule.UNSATISFIED_LINK_ERROR),
                analysis.suppressedResults().stream().map(CrashReportAnalyzer.Result::rule).toList());
        assertTrue(analysis.results().stream()
                .noneMatch(result -> result.rule() == CrashReportAnalyzer.Rule.UNSATISFIED_LINK_ERROR));
    }

    /// Suppresses every legacy low-Java and high-Java rule after the typed Java-version diagnosis is established.
    ///
    /// @throws Exception when bounded asynchronous completion fails
    @Test
    void javaVersionDiagnosisSupersedesAllLegacyJavaRules() throws Exception {
        String failure = "java.lang.IllegalArgumentException: The requested compatibility level JAVA_11 "
                + "could not be set. Level is not supported by the active JRE or ASM version\n"
                + "java.lang.UnsupportedClassVersionError: example/Mod has been compiled by a more recent "
                + "version of the Java Runtime (class file version 61.0)\n"
                + "java.lang.ClassCastException: java.base/jdk\n"
                + "Unsupported class file major version";

        GameCrashAnalysis analysis = analyze(
                inputWithRuntimeMismatch(
                        List.of(new Log(failure)),
                        Bits.BIT_64,
                        Path.of("C:/Games/Minecraft/.minecraft")),
                temporaryDirectory.resolve("missing-java-version.log"));

        assertEquals(List.of(ResultID.JRE_VERSION), analysis.logResults().stream()
                .map(result -> result.resultId())
                .toList());
        assertEquals(List.of(
                CrashReportAnalyzer.Rule.NEED_JDK11,
                CrashReportAnalyzer.Rule.TOO_OLD_JAVA,
                CrashReportAnalyzer.Rule.JDK_9,
                CrashReportAnalyzer.Rule.JAVA_VERSION_IS_TOO_HIGH), analysis.suppressedResults().stream()
                .map(CrashReportAnalyzer.Result::rule)
                .toList());
    }

    /// Suppresses both established memory rules after the typed virtual-memory diagnosis is established.
    ///
    /// @throws Exception when bounded asynchronous completion fails
    @Test
    void virtualMemoryDiagnosisSupersedesBothLegacyMemoryRules() throws Exception {
        String failure = "Native memory allocation (mmap) failed to commit 65536 bytes\n"
                + "java.lang.OutOfMemoryError: unable to create native thread";

        GameCrashAnalysis analysis = analyze(
                input(List.of(new Log(failure)), Bits.BIT_64),
                temporaryDirectory.resolve("missing-virtual-memory.log"));

        assertEquals(List.of(ResultID.VIRTUAL_MEMORY), analysis.logResults().stream()
                .map(result -> result.resultId())
                .toList());
        assertEquals(List.of(
                CrashReportAnalyzer.Rule.MEMORY_EXCEEDED,
                CrashReportAnalyzer.Rule.OUT_OF_MEMORY), analysis.suppressedResults().stream()
                .map(CrashReportAnalyzer.Result::rule)
                .toList());
    }

    /// Suppresses all broad Fabric resolution rules after one structured missing-dependency result is established.
    ///
    /// @throws Exception when bounded asynchronous completion fails
    @Test
    void fabricDependencyDiagnosisSupersedesAllLegacyFabricRules() throws Exception {
        String failure = "net.fabricmc.loader.discovery.ModResolutionException: Could not find required mod: "
                + "client_mod requires {fabric_api @ [>=1.0]}\n"
                + " - client_mod depends on fabric_api\n"
                + "Incompatible mod set!\n"
                + "fabric dependency failure [details]";

        GameCrashAnalysis analysis = analyze(
                input(List.of(new Log(failure)), Bits.BIT_64),
                temporaryDirectory.resolve("missing-fabric-rules.log"));

        assertEquals(List.of(ResultID.FABRIC_MISSING_DEPENDENCY), analysis.logResults().stream()
                .map(result -> result.resultId())
                .toList());
        assertEquals(List.of(
                CrashReportAnalyzer.Rule.MOD_RESOLUTION,
                CrashReportAnalyzer.Rule.MOD_RESOLUTION_MISSING,
                CrashReportAnalyzer.Rule.FABRIC_WARNINGS), analysis.suppressedResults().stream()
                .map(CrashReportAnalyzer.Result::rule)
                .toList());
    }

    /// Analyzes the body of a crash report referenced by captured launcher output, not only the marker line.
    ///
    /// @throws Exception when temporary I/O or bounded asynchronous completion fails
    @Test
    void analyzesReferencedCrashReportBody() throws Exception {
        Path crashReport = temporaryDirectory.resolve("crash-report.txt");
        Files.writeString(crashReport, "java.lang.NoSuchMethodError: com.example.Missing.method()");
        String captured = "#@!@# Game crashed! Crash report saved to: #@!@# " + crashReport;

        GameCrashAnalysis analysis = analyze(
                input(List.of(new Log(captured)), Bits.BIT_64),
                temporaryDirectory.resolve("missing-latest.log"));

        assertEquals(List.of(CrashReportAnalyzer.Rule.NO_SUCH_METHOD_ERROR), analysis.results().stream()
                .map(CrashReportAnalyzer.Result::rule)
                .toList());
        assertEquals(List.of("crash_report"), analysis.evidenceSources()
                .get(CrashReportAnalyzer.Rule.NO_SUCH_METHOD_ERROR));
    }

    /// Deduplicates identical captured and latest-log text while retaining both physical source names.
    ///
    /// @throws Exception when temporary I/O or bounded asynchronous completion fails
    @Test
    void deduplicatesIdenticalSourceContentsAndRetainsSources() throws Exception {
        String duplicate = "java.lang.NoSuchMethodError: com.example.Missing.method()";
        Path latestLog = temporaryDirectory.resolve("latest.log");
        Files.writeString(latestLog, duplicate);

        GameCrashAnalysis analysis = analyze(
                input(List.of(new Log(duplicate)), Bits.BIT_64),
                latestLog);

        assertEquals(1, analysis.results().stream()
                .filter(result -> result.rule() == CrashReportAnalyzer.Rule.NO_SUCH_METHOD_ERROR)
                .count());
        assertEquals(List.of("captured", "latest_log"), analysis.evidenceSources()
                .get(CrashReportAnalyzer.Rule.NO_SUCH_METHOD_ERROR));
    }

    /// Analyzes validated exported-bundle text and preserves its archive entry as evidence provenance.
    ///
    /// @throws Exception when bounded asynchronous completion fails
    @Test
    void analyzesExportedCrashBundleAndRetainsEntrySource() throws Exception {
        String report = "java.lang.NoSuchMethodError: com.example.Missing.method()";
        ExportedCrashBundle bundle = new ExportedCrashBundle(
                temporaryDirectory.resolve("minecraft-exported-crash-info-test.zip"),
                List.of(new ExportedCrashBundleText(
                        ExportedCrashBundleText.Kind.CRASH_REPORT,
                        report,
                        List.of("crash-reports/crash-2026-09-16.txt"))));
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            GameCrashAnalysis analysis = new DefaultGameCrashAnalysisService(executor)
                    .analyze(bundle)
                    .toCompletableFuture()
                    .get(5, TimeUnit.SECONDS);

            assertEquals(List.of(CrashReportAnalyzer.Rule.NO_SUCH_METHOD_ERROR), analysis.results().stream()
                    .map(CrashReportAnalyzer.Result::rule)
                    .toList());
            assertEquals(List.of("crash-reports/crash-2026-09-16.txt"), analysis.evidenceSources()
                    .get(CrashReportAnalyzer.Rule.NO_SUCH_METHOD_ERROR));
        } finally {
            executor.shutdownNow();
        }
    }

    /// Analyzes diagnostic evidence beyond the UI's bounded evidence-snippet range.
    ///
    /// @throws Exception when bounded asynchronous completion fails
    @Test
    void analyzesCompleteCapturedLogBeyondPresentationSnippetLimit() throws Exception {
        String prefix = "ordinary startup output without a diagnosis\n".repeat(200);
        String diagnosis = "net.fabricmc.loader.discovery.ModResolutionException: "
                + "Could not find required mod: client_mod requires {fabric_api @ [>=1.0]}";
        String fullLog = prefix + diagnosis;
        assertTrue(fullLog.indexOf(diagnosis) > 240);

        GameCrashAnalysis analysis = analyze(
                input(List.of(new Log(fullLog)), Bits.BIT_64),
                temporaryDirectory.resolve("missing-complete-log.log"));

        assertEquals(List.of(ResultID.FABRIC_MISSING_DEPENDENCY), analysis.logResults().stream()
                .map(result -> result.resultId())
                .toList());
    }

    /// Adapts the Swing `Log` model into one deterministic immutable Core input.
    ///
    /// @param logs captured process-output entries
    /// @param javaBits selected Java bitness
    /// @return immutable launch-log analysis input
    private static LogAnalyzable input(List<Log> logs, Bits javaBits) {
        return inputWithPaths(
                logs,
                javaBits,
                17,
                17,
                Path.of("C:/Games/Minecraft/.minecraft"));
    }

    /// Builds one Forge hard-failure fixture containing two explicit missing dependencies.
    ///
    /// @param firstDependency first missing dependency ID
    /// @param firstRequester mod requesting the first dependency
    /// @param secondDependency second missing dependency ID
    /// @param secondRequester mod requesting the second dependency
    /// @return complete Forge dependency failure text
    private static String forgeDependencyFailure(
            String firstDependency,
            String firstRequester,
            String secondDependency,
            String secondRequester) {
        return "Missing or unsupported mandatory dependencies:\n"
                + "\tMod ID: '" + firstDependency + "', Requested by: '" + firstRequester
                + "', Expected range: '[1,)', Actual version: '[MISSING]'\n"
                + "\tMod ID: '" + secondDependency + "', Requested by: '" + secondRequester
                + "', Expected range: '[1,)', Actual version: '[MISSING]'";
    }

    /// Creates a launch input whose selected Java is older than the required runtime.
    ///
    /// @param logs captured process-output entries
    /// @param javaBits selected Java bitness
    /// @param gameDirectory launch path used by the code-page analyzer
    /// @return immutable launch-log analysis input
    private static LogAnalyzable inputWithRuntimeMismatch(
            List<Log> logs,
            Bits javaBits,
            Path gameDirectory) {
        return inputWithPaths(logs, javaBits, 17, 8, gameDirectory);
    }

    /// Creates a deterministic launch input with explicit Java versions and path context.
    ///
    /// @param logs captured process-output entries
    /// @param javaBits selected Java bitness
    /// @param requiredJavaVersion required Java major version
    /// @param currentJavaVersion selected Java major version
    /// @param gameDirectory launch path used by the code-page analyzer
    /// @return immutable launch-log analysis input
    private static LogAnalyzable inputWithPaths(
            List<Log> logs,
            Bits javaBits,
            int requiredJavaVersion,
            int currentJavaVersion,
            Path gameDirectory) {
        return new LogAnalyzable(
                "1.20.4",
                "net.minecraft.client.main.Main",
                ProcessListener.ExitType.APPLICATION_ERROR,
                OperatingSystem.WINDOWS,
                936,
                gameDirectory,
                Path.of("C:/Java/bin/javaw.exe"),
                requiredJavaVersion,
                currentJavaVersion,
                javaBits,
                4096,
                logs.stream().map(Log::getLog).toList());
    }

    /// Runs one analysis using two workers and shuts the executor down after bounded completion.
    ///
    /// @param input immutable captured-log context
    /// @param latestLog persisted latest-log path
    /// @return merged crash diagnosis
    /// @throws Exception when bounded asynchronous completion fails
    private static GameCrashAnalysis analyze(LogAnalyzable input, Path latestLog) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            return new DefaultGameCrashAnalysisService(executor)
                    .analyze(input, latestLog)
                    .toCompletableFuture()
                    .get(5, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }
    }

}
