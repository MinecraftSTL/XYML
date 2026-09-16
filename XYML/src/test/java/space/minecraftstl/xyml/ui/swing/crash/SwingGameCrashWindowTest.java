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
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import space.minecraftstl.xyml.game.ExportedCrashBundle;
import space.minecraftstl.xyml.game.ExportedCrashBundleText;
import space.minecraftstl.xyml.game.Log;
import space.minecraftstl.xyml.game.analyzer.AnalyzeResult;
import space.minecraftstl.xyml.game.analyzer.FabricMissingDependencyAnalyzer;
import space.minecraftstl.xyml.game.analyzer.ForgeMissingDependencyAnalyzer;
import space.minecraftstl.xyml.game.analyzer.JREVersionAnalyzer;
import space.minecraftstl.xyml.game.analyzer.LogAnalyzable;
import space.minecraftstl.xyml.game.analyzer.RepairTaskPhase;
import space.minecraftstl.xyml.game.analyzer.ResultID;
import space.minecraftstl.xyml.game.analyzer.Solver;
import space.minecraftstl.xyml.launch.ProcessListener;
import space.minecraftstl.xyml.task.Task;
import space.minecraftstl.xyml.util.platform.Bits;
import space.minecraftstl.xyml.util.platform.OperatingSystem;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;

import javax.swing.JPanel;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static space.minecraftstl.xyml.util.i18n.I18n.i18n;

/// Verifies headless composition, EDT result application, exit titles, and late-result cancellation.
@NotNullByDefault
class SwingGameCrashWindowTest {
    /// Builds testable content without a native frame and applies a completed diagnosis on the EDT.
    @Test
    void composesAndAppliesAnalysisWithoutNativeFrame() {
        ControlledAnalysisService service = new ControlledAnalysisService();
        ExecutorService worker = Executors.newSingleThreadExecutor();
        SwingGameCrashWindow window = window(service, worker);

        window.show();
        EdtDispatcher.executeAndWait(() -> {
            assertTrue(window.hasContentOnEdt());
            assertTrue(window.hasReportQrCodeOnEdt());
        });
        service.result.complete(new GameCrashAnalysis(List.of(), Set.of()));
        EdtDispatcher.executeAndWait(() -> assertEquals(
                i18n("game.crash.reason.unknown"),
                window.displayedReasonOnEdt()));

        window.close();
        EdtDispatcher.executeAndWait(() -> {
        });
    }

    /// Ignores an analysis that completes after close and shuts down the window-owned executor.
    @Test
    void closeSuppressesLateAnalysisUpdate() {
        ControlledAnalysisService service = new ControlledAnalysisService();
        ExecutorService worker = Executors.newSingleThreadExecutor();
        SwingGameCrashWindow window = window(service, worker);

        window.show();
        EdtDispatcher.executeAndWait(() -> assertEquals(
                i18n("game.crash.reason.analyzing"),
                window.displayedReasonOnEdt()));
        window.close();
        service.result.complete(new GameCrashAnalysis(List.of(), Set.of("late.keyword")));
        EdtDispatcher.executeAndWait(() -> assertEquals(
                i18n("game.crash.reason.analyzing"),
                window.displayedReasonOnEdt()));

        assertTrue(window.isClosed());
        assertTrue(worker.isShutdown());
    }

    /// Closes the injected actions exactly once even when the crash-window close boundary is called repeatedly.
    @Test
    void closeReleasesActionsExactlyOnce() {
        ControlledAnalysisService service = new ControlledAnalysisService();
        ExecutorService worker = Executors.newSingleThreadExecutor();
        RecordingActions actions = new RecordingActions();
        SwingGameCrashWindow window = window(
                service,
                worker,
                new RecordingRepairInteraction(true, null),
                actions);

        window.close();
        window.close();
        EdtDispatcher.executeAndWait(() -> { });

        assertEquals(1, actions.closeCalls.get());
        assertTrue(worker.isShutdown());
    }

    /// Keeps imported-log actions closed and unable to recreate secondary UI in headless lifecycle tests.
    @Test
    void importedActionsReleaseLogViewerBoundaryIdempotently() {
        ExportedCrashBundle bundle = new ExportedCrashBundle(
                Path.of("minecraft-exported-crash-info-test.zip"),
                List.of(new ExportedCrashBundleText(
                        ExportedCrashBundleText.Kind.LOG,
                        "first imported log",
                        List.of("minecraft.log"))));
        ImportedGameCrashWindowActions actions = new ImportedGameCrashWindowActions(new JPanel(), bundle);

        assertEquals(1, actions.sourceCount());
        assertFalse(actions.isClosed());
        actions.close();
        actions.close();

        EdtDispatcher.executeAndWait(() -> {
            actions.showGameLogs();
            assertFalse(actions.hasOpenLogDialogOnEdt());
        });
        assertTrue(actions.isClosed());
    }

