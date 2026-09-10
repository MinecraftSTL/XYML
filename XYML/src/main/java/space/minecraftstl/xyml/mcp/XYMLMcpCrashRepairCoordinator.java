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
package space.minecraftstl.xyml.mcp;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import space.minecraftstl.xyml.game.CrashReportAnalyzer;
import space.minecraftstl.xyml.game.analyzer.AnalyzeResult;
import space.minecraftstl.xyml.game.analyzer.LogAnalyzable;
import space.minecraftstl.xyml.game.analyzer.LogAnalyzer;
import space.minecraftstl.xyml.game.analyzer.RepairCheckpoint;
import space.minecraftstl.xyml.game.analyzer.RepairActionDescriptor;
import space.minecraftstl.xyml.game.analyzer.ResultID;
import space.minecraftstl.xyml.game.analyzer.Solver;
import space.minecraftstl.xyml.task.Task;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import static space.minecraftstl.xyml.util.logging.Logger.LOG;

/// Retains bounded XYAT analyses and mediates retryable MCP repair execution.
///
/// Supplied log text is deliberately analysis-only. Executable plans require launcher-owned input, an unchanged source
/// fingerprint, an application task boundary, and an action explicitly allowed by the MCP policy in this class.
@NotNullByDefault
public final class XYMLMcpCrashRepairCoordinator implements AutoCloseable {
    /// Stable block reason for solutions derived from caller-supplied logs.
    public static final String EXTERNAL_LOG_NOT_EXECUTABLE = "external_log_not_executable";

    /// Stable block reason for application actions unavailable in the current context.
    public static final String APPLICATION_BOUNDARY_UNAVAILABLE = "application_boundary_unavailable";

    /// Stable block reason for manual guidance with no automatic task.
    public static final String MANUAL_GUIDANCE = "manual_guidance";

    /// Stable block reason for an executable Core action not approved for MCP.
    public static final String UNSUPPORTED_ACTION_TYPE = "unsupported_action_type";

    /// Serializes analysis, plan, execution claims, pruning, and shutdown.
    private final Object stateLock = new Object();

    /// Time source for expiry and result timestamps.
    private final Clock clock;

    /// Lifetime of a stored crash analysis.
    private final Duration analysisLifetime;

    /// Lifetime of a repair plan, including retryable failed attempts.
    private final Duration planLifetime;

    /// Maximum retained analysis count.
    private final int maximumAnalyses;

    /// Maximum retained plan count.
    private final int maximumPlans;

    /// Bounded task-operation owner.
    private final McpTaskOperationRegistry operations;

    /// Insertion-ordered analyses indexed by opaque identifier.
    private final Map<String, AnalysisSession> analyses = new LinkedHashMap<>();

    /// Insertion-ordered plans indexed by opaque identifier.
    private final Map<String, RepairPlan> plans = new LinkedHashMap<>();

    /// Operation-to-plan links used to publish retry state without retaining task objects.
    private final Map<String, String> operationPlans = new LinkedHashMap<>();

    /// Whether this coordinator has released its application boundaries.
    private boolean closed;

    /// Creates a production coordinator with short-lived analysis and plan state.
    public XYMLMcpCrashRepairCoordinator() {
        this(
                Clock.systemUTC(),
                Duration.ofMinutes(15),
                Duration.ofMinutes(5),
                128,
                256,
                new McpTaskOperationRegistry());
    }

