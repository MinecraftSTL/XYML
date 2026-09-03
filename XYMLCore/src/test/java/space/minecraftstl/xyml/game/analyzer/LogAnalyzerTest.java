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
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import space.minecraftstl.xyml.game.Log;
import space.minecraftstl.xyml.launch.ProcessListener;
import space.minecraftstl.xyml.task.Task;
import space.minecraftstl.xyml.util.platform.Bits;
import space.minecraftstl.xyml.util.platform.OperatingSystem;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies high-confidence launch-log matches, false-positive guards, ordering, and immutable output.
@NotNullByDefault
class LogAnalyzerTest {
    /// Resolved Minecraft client main class used by real launch logs.
    private static final String MAIN_CLASS = "net.minecraft.client.main.Main";

    /// Non-ASCII game directory represented with ASCII-only Unicode escapes in source.
    private static final Path NON_ASCII_GAME_DIRECTORY = Path.of("C:/Games/\u6e38\u620f/.minecraft");

    /// ASCII-only control directory.
    private static final Path ASCII_GAME_DIRECTORY = Path.of("C:/Games/Minecraft/.minecraft");

    /// Representative selected Java executable.
    private static final Path JAVA_PATH = Path.of("C:/Java/bin/javaw.exe");

    /// Accepts the launcher's typed log model through the standalone convenience entry.
    ///
    /// @throws IOException when the real regression log cannot be read
    @Test
    void analyzesTypedLogSnapshotDirectly() throws IOException {
        @Unmodifiable List<AnalyzeResult<LogAnalyzable>> results = LogAnalyzer.analyze(
                loadLines("/logs/forgemod_resolution.txt").stream().map(Log::new).toList());

        assertEquals(1, results.size());
        assertEquals(ResultID.FORGE_MISSING_DEPENDENCY, results.get(0).resultId());
        assertEquals(ForgeMissingDependencyAnalyzer.class, results.get(0).analyzer().getClass());
    }

    /// Continues the generic driver after one analyzer throws a checked exception.
    @Test
    void isolatesAnalyzerFailureAndContinues() {
        AtomicBoolean followingAnalyzerInvoked = new AtomicBoolean();
        Analyzer<String> failingAnalyzer = (input, results) -> {
            throw new IOException("expected analyzer failure");
        };
        Analyzer<String> followingAnalyzer = (input, results) -> {
            followingAnalyzerInvoked.set(true);
            return Analyzer.ControlFlow.CONTINUE;
        };

        assertTrue(Analyzer.analyze(List.of(failingAnalyzer, followingAnalyzer), "input").isEmpty());
        assertTrue(followingAnalyzerInvoked.get());
    }

    /// Detects a Windows LWJGL native-loading failure only with a non-ASCII launch path and legacy code page.
    ///
    /// @throws IOException when the real regression log cannot be read
    @Test
    void detectsCodePageFailureFromRealLwjglLog() throws IOException {
        LogAnalyzable input = input(
                loadLines("/logs/unsatisfied_link_error.txt"),
                OperatingSystem.WINDOWS,
                1252,
                NON_ASCII_GAME_DIRECTORY,
                Bits.BIT_64,
                8,
                8,
                ProcessListener.ExitType.APPLICATION_ERROR);

        assertOnlyResult(input, ResultID.CODE_PAGE, CodePageAnalyzer.class);
    }

    /// Detects the real two-line JVM launcher diagnostic when the main class is behind an unencodable path.
    ///
    /// @throws IOException when the real regression log cannot be read
    @Test
    void detectsCodePageFailureFromRealMainClassDiagnostic() throws IOException {
        LogAnalyzable input = input(
                loadLines("/logs/code_page_main_class.txt"),
                OperatingSystem.WINDOWS,
                1252,
                NON_ASCII_GAME_DIRECTORY,
                Bits.BIT_64,
                8,
                8,
                ProcessListener.ExitType.APPLICATION_ERROR);

        assertOnlyResult(input, ResultID.CODE_PAGE, CodePageAnalyzer.class);
    }