    /// Keeps a missing-dependency search idle until the corresponding reason row is explicitly clicked.
    @Test
    void doesNotRunMissingDependencySearchAfterAnalysis() throws Exception {
        ControlledAnalysisService service = new ControlledAnalysisService();
        ExecutorService worker = Executors.newSingleThreadExecutor();
        AtomicInteger searchCalls = new AtomicInteger();
        SwingGameCrashWindow window = window(service, worker);
        Solver solver = Solver.ofTask(Task.runAsync(Runnable::run, searchCalls::incrementAndGet));
        AnalyzeResult<LogAnalyzable> result = new AnalyzeResult<>(
                new ForgeMissingDependencyAnalyzer(),
                ResultID.FORGE_MISSING_DEPENDENCY,
                solver);

        try {
            window.show();
            EdtDispatcher.executeAndWait(() -> { });
            service.result.complete(new GameCrashAnalysis(List.of(), List.of(result), Set.of()));
            EdtDispatcher.executeAndWait(() -> { });
            worker.submit(() -> { }).get(5, java.util.concurrent.TimeUnit.SECONDS);
            EdtDispatcher.executeAndWait(() -> { });

            assertEquals(0, searchCalls.get());
            assertTrue(window.followUpCompletion().toCompletableFuture().isDone());
        } finally {
            window.close();
            EdtDispatcher.executeAndWait(() -> { });
        }
    }

    /// Renders exact typed evidence with source provenance and applies the existing display bound.
    @Test
    void rendersBoundedTypedEvidenceInsteadOfSourceNameOnly() {
        ControlledAnalysisService service = new ControlledAnalysisService();
        ExecutorService worker = Executors.newSingleThreadExecutor();
        SwingGameCrashWindow window = window(service, worker);
        String evidence = "matched-line-" + "x".repeat(300) + "-hidden-tail";
        AnalyzeResult<LogAnalyzable> result = new AnalyzeResult<>(
                new ForgeMissingDependencyAnalyzer(),
                ResultID.FORGE_MISSING_DEPENDENCY,
                Solver.ofTask(Task.completed(null)),
                List.of(evidence));

        try {
            window.show();
            EdtDispatcher.executeAndWait(() -> { });
            service.result.complete(new GameCrashAnalysis(
                    List.of(),
                    List.of(result),
                    Set.of(),
                    List.of(),
                    Map.of(),
                    Map.of(ResultID.FORGE_MISSING_DEPENDENCY, List.of("captured"))));
            EdtDispatcher.executeAndWait(() -> {
                String visible = window.repairEvidenceTextOnEdt(ResultID.FORGE_MISSING_DEPENDENCY.name());
                assertTrue(visible.contains("captured"));
                assertTrue(visible.contains("matched-line-"));
                assertTrue(visible.endsWith("..."));
                assertFalse(visible.contains("hidden-tail"));
            });
        } finally {
            window.close();
            EdtDispatcher.executeAndWait(() -> { });
        }
    }

    /// Keeps a hidden launcher's follow-up boundary open until an explicit dependency search succeeds.
    @Test
    void pendingDependencySearchCompletesFollowUpOnlyAfterSuccess() throws Exception {
        ControlledAnalysisService service = new ControlledAnalysisService();
        ExecutorService worker = Executors.newSingleThreadExecutor();
        AtomicInteger searchExecutions = new AtomicInteger();
        SwingGameCrashWindow window = window(service, worker);
        AnalyzeResult<LogAnalyzable> result = new AnalyzeResult<>(
                new ForgeMissingDependencyAnalyzer(),
                ResultID.FORGE_MISSING_DEPENDENCY,
                missingDependencySearchSolver("fabric-api", searchExecutions));

        try {
            renderAnalysis(window, service, List.of(result));

            assertFalse(window.followUpCompletion().toCompletableFuture().isDone());
            EdtDispatcher.executeAndWait(() -> window.clickRepairOnEdt(
                    ResultID.FORGE_MISSING_DEPENDENCY.name()));
            awaitRepairState(window, ResultID.FORGE_MISSING_DEPENDENCY, "SUCCEEDED");

            assertEquals(1, searchExecutions.get());
            assertTrue(window.followUpCompletion().toCompletableFuture().isDone());
        } finally {
            window.close();
            EdtDispatcher.executeAndWait(() -> { });
        }
    }

    /// Releases a pending missing-dependency follow-up when the user closes the crash window without searching.
    @Test
    void closingPendingDependencySearchCompletesFollowUp() {
        ControlledAnalysisService service = new ControlledAnalysisService();
        ExecutorService worker = Executors.newSingleThreadExecutor();
        SwingGameCrashWindow window = window(service, worker);
        AnalyzeResult<LogAnalyzable> result = new AnalyzeResult<>(
                new ForgeMissingDependencyAnalyzer(),
                ResultID.FORGE_MISSING_DEPENDENCY,
                missingDependencySearchSolver("fabric-api", new AtomicInteger()));

        renderAnalysis(window, service, List.of(result));
        assertFalse(window.followUpCompletion().toCompletableFuture().isDone());

        window.close();
        EdtDispatcher.executeAndWait(() -> { });

        assertTrue(window.followUpCompletion().toCompletableFuture().isDone());
    }