    /// Creates a coordinator with explicit retention policies and operation owner.
    ///
    /// @param clock timestamp source
    /// @param analysisLifetime retained analysis lifetime
    /// @param planLifetime unconsumed plan lifetime
    /// @param maximumAnalyses maximum retained analyses
    /// @param maximumPlans maximum retained plans
    /// @param operations task-operation owner
    XYMLMcpCrashRepairCoordinator(
            Clock clock,
            Duration analysisLifetime,
            Duration planLifetime,
            int maximumAnalyses,
            int maximumPlans,
            McpTaskOperationRegistry operations) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.analysisLifetime = requirePositive(analysisLifetime, "analysisLifetime");
        this.planLifetime = requirePositive(planLifetime, "planLifetime");
        if (maximumAnalyses <= 0 || maximumPlans <= 0) {
            throw new IllegalArgumentException("Analysis and plan capacities must be positive");
        }
        this.maximumAnalyses = maximumAnalyses;
        this.maximumPlans = maximumPlans;
        this.operations = Objects.requireNonNull(operations, "operations");
        this.operations.setCompletionListener(this::observeOperationCompletion);
    }

    /// Updates a linked plan as soon as its operation reaches a terminal state.
    ///
    /// This callback closes the retention gap between the bounded operation registry and a longer-lived repair plan:
    /// a caller no longer has to poll before the operation snapshot expires.
    ///
    /// @param operationId completed operation identifier
    private void observeOperationCompletion(String operationId) {
        @Nullable String planId;
        synchronized (stateLock) {
            planId = operationPlans.get(operationId);
        }
        if (planId == null) {
            return;
        }
        try {
            Map<String, Object> operation = operations.status(operationId);
            synchronized (stateLock) {
                @Nullable RepairPlan plan = plans.get(planId);
                if (plan != null) {
                    updatePlanFromOperation(plan, operationId, operation);
                }
            }
        } catch (IllegalArgumentException ignored) {
            synchronized (stateLock) {
                markExpiredOperationLocked(planId, operationId);
            }
        } catch (RuntimeException | Error callbackFailure) {
            // Completion publication is advisory. The registry has already committed its terminal state, so a
            // coordinator callback failure must never turn that task outcome into an uncaught task-listener failure.
            LOG.warning("Unable to publish crash repair operation completion " + operationId, callbackFailure);
        }
    }

    /// Runs XYAT and registers the immutable diagnoses for later repair planning.
    ///
    /// @param instanceId analyzed instance identifier
    /// @param source input ownership class
    /// @param fingerprint SHA-256 fingerprint of the analyzed log source
    /// @param input immutable contextual analyzer input
    /// @param sourceValidator validator proving launcher-owned input is still current, or null for supplied text
    /// @return analysis identifier, source policy, and immutable XYAT diagnosis list
    public @Unmodifiable Map<String, Object> analyze(
            String instanceId,
            AnalysisSource source,
            String fingerprint,
            LogAnalyzable input,
            @Nullable SourceValidator sourceValidator) {
        Objects.requireNonNull(instanceId, "instanceId");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(fingerprint, "fingerprint");
        Objects.requireNonNull(input, "input");
        if (source == AnalysisSource.LAUNCHER_LATEST_LOG && sourceValidator == null) {
            throw new IllegalArgumentException("Launcher-owned analysis requires source revalidation");
        }
        if (source == AnalysisSource.PROVIDED_LOG && sourceValidator != null) {
            throw new IllegalArgumentException("Provided log text cannot have an executable source validator");
        }

        @Unmodifiable List<AnalyzeResult<LogAnalyzable>> results = LogAnalyzer.analyzeAll(input);
        Instant createdAt = clock.instant();
        AnalysisSession session = new AnalysisSession(
                UUID.randomUUID().toString(),
                instanceId,
                source,
                fingerprint,
                createdAt,
                createdAt.plus(analysisLifetime),
                sourceValidator,
                solutions(results, input, source));
        synchronized (stateLock) {
            requireOpenLocked();
            pruneLocked();
            ensureAnalysisCapacityLocked();
            analyses.put(session.id, session);
        }
        return analysisSnapshot(session);
    }

    /// Creates a short-lived execution plan for one analyzed solution.
    ///
    /// Non-executable solutions return their stable block reason without allocating a plan identifier.
    ///
    /// @param analysisId opaque analysis identifier returned by [#analyze]
    /// @param solutionId stable solution identifier from the diagnosis list
    /// @return immutable plan description or non-executable explanation
    public @Unmodifiable Map<String, Object> plan(String analysisId, String solutionId) {
        return plan(analysisId, solutionId, null);
    }

    /// Creates a short-lived execution plan and optionally records one Java-runtime candidate selection.
    ///
    /// Candidate selection is metadata only. No task is created and no launcher state is changed by this method.
    /// When more than one candidate is available, callers may supply an identifier here or later to [#execute].
    ///
    /// @param analysisId opaque analysis identifier
    /// @param solutionId stable solution identifier from the diagnosis list
    /// @param candidateId selected Java candidate, or null to defer selection
    /// @return immutable plan description or non-executable explanation
    public @Unmodifiable Map<String, Object> plan(
            String analysisId,
            String solutionId,
            @Nullable String candidateId) {
        AnalysisSession session;
        Solution solution;
        ExecutionDecision decision;
        synchronized (stateLock) {
            requireOpenLocked();
            pruneLocked();
            session = requireAnalysisLocked(analysisId);
            solution = requireSolution(session, solutionId);
            decision = executionDecision(session, solution);
            if (!decision.executable()) {
                return planSnapshot(session, solution, null, decision);
            }
            @Nullable String selectedCandidate = resolveCandidate(solution.candidates, candidateId, false);
            ensurePlanCapacityLocked();
            Instant createdAt = clock.instant();
            RepairPlan plan = new RepairPlan(
                    UUID.randomUUID().toString(),
                    session.id,
                    solution.id,
                    createdAt,
                    createdAt.plus(planLifetime),
                    selectedCandidate);
            plans.put(plan.id, plan);
            return planSnapshot(session, solution, plan, decision);
        }
    }

    /// Claims one repair plan, revalidates its launcher-owned source, and starts a fresh task.
    ///
    /// A failed or cancelled attempt leaves the plan retryable; successful plans remain terminal.
    ///
    /// @param planId opaque repair-plan identifier
    /// @return immutable asynchronous operation status
    public @Unmodifiable Map<String, Object> execute(String planId) {
        return execute(planId, null);
    }

    /// Claims one repair plan with an optional Java-runtime candidate selection.
    ///
    /// If a solver exposes multiple candidates, an explicit candidate is required unless one was recorded while
    /// planning. Missing selection is a non-consuming validation error: the plan remains available for a later call.
    ///
    /// @param planId opaque repair-plan identifier
    /// @param candidateId selected Java candidate, or null to use the planned/default candidate
    /// @return immutable asynchronous operation status
    public @Unmodifiable Map<String, Object> execute(
            String planId,
            @Nullable String candidateId) {
        return executeInternal(planId, candidateId, false);
    }

    /// Executes one plan while optionally consuming a reservation made by [#retry(String)].
    ///
    /// The reservation closes the gap between retry cleanup and the fresh claim. Public callers cannot bypass it,
    /// while the retry owner can keep the plan unavailable to competing execute/retry calls until the new operation
    /// has been registered.
    ///
    /// @param planId opaque repair-plan identifier
    /// @param candidateId selected Java candidate, or null to use the planned/default candidate
    /// @param retryReservation whether this call owns the plan's retry reservation
    /// @return immutable asynchronous operation status
    private @Unmodifiable Map<String, Object> executeInternal(
            String planId,
            @Nullable String candidateId,
            boolean retryReservation) {
        AnalysisSession session;
        Solution solution;
        @Nullable RepairPlan claimedPlan = null;
        RepairCheckpoint checkpoint = RepairCheckpoint.initial();
        try {
            synchronized (stateLock) {
                requireOpenLocked();
                pruneLocked();
                RepairPlan plan = requirePlanLocked(planId);
                if (plan.retryInProgress != retryReservation) {
                    throw new IllegalStateException(
                            retryReservation
                                    ? "Crash repair retry reservation is no longer available: " + planId
                                    : "Crash repair plan is being retried: " + planId);
                }
                if (plan.state != PlanState.AVAILABLE) {
                    if (plan.state == PlanState.RUNNING) {
                        refreshRunningPlanLocked(plan);
                    }
                    throw new IllegalStateException(
                            "Crash repair plan is not available; use retry for a failed attempt: " + planId);
                }
                session = requireAnalysisLocked(plan.analysisId);
                solution = requireSolution(session, plan.solutionId);
                @Nullable String requestedCandidate = candidateId != null ? candidateId : plan.candidateId;
                @Nullable String selectedCandidate = resolveCandidate(
                        solution.candidates,
                        requestedCandidate,
                        true);
                plan.candidateId = selectedCandidate;
                ExecutionDecision decision = executionDecision(session, solution);
                if (!decision.executable()) {
                    throw new IllegalStateException("Crash repair plan is no longer executable: " + decision.reason());
                }
                // Claim only after non-consuming validation (including candidate selection). Failures after this
                // point are represented as retryable attempts instead of silently consuming the plan.
                claimedPlan = plan;
                plan.state = PlanState.RUNNING;
                plan.attempts++;
                plan.failureType = null;
                plan.failureMessage = null;
                // The previous operation must not be allowed to publish a late terminal state while this fresh
                // attempt is being registered. The new operation ID is installed atomically below.
                plan.lastOperationId = null;
                checkpoint = plan.checkpoint();
            }
        } catch (RuntimeException failure) {
            if (claimedPlan != null) {
                markPlanRetryable(claimedPlan, failure);
            }
            if (retryReservation && claimedPlan == null) {
                clearRetryReservation(planId);
            }
            throw failure;
        } catch (Error failure) {
            if (claimedPlan != null) {
                markPlanRetryable(claimedPlan, failure);
            }
            if (retryReservation && claimedPlan == null) {
                clearRetryReservation(planId);
            }
            throw failure;
        }
        RepairPlan plan = Objects.requireNonNull(claimedPlan, "claimed crash repair plan");
        RepairCheckpoint executionCheckpoint = checkpoint;

        Map<String, Object> operation;
        try {
            SourceValidator validator = Objects.requireNonNull(session.sourceValidator,
                    "Executable crash repair plan has no source validator");
            RepairActionDescriptor descriptor = solution.solver.repairAction();
            operation = operations.startForOwner(
                    descriptor.actionType().name(),
                    descriptor.actionType() == RepairActionDescriptor.ActionType.OPEN_MOD_SEARCH,
                    executionCheckpoint,
                    () -> Objects.requireNonNull(
                            validator.createTask(executionCheckpoint),
                            "Crash source validator did not create a task")
                            .thenComposeAsync(() -> Objects.requireNonNull(
                                    solution.solver.createTask(plan.candidateId, executionCheckpoint),
                                    "Crash repair solver did not create a task"))
                            .asOrchestration());
        } catch (RuntimeException failure) {
            markPlanRetryable(plan, failure);
            clearRetryReservation(plan);
            throw failure;
        } catch (Error failure) {
            markPlanRetryable(plan, failure);
            clearRetryReservation(plan);
            throw failure;
        }

        @Nullable Object operationIdValue = operation.get("operation_id");
        if (!(operationIdValue instanceof String operationId) || operationId.isBlank()) {
            IllegalStateException invalidOperation = new IllegalStateException(
                    "Crash repair operation did not return an operation identifier");
            markPlanRetryable(plan, invalidOperation);
            clearRetryReservation(plan);
            throw invalidOperation;
        }
        List<String> supersededOperationIds;
        boolean handoffPublished;
        synchronized (stateLock) {
            if (closed) {
                // The owner reservation was installed atomically by the registry, but close() may have won the
                // coordinator lock before this hand-off. Do not leave an unlinked pinned operation behind.
                supersededOperationIds = List.of();
                handoffPublished = false;
            } else {
                supersededOperationIds = operationPlans.entrySet().stream()
                        .filter(entry -> entry.getValue().equals(plan.id) && !entry.getKey().equals(operationId))
                        .map(Map.Entry::getKey)
                        .toList();
                plan.lastOperationId = operationId;
                operationPlans.put(operationId, plan.id);
                // Keep historical operation-to-plan links until the plan itself is pruned. This lets a caller inspect
                // an older terminal attempt and still receive the plan's current operation identifier, while the
                // owner-retention pins below are released after the new link is visible.
                plan.retryInProgress = false;
                handoffPublished = true;
            }
        }
        if (!handoffPublished) {
            operations.releaseOwnerRetention(operationId);
            IllegalStateException closedFailure = new IllegalStateException("Crash repair coordinator is closed");
            markPlanRetryable(plan, closedFailure);
            clearRetryReservation(plan);
            throw closedFailure;
        }
        for (String supersededOperationId : supersededOperationIds) {
            operations.releaseOwnerRetention(supersededOperationId);
        }
        updatePlanFromOperation(plan, operationId, operation);
        return operationWithPlan(operation, plan);
    }

    /// Retries the latest failed or cancelled attempt using a fresh task instance.
    ///
    /// @param planId retryable repair-plan identifier
    /// @return immutable new operation status
    public @Unmodifiable Map<String, Object> retry(String planId) {
        RepairPlan plan;
        @Nullable String cleanupOperationId;
        synchronized (stateLock) {
            requireOpenLocked();
            pruneLocked();
            plan = requirePlanLocked(planId);
            if (plan.state == PlanState.RUNNING) {
                refreshRunningPlanLocked(plan);
            }
            if (plan.retryInProgress) {
                throw new IllegalStateException("Crash repair plan retry is already in progress: " + planId);
            }
            if (!plan.state.retryable() || plan.state == PlanState.AVAILABLE) {
                throw new IllegalStateException("Crash repair plan is not retryable: " + planId);
            }
            plan.retryInProgress = true;
            cleanupOperationId = plan.state == PlanState.BLOCKED_RESIDUAL ? plan.lastOperationId : null;
        }
        try {
            if (cleanupOperationId != null) {
                Map<String, Object> cleanup;
                try {
                    cleanup = operations.retryResourceCleanup(cleanupOperationId);
                } catch (IllegalArgumentException cleanupUnavailable) {
                    // A residual operation must never be treated as an available plan: its leases may still be held
                    // even when the registry link is unavailable. Surface a structured retryable failure and keep
                    // the residual state so a later bounded cleanup attempt can recover it.
                    String cleanupMessage =
                            "Crash repair residual cleanup is unavailable; retry cleanup before repair";
                    synchronized (stateLock) {
                        plan.state = PlanState.BLOCKED_RESIDUAL;
                        plan.failureType = cleanupUnavailable.getClass().getName();
                        plan.failureMessage = cleanupMessage;
                        plan.retryInProgress = false;
                    }
                    Map<String, Object> unavailable = new LinkedHashMap<>();
                    unavailable.put("operation_id", cleanupOperationId);
                    unavailable.put("status", "FAILED");
                    unavailable.put("retryable", true);
                    unavailable.put("cleanup_succeeded", false);
                    unavailable.put("cleanup_unavailable", true);
                    unavailable.put("failure_type", cleanupUnavailable.getClass().getName());
                    unavailable.put("failure_message", cleanupMessage);
                    unavailable.put("residual_resources", List.of("resource cleanup operation unavailable"));
                    return operationWithPlan(unavailable, plan);
                }
                if (!Boolean.TRUE.equals(cleanup.get("cleanup_succeeded"))) {
                    synchronized (stateLock) {
                        plan.state = PlanState.BLOCKED_RESIDUAL;
                        plan.retryInProgress = false;
                    }
                    return operationWithPlan(cleanup, plan);
                }
                // A successful task may be blocked only by its own lease cleanup. Once that cleanup succeeds, the
                // original operation is complete and must not be replayed. Failed or cancelled operations continue
                // into a fresh attempt below.
                if ("SUCCEEDED".equals(String.valueOf(cleanup.get("status")))) {
                    synchronized (stateLock) {
                        plan.state = PlanState.SUCCEEDED;
                        plan.failureType = null;
                        plan.failureMessage = null;
                        plan.failedSteps = List.of();
                        plan.resumeFromStep = null;
                        plan.retryInProgress = false;
                    }
                    return operationWithPlan(cleanup, plan);
                }
            }
            synchronized (stateLock) {
                plan.state = PlanState.AVAILABLE;
            }
            return executeInternal(planId, null, true);
        } catch (RuntimeException failure) {
            clearRetryReservation(plan);
            throw failure;
        } catch (Error failure) {
            clearRetryReservation(plan);
            throw failure;
        }
    }

    /// Returns the latest state of one repair operation.
    ///
    /// @param operationId opaque operation identifier
    /// @return immutable operation status
    public @Unmodifiable Map<String, Object> status(String operationId) {
        Map<String, Object> result = operations.status(operationId);
        @Nullable String planId;
        synchronized (stateLock) {
            planId = operationPlans.get(operationId);
        }
        if (planId == null) {
            return result;
        }
        RepairPlan plan;
        synchronized (stateLock) {
            @Nullable RepairPlan current = plans.get(planId);
            if (current == null) {
                return result;
            }
            plan = current;
        }
        updatePlanFromOperation(plan, operationId, result);
        return operationWithPlan(result, plan);
    }

    /// Requests cooperative cancellation for one repair operation.
    ///
    /// @param operationId opaque operation identifier
    /// @return immutable operation status including whether cancellation was accepted
    public @Unmodifiable Map<String, Object> cancel(String operationId) {
        Map<String, Object> result = operations.cancel(operationId);
        @Nullable String planId;
        synchronized (stateLock) {
            planId = operationPlans.get(operationId);
        }
        if (planId == null) {
            return result;
        }
        synchronized (stateLock) {
            @Nullable RepairPlan plan = plans.get(planId);
            if (plan != null) {
                updatePlanFromOperation(plan, operationId, result);
                return operationWithPlan(result, plan);
            }
        }
        return result;
    }

    /// Clears analyses and plans, then requests cancellation of active operations.
    ///
    /// This is an application/process-shutdown boundary rather than a hot-restart recovery operation. The operation
    /// registry keeps task-owned residual-resource markers while cancellation is in flight; after this coordinator is
    /// closed there is intentionally no retry route through the discarded analysis plans, and a subsequent process
    /// starts with a fresh in-memory resource registry.
    @Override
    public void close() {
        List<String> retainedOperations;
        synchronized (stateLock) {
            if (closed) {
                return;
            }
            closed = true;
            analyses.clear();
            plans.clear();
            retainedOperations = List.copyOf(operationPlans.keySet());
            operationPlans.clear();
        }
        operations.setCompletionListener(null);
        for (String operationId : retainedOperations) {
            operations.releaseOwnerRetention(operationId);
        }
        operations.close();
    }

    /// Converts ordered analyzer results into a stable solution index.
    ///
    /// Legacy crash-rule matches are captured at this boundary so a later response never has to re-read or re-analyze
    /// mutable log state. Only rules explicitly superseded by one of the retained limited diagnoses are copied into
    /// each cause snapshot; unrelated legacy matches remain owned by the raw analysis response.
    ///
    /// @param results ordered limited analyzer results
    /// @param input immutable analysis input used to capture legacy evidence
    /// @param source ownership class of the analyzed log
    /// @return immutable solution index
    private static @Unmodifiable Map<String, Solution> solutions(
            @Unmodifiable List<AnalyzeResult<LogAnalyzable>> results,
            LogAnalyzable input,
            AnalysisSource source) {
        Map<String, Solution> result = new LinkedHashMap<>();
        @Unmodifiable Map<CrashReportAnalyzer.Rule, CrashReportAnalyzer.Result> legacyEvidence =
                legacyEvidence(input.logText());
        for (AnalyzeResult<LogAnalyzable> diagnosis : results) {
            String solutionId = diagnosis.resultId().name();
            result.putIfAbsent(solutionId, new Solution(solutionId, diagnosis, source, legacyEvidence));
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(result));
    }

    /// Captures the first legacy match for every rule in stable enum order.
    ///
    /// The raw matcher retains the complete source string internally. This method deliberately stores only the matcher
    /// object in the short-lived construction scope; response snapshots copy a bounded match string and never expose
    /// the source log itself.
    ///
    /// @param logText complete immutable log text
    /// @return immutable rule-to-match snapshot
    private static @Unmodifiable Map<CrashReportAnalyzer.Rule, CrashReportAnalyzer.Result> legacyEvidence(
            String logText) {
        Map<CrashReportAnalyzer.Rule, CrashReportAnalyzer.Result> result = new LinkedHashMap<>();
        for (CrashReportAnalyzer.Result match : CrashReportAnalyzer.analyze(
                Objects.requireNonNull(logText, "logText"))) {
            result.putIfAbsent(match.rule(), match);
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(result));
    }

    /// Captures only legacy matches explicitly owned by one limited diagnosis.
    ///
    /// A raw match is retained as evidence rather than silently discarded. The relation list is intentionally fixed and
    /// mirrors the desktop analyzer policy; an unknown future result ID produces no speculative suppression.
    ///
    /// @param resultId limited diagnosis that owns the suppression
    /// @param source ownership class of the analyzed log
    /// @param legacyEvidence immutable raw-rule matches
    /// @return immutable suppressed-evidence records in rule declaration order
    private static @Unmodifiable List<@Unmodifiable Map<String, Object>> suppressedEvidence(
            ResultID resultId,
            AnalysisSource source,
            @Unmodifiable Map<CrashReportAnalyzer.Rule, CrashReportAnalyzer.Result> legacyEvidence) {
        List<@Unmodifiable Map<String, Object>> result = new ArrayList<>();
        for (CrashReportAnalyzer.Rule rule : supersededRules(Objects.requireNonNull(resultId, "resultId"))) {
            @Nullable CrashReportAnalyzer.Result match = legacyEvidence.get(rule);
            if (match == null) {
                continue;
            }
            Map<String, Object> evidence = new LinkedHashMap<>();
            evidence.put("result_id", rule.name());
            evidence.put("rule", rule.name());
            evidence.put("suppressed_by", resultId.name());
            evidence.put("matched_text", boundedEvidence(match.matcher().group()));
            evidence.put("evidence_sources", List.of(
                    Objects.requireNonNull(source, "source").externalName));
            result.add(Collections.unmodifiableMap(new LinkedHashMap<>(evidence)));
        }
        return List.copyOf(result);
    }

    /// Returns the fixed legacy-rule supersession relation for one limited result ID.
    ///
    /// @param resultId limited diagnosis identifier
    /// @return immutable legacy rules hidden by that diagnosis
    private static @Unmodifiable List<CrashReportAnalyzer.Rule> supersededRules(ResultID resultId) {
        return switch (Objects.requireNonNull(resultId, "resultId")) {
            case CODE_PAGE -> List.of(CrashReportAnalyzer.Rule.UNSATISFIED_LINK_ERROR);
            case JRE_32BIT -> List.of(CrashReportAnalyzer.Rule.JVM_32BIT);
            case JRE_VERSION -> List.of(
                    CrashReportAnalyzer.Rule.NEED_JDK11,
                    CrashReportAnalyzer.Rule.TOO_OLD_JAVA,
                    CrashReportAnalyzer.Rule.JDK_9,
                    CrashReportAnalyzer.Rule.JAVA_VERSION_IS_TOO_HIGH);
            case VIRTUAL_MEMORY -> List.of(
                    CrashReportAnalyzer.Rule.MEMORY_EXCEEDED,
                    CrashReportAnalyzer.Rule.OUT_OF_MEMORY);
            case FORGE_MISSING_DEPENDENCY -> List.of(CrashReportAnalyzer.Rule.FORGEMOD_RESOLUTION);
            case FABRIC_MISSING_DEPENDENCY -> List.of(
                    CrashReportAnalyzer.Rule.MOD_RESOLUTION,
                    CrashReportAnalyzer.Rule.MOD_RESOLUTION_MISSING,
                    CrashReportAnalyzer.Rule.FABRIC_WARNINGS);
        };
    }

    /// Bounds a matcher fragment before it is retained in an MCP response.
    ///
    /// @param evidence raw matcher fragment
    /// @return normalized bounded evidence text
    private static String boundedEvidence(String evidence) {
        String normalized = Objects.requireNonNull(evidence, "evidence")
                .replaceAll("[\\r\\n\\t]+", " ")
                .strip();
        return normalized.length() <= 512 ? normalized : normalized.substring(0, 512);
    }

    /// Refreshes a running plan from its operation snapshot before accepting a retry.
    private void refreshRunningPlanLocked(RepairPlan plan) {
        @Nullable String operationId = plan.lastOperationId;
        if (operationId == null) {
            return;
        }
        try {
            updatePlanFromOperation(plan, operationId, operations.status(operationId));
        } catch (IllegalArgumentException ignored) {
            // A missing operation can no longer prove success or failure. Do not leave the plan permanently RUNNING:
            // make the uncertainty explicitly retryable and remove the stale owner link so a later retry can publish a
            // fresh operation without waiting for a callback that can never arrive.
            markExpiredOperationLocked(plan.id, operationId);
        }
    }

    /// Converts a missing linked operation into an explicitly retryable plan state.
    ///
    /// The operation may have expired between callback dispatch and status lookup. A plan that remains RUNNING in
    /// that situation can never receive another terminal callback, so the uncertainty must be surfaced as a retryable
    /// failure while retaining the source-bound plan metadata.
    ///
    /// @param planId owning plan identifier
    /// @param operationId missing operation identifier
    private void markExpiredOperationLocked(String planId, String operationId) {
        @Nullable RepairPlan plan = plans.get(planId);
        if (plan == null
                || plan.state != PlanState.RUNNING
                || plan.retryInProgress
                || !operationId.equals(plan.lastOperationId)) {
            return;
        }
        plan.state = PlanState.FAILED_RETRYABLE;
        plan.failureType = IllegalStateException.class.getName();
        plan.failureMessage = "Crash repair operation expired before terminal state was observed";
        operationPlans.remove(operationId, plan.id);
    }

    /// Marks a task-creation or startup failure as retryable without consuming the plan permanently.
    private void markPlanRetryable(RepairPlan plan, Throwable failure) {
        synchronized (stateLock) {
            if (plan.state == PlanState.RUNNING) {
                plan.state = PlanState.FAILED_RETRYABLE;
                plan.failureType = failure.getClass().getName();
                plan.failureMessage = boundedFailureMessage(failure);
            }
        }
    }

    /// Releases a retry reservation without changing the plan lifecycle state.
    ///
    /// @param plan reserved plan
    private void clearRetryReservation(RepairPlan plan) {
        synchronized (stateLock) {
            plan.retryInProgress = false;
        }
    }

    /// Releases a retry reservation after a validation failure before the plan object is locally claimed.
    ///
    /// @param planId opaque plan identifier
    private void clearRetryReservation(String planId) {
        synchronized (stateLock) {
            @Nullable RepairPlan plan = plans.get(planId);
            if (plan != null) {
                plan.retryInProgress = false;
            }
        }
    }

    /// Applies a terminal operation state to its owning plan.
    private void updatePlanFromOperation(
            RepairPlan plan,
            String operationId,
            Map<String, Object> operation) {
        String status = String.valueOf(operation.get("status"));
        synchronized (stateLock) {
            if (plan.retryInProgress || plan.state != PlanState.RUNNING || !operationId.equals(plan.lastOperationId)) {
                return;
            }
            List<String> operationCompleted = stringList(operation.get("completed_steps"));
            if (!operationCompleted.isEmpty()) {
                List<String> completed = new ArrayList<>(plan.completedSteps);
                for (String step : operationCompleted) {
                    if (!completed.contains(step)) {
                        completed.add(step);
                    }
                }
                plan.completedSteps = List.copyOf(completed);
            }
            plan.failedSteps = stringList(operation.get("failed_steps"));
            plan.resumeFromStep = plan.failedSteps.isEmpty() ? null : plan.failedSteps.get(0);
            switch (status) {
                case "SUCCEEDED" -> {
                    plan.state = PlanState.SUCCEEDED;
                    plan.failureType = null;
                    plan.failureMessage = null;
                }
                case "FAILED" -> {
                    plan.state = PlanState.FAILED_RETRYABLE;
                    @Nullable Object failureType = operation.get("failure_type");
                    @Nullable Object failureMessage = operation.get("failure_message");
                    plan.failureType = failureType == null ? null : String.valueOf(failureType);
                    plan.failureMessage = failureMessage == null ? null : String.valueOf(failureMessage);
                }
                case "BLOCKED_RESIDUAL" -> {
                    plan.state = PlanState.BLOCKED_RESIDUAL;
                    @Nullable Object failureType = operation.get("failure_type");
                    @Nullable Object failureMessage = operation.get("failure_message");
                    plan.failureType = failureType == null ? null : String.valueOf(failureType);
                    plan.failureMessage = failureMessage == null ? null : String.valueOf(failureMessage);
                }
                case "CANCELLED" -> {
                    plan.state = PlanState.CANCELLED;
                    plan.failureType = null;
                    plan.failureMessage = null;
                }
                default -> {
                    // Queued and running operations remain active until their operation reaches a terminal state.
                }
            }
        }
    }

    /// Copies a JSON-safe string list returned by the operation registry.
    ///
    /// @param value untrusted operation value
    /// @return immutable non-blank string list
    private static @Unmodifiable List<String> stringList(@Nullable Object value) {
        if (!(value instanceof List<?> values)) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (Object item : values) {
            if (item instanceof String string && !string.isBlank() && !result.contains(string)) {
                result.add(string);
            }
        }
        return List.copyOf(result);
    }

    /// Adds plan lifecycle metadata to an operation response while preserving its insertion order.
    private @Unmodifiable Map<String, Object> operationWithPlan(
            Map<String, Object> operation,
            RepairPlan plan) {
        Map<String, Object> result = new LinkedHashMap<>(operation);
        synchronized (stateLock) {
            result.put("plan_id", plan.id);
            result.put("plan_state", plan.state.name());
            result.put("retryable", plan.state.retryable());
            result.put("attempt", plan.attempts);
            if (plan.lastOperationId != null) {
                result.put("current_operation_id", plan.lastOperationId);
            }
            if (plan.candidateId != null) {
                result.put("candidate_id", plan.candidateId);
            }
            result.put("completed_steps", plan.completedSteps);
            result.put("failed_steps", plan.failedSteps);
            if (plan.resumeFromStep != null) {
                result.put("resume_from_step", plan.resumeFromStep);
            }
            if (plan.failureType != null) {
                result.put("plan_failure_type", plan.failureType);
            }
            if (plan.failureMessage != null) {
                result.put("plan_failure_message", plan.failureMessage);
            }
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(result));
    }

    /// Converts an exception into bounded plan metadata without exposing a stack trace.
    private static String boundedFailureMessage(Throwable failure) {
        String message = Objects.requireNonNullElse(failure.getMessage(), failure.getClass().getSimpleName())
                .replaceAll("[\\r\\n\\t]+", " ")
                .strip();
        return message.length() <= 512 ? message : message.substring(0, 512);
    }

    /// Publishes one stored analysis without exposing executable task objects.
    private @Unmodifiable Map<String, Object> analysisSnapshot(AnalysisSession session) {
        List<Map<String, Object>> diagnoses = new ArrayList<>();
        for (Solution solution : session.solutions.values()) {
            diagnoses.add(diagnosisSnapshot(session, solution));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("analysis_id", session.id);
        result.put("instance_id", session.instanceId);
        result.put("input_source", session.source.externalName);
        result.put("input_fingerprint", session.fingerprint);
        result.put("created_at", session.createdAt.toString());
        result.put("expires_at", session.expiresAt.toString());
        result.put("diagnoses", List.copyOf(diagnoses));
        Map<String, Object> executionPolicy = new LinkedHashMap<>();
        executionPolicy.put("launcher_owned_log_required", true);
        executionPolicy.put("supported_action_types", List.of(
                RepairActionDescriptor.ActionType.OPEN_MOD_SEARCH.name(),
                RepairActionDescriptor.ActionType.REPLACE_JAVA_RUNTIME.name()));
        result.put("repair_execution_policy", Collections.unmodifiableMap(new LinkedHashMap<>(executionPolicy)));
        return Collections.unmodifiableMap(new LinkedHashMap<>(result));
    }

    /// Publishes one diagnosis and its structured solution descriptor.
    private @Unmodifiable Map<String, Object> diagnosisSnapshot(AnalysisSession session, Solution solution) {
        AnalyzeResult<LogAnalyzable> diagnosis = solution.diagnosis;
        Solver solver = solution.solver;
        @Unmodifiable Map<String, Object> causeSnapshot = causeSnapshot(session, solution, null);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("result_id", diagnosis.resultId().name());
        result.put("message_key", solver.messageKey());
        result.put("message_arguments", List.copyOf(solver.messageArguments()));
        result.put("fallback_message", solver.fallbackMessage());
        result.put("cause_snapshot", causeSnapshot);
        // Keep these fields at the diagnosis level for clients which do not understand the nested snapshot yet.
        result.put("evidence_sources", causeSnapshot.get("evidence_sources"));
        result.put("suppressed_evidence", causeSnapshot.get("suppressed_evidence"));
        result.put("repair_state", causeSnapshot.get("repair_state"));
        result.put("solution", solutionSnapshot(session, solution));
        return Collections.unmodifiableMap(new LinkedHashMap<>(result));
    }

    /// Publishes one action descriptor plus the stricter MCP execution decision.
    private @Unmodifiable Map<String, Object> solutionSnapshot(AnalysisSession session, Solution solution) {
        return solutionSnapshot(session, solution, null);
    }

    /// Publishes one action descriptor with a plan-aware repair state.
    ///
    /// @param session retained analysis session
    /// @param solution retained diagnosis and action
    /// @param plan current plan, or null before planning
    /// @return immutable action snapshot
    private @Unmodifiable Map<String, Object> solutionSnapshot(
            AnalysisSession session,
            Solution solution,
            @Nullable RepairPlan plan) {
        RepairActionDescriptor action = solution.solver.repairAction();
        ExecutionDecision decision = executionDecision(session, solution);
        @Unmodifiable Map<String, Object> causeSnapshot = causeSnapshot(session, solution, plan);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("solution_id", solution.id);
        result.put("action_type", action.actionType().name());
        result.put("availability", action.availability().name());
        result.put("risk_level", action.riskLevel().name());
        result.put("confirmation_requirement", action.confirmationRequirement().name());
        result.put("dependency_ids", action.dependencyIds());
        result.put("candidates", candidateSnapshots(solution.candidates));
        result.put("candidate_selection_required", requiresCandidateSelection(solution.candidates));
        result.put("repair_state", causeSnapshot.get("repair_state"));
        result.put("evidence_sources", causeSnapshot.get("evidence_sources"));
        result.put("suppressed_evidence", causeSnapshot.get("suppressed_evidence"));
        result.put("mcp_executable", decision.executable());
        if (!decision.executable()) {
            result.put("blocked_reason", decision.reason());
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(result));
    }

    /// Builds an immutable cause snapshot and derives its current repair state.
    ///
    /// The snapshot intentionally contains no task, solver, path, or mutable analyzer object. Evidence strings are
    /// bounded before publication, and every nested collection is copied so callers cannot mutate retained state.
    ///
    /// @param session retained analysis session
    /// @param solution retained diagnosis
    /// @param plan current plan, or null before a plan exists
    /// @return immutable cause snapshot
    private @Unmodifiable Map<String, Object> causeSnapshot(
            AnalysisSession session,
            Solution solution,
            @Nullable RepairPlan plan) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("result_id", solution.diagnosis.resultId().name());
        result.put("solution_id", solution.id);
        result.put("analyzer", solution.diagnosis.analyzer().getClass().getSimpleName());
        result.put("evidence_sources", solution.evidenceSources);
        result.put("suppressed_evidence", solution.suppressedEvidence);
        result.put("repair_state", repairState(session, solution, plan));
        return Collections.unmodifiableMap(new LinkedHashMap<>(result));
    }

    /// Derives the externally visible state for one independent repair row.
    ///
    /// @param session retained analysis session
    /// @param solution retained diagnosis
    /// @param plan current plan, or null before planning
    /// @return stable state-machine value
    private static String repairState(
            AnalysisSession session,
            Solution solution,
            @Nullable RepairPlan plan) {
        if (plan != null) {
            if (plan.state == PlanState.AVAILABLE
                    && requiresCandidateSelection(solution.candidates)
                    && plan.candidateId == null) {
                return "AWAITING_SELECTION";
            }
            return plan.state.name();
        }
        ExecutionDecision decision = executionDecision(session, solution);
        if (!decision.executable()) {
            return "BLOCKED";
        }
        return requiresCandidateSelection(solution.candidates) ? "AWAITING_SELECTION" : "AVAILABLE";
    }

    /// Publishes an allocated plan or a non-executable planning result.
    private @Unmodifiable Map<String, Object> planSnapshot(
            AnalysisSession session,
            Solution solution,
            @Nullable RepairPlan plan,
            ExecutionDecision decision) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("planned", plan != null);
        result.put("executable", decision.executable());
        result.put("analysis_id", session.id);
        result.put("solution_id", solution.id);
        result.put("instance_id", session.instanceId);
        result.put("input_source", session.source.externalName);
        result.put("input_fingerprint", session.fingerprint);
        @Unmodifiable Map<String, Object> solutionView = solutionSnapshot(session, solution, plan);
        result.put("solution", solutionView);
        result.put("cause_snapshot", causeSnapshot(session, solution, plan));
        result.put("repair_state", solutionView.get("repair_state"));
        result.put("evidence_sources", solutionView.get("evidence_sources"));
        result.put("suppressed_evidence", solutionView.get("suppressed_evidence"));
        if (plan == null) {
            result.put("blocked_reason", decision.reason());
        } else {
            result.put("plan_id", plan.id);
            result.put("created_at", plan.createdAt.toString());
            result.put("expires_at", plan.expiresAt.toString());
            result.put("single_use", false);
            result.put("retry_after_failure", true);
            result.put("plan_state", plan.state.name());
            result.put("retryable", plan.state.retryable());
            result.put("attempt", plan.attempts);
            result.put("completed_steps", plan.completedSteps);
            result.put("failed_steps", plan.failedSteps);
            if (plan.resumeFromStep != null) {
                result.put("resume_from_step", plan.resumeFromStep);
            }
            if (plan.candidateId != null) {
                result.put("selected_candidate_id", plan.candidateId);
            }
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(result));
    }

    /// Applies the explicit MCP action allowlist and source-ownership requirements.
    private static ExecutionDecision executionDecision(AnalysisSession session, Solution solution) {
        RepairActionDescriptor action = solution.solver.repairAction();
        if (session.source != AnalysisSource.LAUNCHER_LATEST_LOG) {
            return new ExecutionDecision(false, EXTERNAL_LOG_NOT_EXECUTABLE);
        }
        if (!action.executable()) {
            String reason = action.actionType() == RepairActionDescriptor.ActionType.MANUAL_GUIDANCE
                    ? MANUAL_GUIDANCE
                    : APPLICATION_BOUNDARY_UNAVAILABLE;
            return new ExecutionDecision(false, reason);
        }
        return switch (action.actionType()) {
            case OPEN_MOD_SEARCH, REPLACE_JAVA_RUNTIME -> new ExecutionDecision(true, "");
            case MANUAL_GUIDANCE, AUTOMATIC_REPAIR -> new ExecutionDecision(false, UNSUPPORTED_ACTION_TYPE);
        };
    }

    /// Resolves and validates a candidate identifier without creating a task.
    ///
    /// @param candidates immutable candidates captured with the analysis
    /// @param candidateId requested identifier, or null
    /// @param requireSelection whether multiple candidates must have an explicit selection
    /// @return validated candidate identifier, or null for the ordinary automatic path
    private static @Nullable String resolveCandidate(
            @Unmodifiable List<LogAnalyzable.JavaRuntimeCandidate> candidates,
            @Nullable String candidateId,
            boolean requireSelection) {
        Objects.requireNonNull(candidates, "candidates");
        if (candidateId != null) {
            if (candidates.stream().noneMatch(candidate -> candidate.id().equals(candidateId))) {
                throw new IllegalArgumentException("Unknown crash repair candidate: " + candidateId);
            }
            return candidateId;
        }
        if (candidates.size() <= 1) {
            return candidates.isEmpty() ? null : candidates.get(0).id();
        }
        if (requireSelection) {
            throw new IllegalStateException("Crash repair candidate selection is required");
        }
        return null;
    }

    /// Converts candidate metadata into an immutable MCP-safe map without exposing local runtime paths.
    private static @Unmodifiable List<@Unmodifiable Map<String, Object>> candidateSnapshots(
            @Unmodifiable List<LogAnalyzable.JavaRuntimeCandidate> candidates) {
        List<@Unmodifiable Map<String, Object>> result = new ArrayList<>();
        for (LogAnalyzable.JavaRuntimeCandidate candidate : Objects.requireNonNull(candidates, "candidates")) {
            result.add(Map.of(
                    "id", candidate.id(),
                    "display_name", candidate.displayName(),
                    "recommended", candidate.recommended()));
        }
        return List.copyOf(result);
    }

    /// Returns whether a solver requires a caller to choose among multiple candidates.
    private static boolean requiresCandidateSelection(
            @Unmodifiable List<LogAnalyzable.JavaRuntimeCandidate> candidates) {
        return Objects.requireNonNull(candidates, "candidates").size() > 1;
    }

    /// Resolves one live analysis while the state lock is held.
    private AnalysisSession requireAnalysisLocked(String analysisId) {
        @Nullable AnalysisSession session = analyses.get(Objects.requireNonNull(analysisId, "analysisId"));
        if (session == null) {
            throw new IllegalArgumentException("Unknown or expired crash analysis: " + analysisId);
        }
        return session;
    }

    /// Resolves one solution owned by an analysis.
    private static Solution requireSolution(AnalysisSession session, String solutionId) {
        @Nullable Solution solution = session.solutions.get(Objects.requireNonNull(solutionId, "solutionId"));
        if (solution == null) {
            throw new IllegalArgumentException("Unknown crash repair solution: " + solutionId);
        }
        return solution;
    }

    /// Resolves one live plan while the state lock is held.
    private RepairPlan requirePlanLocked(String planId) {
        @Nullable RepairPlan plan = plans.get(Objects.requireNonNull(planId, "planId"));
        if (plan == null) {
            throw new IllegalArgumentException("Unknown or expired crash repair plan: " + planId);
        }
        return plan;
    }

    /// Rejects mutations after shutdown.
    private void requireOpenLocked() {
        if (closed) {
            throw new IllegalStateException("Crash repair coordinator is closed");
        }
    }

    /// Removes expired analyses and plans.
    private void pruneLocked() {
        Instant now = clock.instant();
        analyses.values().removeIf(session -> !session.expiresAt.isAfter(now)
                && plans.values().stream().noneMatch(plan ->
                        plan.analysisId.equals(session.id) && retainedForLifecycle(plan)));
        List<String> removedPlanIds = plans.entrySet().stream()
                .filter(entry -> (!entry.getValue().expiresAt.isAfter(now)
                        && !retainedForLifecycle(entry.getValue()))
                        || !analyses.containsKey(entry.getValue().analysisId))
                .map(Map.Entry::getKey)
                .toList();
        plans.keySet().removeAll(removedPlanIds);
        List<String> releasedOperations = operationPlans.entrySet().stream()
                .filter(entry -> !plans.containsKey(entry.getValue()))
                .map(Map.Entry::getKey)
                .toList();
        operationPlans.entrySet().removeIf(entry -> !plans.containsKey(entry.getValue()));
        // Do not call the registry while holding the coordinator lock; release pins after the map mutation is visible.
        for (String operationId : releasedOperations) {
            operations.releaseOwnerRetention(operationId);
        }
    }

    /// Keeps a plan reachable while an operation or cleanup reservation can still publish state.
    ///
    /// Expiry is a retention policy for unclaimed or terminal plans; it must never invalidate a live task's
    /// operation-to-plan link. A retry reservation is included because cleanup and fresh-task registration happen
    /// outside the coordinator state lock.
    ///
    /// @param plan plan under the state lock
    /// @return whether the plan must remain retained despite expiry
    private static boolean retainedForLifecycle(RepairPlan plan) {
        return plan.state == PlanState.RUNNING
                || plan.state == PlanState.BLOCKED_RESIDUAL
                || plan.retryInProgress;
    }

    /// Makes room for an analysis without invalidating an unconsumed plan.
    private void ensureAnalysisCapacityLocked() {
        if (analyses.size() < maximumAnalyses) {
            return;
        }
        @Nullable String removableId = analyses.keySet().stream()
                .filter(analysisId -> plans.values().stream()
                        .noneMatch(plan -> plan.analysisId.equals(analysisId)
                                && plan.state != PlanState.SUCCEEDED))
                .findFirst()
                .orElse(null);
        if (removableId == null) {
            throw new IllegalStateException("Too many crash analyses with active repair plans");
        }
        analyses.remove(removableId);
        List<String> removedOperationIds = plans.values().stream()
                .filter(plan -> plan.analysisId.equals(removableId))
                .map(plan -> plan.lastOperationId)
                .filter(Objects::nonNull)
                .toList();
        List<String> removedPlanIds = plans.values().stream()
                .filter(plan -> plan.analysisId.equals(removableId))
                .map(plan -> plan.id)
                .toList();
        plans.values().removeIf(plan -> plan.analysisId.equals(removableId));
        operationPlans.entrySet().removeIf(entry -> removedPlanIds.contains(entry.getValue()));
        for (String operationId : removedOperationIds) {
            operations.releaseOwnerRetention(operationId);
        }
    }

    /// Makes room for a plan by evicting the oldest terminal plan.
    private void ensurePlanCapacityLocked() {
        if (plans.size() < maximumPlans) {
            return;
        }
        @Nullable String consumedId = plans.entrySet().stream()
                .filter(entry -> entry.getValue().state == PlanState.SUCCEEDED)
                .map(Map.Entry::getKey)
                .findFirst()
                .orElse(null);
        if (consumedId == null) {
            throw new IllegalStateException("Too many unconsumed crash repair plans");
        }
        @Nullable RepairPlan removed = plans.remove(consumedId);
        if (removed != null && removed.lastOperationId != null) {
            operationPlans.remove(removed.lastOperationId);
            operations.releaseOwnerRetention(removed.lastOperationId);
        }
    }

    /// Validates one strictly positive retention duration.
    private static Duration requirePositive(Duration duration, String name) {
        Duration checked = Objects.requireNonNull(duration, name);
        if (checked.isZero() || checked.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return checked;
    }

    /// Identifies whether analyzed text belongs to the launcher's current instance state.
    @NotNullByDefault
    public enum AnalysisSource {
        /// The latest log was loaded from the selected instance and can be revalidated.
        LAUNCHER_LATEST_LOG("launcher_latest_log"),

        /// The client supplied arbitrary text that must remain analysis-only.
        PROVIDED_LOG("provided_log");

        /// Stable external JSON value.
        private final String externalName;

        /// Creates a source classification.
        AnalysisSource(String externalName) {
            this.externalName = externalName;
        }
    }

    /// Creates a fresh stopped task that revalidates the exact launcher-owned source bound to a repair plan.
    ///
    /// Implementations must declare every resource read while validating and must not perform I/O while constructing
    /// the task. The coordinator runs the task within the same cancellable execution chain as the selected repair.
    @FunctionalInterface
    @NotNullByDefault
    public interface SourceValidator {
        /// Creates one independent source-validation task.
        ///
        /// @return fresh stopped task with a concrete resource declaration
        Task<?> createTask();

        /// Creates one validation task while forwarding retained retry progress.
        ///
        /// Existing validators remain source-compatible because the default delegates to [#createTask()].
        /// Validators that persist durable checkpoints may override this hook to resume from the supplied step.
        ///
        /// @param checkpoint immutable progress from an earlier repair attempt
        /// @return fresh stopped task with a concrete resource declaration
        default Task<?> createTask(RepairCheckpoint checkpoint) {
            Objects.requireNonNull(checkpoint, "checkpoint");
            return createTask();
        }
    }

    /// Immutable retained analysis and its application boundaries.
    @NotNullByDefault
    private static final class AnalysisSession {
        /// Opaque analysis identifier.
        private final String id;

        /// Instance identifier used for the analysis.
        private final String instanceId;

        /// Ownership class of the analyzed log.
        private final AnalysisSource source;

        /// SHA-256 fingerprint of the analyzed log.
        private final String fingerprint;

        /// Analysis registration time.
        private final Instant createdAt;

        /// Analysis expiry time.
        private final Instant expiresAt;

        /// Launcher-source revalidator, or null for caller-supplied text.
        private final @Nullable SourceValidator sourceValidator;

        /// Immutable solutions indexed by stable result ID.
        private final @Unmodifiable Map<String, Solution> solutions;

        /// Creates one retained analysis.
        private AnalysisSession(
                String id,
                String instanceId,
                AnalysisSource source,
                String fingerprint,
                Instant createdAt,
                Instant expiresAt,
                @Nullable SourceValidator sourceValidator,
                @Unmodifiable Map<String, Solution> solutions) {
            this.id = Objects.requireNonNull(id, "id");
            this.instanceId = Objects.requireNonNull(instanceId, "instanceId");
            this.source = Objects.requireNonNull(source, "source");
            this.fingerprint = Objects.requireNonNull(fingerprint, "fingerprint");
            this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
            this.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
            this.sourceValidator = sourceValidator;
            this.solutions = Collections.unmodifiableMap(
                    new LinkedHashMap<>(Objects.requireNonNull(solutions, "solutions")));
        }
    }

    /// One analyzer result paired with its solver.
    @NotNullByDefault
    private static final class Solution {
        /// Stable result-derived solution identifier.
        private final String id;

        /// Original immutable diagnosis.
        private final AnalyzeResult<LogAnalyzable> diagnosis;

        /// Solver retained only inside the trusted application process.
        private final Solver solver;

        /// Immutable runtime candidates captured with this diagnosis.
        private final @Unmodifiable List<LogAnalyzable.JavaRuntimeCandidate> candidates;

        /// Immutable physical evidence source identifiers captured with this diagnosis.
        private final @Unmodifiable List<String> evidenceSources;

        /// Immutable legacy evidence hidden by this diagnosis' explicit supersession rules.
        private final @Unmodifiable List<@Unmodifiable Map<String, Object>> suppressedEvidence;

        /// Creates one retained solution.
        private Solution(
                String id,
                AnalyzeResult<LogAnalyzable> diagnosis,
                AnalysisSource source,
                @Unmodifiable Map<CrashReportAnalyzer.Rule, CrashReportAnalyzer.Result> legacyEvidence) {
            this.id = Objects.requireNonNull(id, "id");
            this.diagnosis = Objects.requireNonNull(diagnosis, "diagnosis");
            this.solver = diagnosis.solver();
            this.candidates = List.copyOf(solver.candidates());
            this.evidenceSources = List.of(Objects.requireNonNull(source, "source").externalName);
            this.suppressedEvidence = suppressedEvidence(diagnosis.resultId(), source, legacyEvidence);
        }
    }

    /// Mutable retryable repair-plan claim state.
    @NotNullByDefault
    private static final class RepairPlan {
        /// Opaque plan identifier.
        private final String id;

        /// Owning analysis identifier.
        private final String analysisId;

        /// Selected solution identifier.
        private final String solutionId;

        /// Plan creation time.
        private final Instant createdAt;

        /// Plan expiry time.
        private final Instant expiresAt;

        /// Current lifecycle state for the plan.
        private PlanState state = PlanState.AVAILABLE;

        /// Candidate selected for this plan, or null until execution resolves it.
        private @Nullable String candidateId;

        /// Number of task attempts started for this plan.
        private int attempts;

        /// Most recent operation identifier, or null before the first attempt.
        private @Nullable String lastOperationId;

        /// Last retryable failure type, or null when none is recorded.
        private @Nullable String failureType;

        /// Last retryable failure message, or null when none is recorded.
        private @Nullable String failureMessage;

        /// Whether one caller has reserved the plan between residual cleanup and fresh task registration.
        private boolean retryInProgress;

        /// Ordered steps that have completed successfully across attempts.
        private @Unmodifiable List<String> completedSteps = List.of();

        /// Ordered steps reported as failed by the most recent attempt.
        private @Unmodifiable List<String> failedSteps = List.of();

        /// First step that should be resumed on the next attempt, or null when none is known.
        private @Nullable String resumeFromStep;

        /// Creates one available plan.
        private RepairPlan(
                String id,
                String analysisId,
                String solutionId,
                Instant createdAt,
                Instant expiresAt,
                @Nullable String candidateId) {
            this.id = Objects.requireNonNull(id, "id");
            this.analysisId = Objects.requireNonNull(analysisId, "analysisId");
            this.solutionId = Objects.requireNonNull(solutionId, "solutionId");
            this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
            this.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
            this.candidateId = candidateId;
        }

        /// Creates the immutable retry checkpoint currently retained by this plan.
        ///
        /// @return checkpoint for a fresh task attempt
        private RepairCheckpoint checkpoint() {
            return new RepairCheckpoint(completedSteps, failedSteps, resumeFromStep);
        }
    }

    /// Lifecycle states retained for one repair plan.
    @NotNullByDefault
    private enum PlanState {
        /// Plan has not yet been claimed.
        AVAILABLE(true),

        /// One fresh task attempt is active.
        RUNNING(false),

        /// Attempt completed successfully and cannot be replayed.
        SUCCEEDED(false),

        /// Attempt failed before or during execution and may be retried.
        FAILED_RETRYABLE(true),

        /// Task-owned resource cleanup is blocked and must be retried before a new task.
        BLOCKED_RESIDUAL(true),

        /// Attempt was explicitly cancelled and may be retried after review.
        CANCELLED(true);

        /// Whether another fresh attempt may be claimed.
        private final boolean retryable;

        /// Creates one plan lifecycle state.
        PlanState(boolean retryable) {
            this.retryable = retryable;
        }

        /// Returns whether this state accepts a new attempt.
        private boolean retryable() {
            return retryable;
        }
    }

    /// MCP-specific execution eligibility and stable block reason.
    ///
    /// @param executable whether a plan may be allocated
    /// @param reason empty for executable actions, otherwise a stable machine-readable block reason
    @NotNullByDefault
    private record ExecutionDecision(boolean executable, String reason) {
        /// Validates the reason invariant.
        private ExecutionDecision {
            Objects.requireNonNull(reason, "reason");
            if (executable != reason.isEmpty()) {
                throw new IllegalArgumentException("Executable decisions require an empty reason");
            }
        }
    }
}