    /// Rejects the same real native-loading failure when the launch path is ASCII-only.
    ///
    /// @throws IOException when the real regression log cannot be read
    @Test
    void codePageAnalyzerRejectsAsciiPath() throws IOException {
        LogAnalyzable input = input(
                loadLines("/logs/unsatisfied_link_error.txt"),
                OperatingSystem.WINDOWS,
                1252,
                ASCII_GAME_DIRECTORY,
                Bits.BIT_64,
                8,
                8,
                ProcessListener.ExitType.APPLICATION_ERROR);

        assertTrue(LogAnalyzer.analyze(input).isEmpty());
    }

    /// Rejects the same real native-loading failure on a non-Windows platform.
    ///
    /// @throws IOException when the real regression log cannot be read
    @Test
    void codePageAnalyzerRejectsNonWindowsPlatform() throws IOException {
        LogAnalyzable input = input(
                loadLines("/logs/failed_to_load_a_library.txt"),
                OperatingSystem.LINUX,
                -1,
                NON_ASCII_GAME_DIRECTORY,
                Bits.BIT_64,
                17,
                17,
                ProcessListener.ExitType.APPLICATION_ERROR);

        assertTrue(LogAnalyzer.analyze(input).isEmpty());
    }

    /// Rejects ordinary mod class-loading failures even with a Windows non-ASCII path.
    ///
    /// @throws IOException when the real regression log cannot be read
    @Test
    void codePageAnalyzerRejectsUnrelatedClassNotFoundException() throws IOException {
        LogAnalyzable input = input(
                loadLines("/logs/install_mixinbootstrap.txt"),
                OperatingSystem.WINDOWS,
                1252,
                NON_ASCII_GAME_DIRECTORY,
                Bits.BIT_64,
                8,
                8,
                ProcessListener.ExitType.APPLICATION_ERROR);

        assertTrue(LogAnalyzer.analyze(input).isEmpty());
    }

    /// Rejects a launch-main-class failure when the same real log proves an unsupported class-file version.
    ///
    /// @throws IOException when the real regression log cannot be read
    @Test
    void codePageAnalyzerRejectsJavaVersionEvidence() throws IOException {
        LogAnalyzable input = input(
                loadLines("/logs/too_old_java.txt"),
                OperatingSystem.WINDOWS,
                1252,
                NON_ASCII_GAME_DIRECTORY,
                Bits.BIT_64,
                8,
                8,
                ProcessListener.ExitType.APPLICATION_ERROR);

        assertTrue(LogAnalyzer.analyze(input).isEmpty());
    }

    /// Rejects an explicit LWJGL platform-architecture mismatch even when a non-ASCII path is present.
    ///
    /// @throws IOException when the real regression log cannot be read
    @Test
    void codePageAnalyzerRejectsLwjglArchitectureMismatch() throws IOException {
        LogAnalyzable input = input(
                loadLines("/logs/failed_to_load_a_library.txt"),
                OperatingSystem.WINDOWS,
                1252,
                NON_ASCII_GAME_DIRECTORY,
                Bits.BIT_64,
                17,
                17,
                ProcessListener.ExitType.APPLICATION_ERROR);

        assertTrue(LogAnalyzer.analyze(input).isEmpty());
    }

    /// Rejects code-page attribution when the system code page is unavailable.
    ///
    /// @throws IOException when the real regression log cannot be read
    @Test
    void codePageAnalyzerRejectsUnknownSystemCodePage() throws IOException {
        LogAnalyzable input = input(
                loadLines("/logs/code_page_main_class.txt"),
                OperatingSystem.WINDOWS,
                -1,
                NON_ASCII_GAME_DIRECTORY,
                Bits.BIT_64,
                8,
                8,
                ProcessListener.ExitType.APPLICATION_ERROR);

        assertTrue(LogAnalyzer.analyze(input).isEmpty());
    }

