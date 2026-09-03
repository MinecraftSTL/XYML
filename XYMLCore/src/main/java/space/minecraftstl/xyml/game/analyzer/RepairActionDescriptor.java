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
import org.jetbrains.annotations.Unmodifiable;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/// Immutable, serializable description of one proposed crash repair action.
///
/// @param actionType stable action category
/// @param availability whether the action can be executed in the current application context
/// @param riskLevel highest expected side-effect class
/// @param confirmationRequirement whether an application-owned confirmation is mandatory before execution
/// @param dependencyIds immutable validated mod identifiers in diagnosis order
@NotNullByDefault
public record RepairActionDescriptor(
        ActionType actionType,
        Availability availability,
        RiskLevel riskLevel,
        ConfirmationRequirement confirmationRequirement,
        @Unmodifiable List<String> dependencyIds) {
    /// Accepted Forge and Fabric mod identifier syntax.
    private static final Pattern MOD_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.-]*");

    /// Validates the policy combination and snapshots dependency identifiers.
    public RepairActionDescriptor {
        Objects.requireNonNull(actionType, "actionType");
        Objects.requireNonNull(availability, "availability");
        Objects.requireNonNull(riskLevel, "riskLevel");
        Objects.requireNonNull(confirmationRequirement, "confirmationRequirement");
        dependencyIds = List.copyOf(Objects.requireNonNull(dependencyIds, "dependencyIds"));
        validateDependencyIds(actionType, dependencyIds);
        validatePolicy(actionType, availability, riskLevel, confirmationRequirement);
    }

    /// Creates the standard non-executable manual guidance descriptor.
    ///
    /// @return information-only manual guidance
    public static RepairActionDescriptor manualGuidance() {
        return new RepairActionDescriptor(
                ActionType.MANUAL_GUIDANCE,
                Availability.INFORMATION_ONLY,
                RiskLevel.NONE,
                ConfirmationRequirement.NOT_REQUIRED,
                List.of());
    }

    /// Creates a generic automatic repair descriptor for legacy task-based callers.
    ///
    /// Generic automatic tasks are treated conservatively because Core cannot infer their effects.
    ///
    /// @return executable automatic repair requiring application confirmation
    public static RepairActionDescriptor automaticRepair() {
        return new RepairActionDescriptor(
                ActionType.AUTOMATIC_REPAIR,
                Availability.EXECUTABLE,
                RiskLevel.SYSTEM_CONFIGURATION_CHANGE,
                ConfirmationRequirement.REQUIRED,
                List.of());
    }

    /// Creates a missing-mod search descriptor while retaining the exact validated identifiers.
    ///
    /// @param dependencyIds missing mod identifiers in diagnosis order
    /// @param executable whether the application supplied a search boundary
    /// @return structured missing-mod search proposal
    public static RepairActionDescriptor openModSearch(
            @Unmodifiable List<String> dependencyIds,
            boolean executable) {
        return new RepairActionDescriptor(
                ActionType.OPEN_MOD_SEARCH,
                executable ? Availability.EXECUTABLE : Availability.APPLICATION_BOUNDARY_UNAVAILABLE,
                RiskLevel.INTERACTIVE_SIDE_EFFECT,
                ConfirmationRequirement.NOT_REQUIRED,
                dependencyIds);
    }

    /// Creates a non-destructive Java-runtime selection descriptor.
    ///
    /// @param executable whether the application supplied a Java-runtime repair boundary
    /// @return structured Java-runtime selection proposal
    public static RepairActionDescriptor replaceJavaRuntime(boolean executable) {
        return new RepairActionDescriptor(
                ActionType.REPLACE_JAVA_RUNTIME,
                executable ? Availability.EXECUTABLE : Availability.APPLICATION_BOUNDARY_UNAVAILABLE,
                RiskLevel.SYSTEM_CONFIGURATION_CHANGE,
                ConfirmationRequirement.NOT_REQUIRED,
                List.of());
    }

    /// Reports whether the current application context can execute this action.
    ///
    /// @return true only for an executable action
    public boolean executable() {
        return availability == Availability.EXECUTABLE;
    }

    /// Validates action-specific dependency identifier ownership.
    ///
    /// @param actionType action category
    /// @param dependencyIds immutable dependency identifiers
    private static void validateDependencyIds(
            ActionType actionType,
            @Unmodifiable List<String> dependencyIds) {
        if (actionType != ActionType.OPEN_MOD_SEARCH) {
            if (!dependencyIds.isEmpty()) {
                throw new IllegalArgumentException("Only OPEN_MOD_SEARCH may contain dependency IDs");
            }
            return;
        }
        if (dependencyIds.isEmpty()) {
            throw new IllegalArgumentException("OPEN_MOD_SEARCH requires at least one dependency ID");
        }

        Set<String> uniqueIds = new HashSet<>();
        for (String dependencyId : dependencyIds) {
            if (!MOD_ID.matcher(dependencyId).matches()) {
                throw new IllegalArgumentException("Invalid dependency ID: " + dependencyId);
            }
            if (!uniqueIds.add(dependencyId)) {
                throw new IllegalArgumentException("Duplicate dependency ID: " + dependencyId);
            }
        }
    }

    /// Validates the fixed risk and confirmation policy for each stable action category.
    ///
    /// @param actionType action category
    /// @param availability current application availability
    /// @param riskLevel expected side-effect class
    /// @param confirmationRequirement application confirmation policy
    private static void validatePolicy(
            ActionType actionType,
            Availability availability,
            RiskLevel riskLevel,
            ConfirmationRequirement confirmationRequirement) {
        switch (actionType) {
            case MANUAL_GUIDANCE -> requirePolicy(
                    availability == Availability.INFORMATION_ONLY
                            && riskLevel == RiskLevel.NONE
                            && confirmationRequirement == ConfirmationRequirement.NOT_REQUIRED,
                    actionType);
            case OPEN_MOD_SEARCH -> requirePolicy(
                    availability != Availability.INFORMATION_ONLY
                            && riskLevel == RiskLevel.INTERACTIVE_SIDE_EFFECT
                            && confirmationRequirement == ConfirmationRequirement.NOT_REQUIRED,
                    actionType);
            case REPLACE_JAVA_RUNTIME -> requirePolicy(
                    availability != Availability.INFORMATION_ONLY
                            && riskLevel == RiskLevel.SYSTEM_CONFIGURATION_CHANGE
                            && confirmationRequirement == ConfirmationRequirement.NOT_REQUIRED,
                    actionType);
            case AUTOMATIC_REPAIR -> requirePolicy(
                    availability == Availability.EXECUTABLE
                            && riskLevel == RiskLevel.SYSTEM_CONFIGURATION_CHANGE
                            && confirmationRequirement == ConfirmationRequirement.REQUIRED,
                    actionType);
        }
    }

    /// Rejects one invalid fixed-policy combination.
    ///
    /// @param valid whether the supplied policy matches the action category
    /// @param actionType action category used in the failure message
    private static void requirePolicy(boolean valid, ActionType actionType) {
        if (!valid) {
            throw new IllegalArgumentException("Invalid policy for action type " + actionType);
        }
    }

    /// Stable categories understood by presentation and remote-control layers.
    @NotNullByDefault
    public enum ActionType {
        /// Presents a repair explanation that must be followed manually.
        MANUAL_GUIDANCE,

        /// Opens or prepares a launcher-owned search for missing mods.
        OPEN_MOD_SEARCH,

        /// Selects a compatible Java runtime, downloading one if the registry has no suitable candidate.
        REPLACE_JAVA_RUNTIME,

        /// Executes a legacy task whose precise effects are not described by Core.
        AUTOMATIC_REPAIR
    }

    /// Execution availability in the application context that produced the diagnosis.
    @NotNullByDefault
    public enum Availability {
        /// The proposal contains guidance and is not an executable operation.
        INFORMATION_ONLY,

        /// The application supplied the boundary required to execute the proposal.
        EXECUTABLE,

        /// The action is known but the current application context cannot execute it.
        APPLICATION_BOUNDARY_UNAVAILABLE
    }

    /// Highest class of side effect expected from execution.
    @NotNullByDefault
    public enum RiskLevel {
        /// The proposal has no executable side effect.
        NONE,

        /// Execution performs an interactive or network-backed application action.
        INTERACTIVE_SIDE_EFFECT,

        /// Execution can modify persistent runtime or launcher configuration.
        SYSTEM_CONFIGURATION_CHANGE
    }

    /// Application-owned confirmation requirement for execution.
    @NotNullByDefault
    public enum ConfirmationRequirement {
        /// Execution does not require a destructive-action confirmation prompt.
        NOT_REQUIRED,

        /// The application must obtain per-use confirmation before execution.
        REQUIRED
    }
}
