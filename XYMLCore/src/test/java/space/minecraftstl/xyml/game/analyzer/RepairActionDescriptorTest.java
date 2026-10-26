// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.game.analyzer;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies structured repair action ownership, policy invariants, and immutable parameters.
@NotNullByDefault
class RepairActionDescriptorTest {
    /// Defensively copies validated missing-mod identifiers in their diagnosis order.
    @Test
    void copiesMissingDependencyIds() {
        List<String> mutableIds = new ArrayList<>(List.of("fabric-api", "sodium"));

        RepairActionDescriptor descriptor = RepairActionDescriptor.openModSearch(mutableIds, true);
        mutableIds.add("late-mutation");

        assertEquals(List.of("fabric-api", "sodium"), descriptor.dependencyIds());
        assertThrows(UnsupportedOperationException.class, () -> descriptor.dependencyIds().add("forbidden"));
        assertEquals(RepairActionDescriptor.ActionType.OPEN_MOD_SEARCH, descriptor.actionType());
        assertEquals(RepairActionDescriptor.RiskLevel.INTERACTIVE_SIDE_EFFECT, descriptor.riskLevel());
        assertEquals(
                RepairActionDescriptor.ConfirmationRequirement.NOT_REQUIRED,
                descriptor.confirmationRequirement());
        assertTrue(descriptor.executable());
    }

    /// Retains a known missing-mod action when no application search boundary is available.
    @Test
    void describesUnavailableMissingDependencySearch() {
        RepairActionDescriptor descriptor = RepairActionDescriptor.openModSearch(List.of("cloth-config"), false);

        assertEquals(
                RepairActionDescriptor.Availability.APPLICATION_BOUNDARY_UNAVAILABLE,
                descriptor.availability());
        assertFalse(descriptor.executable());
        assertEquals(List.of("cloth-config"), descriptor.dependencyIds());
    }

    /// Marks Java selection as a persistent configuration operation that needs per-use confirmation.
    @Test
    void describesJavaRuntimeReplacementPolicy() {
        RepairActionDescriptor descriptor = RepairActionDescriptor.replaceJavaRuntime(true);

        assertEquals(RepairActionDescriptor.ActionType.REPLACE_JAVA_RUNTIME, descriptor.actionType());
        assertEquals(RepairActionDescriptor.RiskLevel.SYSTEM_CONFIGURATION_CHANGE, descriptor.riskLevel());
        assertEquals(
                RepairActionDescriptor.ConfirmationRequirement.REQUIRED,
                descriptor.confirmationRequirement());
        assertTrue(descriptor.executable());
        assertTrue(descriptor.dependencyIds().isEmpty());
    }

    /// Rejects missing, malformed, or duplicate dependency identifiers.
    @Test
    void rejectsInvalidMissingDependencyIds() {
        assertThrows(
                IllegalArgumentException.class,
                () -> RepairActionDescriptor.openModSearch(List.of(), true));
        assertThrows(
                IllegalArgumentException.class,
                () -> RepairActionDescriptor.openModSearch(List.of("invalid id"), true));
        assertThrows(
                IllegalArgumentException.class,
                () -> RepairActionDescriptor.openModSearch(List.of("fabric-api", "fabric-api"), true));
    }

    /// Rejects dependency parameters while allowing a Java action to obtain its candidate later.
    @Test
    void rejectsInconsistentActionPolicy() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new RepairActionDescriptor(
                        RepairActionDescriptor.ActionType.MANUAL_GUIDANCE,
                        RepairActionDescriptor.Availability.INFORMATION_ONLY,
                        RepairActionDescriptor.RiskLevel.NONE,
                RepairActionDescriptor.ConfirmationRequirement.REQUIRED,
                        List.of("fabric-api")));
        RepairActionDescriptor javaAction = new RepairActionDescriptor(
                RepairActionDescriptor.ActionType.REPLACE_JAVA_RUNTIME,
                RepairActionDescriptor.Availability.EXECUTABLE,
                RepairActionDescriptor.RiskLevel.SYSTEM_CONFIGURATION_CHANGE,
                RepairActionDescriptor.ConfirmationRequirement.REQUIRED,
                List.of());
        assertTrue(javaAction.executable());
    }
}