    /// Rejects code-page attribution when the selected path is representable by the active Windows code page.
    ///
    /// @throws IOException when the real regression log cannot be read
    @Test
    void codePageAnalyzerRejectsPathEncodableBySystemCodePage() throws IOException {
        LogAnalyzable input = input(
                loadLines("/logs/code_page_main_class.txt"),
                OperatingSystem.WINDOWS,
                936,
                NON_ASCII_GAME_DIRECTORY,
                Bits.BIT_64,
                8,
                8,
                ProcessListener.ExitType.APPLICATION_ERROR);

        assertTrue(LogAnalyzer.analyze(input).isEmpty());
    }

    /// Rejects code-page attribution when Windows already reports the UTF-8 system code page.
    ///
    /// @throws IOException when the real regression log cannot be read
    @Test
    void codePageAnalyzerRejectsUtf8SystemCodePage() throws IOException {
        LogAnalyzable input = input(
                loadLines("/logs/unsatisfied_link_error.txt"),
                OperatingSystem.WINDOWS,
                65001,
                NON_ASCII_GAME_DIRECTORY,
                Bits.BIT_64,
                8,
                8,
                ProcessListener.ExitType.APPLICATION_ERROR);

        assertTrue(LogAnalyzer.analyze(input).isEmpty());
    }

    /// Detects Forge's explicit missing dependency entry from a real launch log.
    ///
    /// @throws IOException when the real regression log cannot be read
    @Test
    void detectsForgeMissingDependency() throws IOException {
        LogAnalyzable input = input(
                loadLines("/logs/forgemod_resolution.txt"),
                OperatingSystem.WINDOWS,
                936,
                ASCII_GAME_DIRECTORY,
                Bits.BIT_64,
                17,
                17,
                ProcessListener.ExitType.APPLICATION_ERROR);

        AnalyzeResult<LogAnalyzable> result = assertOnlyResult(
                input,
                ResultID.FORGE_MISSING_DEPENDENCY,
                ForgeMissingDependencyAnalyzer.class);
        assertEquals(List.of("vampirism (required by werewolves)"), result.solver().messageArguments());
        assertEquals(
                RepairActionDescriptor.ActionType.OPEN_MOD_SEARCH,
                result.solver().repairAction().actionType());
        assertEquals(List.of("vampirism"), result.solver().repairAction().dependencyIds());
        assertFalse(result.solver().repairAction().executable());
    }

    /// Exposes Forge's validated dependency ID to the application search boundary.
    ///
    /// @throws IOException when the real regression log cannot be read
    @Test
    void forgeMissingDependencySolverSearchesNamedDependency() throws IOException {
        AtomicInteger creationCount = new AtomicInteger();
        AtomicReference<List<String>> searchedIds = new AtomicReference<>(List.of());
        LogAnalyzable input = input(
                loadLines("/logs/forgemod_resolution.txt"),
                OperatingSystem.WINDOWS,
                936,
                ASCII_GAME_DIRECTORY,
                Bits.BIT_64,
                17,
                17,
                ProcessListener.ExitType.APPLICATION_ERROR)
                .withMissingDependencySearch(ids -> {
                    creationCount.incrementAndGet();
                    searchedIds.set(ids);
                    return Task.completed(null);
                });

        AnalyzeResult<LogAnalyzable> result = assertOnlyResult(
                input,
                ResultID.FORGE_MISSING_DEPENDENCY,
                ForgeMissingDependencyAnalyzer.class);

        assertEquals(0, creationCount.get());
        assertTrue(result.solver().repairAction().executable());
        assertEquals(List.of("vampirism"), result.solver().repairAction().dependencyIds());
        Task<?> firstTask = result.solver().createTask();
        Task<?> secondTask = result.solver().createTask();
        assertNotSame(firstTask, secondTask);
        assertEquals(2, creationCount.get());
        assertEquals(List.of("vampirism"), searchedIds.get());
    }