    /// Runs one confirmed repair through the specified preparation, selection, running, and success states.
    @Test
    void followsConfirmedRepairStateOrder() throws Exception {
        ControlledAnalysisService service = new ControlledAnalysisService();
        ExecutorService worker = Executors.newSingleThreadExecutor();
        ExecutorService repairWorker = Executors.newSingleThreadExecutor();
        CountDownLatch repairStarted = new CountDownLatch(1);
        CountDownLatch releaseRepair = new CountDownLatch(1);
        RecordingRepairInteraction interaction = new RecordingRepairInteraction(true, null);
        SwingGameCrashWindow window = window(service, worker, interaction);
        Solver solver = Solver.ofTask(() -> Task.runAsync(repairWorker, () -> {
            repairStarted.countDown();
            awaitLatch(releaseRepair);
        }));
        AnalyzeResult<LogAnalyzable> result = new AnalyzeResult<>(
                new ForgeMissingDependencyAnalyzer(),
                ResultID.FORGE_MISSING_DEPENDENCY,
                solver);

        try {
            renderAnalysis(window, service, List.of(result));
            EdtDispatcher.executeAndWait(() -> window.clickRepairOnEdt(ResultID.FORGE_MISSING_DEPENDENCY.name()));

            assertTrue(repairStarted.await(5L, TimeUnit.SECONDS));
            awaitRepairState(window, ResultID.FORGE_MISSING_DEPENDENCY, "RUNNING");
            EdtDispatcher.executeAndWait(() -> assertEquals(
                    List.of("AVAILABLE", "PREPARING", "AWAITING_SELECTION", "RUNNING"),
                    window.repairStateHistoryOnEdt(ResultID.FORGE_MISSING_DEPENDENCY.name())));

            releaseRepair.countDown();
            awaitRepairState(window, ResultID.FORGE_MISSING_DEPENDENCY, "SUCCEEDED");
            EdtDispatcher.executeAndWait(() -> assertEquals(
                    List.of("AVAILABLE", "PREPARING", "AWAITING_SELECTION", "RUNNING", "SUCCEEDED"),
                    window.repairStateHistoryOnEdt(ResultID.FORGE_MISSING_DEPENDENCY.name())));
        } finally {
            releaseRepair.countDown();
            window.close();
            repairWorker.shutdownNow();
            EdtDispatcher.executeAndWait(() -> { });
        }
    }

    /// Preselects the recommended Java candidate and leaves the repair available when the chooser is cancelled.
    @Test
    void candidateCancellationRestoresAvailableStateWithoutCreatingTask() {
        ControlledAnalysisService service = new ControlledAnalysisService();
        ExecutorService worker = Executors.newSingleThreadExecutor();
        AtomicInteger taskCreations = new AtomicInteger();
        RecordingRepairInteraction interaction = new RecordingRepairInteraction(true, null);
        SwingGameCrashWindow window = window(service, worker, interaction);
        LogAnalyzable.JavaRuntimeCandidate existing =
                new LogAnalyzable.JavaRuntimeCandidate("existing", "Existing Java 17", false);
        LogAnalyzable.JavaRuntimeCandidate recommended =
                new LogAnalyzable.JavaRuntimeCandidate("recommended", "Recommended Java 17", true);
        Solver solver = candidateSolver(
                List.of(existing, recommended),
                candidateId -> {
                    taskCreations.incrementAndGet();
                    return Task.runAsync(Runnable::run, () -> { });
                });
        AnalyzeResult<LogAnalyzable> result = new AnalyzeResult<>(
                new JREVersionAnalyzer(),
                ResultID.JRE_VERSION,
                solver);

        try {
            renderAnalysis(window, service, List.of(result));
            EdtDispatcher.executeAndWait(() -> window.clickRepairOnEdt(ResultID.JRE_VERSION.name()));

            assertEquals(recommended, interaction.recommendedCandidate);
            assertEquals(0, taskCreations.get());
            EdtDispatcher.executeAndWait(() -> {
                assertEquals("AVAILABLE", window.repairStateOnEdt(ResultID.JRE_VERSION.name()));
                assertEquals(
                        List.of("AVAILABLE", "PREPARING", "AWAITING_SELECTION", "AVAILABLE"),
                        window.repairStateHistoryOnEdt(ResultID.JRE_VERSION.name()));
                assertTrue(window.isRepairActionEnabledOnEdt(ResultID.JRE_VERSION.name()));
            });
        } finally {
            window.close();
            EdtDispatcher.executeAndWait(() -> { });
        }
    }

