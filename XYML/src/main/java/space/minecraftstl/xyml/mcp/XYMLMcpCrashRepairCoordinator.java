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
import space.minecraftstl.xyml.game.analyzer.AnalyzeResult;
import space.minecraftstl.xyml.game.analyzer.LogAnalyzable;
import space.minecraftstl.xyml.game.analyzer.LogAnalyzer;
import space.minecraftstl.xyml.game.analyzer.RepairActionDescriptor;
import space.minecraftstl.xyml.game.analyzer.Solver;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/// Retains bounded XYAT analyses and mediates one-time MCP repair execution.
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

    /// Stable block reason for the currently non-atomic Java replacement task.
    public static final String JAVA_REPAIR_NOT_ATOMIC = "java_runtime_repair_not_atomic";

    /// Stable block reason for an executable Core action not approved for MCP.
    public static final String UNSUPPORTED_ACTION_TYPE = "unsupported_action_type";

    /// Serializes analysis, plan, execution claims, pruning, and shutdown.
    private final Object stateLock = new Object();

    /// Time source for expiry and result timestamps.
    private final Clock clock;

    /// Lifetime of a stored crash analysis.
    private final Duration analysisLifetime;

    /// Lifetime of a one-time repair plan.
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

        @Unmodifiable List<AnalyzeResult<LogAnalyzable>> results = LogAnalyzer.analyze(input);
        Instant createdAt = clock.instant();
        AnalysisSession session = new AnalysisSession(
                UUID.randomUUID().toString(),
                instanceId,
                source,
                fingerprint,
                createdAt,
                createdAt.plus(analysisLifetime),
                sourceValidator,
                solutions(results));
        synchronized (stateLock) {
            requireOpenLocked();
            pruneLocked();
            ensureAnalysisCapacityLocked();
            analyses.put(session.id, session);
        }
        return analysisSnapshot(session);
    }

    /// Creates a short-lived one-time execution plan for one analyzed solution.
    ///
    /// Non-executable solutions return their stable block reason without allocating a plan identifier.
    ///
    /// @param analysisId opaque analysis identifier returned by [#analyze]
    /// @param solutionId stable solution identifier from the diagnosis list
    /// @return immutable plan description or non-executable explanation
    public @Unmodifiable Map<String, Object> plan(String analysisId, String solutionId) {
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
            ensurePlanCapacityLocked();
            Instant createdAt = clock.instant();
            RepairPlan plan = new RepairPlan(
                    UUID.randomUUID().toString(),
                    session.id,
                    solution.id,
                    createdAt,
                    createdAt.plus(planLifetime));
            plans.put(plan.id, plan);
            return planSnapshot(session, solution, plan, decision);
        }
    }

    /// Consumes one repair plan, revalidates its launcher-owned source, and starts a fresh task.
    ///
    /// @param planId opaque one-time plan identifier
    /// @return immutable asynchronous operation status
    public @Unmodifiable Map<String, Object> execute(String planId) {
        AnalysisSession session;
        Solution solution;
        synchronized (stateLock) {
            requireOpenLocked();
            pruneLocked();
            RepairPlan plan = requirePlanLocked(planId);
            if (plan.consumed) {
                throw new IllegalStateException("Crash repair plan was already consumed: " + planId);
            }
            session = requireAnalysisLocked(plan.analysisId);
            solution = requireSolution(session, plan.solutionId);
            ExecutionDecision decision = executionDecision(session, solution);
            if (!decision.executable()) {
                throw new IllegalStateException("Crash repair plan is no longer executable: " + decision.reason());
            }
            plan.consumed = true;
        }

        SourceValidator validator = Objects.requireNonNull(
                session.sourceValidator,
                "Executable crash repair plan has no source validator");
        try {
            validator.validate();
        } catch (IOException validationFailure) {
            throw new IllegalStateException("Crash analysis source could not be revalidated", validationFailure);
        }

        RepairActionDescriptor descriptor = solution.solver.repairAction();
        return operations.start(
                descriptor.actionType().name(),
                descriptor.actionType() == RepairActionDescriptor.ActionType.OPEN_MOD_SEARCH,
                () -> Objects.requireNonNull(
                        solution.solver.createTask(),
                        "Crash repair solver did not create a task"));
    }

    /// Returns the latest state of one repair operation.
    ///
    /// @param operationId opaque operation identifier
    /// @return immutable operation status
    public @Unmodifiable Map<String, Object> status(String operationId) {
        return operations.status(operationId);
    }

    /// Requests cooperative cancellation for one repair operation.
    ///
    /// @param operationId opaque operation identifier
    /// @return immutable operation status including whether cancellation was accepted
    public @Unmodifiable Map<String, Object> cancel(String operationId) {
        return operations.cancel(operationId);
    }

    /// Clears analyses and plans, then requests cancellation of active operations.
    @Override
    public void close() {
        synchronized (stateLock) {
            if (closed) {
                return;
            }
            closed = true;
            analyses.clear();
            plans.clear();
        }
        operations.close();
    }

    /// Converts ordered analyzer results into a stable solution index.
    private static @Unmodifiable Map<String, Solution> solutions(
            @Unmodifiable List<AnalyzeResult<LogAnalyzable>> results) {
        Map<String, Solution> result = new LinkedHashMap<>();
        for (AnalyzeResult<LogAnalyzable> diagnosis : results) {
            String solutionId = diagnosis.resultId().name();
            result.putIfAbsent(solutionId, new Solution(solutionId, diagnosis));
        }
        return Map.copyOf(result);
    }

    /// Publishes one stored analysis without exposing executable task objects.
    private @Unmodifiable Map<String, Object> analysisSnapshot(AnalysisSession session) {
        List<Map<String, Object>> diagnoses = new ArrayList<>();
        for (Solution solution : session.solutions.values()) {
            diagnoses.add(diagnosisSnapshot(session, solution));
        }
        return Map.of(
                "analysis_id", session.id,
                "instance_id", session.instanceId,
                "input_source", session.source.externalName,
                "input_fingerprint", session.fingerprint,
                "created_at", session.createdAt.toString(),
                "expires_at", session.expiresAt.toString(),
                "diagnoses", List.copyOf(diagnoses),
                "repair_execution_policy", Map.of(
                        "launcher_owned_log_required", true,
                        "supported_action_types", List.of(
                                RepairActionDescriptor.ActionType.OPEN_MOD_SEARCH.name())));
    }

    /// Publishes one diagnosis and its structured solution descriptor.
    private @Unmodifiable Map<String, Object> diagnosisSnapshot(AnalysisSession session, Solution solution) {
        AnalyzeResult<LogAnalyzable> diagnosis = solution.diagnosis;
        Solver solver = solution.solver;
        return Map.of(
                "result_id", diagnosis.resultId().name(),
                "message_key", solver.messageKey(),
                "message_arguments", solver.messageArguments(),
                "fallback_message", solver.fallbackMessage(),
                "solution", solutionSnapshot(session, solution));
    }

    /// Publishes one action descriptor plus the stricter MCP execution decision.
    private @Unmodifiable Map<String, Object> solutionSnapshot(AnalysisSession session, Solution solution) {
        RepairActionDescriptor action = solution.solver.repairAction();
        ExecutionDecision decision = executionDecision(session, solution);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("solution_id", solution.id);
        result.put("action_type", action.actionType().name());
        result.put("availability", action.availability().name());
        result.put("risk_level", action.riskLevel().name());
        result.put("confirmation_requirement", action.confirmationRequirement().name());
        result.put("dependency_ids", action.dependencyIds());
        result.put("mcp_executable", decision.executable());
        if (!decision.executable()) {
            result.put("blocked_reason", decision.reason());
        }
        return Map.copyOf(result);
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
        result.put("solution", solutionSnapshot(session, solution));
        if (plan == null) {
            result.put("blocked_reason", decision.reason());
        } else {
            result.put("plan_id", plan.id);
            result.put("created_at", plan.createdAt.toString());
            result.put("expires_at", plan.expiresAt.toString());
            result.put("single_use", true);
        }
        return Map.copyOf(result);
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
            case OPEN_MOD_SEARCH -> new ExecutionDecision(true, "");
            case REPLACE_JAVA_RUNTIME -> new ExecutionDecision(false, JAVA_REPAIR_NOT_ATOMIC);
            case MANUAL_GUIDANCE, AUTOMATIC_REPAIR -> new ExecutionDecision(false, UNSUPPORTED_ACTION_TYPE);
        };
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
        analyses.values().removeIf(session -> !session.expiresAt.isAfter(now));
        plans.values().removeIf(plan -> !plan.expiresAt.isAfter(now) || !analyses.containsKey(plan.analysisId));
    }

    /// Makes room for an analysis without invalidating an unconsumed plan.
    private void ensureAnalysisCapacityLocked() {
        if (analyses.size() < maximumAnalyses) {
            return;
        }
        @Nullable String removableId = analyses.keySet().stream()
                .filter(analysisId -> plans.values().stream()
                        .noneMatch(plan -> !plan.consumed && plan.analysisId.equals(analysisId)))
                .findFirst()
                .orElse(null);
        if (removableId == null) {
            throw new IllegalStateException("Too many crash analyses with active repair plans");
        }
        analyses.remove(removableId);
        plans.values().removeIf(plan -> plan.analysisId.equals(removableId));
    }

    /// Makes room for a plan by evicting the oldest consumed plan.
    private void ensurePlanCapacityLocked() {
        if (plans.size() < maximumPlans) {
            return;
        }
        @Nullable String consumedId = plans.entrySet().stream()
                .filter(entry -> entry.getValue().consumed)
                .map(Map.Entry::getKey)
                .findFirst()
                .orElse(null);
        if (consumedId == null) {
            throw new IllegalStateException("Too many unconsumed crash repair plans");
        }
        plans.remove(consumedId);
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

    /// Revalidates the exact launcher-owned source bound to a repair plan.
    @FunctionalInterface
    @NotNullByDefault
    public interface SourceValidator {
        /// Confirms the instance and source fingerprint still match the analysis.
        ///
        /// @throws IOException when the source cannot be read or no longer matches
        void validate() throws IOException;
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
            this.solutions = Map.copyOf(Objects.requireNonNull(solutions, "solutions"));
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

        /// Creates one retained solution.
        private Solution(String id, AnalyzeResult<LogAnalyzable> diagnosis) {
            this.id = Objects.requireNonNull(id, "id");
            this.diagnosis = Objects.requireNonNull(diagnosis, "diagnosis");
            this.solver = diagnosis.solver();
        }
    }

    /// Mutable one-time repair-plan claim state.
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

        /// Whether execution has claimed this plan.
        private boolean consumed;

        /// Creates one unconsumed plan.
        private RepairPlan(
                String id,
                String analysisId,
                String solutionId,
                Instant createdAt,
                Instant expiresAt) {
            this.id = Objects.requireNonNull(id, "id");
            this.analysisId = Objects.requireNonNull(analysisId, "analysisId");
            this.solutionId = Objects.requireNonNull(solutionId, "solutionId");
            this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
            this.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
        }
    }

    /// MCP-specific execution eligibility and stable block reason.
    ///
    /// @param executable whether a one-time plan may be allocated
    /// @param reason empty for executable actions, otherwise a stable machine-readable block reason
    @NotNullByDefault
    private record ExecutionDecision(boolean executable, String reason) {
        /// Validates the reason invariant.
        private ExecutionDecision {
            Objects.requireNonNull(reason, "reason");
            if (executable == !reason.isEmpty()) {
                throw new IllegalArgumentException("Executable decisions require an empty reason");
            }
        }
    }
}