    /// Rejects Forge's unsupported-version entry when no dependency is actually missing.
    ///
    /// @throws IOException when the real regression log cannot be read
    @Test
    void forgeMissingDependencyRejectsPresentIncompatibleVersion() throws IOException {
        List<String> logLines = loadLines("/logs/forgemod_resolution.txt").stream()
                .map(line -> line.replace("[MISSING]", "[1.8.0]"))
                .toList();
        LogAnalyzable input = input(
                logLines,
                OperatingSystem.WINDOWS,
                936,
                ASCII_GAME_DIRECTORY,
                Bits.BIT_64,
                17,
                17,
                ProcessListener.ExitType.APPLICATION_ERROR);

        assertTrue(LogAnalyzer.analyze(input).isEmpty());
    }

    /// Detects Fabric's old single-line missing dependency format from a real launch log.
    ///
    /// @throws IOException when the real regression log cannot be read
    @Test
    void detectsFabricMissingDependency() throws IOException {
        LogAnalyzable input = input(
                loadLines("/logs/fabric-mod-missing.txt"),
                OperatingSystem.WINDOWS,
                936,
                ASCII_GAME_DIRECTORY,
                Bits.BIT_64,
                17,
                17,
                ProcessListener.ExitType.APPLICATION_ERROR);

        AnalyzeResult<LogAnalyzable> result = assertOnlyResult(
                input,
                ResultID.FABRIC_MISSING_DEPENDENCY,
                FabricMissingDependencyAnalyzer.class);
        assertEquals(List.of("fabric (required by pca)"), result.solver().messageArguments());
        assertEquals(
                RepairActionDescriptor.ActionType.OPEN_MOD_SEARCH,
                result.solver().repairAction().actionType());
        assertEquals(List.of("fabric"), result.solver().repairAction().dependencyIds());
        assertFalse(result.solver().repairAction().executable());
    }

    /// Exposes Fabric's validated dependency ID to the application search boundary.
    ///
    /// @throws IOException when the real regression log cannot be read
    @Test
    void fabricMissingDependencySolverSearchesNamedDependency() throws IOException {
        AtomicInteger creationCount = new AtomicInteger();
        AtomicReference<List<String>> searchedIds = new AtomicReference<>(List.of());
        LogAnalyzable input = input(
                loadLines("/logs/fabric-mod-missing.txt"),
                OperatingSystem.WINDOWS,
                936,
                ASCII_GAME_DIRECTORY,
                Bits.BIT_64,
                17,
                17,
                ProcessListener.ExitType.APPLICATION_ERROR)
                .withMissingDependencySearch(ids -> {
                    creationCount.incrementAndGet();
                    searchedIds.set(ids);
                    return Task.completed(null);
                });

        AnalyzeResult<LogAnalyzable> result = assertOnlyResult(
                input,
                ResultID.FABRIC_MISSING_DEPENDENCY,
                FabricMissingDependencyAnalyzer.class);

        assertEquals(0, creationCount.get());
        assertTrue(result.solver().repairAction().executable());
        assertEquals(List.of("fabric"), result.solver().repairAction().dependencyIds());
        Task<?> firstTask = result.solver().createTask();
        Task<?> secondTask = result.solver().createTask();
        assertNotSame(firstTask, secondTask);
        assertEquals(2, creationCount.get());
        assertEquals(List.of("fabric"), searchedIds.get());
    }

    /// Detects multiple Fabric missing dependencies from the older resolution-list format.
    ///
    /// @throws IOException when the real regression log cannot be read
    @Test
    void detectsFabricMissingDependencyList() throws IOException {
        LogAnalyzable input = input(
                loadLines("/logs/mod_resolution.txt"),
                OperatingSystem.WINDOWS,
                936,
                ASCII_GAME_DIRECTORY,
                Bits.BIT_64,
                17,
                17,
                ProcessListener.ExitType.APPLICATION_ERROR);

        AnalyzeResult<LogAnalyzable> result = assertOnlyResult(
                input,
                ResultID.FABRIC_MISSING_DEPENDENCY,
                FabricMissingDependencyAnalyzer.class);
        assertEquals(
                List.of("fabricloader (required by test), fabric (required by test)"),
                result.solver().messageArguments());
        assertEquals(List.of("fabricloader", "fabric"), result.solver().repairAction().dependencyIds());
    }