    /// Changes a failed row to the retry command and creates an independent task for the next attempt.
    @Test
    void failedRepairOffersRetryAndCreatesFreshTask() throws Exception {
        ControlledAnalysisService service = new ControlledAnalysisService();
        ExecutorService worker = Executors.newSingleThreadExecutor();
        AtomicInteger taskCreations = new AtomicInteger();
        RecordingRepairInteraction interaction = new RecordingRepairInteraction(true, null);
        SwingGameCrashWindow window = window(service, worker, interaction);
        Solver solver = Solver.ofTask(() -> {
            if (taskCreations.incrementAndGet() == 1) {
                throw new IllegalStateException("first task creation failed");
            }
            return Task.runAsync(Runnable::run, () -> { });
        });
        AnalyzeResult<LogAnalyzable> result = new AnalyzeResult<>(
                new ForgeMissingDependencyAnalyzer(),
                ResultID.FORGE_MISSING_DEPENDENCY,
                solver);

        try {
            renderAnalysis(window, service, List.of(result));
            EdtDispatcher.executeAndWait(() -> window.clickRepairOnEdt(ResultID.FORGE_MISSING_DEPENDENCY.name()));
            awaitRepairState(window, ResultID.FORGE_MISSING_DEPENDENCY, "FAILED_RETRYABLE");
            EdtDispatcher.executeAndWait(() -> {
                assertEquals(
                        i18n("game.crash.repair.retry"),
                        window.repairActionTextOnEdt(ResultID.FORGE_MISSING_DEPENDENCY.name()));
                assertTrue(window.isRepairActionEnabledOnEdt(ResultID.FORGE_MISSING_DEPENDENCY.name()));
            });

            EdtDispatcher.executeAndWait(() -> window.clickRepairOnEdt(ResultID.FORGE_MISSING_DEPENDENCY.name()));
            awaitRepairState(window, ResultID.FORGE_MISSING_DEPENDENCY, "SUCCEEDED");

            assertEquals(2, taskCreations.get());
            EdtDispatcher.executeAndWait(() -> {
                assertFalse(window.isRepairActionEnabledOnEdt(ResultID.FORGE_MISSING_DEPENDENCY.name()));
                assertEquals(
                        List.of(
                                "AVAILABLE",
                                "PREPARING",
                                "AWAITING_SELECTION",
                                "FAILED_RETRYABLE",
                                "PREPARING",
                                "AWAITING_SELECTION",
                                "RUNNING",
                                "SUCCEEDED"),
                        window.repairStateHistoryOnEdt(ResultID.FORGE_MISSING_DEPENDENCY.name()));
            });
        } finally {
            window.close();
            EdtDispatcher.executeAndWait(() -> { });
        }
    }

    /// Executes each diagnosis row independently without consuming an adjacent repair action.
    @Test
    void independentRepairButtonsExecuteOnlyTheirOwnFreshTasks() throws Exception {
        ControlledAnalysisService service = new ControlledAnalysisService();
        ExecutorService worker = Executors.newSingleThreadExecutor();
        AtomicInteger forgeTasks = new AtomicInteger();
        AtomicInteger fabricTasks = new AtomicInteger();
        RecordingRepairInteraction interaction = new RecordingRepairInteraction(true, null);
        SwingGameCrashWindow window = window(service, worker, interaction);
        AnalyzeResult<LogAnalyzable> forgeResult = new AnalyzeResult<>(
                new ForgeMissingDependencyAnalyzer(),
                ResultID.FORGE_MISSING_DEPENDENCY,
                missingDependencySearchSolver("fabric-api", forgeTasks));
        AnalyzeResult<LogAnalyzable> fabricResult = new AnalyzeResult<>(
                new FabricMissingDependencyAnalyzer(),
                ResultID.FABRIC_MISSING_DEPENDENCY,
                missingDependencySearchSolver("fabric-language-kotlin", fabricTasks));

        try {
            renderAnalysis(window, service, List.of(forgeResult, fabricResult));
            EdtDispatcher.executeAndWait(() -> window.clickRepairOnEdt(ResultID.FORGE_MISSING_DEPENDENCY.name()));
            awaitRepairState(window, ResultID.FORGE_MISSING_DEPENDENCY, "SUCCEEDED");

            assertEquals(1, forgeTasks.get());
            assertEquals(0, fabricTasks.get());
            EdtDispatcher.executeAndWait(() -> assertEquals(
                    "AVAILABLE",
                    window.repairStateOnEdt(ResultID.FABRIC_MISSING_DEPENDENCY.name())));

            EdtDispatcher.executeAndWait(() -> window.clickRepairOnEdt(ResultID.FABRIC_MISSING_DEPENDENCY.name()));
            awaitRepairState(window, ResultID.FABRIC_MISSING_DEPENDENCY, "SUCCEEDED");
            assertEquals(1, fabricTasks.get());

            EdtDispatcher.executeAndWait(() -> {
                assertTrue(window.isRepairActionEnabledOnEdt(ResultID.FORGE_MISSING_DEPENDENCY.name()));
                assertTrue(window.isRepairActionEnabledOnEdt(ResultID.FABRIC_MISSING_DEPENDENCY.name()));
                window.clickRepairOnEdt(ResultID.FORGE_MISSING_DEPENDENCY.name());
            });
            awaitRepairState(window, ResultID.FORGE_MISSING_DEPENDENCY, "SUCCEEDED");
            assertEquals(2, forgeTasks.get());
            assertEquals(1, fabricTasks.get());
        } finally {
            window.close();
            EdtDispatcher.executeAndWait(() -> { });
        }
    }

