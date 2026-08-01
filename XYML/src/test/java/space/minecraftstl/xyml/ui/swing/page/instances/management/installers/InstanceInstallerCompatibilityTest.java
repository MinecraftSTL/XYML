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
package space.minecraftstl.xyml.ui.swing.page.instances.management.installers;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import space.minecraftstl.xyml.download.LibraryAnalyzer;
import space.minecraftstl.xyml.download.RemoteVersion;
import space.minecraftstl.xyml.game.GameInstanceID;
import space.minecraftstl.xyml.ui.swing.page.downloads.loaders.GameLoaderKind;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Verifies existing-instance validation reuses the exact new-game loader compatibility policy.
@NotNullByDefault
final class InstanceInstallerCompatibilityTest {
    /// Preserves the caller's original remote objects and installation order for a compatible request.
    @Test
    void preservesOriginalRemoteVersionsInCompatibleOrder() {
        RemoteVersion forge = remote("forge", "47.2.0");
        RemoteVersion optiFine = remote("optifine", "HD_U_I6");

        @Unmodifiable List<RemoteVersion> validated = InstanceInstallerCompatibility.validateRemoteInstallation(
                snapshot(),
                List.of(forge, optiFine));

        assertAll(
                () -> assertEquals(2, validated.size()),
                () -> assertSame(forge, validated.get(0)),
                () -> assertSame(optiFine, validated.get(1)));
    }

    /// Rejects mutually incompatible loader combinations using the shared historical matrix.
    @Test
    void rejectsMutuallyIncompatibleRemoteSelections() {
        InstanceInstallerValidationException exception = assertThrows(
                InstanceInstallerValidationException.class,
                () -> InstanceInstallerCompatibility.validateRemoteInstallation(
                        snapshot(),
                        List.of(remote("fabric", "0.16.0"), remote("optifine", "HD_U_I6"))));

        assertEquals(
                InstanceInstallerValidationException.Reason.INCOMPATIBLE_LOADER_SELECTION,
                exception.reason());
    }

    /// Requires API parents and preserves the input ordering contract required by Core API installer tasks.
    @Test
    void requiresParentAndParentBeforeApiCompanion() {
        InstanceInstallerValidationException missingParent = assertThrows(
                InstanceInstallerValidationException.class,
                () -> InstanceInstallerCompatibility.validateRemoteInstallation(
                        snapshot(),
                        List.of(remote("fabric-api", "0.104.0"))));
        InstanceInstallerValidationException wrongOrder = assertThrows(
                InstanceInstallerValidationException.class,
                () -> InstanceInstallerCompatibility.validateRemoteInstallation(
                        snapshot(),
                        List.of(remote("fabric-api", "0.104.0"), remote("fabric", "0.16.0"))));

        assertAll(
                () -> assertEquals(
                        InstanceInstallerValidationException.Reason.REQUIRED_PARENT_MISSING,
                        missingParent.reason()),
                () -> assertEquals(
                        InstanceInstallerValidationException.Reason.REQUIRED_PARENT_AFTER_COMPANION,
                        wrongOrder.reason()));
    }