    /// Detects the current Fabric loader's hard dependency format from a real launch log.
    ///
    /// @throws IOException when the real regression log cannot be read
    @Test
    void detectsCurrentFabricMissingDependency() throws IOException {
        LogAnalyzable input = input(
                loadLines("/logs/fabric_warnings2.txt"),
                OperatingSystem.WINDOWS,
                936,
                ASCII_GAME_DIRECTORY,
                Bits.BIT_64,
                17,
                17,
                ProcessListener.ExitType.APPLICATION_ERROR);

        AnalyzeResult<LogAnalyzable> result = assertOnlyResult(
                input,
                ResultID.FABRIC_MISSING_DEPENDENCY,
                FabricMissingDependencyAnalyzer.class);
        assertTrue(result.solver().messageArguments().get(0).toString().contains("roughlyenoughitems"));
    }

    /// Detects missing dependencies from a localized current Fabric log via its machine-readable fix list.
    ///
    /// @throws IOException when the real regression log cannot be read
    @Test
    void detectsLocalizedCurrentFabricMissingDependency() throws IOException {
        List<String> logLines = loadLines("/logs/fabric_warnings3.txt").stream()
                .filter(line -> !line.contains("HARD_DEP"))
                .toList();
        LogAnalyzable input = input(
                logLines,
                OperatingSystem.WINDOWS,
                936,
                ASCII_GAME_DIRECTORY,
                Bits.BIT_64,
                17,
                17,
                ProcessListener.ExitType.APPLICATION_ERROR);

        AnalyzeResult<LogAnalyzable> result = assertOnlyResult(
                input,
                ResultID.FABRIC_MISSING_DEPENDENCY,
                FabricMissingDependencyAnalyzer.class);
        String summary = result.solver().messageArguments().get(0).toString();
        assertTrue(summary.contains("fabric-api"));
        assertTrue(summary.contains("sodium"));
    }

    /// Rejects Fabric conflict reports, optional recommendations, and environment requirements.
    ///
    /// @throws IOException when the real regression logs cannot be read
    @Test
    void fabricMissingDependencyRejectsAdjacentCauses() throws IOException {
        for (String resource : List.of(
                "/logs/fabric-mod-conflict.txt",
                "/logs/fabric_warnings.txt",
                "/logs/fabric-minecraft.txt",
                "/logs/mod_resolution_collection.txt")) {
            LogAnalyzable input = input(
                    loadLines(resource),
                    OperatingSystem.WINDOWS,
                    936,
                    ASCII_GAME_DIRECTORY,
                    Bits.BIT_64,
                    17,
                    17,
                    ProcessListener.ExitType.APPLICATION_ERROR);

            assertTrue(LogAnalyzer.analyze(input).isEmpty(), resource);
        }
    }

    /// Reuses the established invalid-heap-size evidence when the selected runtime is verified as 32-bit.
    ///
    /// @throws IOException when the real regression log cannot be read
    @Test
    void detects32BitInitialHeapFailure() throws IOException {
        LogAnalyzable input = input(
                loadLines("/logs/jvm_32bit.txt"),
                OperatingSystem.WINDOWS,
                936,
                ASCII_GAME_DIRECTORY,
                Bits.BIT_32,
                8,
                8,
                ProcessListener.ExitType.JVM_ERROR);

        AnalyzeResult<LogAnalyzable> result = assertOnlyResult(
                input,
                ResultID.JRE_32BIT,
                JRE32BitAnalyzer.class);
        assertEquals(
                RepairActionDescriptor.ActionType.REPLACE_JAVA_RUNTIME,
                result.solver().repairAction().actionType());
        assertFalse(result.solver().repairAction().executable());
        assertEquals(
                RepairActionDescriptor.ConfirmationRequirement.NOT_REQUIRED,
                result.solver().repairAction().confirmationRequirement());
    }