    /// Restores an explicitly cancelled read-only dependency search without presenting it as a failed repair.
    @Test
    void cancelledDependencySearchReturnsToAvailableState() throws Exception {
        ControlledAnalysisService service = new ControlledAnalysisService();
        ExecutorService worker = Executors.newSingleThreadExecutor();
        LogAnalyzable input = testInput().withMissingDependencySearch(dependencyIds -> Task.runAsync(
                Runnable::run,
                () -> {
                    throw new CancellationException("synthetic search cancellation");
                }));
        Solver solver = Solver.ofMissingDependencySearch(
                input,
                List.of("fabric-api"),
                "game.crash.reason.mod.missing",
                List.of("fabric-api"),
                "Missing dependency: fabric-api");
        SwingGameCrashWindow window = window(service, worker);
        AnalyzeResult<LogAnalyzable> result = new AnalyzeResult<>(
                new ForgeMissingDependencyAnalyzer(),
                ResultID.FORGE_MISSING_DEPENDENCY,
                solver);

        try {
            renderAnalysis(window, service, List.of(result));
            EdtDispatcher.executeAndWait(() -> window.clickRepairOnEdt(
                    ResultID.FORGE_MISSING_DEPENDENCY.name()));
            awaitRepairState(window, ResultID.FORGE_MISSING_DEPENDENCY, "AVAILABLE");

            EdtDispatcher.executeAndWait(() -> {
                assertTrue(window.isRepairActionEnabledOnEdt(ResultID.FORGE_MISSING_DEPENDENCY.name()));
                assertEquals(
                        i18n("game.crash.search_missing_dependency"),
                        window.repairActionTextOnEdt(ResultID.FORGE_MISSING_DEPENDENCY.name()));
                assertEquals(
                        List.of("AVAILABLE", "PREPARING", "AWAITING_SELECTION", "RUNNING", "AVAILABLE"),
                        window.repairStateHistoryOnEdt(ResultID.FORGE_MISSING_DEPENDENCY.name()));
            });
        } finally {
            window.close();
            EdtDispatcher.executeAndWait(() -> { });
        }
    }

    /// Keeps the live row state aligned with a task's real modal-selection and explicit-action phases.
    @Test
    void reflectsSelectionAwareTaskPhaseWhileEachPhaseIsActive() throws Exception {
        ControlledAnalysisService service = new ControlledAnalysisService();
        ExecutorService worker = Executors.newSingleThreadExecutor();
        CountDownLatch selectionActive = new CountDownLatch(1);
        CountDownLatch releaseSelection = new CountDownLatch(1);
        CountDownLatch runningActive = new CountDownLatch(1);
        CountDownLatch releaseRunning = new CountDownLatch(1);
        LogAnalyzable input = testInput().withMissingDependencySearch(dependencyIds ->
                new ControlledPhasedSearchTask(
                        selectionActive,
                        releaseSelection,
                        runningActive,
                        releaseRunning).asOrchestration());
        Solver solver = Solver.ofMissingDependencySearch(
                input,
                List.of("fabric-api"),
                "game.crash.reason.mod.missing",
                List.of("fabric-api"),
                "Missing dependency: fabric-api");
        SwingGameCrashWindow window = window(service, worker);
        AnalyzeResult<LogAnalyzable> result = new AnalyzeResult<>(
                new ForgeMissingDependencyAnalyzer(),
                ResultID.FORGE_MISSING_DEPENDENCY,
                solver);

        try {
            renderAnalysis(window, service, List.of(result));
            EdtDispatcher.executeAndWait(() -> window.clickRepairOnEdt(
                    ResultID.FORGE_MISSING_DEPENDENCY.name()));

            assertTrue(selectionActive.await(5L, TimeUnit.SECONDS));
            awaitRepairState(window, ResultID.FORGE_MISSING_DEPENDENCY, "AWAITING_SELECTION");
            EdtDispatcher.executeAndWait(() -> assertEquals(
                    i18n("game.crash.search_missing_dependency.awaiting_selection"),
                    window.repairStatusTextOnEdt(ResultID.FORGE_MISSING_DEPENDENCY.name())));

            releaseSelection.countDown();
            assertTrue(runningActive.await(5L, TimeUnit.SECONDS));
            awaitRepairState(window, ResultID.FORGE_MISSING_DEPENDENCY, "RUNNING");

            releaseRunning.countDown();
            awaitRepairState(window, ResultID.FORGE_MISSING_DEPENDENCY, "SUCCEEDED");
            EdtDispatcher.executeAndWait(() -> assertEquals(
                    List.of("AVAILABLE", "PREPARING", "AWAITING_SELECTION", "RUNNING", "SUCCEEDED"),
                    window.repairStateHistoryOnEdt(ResultID.FORGE_MISSING_DEPENDENCY.name())));
        } finally {
            releaseSelection.countDown();
            releaseRunning.countDown();
            window.close();
            EdtDispatcher.executeAndWait(() -> { });
        }
    }