    /// Allows a companion when its parent is already installed, but blocks removal that would orphan it.
    @Test
    void respectsInstalledParentsDuringInstallationAndRemoval() {
        InstanceInstallerSnapshot fabricInstance = snapshot(new InstanceInstallerEntry(
                GameLoaderKind.FABRIC,
                "0.16.0",
                LibraryAnalyzer.LibraryMark.LibraryStatus.CLEAR));
        InstanceInstallerSnapshot fabricWithApi = snapshot(
                new InstanceInstallerEntry(
                        GameLoaderKind.FABRIC,
                        "0.16.0",
                        LibraryAnalyzer.LibraryMark.LibraryStatus.CLEAR),
                new InstanceInstallerEntry(
                        GameLoaderKind.FABRIC_API,
                        "0.104.0",
                        LibraryAnalyzer.LibraryMark.LibraryStatus.CLEAR));

        @Unmodifiable List<RemoteVersion> validated = InstanceInstallerCompatibility.validateRemoteInstallation(
                fabricInstance,
                List.of(remote("fabric-api", "0.104.0")));
        InstanceInstallerValidationException removalFailure = assertThrows(
                InstanceInstallerValidationException.class,
                () -> InstanceInstallerCompatibility.validateRemoval(fabricWithApi, "fabric"));

        assertAll(
                () -> assertEquals(1, validated.size()),
                () -> assertEquals("fabric-api", validated.get(0).getLibraryId()),
                () -> assertEquals(
                        InstanceInstallerValidationException.Reason.REQUIRED_COMPANION_WOULD_BE_ORPHANED,
                        removalFailure.reason()));
    }

    /// Rejects a valid Core loader type when it was not historically offered for the target game version.
    @Test
    void rejectsKindsUnavailableForTheTargetGameVersion() {
        InstanceInstallerValidationException exception = assertThrows(
                InstanceInstallerValidationException.class,
                () -> InstanceInstallerCompatibility.validateRemoteInstallation(
                        snapshotForGameVersion("1.12.2"),
                        List.of(remote("neoforge", "20.6.0", "1.12.2"))));

        assertEquals(
                InstanceInstallerValidationException.Reason.LOADER_UNAVAILABLE_FOR_GAME_VERSION,
                exception.reason());
    }