    /// Reuses the established object-heap reservation variant for a verified 32-bit runtime.
    ///
    /// @throws IOException when the real regression log cannot be read
    @Test
    void detects32BitObjectHeapReservationFailure() throws IOException {
        LogAnalyzable input = input(
                loadLines("/logs/jvm_32bit2.txt"),
                OperatingSystem.WINDOWS,
                936,
                ASCII_GAME_DIRECTORY,
                Bits.BIT_32,
                8,
                8,
                ProcessListener.ExitType.JVM_ERROR);

        assertOnlyResult(input, ResultID.JRE_32BIT, JRE32BitAnalyzer.class);
    }

    /// Refuses to attribute heap reservation to 32-bit Java when runtime bitness says otherwise.
    ///
    /// @throws IOException when the real regression log cannot be read
    @Test
    void jre32BitAnalyzerRejects64BitRuntime() throws IOException {
        LogAnalyzable input = input(
                loadLines("/logs/jvm_32bit2.txt"),
                OperatingSystem.WINDOWS,
                936,
                ASCII_GAME_DIRECTORY,
                Bits.BIT_64,
                8,
                8,
                ProcessListener.ExitType.JVM_ERROR);

        assertTrue(LogAnalyzer.analyze(input).isEmpty());
    }

    /// Correlates a real unsupported-class-version log with a too-old selected Java runtime.
    ///
    /// @throws IOException when the real regression log cannot be read
    @Test
    void detectsTooOldJavaVersion() throws IOException {
        LogAnalyzable input = input(
                loadLines("/logs/too_old_java.txt"),
                OperatingSystem.WINDOWS,
                936,
                ASCII_GAME_DIRECTORY,
                Bits.BIT_64,
                16,
                8,
                ProcessListener.ExitType.APPLICATION_ERROR);

        AnalyzeResult<LogAnalyzable> result = assertOnlyResult(
                input,
                ResultID.JRE_VERSION,
                JREVersionAnalyzer.class);
        assertEquals(List.of(16, 8), result.solver().messageArguments());
        assertEquals(
                RepairActionDescriptor.ActionType.REPLACE_JAVA_RUNTIME,
                result.solver().repairAction().actionType());
        assertFalse(result.solver().repairAction().executable());
    }

    /// Binds the application replacement task while retaining the specific Java-version diagnosis.
    ///
    /// @throws IOException when the real regression log cannot be read
    @Test
    void jreVersionAnalyzerUsesApplicationRepairTask() throws IOException {
        AtomicInteger creationCount = new AtomicInteger();
        LogAnalyzable input = input(
                loadLines("/logs/too_old_java.txt"),
                OperatingSystem.WINDOWS,
                936,
                ASCII_GAME_DIRECTORY,
                Bits.BIT_64,
                16,
                8,
                ProcessListener.ExitType.APPLICATION_ERROR)
                .withJavaRuntimeRepair(() -> {
                    creationCount.incrementAndGet();
                    return Task.completed(null);
                });

        AnalyzeResult<LogAnalyzable> result = assertOnlyResult(
                input,
                ResultID.JRE_VERSION,
                JREVersionAnalyzer.class);

        assertEquals("game.crash.reason.log.jre_version", result.solver().messageKey());
        assertEquals(List.of(16, 8), result.solver().messageArguments());
        assertEquals(0, creationCount.get());
        assertEquals(
                RepairActionDescriptor.ActionType.REPLACE_JAVA_RUNTIME,
                result.solver().repairAction().actionType());
        assertEquals(
                RepairActionDescriptor.ConfirmationRequirement.NOT_REQUIRED,
                result.solver().repairAction().confirmationRequirement());
        assertTrue(result.solver().repairAction().executable());
        Task<?> firstTask = result.solver().createTask();
        Task<?> secondTask = result.solver().createTask();
        assertNotSame(firstTask, secondTask);
        assertEquals(2, creationCount.get());
    }

    /// Correlates a real legacy Forge failure with a selected Java runtime that is too new.
    ///
    /// @throws IOException when the real regression log cannot be read
    @Test
    void detectsTooNewJavaVersion() throws IOException {
        LogAnalyzable input = input(
                loadLines("/logs/java_version_is_too_high.txt"),
                OperatingSystem.WINDOWS,
                936,
                ASCII_GAME_DIRECTORY,
                Bits.BIT_64,
                8,
                17,
                ProcessListener.ExitType.APPLICATION_ERROR);

        assertOnlyResult(input, ResultID.JRE_VERSION, JREVersionAnalyzer.class);
    }