    /// Keeps a failed read-only search distinct from an automatic repair and offers a search-specific retry.
    @Test
    void failedDependencySearchUsesSearchSpecificRetryText() throws Exception {
        ControlledAnalysisService service = new ControlledAnalysisService();
        ExecutorService worker = Executors.newSingleThreadExecutor();
        AtomicInteger taskCreations = new AtomicInteger();
        LogAnalyzable input = testInput().withMissingDependencySearch(dependencyIds -> {
            taskCreations.incrementAndGet();
            return Task.runAsync(Runnable::run, () -> {
                throw new IllegalStateException("synthetic search failure");
            });
        });
        Solver solver = Solver.ofMissingDependencySearch(
                input,
                List.of("fabric-api"),
                "game.crash.reason.mod.missing",
                List.of("fabric-api"),
                "Missing dependency: fabric-api");
        SwingGameCrashWindow window = window(service, worker);
        AnalyzeResult<LogAnalyzable> result = new AnalyzeResult<>(
                new ForgeMissingDependencyAnalyzer(),
                ResultID.FORGE_MISSING_DEPENDENCY,
                solver);

        try {
            renderAnalysis(window, service, List.of(result));
            EdtDispatcher.executeAndWait(() -> window.clickRepairOnEdt(
                    ResultID.FORGE_MISSING_DEPENDENCY.name()));
            awaitRepairState(window, ResultID.FORGE_MISSING_DEPENDENCY, "FAILED_RETRYABLE");

            assertEquals(1, taskCreations.get());
            EdtDispatcher.executeAndWait(() -> {
                assertEquals(
                        i18n("game.crash.search_missing_dependency.failed"),
                        window.repairStatusTextOnEdt(ResultID.FORGE_MISSING_DEPENDENCY.name()));
                assertEquals(
                        i18n("game.crash.search_missing_dependency.retry"),
                        window.repairActionTextOnEdt(ResultID.FORGE_MISSING_DEPENDENCY.name()));
                assertTrue(window.isRepairActionEnabledOnEdt(ResultID.FORGE_MISSING_DEPENDENCY.name()));
            });
        } finally {
            window.close();
            EdtDispatcher.executeAndWait(() -> { });
        }
    }

    /// Maps every process-exit classification to a deliberate localized headline.
    @Test
    void mapsExitTypesToLocalizedTitles() {
        assertEquals(i18n("launch.failed.cannot_create_jvm"),
                SwingGameCrashWindow.titleFor(ProcessListener.ExitType.JVM_ERROR));
        assertEquals(i18n("launch.failed.exited_abnormally"),
                SwingGameCrashWindow.titleFor(ProcessListener.ExitType.APPLICATION_ERROR));
        assertEquals(i18n("launch.failed.sigkill"),
                SwingGameCrashWindow.titleFor(ProcessListener.ExitType.SIGKILL));
        assertEquals(i18n("game.crash.title"),
                SwingGameCrashWindow.titleFor(ProcessListener.ExitType.NORMAL));
        assertEquals(i18n("game.crash.title"),
                SwingGameCrashWindow.titleFor(ProcessListener.ExitType.INTERRUPTED));
    }

    /// Creates one native-frame-disabled window around controlled boundaries.
    ///
    /// @param service controlled analysis service
    /// @param worker window-owned executor
    /// @return test window
    private static SwingGameCrashWindow window(
            ControlledAnalysisService service,
            ExecutorService worker) {
        return window(service, worker, new RecordingRepairInteraction(true, null));
    }

    /// Creates one native-frame-disabled window with a controlled repair-interaction boundary.
    ///
    /// @param service controlled analysis service
    /// @param worker window-owned executor
    /// @param repairInteraction deterministic confirmation and candidate selector
    /// @return test window
    private static SwingGameCrashWindow window(
            ControlledAnalysisService service,
            ExecutorService worker,
            SwingGameCrashWindow.RepairInteraction repairInteraction) {
        return window(service, worker, repairInteraction, new RecordingActions());
    }

    /// Creates one native-frame-disabled window with explicit action and repair boundaries.
    ///
    /// @param service controlled analysis service
    /// @param worker window-owned executor
    /// @param repairInteraction deterministic confirmation and candidate selector
    /// @param actions controlled window actions
    /// @return test window
    private static SwingGameCrashWindow window(
            ControlledAnalysisService service,
            ExecutorService worker,
            SwingGameCrashWindow.RepairInteraction repairInteraction,
            GameCrashWindowActions actions) {
        GameCrashWindowModel model = new GameCrashWindowModel(
                ProcessListener.ExitType.APPLICATION_ERROR,
                List.of(new GameCrashWindowModel.Detail("Instance", "Test")),
                testInput(),
                Path.of("missing-latest.log"));
        return new SwingGameCrashWindow(
                model,
                service,
                new GameCrashReasonFormatter(),
                actions,
                worker,
                false,
                repairInteraction);
    }

    /// Renders one immutable analysis result before a test interacts with its repair rows.
    ///
    /// @param window window under test
    /// @param service controlled analysis service
    /// @param results ordered log-analysis results
    private static void renderAnalysis(
            SwingGameCrashWindow window,
            ControlledAnalysisService service,
            @Unmodifiable List<AnalyzeResult<LogAnalyzable>> results) {
        window.show();
        EdtDispatcher.executeAndWait(() -> { });
        service.result.complete(new GameCrashAnalysis(List.of(), results, Set.of()));
        EdtDispatcher.executeAndWait(() -> { });
    }