    /// Preserves unrecognized third-party removal data without treating it as a managed loader kind.
    @Test
    void modelsOtherRemovableLibrariesWithExplicitStructuralCertainty() {
        InstanceOtherLibraryEntry clear = new InstanceOtherLibraryEntry(
                "third-party-clear",
                "1.0.0",
                InstanceOtherLibraryEntry.StructureState.fromAnalyzerStatus(
                        LibraryAnalyzer.LibraryMark.LibraryStatus.CLEAR));
        InstanceOtherLibraryEntry uncertain = new InstanceOtherLibraryEntry(
                "third-party-external",
                null,
                InstanceOtherLibraryEntry.StructureState.fromAnalyzerStatus(
                        LibraryAnalyzer.LibraryMark.LibraryStatus.JUST_EXISTED));
        List<InstanceOtherLibraryEntry> mutableEntries = new ArrayList<>(List.of(clear, uncertain));
        InstanceInstallerSnapshot snapshot = new InstanceInstallerSnapshot(
                new GameInstanceID("existing-instance"),
                Optional.of("1.21.1"),
                List.of(),
                mutableEntries);
        mutableEntries.clear();

        assertAll(
                () -> assertEquals(2, snapshot.otherRemovableLibraries().size()),
                () -> assertEquals(InstanceOtherLibraryEntry.StructureState.CLEAR, clear.structureState()),
                () -> assertEquals(
                        InstanceOtherLibraryEntry.StructureState.EXTERNALLY_UNCERTAIN,
                        uncertain.structureState()),
                () -> assertEquals(
                        InstanceOtherLibraryEntry.StructureState.EXTERNALLY_UNCERTAIN,
                        InstanceOtherLibraryEntry.StructureState.fromAnalyzerStatus(
                                LibraryAnalyzer.LibraryMark.LibraryStatus.UNSURE)),
                () -> assertEquals(List.of(clear, uncertain), snapshot.otherRemovableLibraries()));

        assertAll(
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> snapshotWithOtherLibrary("mcbbs")),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> snapshotWithOtherLibrary("forge")));
    }

    /// Ensures direct callers cannot bypass the page's clear-structure removal boundary.
    @Test
    void rejectsProtectedAbsentAndExternallyUncertainRemovalTargets() {
        InstanceInstallerSnapshot snapshot = new InstanceInstallerSnapshot(
                new GameInstanceID("existing-instance"),
                Optional.of("1.21.1"),
                List.of(new InstanceInstallerEntry(
                        GameLoaderKind.FORGE,
                        "47.2.0",
                        LibraryAnalyzer.LibraryMark.LibraryStatus.JUST_EXISTED)),
                List.of(
                        new InstanceOtherLibraryEntry(
                                "clear-third-party",
                                "1.0.0",
                                InstanceOtherLibraryEntry.StructureState.CLEAR),
                        new InstanceOtherLibraryEntry(
                                "uncertain-third-party",
                                "2.0.0",
                                InstanceOtherLibraryEntry.StructureState.EXTERNALLY_UNCERTAIN)));

        assertAll(
                () -> assertDoesNotThrow(
                        () -> InstanceInstallerCompatibility.validateRemoval(snapshot, "clear-third-party")),
                () -> assertRemovalRejected(snapshot, "mcbbs"),
                () -> assertRemovalRejected(snapshot, "missing-library"),
                () -> assertRemovalRejected(snapshot, "uncertain-third-party"),
                () -> assertRemovalRejected(snapshot, "forge"));
    }

    /// Creates an empty modern-instance snapshot for validation tests.
    ///
    /// @param entries recognized installed loaders
    /// @return immutable snapshot for Minecraft 1.21.1
    private static InstanceInstallerSnapshot snapshot(InstanceInstallerEntry... entries) {
        return new InstanceInstallerSnapshot(
                new GameInstanceID("existing-instance"), Optional.of("1.21.1"), List.of(entries), List.of());
    }

    /// Creates an empty snapshot for one specific Minecraft version.
    ///
    /// @param gameVersion target Minecraft version
    /// @return immutable empty loader snapshot
    private static InstanceInstallerSnapshot snapshotForGameVersion(String gameVersion) {
        return new InstanceInstallerSnapshot(
                new GameInstanceID("existing-instance"), Optional.of(gameVersion), List.of(), List.of());
    }

    /// Creates a snapshot containing one prospective third-party library for boundary tests.
    ///
    /// @param libraryId Core patch identifier under test
    /// @return snapshot construction result
    private static InstanceInstallerSnapshot snapshotWithOtherLibrary(String libraryId) {
        return new InstanceInstallerSnapshot(
                new GameInstanceID("existing-instance"),
                Optional.of("1.21.1"),
                List.of(),
                List.of(new InstanceOtherLibraryEntry(
                        libraryId,
                        "1.0.0",
                        InstanceOtherLibraryEntry.StructureState.EXTERNALLY_UNCERTAIN)));
    }

    /// Asserts that a removal request fails with the non-bypassable safe-removal reason.
    ///
    /// @param snapshot authoritative instance state
    /// @param libraryId requested library identifier
    private static void assertRemovalRejected(InstanceInstallerSnapshot snapshot, String libraryId) {
        InstanceInstallerValidationException exception = assertThrows(
                InstanceInstallerValidationException.class,
                () -> InstanceInstallerCompatibility.validateRemoval(snapshot, libraryId));
        assertEquals(InstanceInstallerValidationException.Reason.LIBRARY_REMOVAL_NOT_ALLOWED, exception.reason());
    }

    /// Creates a minimal original Core remote version for matrix-only tests.
    ///
    /// @param libraryId Core library identifier
    /// @param selfVersion remote display version
    /// @return exact selected Core remote-version object
    private static RemoteVersion remote(String libraryId, String selfVersion) {
        return remote(libraryId, selfVersion, "1.21.1");
    }

    /// Creates a minimal original Core remote version for one Minecraft version.
    ///
    /// @param libraryId Core library identifier
    /// @param selfVersion remote display version
    /// @param gameVersion matching Minecraft version
    /// @return exact selected Core remote-version object
    private static RemoteVersion remote(String libraryId, String selfVersion, String gameVersion) {
        return new RemoteVersion(libraryId, gameVersion, selfVersion, Instant.EPOCH, List.of());
    }
}