    /// Rejects a mere context mismatch when the log has no Java-version incompatibility evidence.
    ///
    /// @throws IOException when the real regression log cannot be read
    @Test
    void jreVersionAnalyzerRequiresLogEvidence() throws IOException {
        LogAnalyzable input = input(
                loadLines("/logs/install_mixinbootstrap.txt"),
                OperatingSystem.WINDOWS,
                936,
                ASCII_GAME_DIRECTORY,
                Bits.BIT_64,
                17,
                8,
                ProcessListener.ExitType.APPLICATION_ERROR);

        assertTrue(LogAnalyzer.analyze(input).isEmpty());
    }

    /// Reuses the established native-allocation failure from a real HotSpot error report.
    ///
    /// @throws IOException when the real regression log cannot be read
    @Test
    void detectsVirtualMemoryCommitFailure() throws IOException {
        LogAnalyzable input = input(
                loadLines("/logs/memory_exceeded.txt"),
                OperatingSystem.WINDOWS,
                936,
                ASCII_GAME_DIRECTORY,
                Bits.BIT_64,
                8,
                8,
                ProcessListener.ExitType.APPLICATION_ERROR);

        assertOnlyResult(input, ResultID.VIRTUAL_MEMORY, VirtualMemoryAnalyzer.class);
    }

    /// Accepts the explicit physical-RAM-or-swap variant from a second real HotSpot error report.
    ///
    /// @throws IOException when the real regression log cannot be read
    @Test
    void detectsPhysicalOrSwapExhaustion() throws IOException {
        LogAnalyzable input = input(
                loadLines("/logs/out_of_memory.txt"),
                OperatingSystem.WINDOWS,
                936,
                ASCII_GAME_DIRECTORY,
                Bits.BIT_64,
                8,
                8,
                ProcessListener.ExitType.APPLICATION_ERROR);

        assertOnlyResult(input, ResultID.VIRTUAL_MEMORY, VirtualMemoryAnalyzer.class);
    }

    /// Rejects ordinary Java heap exhaustion so it remains owned by `CrashReportAnalyzer.OUT_OF_MEMORY`.
    ///
    /// @throws IOException when the real regression log cannot be read
    @Test
    void virtualMemoryAnalyzerRejectsJavaHeapExhaustion() throws IOException {
        LogAnalyzable input = input(
                loadLines("/crash-report/out_of_memory.txt"),
                OperatingSystem.WINDOWS,
                936,
                ASCII_GAME_DIRECTORY,
                Bits.BIT_64,
                8,
                8,
                ProcessListener.ExitType.APPLICATION_ERROR);

        assertTrue(LogAnalyzer.analyze(input).isEmpty());
    }

    /// Rejects all error-like evidence retained after a normal process exit.
    ///
    /// @throws IOException when the real regression log cannot be read
    @Test
    void normalExitProducesNoDiagnosis() throws IOException {
        LogAnalyzable input = input(
                loadLines("/logs/memory_exceeded.txt"),
                OperatingSystem.WINDOWS,
                936,
                ASCII_GAME_DIRECTORY,
                Bits.BIT_64,
                8,
                8,
                ProcessListener.ExitType.NORMAL);

        assertTrue(LogAnalyzer.analyze(input).isEmpty());
    }

    /// Stops after the first exclusive cause and returns an immutable result list.
    ///
    /// @throws IOException when real regression logs cannot be read
    @Test
    void returnsOrderedImmutableExclusiveResult() throws IOException {
        List<String> combinedLogs = new ArrayList<>(loadLines("/logs/jvm_32bit2.txt"));
        combinedLogs.addAll(loadLines("/logs/memory_exceeded.txt"));
        LogAnalyzable input = input(
                combinedLogs,
                OperatingSystem.WINDOWS,
                936,
                ASCII_GAME_DIRECTORY,
                Bits.BIT_32,
                8,
                8,
                ProcessListener.ExitType.JVM_ERROR);

        @Unmodifiable List<AnalyzeResult<LogAnalyzable>> results = LogAnalyzer.analyze(input);

        assertEquals(List.of(ResultID.JRE_32BIT), results.stream().map(AnalyzeResult::resultId).toList());
        assertThrows(UnsupportedOperationException.class, results::clear);
    }