    /// Waits until asynchronous worker and EDT callbacks publish one expected repair state.
    ///
    /// @param window window under test
    /// @param resultId stable diagnosis identifier
    /// @param expectedState expected lifecycle-state name
    /// @throws InterruptedException if the test thread is interrupted while polling
    private static void awaitRepairState(
            SwingGameCrashWindow window,
            ResultID resultId,
            String expectedState) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5L);
        AtomicBoolean reached = new AtomicBoolean();
        while (System.nanoTime() < deadline) {
            EdtDispatcher.executeAndWait(() -> reached.set(
                    expectedState.equals(window.repairStateOnEdt(resultId.name()))));
            if (reached.get()) {
                return;
            }
            Thread.sleep(10L);
        }
        EdtDispatcher.executeAndWait(() -> assertEquals(
                expectedState,
                window.repairStateOnEdt(resultId.name())));
    }

    /// Waits for a bounded repair fixture latch and converts interruption into a task failure.
    ///
    /// @param latch release latch
    private static void awaitLatch(CountDownLatch latch) {
        try {
            if (!latch.await(5L, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting to release repair task");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting to release repair task", exception);
        }
    }

    /// Creates a Java-runtime solver with stable candidates and a controlled fresh-task boundary.
    ///
    /// @param candidates immutable runtime candidates
    /// @param taskFactory selected-candidate task factory
    /// @return candidate-aware solver
    private static Solver candidateSolver(
            @Unmodifiable List<LogAnalyzable.JavaRuntimeCandidate> candidates,
            Function<@Nullable String, Task<?>> taskFactory) {
        LogAnalyzable.JavaRuntimeRepair repair = new CandidateRepair(candidates, taskFactory);
        return Solver.ofUninstallJRE(new LogAnalyzable(
                "1.20.4",
                "net.minecraft.client.main.Main",
                ProcessListener.ExitType.APPLICATION_ERROR,
                OperatingSystem.WINDOWS,
                936,
                Path.of("C:/Games/Minecraft/.minecraft"),
                new LogAnalyzable.JavaRuntimeContext(
                        Path.of("C:/Java/bin/javaw.exe"),
                        17,
                        8,
                        Bits.BIT_64,
                        repair),
                4096,
                List.of("UnsupportedClassVersionError")));
    }

    /// Creates one production-shaped read-only search solver with a controlled task execution counter.
    ///
    /// @param dependencyId missing dependency identifier
    /// @param executions task execution counter
    /// @return repeatable search solver
    private static Solver missingDependencySearchSolver(String dependencyId, AtomicInteger executions) {
        LogAnalyzable input = testInput().withMissingDependencySearch(dependencyIds -> Task.runAsync(
                Runnable::run,
                executions::incrementAndGet));
        return Solver.ofMissingDependencySearch(
                input,
                List.of(dependencyId),
                "game.crash.reason.mod.missing",
                List.of(dependencyId),
                "Missing dependency: " + dependencyId);
    }

    /// Creates the immutable Core analysis input used by headless window lifecycle tests.
    ///
    /// @return deterministic launch-log analysis input
    private static LogAnalyzable testInput() {
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
                Bits.BIT_64,
                4096,
                List.of(new Log("captured").getLog()));
    }

    /// Search task fixture that blocks after publishing each user-visible execution phase.
    @NotNullByDefault
    private static final class ControlledPhasedSearchTask extends Task<@Nullable Void> {
        /// Signals that selection is actively awaiting user input.
        private final CountDownLatch selectionActive;

        /// Releases the simulated selection.
        private final CountDownLatch releaseSelection;

        /// Signals that the selected action is actively running.
        private final CountDownLatch runningActive;

        /// Releases the simulated running action.
        private final CountDownLatch releaseRunning;

        /// Creates one stopped phase-aware search task.
        ///
        /// @param selectionActive selection-entry signal
        /// @param releaseSelection selection release gate
        /// @param runningActive running-entry signal
        /// @param releaseRunning running release gate
        private ControlledPhasedSearchTask(
                CountDownLatch selectionActive,
                CountDownLatch releaseSelection,
                CountDownLatch runningActive,
                CountDownLatch releaseRunning) {
            this.selectionActive = Objects.requireNonNull(selectionActive, "selectionActive");
            this.releaseSelection = Objects.requireNonNull(releaseSelection, "releaseSelection");
            this.runningActive = Objects.requireNonNull(runningActive, "runningActive");
            this.releaseRunning = Objects.requireNonNull(releaseRunning, "releaseRunning");
            getProperties().put(RepairTaskPhase.TASK_PROPERTY, RepairTaskPhase.PREPARING);
        }

        /// Publishes and blocks within the two observable phases.
        @Override
        public void execute() {
            publishPhase(RepairTaskPhase.AWAITING_SELECTION);
            selectionActive.countDown();
            awaitLatch(releaseSelection);
            publishPhase(RepairTaskPhase.RUNNING);
            runningActive.countDown();
            awaitLatch(releaseRunning);
        }

        /// Publishes a phase through the ordinary task-property listener boundary.
        ///
        /// @param phase phase to publish
        private void publishPhase(RepairTaskPhase phase) {
            getProperties().put(RepairTaskPhase.TASK_PROPERTY, Objects.requireNonNull(phase, "phase"));
            notifyPropertiesChanged();
        }
    }

    /// Candidate-aware Java repair boundary used to verify cancellation before task creation.
    @NotNullByDefault
    private static final class CandidateRepair implements LogAnalyzable.JavaRuntimeRepair {
        /// Immutable runtime choices returned to the crash window.
        private final @Unmodifiable List<LogAnalyzable.JavaRuntimeCandidate> candidates;

        /// Controlled factory receiving the selected candidate identifier.
        private final Function<@Nullable String, Task<?>> taskFactory;

        /// Creates one candidate-aware repair fixture.
        ///
        /// @param candidates immutable runtime candidates
        /// @param taskFactory selected-candidate task factory
        private CandidateRepair(
                @Unmodifiable List<LogAnalyzable.JavaRuntimeCandidate> candidates,
                Function<@Nullable String, Task<?>> taskFactory) {
            this.candidates = List.copyOf(Objects.requireNonNull(candidates, "candidates"));
            this.taskFactory = Objects.requireNonNull(taskFactory, "taskFactory");
        }

        /// Creates a fresh task through the automatic candidate path.
        ///
        /// @return fresh stopped repair task
        @Override
        public Task<?> createTask() {
            return createTask(null);
        }

        /// Returns the stable runtime candidates captured for this fixture.
        ///
        /// @return immutable candidate snapshot
        @Override
        public @Unmodifiable List<LogAnalyzable.JavaRuntimeCandidate> candidates() {
            return candidates;
        }

        /// Creates a fresh task for the explicitly selected candidate.
        ///
        /// @param candidateId selected candidate identifier, or null for automatic selection
        /// @return fresh stopped repair task
        @Override
        public Task<?> createTask(@Nullable String candidateId) {
            return Objects.requireNonNull(taskFactory.apply(candidateId), "taskFactory result");
        }
    }

    /// Deterministic modal-interaction fixture that can confirm repairs and select or cancel a runtime candidate.
    @NotNullByDefault
    private static final class RecordingRepairInteraction implements SwingGameCrashWindow.RepairInteraction {
        /// Whether the persistent repair confirmation succeeds.
        private final boolean confirmed;

        /// Candidate identifier selected by the chooser, or null to cancel.
        private final @Nullable String selectedCandidateId;

        /// Candidate supplied as the chooser's initial selection.
        private @Nullable LogAnalyzable.JavaRuntimeCandidate recommendedCandidate;

        /// Creates one controlled repair interaction.
        ///
        /// @param confirmed whether repair confirmation succeeds
        /// @param selectedCandidateId selected candidate identifier, or null to cancel
        private RecordingRepairInteraction(boolean confirmed, @Nullable String selectedCandidateId) {
            this.confirmed = confirmed;
            this.selectedCandidateId = selectedCandidateId;
        }

        /// Returns the configured confirmation response without opening a native dialog.
        ///
        /// @param parent crash-window content used as the modal parent
        /// @return configured confirmation response
        @Override
        public boolean confirm(javax.swing.JPanel parent) {
            Objects.requireNonNull(parent, "parent");
            return confirmed;
        }

        /// Records the recommended candidate and returns the configured selection or cancellation.
        ///
        /// @param parent crash-window content used as the modal parent
        /// @param candidates immutable candidate snapshot in display order
        /// @param recommended candidate preselected by the launcher
        /// @return configured candidate, or null to cancel
        @Override
        public @Nullable LogAnalyzable.JavaRuntimeCandidate selectJavaRuntime(
                javax.swing.JPanel parent,
                @Unmodifiable List<LogAnalyzable.JavaRuntimeCandidate> candidates,
                LogAnalyzable.JavaRuntimeCandidate recommended) {
            Objects.requireNonNull(parent, "parent");
            recommendedCandidate = Objects.requireNonNull(recommended, "recommended");
            @Nullable String selectedId = selectedCandidateId;
            if (selectedId == null) {
                return null;
            }
            return candidates.stream()
                    .filter(candidate -> selectedId.equals(candidate.id()))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Unknown candidate: " + selectedId));
        }
    }

    /// Exposes a manually completed analysis future.
    @NotNullByDefault
    private static final class ControlledAnalysisService implements GameCrashAnalysisService {
        /// Future completed by each test after the window starts analysis.
        private final CompletableFuture<GameCrashAnalysis> result = new CompletableFuture<>();

        /// Returns the controlled future without touching its inputs.
        ///
        /// @param logAnalyzable immutable launch context and captured-output snapshot
        /// @param latestLog on-disk latest-log path
        /// @return manually completed diagnosis
        @Override
        public CompletionStage<GameCrashAnalysis> analyze(
                LogAnalyzable logAnalyzable,
                Path latestLog) {
            return result;
        }
    }

    /// Keeps export, log-window, and desktop effects inert for lifecycle tests.
    @NotNullByDefault
    private static final class RecordingActions implements GameCrashWindowActions {
        /// Number of times the owning crash window released this boundary.
        private final AtomicInteger closeCalls = new AtomicInteger();

        /// Returns an already completed inert export path.
        ///
        /// @return inert export stage
        @Override
        public CompletionStage<Path> exportCrashLogs() {
            return CompletableFuture.completedFuture(Path.of("crash.zip"));
        }

        /// Performs no file-manager side effect.
        ///
        /// @param file exported file
        @Override
        public void revealFile(Path file) {
        }

        /// Performs no game-log-window side effect.
        @Override
        public void showGameLogs() {
        }

        /// Performs no desktop browsing side effect.
        ///
        /// @param destination link destination
        @Override
        public void openLink(URI destination) {
        }

        /// Records release of this action boundary.
        @Override
        public void close() {
            closeCalls.incrementAndGet();
        }
    }
}
