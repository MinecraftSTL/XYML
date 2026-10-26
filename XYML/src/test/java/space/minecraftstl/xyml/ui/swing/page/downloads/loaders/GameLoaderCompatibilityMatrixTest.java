// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.downloads.loaders;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies historical loader visibility, categories, and pairwise incompatibility semantics.
@NotNullByDefault
final class GameLoaderCompatibilityMatrixTest {
    /// Preserves the exact historical installer choice groups for unset, legacy, and modern game versions.
    @Test
    void preservesHistoricalVersionGroups() {
        assertAll(
                () -> assertEquals(List.of(
                        GameLoaderKind.FORGE,
                        GameLoaderKind.NEOFORGE,
                        GameLoaderKind.LITELOADER,
                        GameLoaderKind.OPTIFINE,
                        GameLoaderKind.FABRIC,
                        GameLoaderKind.FABRIC_API,
                        GameLoaderKind.QUILT,
                        GameLoaderKind.QUILT_API,
                        GameLoaderKind.LEGACY_FABRIC,
                        GameLoaderKind.LEGACY_FABRIC_API,
                        GameLoaderKind.CLEANROOM),
                        GameLoaderCompatibilityMatrix.kindsForGameVersion(null)),
                () -> assertEquals(List.of(
                        GameLoaderKind.FORGE,
                        GameLoaderKind.CLEANROOM,
                        GameLoaderKind.LITELOADER,
                        GameLoaderKind.LEGACY_FABRIC,
                        GameLoaderKind.LEGACY_FABRIC_API,
                        GameLoaderKind.OPTIFINE),
                        GameLoaderCompatibilityMatrix.kindsForGameVersion("1.12.2")),
                () -> assertEquals(List.of(
                        GameLoaderKind.FORGE,
                        GameLoaderKind.LITELOADER,
                        GameLoaderKind.OPTIFINE,
                        GameLoaderKind.LEGACY_FABRIC,
                        GameLoaderKind.LEGACY_FABRIC_API),
                        GameLoaderCompatibilityMatrix.kindsForGameVersion("1.13.2")),
                () -> assertEquals(List.of(
                        GameLoaderKind.FORGE,
                        GameLoaderKind.NEOFORGE,
                        GameLoaderKind.OPTIFINE,
                        GameLoaderKind.FABRIC,
                        GameLoaderKind.FABRIC_API,
                        GameLoaderKind.QUILT,
                        GameLoaderKind.QUILT_API),
                        GameLoaderCompatibilityMatrix.kindsForGameVersion("1.13.3")));
    }

    /// Preserves API, Legacy Fabric, Cleanroom, and OptiFine distinctions used by later UI layers.
    @Test
    void preservesHistoricalKindCategories() {
        assertAll(
                () -> assertEquals(GameLoaderCategory.API, GameLoaderKind.FABRIC_API.category()),
                () -> assertEquals(GameLoaderCategory.LEGACY_LOADER,
                        GameLoaderKind.LEGACY_FABRIC.category()),
                () -> assertEquals(GameLoaderCategory.LEGACY_API,
                        GameLoaderKind.LEGACY_FABRIC_API.category()),
                () -> assertEquals(GameLoaderCategory.CLEANROOM,
                        GameLoaderKind.CLEANROOM.category()),
                () -> assertEquals(GameLoaderCategory.OPTIMIZATION,
                        GameLoaderKind.OPTIFINE.category()));
    }

    /// Retains historical exceptions such as Forge plus OptiFine while rejecting incompatible loader pairs.
    @Test
    void retainsPairwiseCompatibilityRules() {
        assertAll(
                () -> assertTrue(GameLoaderCompatibilityMatrix.areCompatible(
                        GameLoaderKind.FORGE,
                        GameLoaderKind.OPTIFINE)),
                () -> assertTrue(GameLoaderCompatibilityMatrix.areCompatible(
                        GameLoaderKind.FABRIC,
                        GameLoaderKind.FABRIC_API)),
                () -> assertFalse(GameLoaderCompatibilityMatrix.areCompatible(
                        GameLoaderKind.FABRIC,
                        GameLoaderKind.OPTIFINE)),
                () -> assertFalse(GameLoaderCompatibilityMatrix.areCompatible(
                        GameLoaderKind.FORGE,
                        GameLoaderKind.FABRIC_API)),
                () -> assertEquals(
                        Set.of(
                                GameLoaderKind.FABRIC,
                                GameLoaderKind.OPTIFINE,
                                GameLoaderKind.FORGE),
                        GameLoaderCompatibilityMatrix.conflictsWith(
                                GameLoaderKind.QUILT,
                                List.of(GameLoaderKind.FABRIC, GameLoaderKind.OPTIFINE,
                                        GameLoaderKind.FORGE))));
    }

    /// API companions require their matching parent loader even though the pair is otherwise compatible.
    @Test
    void requiresMatchingParentLoaderBeforeSelectingApiCompanions() {
        assertAll(
                () -> assertEquals(Optional.of(GameLoaderKind.FABRIC),
                        GameLoaderCompatibilityMatrix.requiredParent(GameLoaderKind.FABRIC_API)),
                () -> assertEquals(Optional.of(GameLoaderKind.QUILT),
                        GameLoaderCompatibilityMatrix.requiredParent(GameLoaderKind.QUILT_API)),
                () -> assertEquals(Optional.of(GameLoaderKind.LEGACY_FABRIC),
                        GameLoaderCompatibilityMatrix.requiredParent(GameLoaderKind.LEGACY_FABRIC_API)),
                () -> assertTrue(GameLoaderCompatibilityMatrix.requiredParent(GameLoaderKind.FORGE).isEmpty()),
                () -> assertFalse(GameLoaderCompatibilityMatrix.hasRequiredParent(
                        GameLoaderKind.FABRIC_API,
                        List.of())),
                () -> assertFalse(GameLoaderCompatibilityMatrix.hasRequiredParent(
                        GameLoaderKind.QUILT_API,
                        List.of(GameLoaderKind.FABRIC))),
                () -> assertTrue(GameLoaderCompatibilityMatrix.hasRequiredParent(
                        GameLoaderKind.FABRIC_API,
                        List.of(GameLoaderKind.FABRIC))),
                () -> assertTrue(GameLoaderCompatibilityMatrix.hasRequiredParent(
                        GameLoaderKind.LEGACY_FABRIC_API,
                        List.of(GameLoaderKind.LEGACY_FABRIC))));
    }
}