    /// Defensively copies both analysis input lines and solver localization arguments.
    @Test
    void copiesInputAndSolverCollections() {
        List<String> mutableLines = new ArrayList<>(List.of("captured"));
        LogAnalyzable input = input(
                mutableLines,
                OperatingSystem.WINDOWS,
                1252,
                ASCII_GAME_DIRECTORY,
                Bits.BIT_64,
                17,
                17,
                ProcessListener.ExitType.APPLICATION_ERROR);
        mutableLines.add("late mutation");

        List<Object> mutableArguments = new ArrayList<>(List.of(17, 8));
        TextSolver solver = new TextSolver("test.message", mutableArguments, "Install Java 17.");
        mutableArguments.add("late mutation");

        assertEquals(List.of("captured"), input.logLines());
        assertThrows(UnsupportedOperationException.class, input.logLines()::clear);
        assertEquals(List.of(17, 8), solver.messageArguments());
        assertThrows(UnsupportedOperationException.class, solver.messageArguments()::clear);
    }

    /// Creates a representative immutable analysis input around one real log source.
    ///
    /// @param logLines source lines in order
    /// @param operatingSystem launch operating system
    /// @param systemCodePage Windows code page or a negative unknown value
    /// @param gameDirectory resolved game directory
    /// @param javaBits selected runtime bitness
    /// @param requiredJavaVersion recommended Java major version, or null when unknown
    /// @param currentJavaVersion selected Java major version, or null when unknown
    /// @param exitType classified process exit
    /// @return immutable analysis input
    private static LogAnalyzable input(
            List<String> logLines,
            OperatingSystem operatingSystem,
            int systemCodePage,
            Path gameDirectory,
            Bits javaBits,
            @Nullable Integer requiredJavaVersion,
            @Nullable Integer currentJavaVersion,
            ProcessListener.ExitType exitType) {
        return new LogAnalyzable(
                "1.16.4",
                MAIN_CLASS,
                exitType,
                operatingSystem,
                systemCodePage,
                gameDirectory,
                JAVA_PATH,
                requiredJavaVersion,
                currentJavaVersion,
                javaBits,
                4096,
                logLines);
    }

    /// Asserts one diagnosis with the expected stable ID and concrete analyzer type.
    ///
    /// @param input immutable analysis input
    /// @param expectedId expected cause identifier
    /// @param expectedAnalyzer expected analyzer class
    /// @return the only diagnosis
    private static AnalyzeResult<LogAnalyzable> assertOnlyResult(
            LogAnalyzable input,
            ResultID expectedId,
            Class<? extends Analyzer<LogAnalyzable>> expectedAnalyzer) {
        @Unmodifiable List<AnalyzeResult<LogAnalyzable>> results = LogAnalyzer.analyze(input);
        assertEquals(1, results.size());
        AnalyzeResult<LogAnalyzable> result = results.get(0);
        assertEquals(expectedId, result.resultId());
        assertEquals(expectedAnalyzer, result.analyzer().getClass());
        assertTrue(result.solver().fallbackMessage().length() > 20);
        return result;
    }

    /// Reads one UTF-8 regression resource as an immutable line snapshot.
    ///
    /// @param resourcePath absolute classpath resource path
    /// @return immutable source lines
    /// @throws IOException when reading fails
    private static @Unmodifiable List<String> loadLines(String resourcePath) throws IOException {
        try (InputStream input = Objects.requireNonNull(
                LogAnalyzerTest.class.getResourceAsStream(resourcePath),
                resourcePath);
             BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            return reader.lines().toList();
        }
    }
}
