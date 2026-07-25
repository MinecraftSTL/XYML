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
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import space.minecraftstl.xyml.download.LibraryAnalyzer;
import space.minecraftstl.xyml.download.RemoteVersion;
import space.minecraftstl.xyml.ui.swing.page.downloads.loaders.GameLoaderCompatibilityMatrix;
import space.minecraftstl.xyml.ui.swing.page.downloads.loaders.GameLoaderKind;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/// Validates existing-instance installer mutations against the new-game loader compatibility matrix.
///
/// Remote versions remain concrete Core objects throughout validation. This is essential because their
/// subclasses carry installer-specific metadata and [RemoteVersion#getInstallTask] cannot be recreated
/// from a library ID and display version alone.
@NotNullByDefault
public final class InstanceInstallerCompatibility {
    /// Maps Core patch and download-list identifiers to their shared compatibility kinds.
    private static final @Unmodifiable Map<String, GameLoaderKind> KINDS_BY_LIBRARY_ID =
            createKindsByLibraryId();

    /// Prevents construction of static compatibility helpers.
    private InstanceInstallerCompatibility() {
    }

    /// Finds the shared loader kind for one Core library identifier.
    ///
    /// @param libraryId Core patch or download-list identifier
    /// @return managed kind, or empty when this service intentionally leaves the library to Core
    public static Optional<GameLoaderKind> kindForLibraryId(String libraryId) {
        return Optional.ofNullable(KINDS_BY_LIBRARY_ID.get(requireNonBlankLibraryId(libraryId)));
    }

    /// Validates an ordered remote installation request and returns the same concrete objects in order.
    ///
    /// Existing selections of a requested kind are treated as replacements because
    /// [space.minecraftstl.xyml.download.DefaultDependencyManager#installLibraryAsync] removes the old
    /// library of that kind before applying the selected remote version.
    ///
    /// @param snapshot authoritative existing-instance snapshot
    /// @param remoteVersions original caller-selected versions in intended installation order
    /// @return defensive immutable copy preserving the input object identity and order
    /// @throws InstanceInstallerValidationException when the resulting set is invalid or unsafe to order
    public static @Unmodifiable List<RemoteVersion> validateRemoteInstallation(
            InstanceInstallerSnapshot snapshot,
            Collection<? extends RemoteVersion> remoteVersions) {
        InstanceInstallerSnapshot current = Objects.requireNonNull(snapshot, "snapshot");
        String gameVersion = current.gameVersion().orElseThrow(() -> new InstanceInstallerValidationException(
                InstanceInstallerValidationException.Reason.GAME_VERSION_UNAVAILABLE,
                "The target instance has no detectable Minecraft version"));
        Collection<? extends RemoteVersion> supplied = Objects.requireNonNull(remoteVersions, "remoteVersions");
        if (supplied.isEmpty()) {
            throw new InstanceInstallerValidationException(
                    InstanceInstallerValidationException.Reason.EMPTY_REMOTE_SELECTION,
                    "At least one remote loader version is required");
        }

        List<RemoteVersion> orderedVersions = new ArrayList<>(supplied.size());
        EnumMap<GameLoaderKind, Integer> requestedPositions = new EnumMap<>(GameLoaderKind.class);
        for (RemoteVersion remoteVersion : supplied) {
            RemoteVersion version = Objects.requireNonNull(remoteVersion, "remoteVersions contains null");
            GameLoaderKind kind = requireManagedKind(version.getLibraryId());
            if (!gameVersion.equals(version.getGameVersion())) {
                throw new InstanceInstallerValidationException(
                        InstanceInstallerValidationException.Reason.GAME_VERSION_MISMATCH,
                        "Remote " + kind + " targets " + version.getGameVersion()
                                + " instead of " + gameVersion);
            }
            if (!GameLoaderCompatibilityMatrix.isAvailableForGameVersion(kind, gameVersion)) {
                throw new InstanceInstallerValidationException(
                        InstanceInstallerValidationException.Reason.LOADER_UNAVAILABLE_FOR_GAME_VERSION,
                        kind + " is not available for Minecraft " + gameVersion);
            }
            Integer previousPosition = requestedPositions.putIfAbsent(kind, orderedVersions.size());
            if (previousPosition != null) {
                throw new InstanceInstallerValidationException(
                        InstanceInstallerValidationException.Reason.DUPLICATE_LOADER_SELECTION,
                        "The request contains " + kind + " at indexes " + previousPosition
                                + " and " + orderedVersions.size());
            }
            orderedVersions.add(version);
        }

        EnumSet<GameLoaderKind> resultingKinds = installedKinds(current);
        resultingKinds.removeAll(requestedPositions.keySet());
        resultingKinds.addAll(requestedPositions.keySet());
        validateRequiredParents(resultingKinds);
        validatePairwiseCompatibility(resultingKinds);
        validateRequestedOrder(requestedPositions);
        return List.copyOf(orderedVersions);
    }

    /// Validates a library removal that can affect the shared API-parent invariant.
    ///
    /// Only a library present in the current snapshot with a clear Core patch structure may be removed.
    /// This keeps the presentation rule authoritative even when a caller bypasses Swing. Recognized
    /// parent loaders additionally cannot be removed while their corresponding API companion remains
    /// installed.
    ///
    /// @param snapshot authoritative existing-instance snapshot
    /// @param libraryId exact Core library identifier requested for removal
    /// @throws InstanceInstallerValidationException when removing the library is structurally unsafe
    public static void validateRemoval(InstanceInstallerSnapshot snapshot, String libraryId) {
        InstanceInstallerSnapshot current = Objects.requireNonNull(snapshot, "snapshot");
        String requestedLibraryId = requireNonBlankLibraryId(libraryId);
        if ("game".equals(requestedLibraryId)) {
            throw new InstanceInstallerValidationException(
                    InstanceInstallerValidationException.Reason.BASE_GAME_REMOVAL_FORBIDDEN,
                    "Minecraft base-game metadata cannot be removed through installer management");
        }
        if ("mcbbs".equals(requestedLibraryId)) {
            throw removalNotAllowed(requestedLibraryId, "the protected mcbbs patch");
        }

        Optional<GameLoaderKind> optionalKind = Optional.ofNullable(KINDS_BY_LIBRARY_ID.get(requestedLibraryId));
        if (optionalKind.isEmpty()) {
            @Nullable InstanceOtherLibraryEntry otherEntry = findOtherLibrary(current, requestedLibraryId);
            if (otherEntry == null) {
                throw removalNotAllowed(requestedLibraryId, "no current snapshot entry");
            }
            if (otherEntry.structureState() != InstanceOtherLibraryEntry.StructureState.CLEAR) {
                throw removalNotAllowed(requestedLibraryId, "an externally uncertain structure");
            }
            return;
        }
        GameLoaderKind removedKind = optionalKind.get();
        @Nullable InstanceInstallerEntry installedEntry = findInstalledLoader(current, removedKind);
        if (installedEntry == null) {
            throw removalNotAllowed(requestedLibraryId, "no installed loader entry");
        }
        if (installedEntry.status() != LibraryAnalyzer.LibraryMark.LibraryStatus.CLEAR) {
            throw removalNotAllowed(requestedLibraryId, "an externally uncertain loader structure");
        }
        for (InstanceInstallerEntry entry : current.installedLoaders()) {
            Optional<GameLoaderKind> requiredParent = GameLoaderCompatibilityMatrix.requiredParent(entry.kind());
            if (requiredParent.isPresent() && requiredParent.get() == removedKind) {
                throw new InstanceInstallerValidationException(
                        InstanceInstallerValidationException.Reason.REQUIRED_COMPANION_WOULD_BE_ORPHANED,
                        "Removing " + removedKind + " would orphan " + entry.kind());
            }
        }
    }

    /// Finds one recognized loader entry from the current immutable snapshot.
    ///
    /// @param snapshot authoritative current instance state
    /// @param kind managed loader kind to locate
    /// @return matching installed entry, or null when the loader is absent
    private static @Nullable InstanceInstallerEntry findInstalledLoader(
            InstanceInstallerSnapshot snapshot,
            GameLoaderKind kind) {
        for (InstanceInstallerEntry entry : Objects.requireNonNull(snapshot, "snapshot").installedLoaders()) {
            if (entry.kind() == Objects.requireNonNull(kind, "kind")) {
                return entry;
            }
        }
        return null;
    }

    /// Finds one third-party library entry from the current immutable snapshot.
    ///
    /// @param snapshot authoritative current instance state
    /// @param libraryId exact Core library identifier to locate
    /// @return matching third-party entry, or null when it is absent
    private static @Nullable InstanceOtherLibraryEntry findOtherLibrary(
            InstanceInstallerSnapshot snapshot,
            String libraryId) {
        String requestedLibraryId = requireNonBlankLibraryId(libraryId);
        for (InstanceOtherLibraryEntry entry : Objects.requireNonNull(
                snapshot,
                "snapshot").otherRemovableLibraries()) {
            if (entry.libraryId().equals(requestedLibraryId)) {
                return entry;
            }
        }
        return null;
    }

    /// Creates one typed rejection for an absent, protected, or structurally uncertain removal target.
    ///
    /// @param libraryId exact requested Core library identifier
    /// @param explanation concise safe-removal reason
    /// @return never returns normally
    private static InstanceInstallerValidationException removalNotAllowed(
            String libraryId,
            String explanation) {
        throw new InstanceInstallerValidationException(
                InstanceInstallerValidationException.Reason.LIBRARY_REMOVAL_NOT_ALLOWED,
                "Removal is not allowed for " + requireNonBlankLibraryId(libraryId) + ": "
                        + Objects.requireNonNull(explanation, "explanation"));
    }

    /// Converts the snapshot's stable recognized entries into a mutable enum set for validation.
    ///
    /// @param snapshot authoritative existing-instance snapshot
    /// @return mutable set of currently installed managed kinds
    private static EnumSet<GameLoaderKind> installedKinds(InstanceInstallerSnapshot snapshot) {
        EnumSet<GameLoaderKind> kinds = EnumSet.noneOf(GameLoaderKind.class);
        for (InstanceInstallerEntry entry : snapshot.installedLoaders()) {
            kinds.add(entry.kind());
        }
        return kinds;
    }

    /// Requires every resulting API companion to retain its corresponding parent loader.
    ///
    /// @param resultingKinds complete loader set after replacements
    private static void validateRequiredParents(Set<GameLoaderKind> resultingKinds) {
        for (GameLoaderKind kind : resultingKinds) {
            Optional<GameLoaderKind> parent = GameLoaderCompatibilityMatrix.requiredParent(kind);
            if (parent.isPresent() && !resultingKinds.contains(parent.get())) {
                throw new InstanceInstallerValidationException(
                        InstanceInstallerValidationException.Reason.REQUIRED_PARENT_MISSING,
                        kind + " requires " + parent.get());
            }
        }
    }

    /// Requires every resulting managed pair to satisfy the historical mutual-exclusion rules.
    ///
    /// @param resultingKinds complete loader set after replacements
    private static void validatePairwiseCompatibility(Set<GameLoaderKind> resultingKinds) {
        for (GameLoaderKind kind : resultingKinds) {
            Set<GameLoaderKind> conflicts = GameLoaderCompatibilityMatrix.conflictsWith(kind, resultingKinds);
            if (!conflicts.isEmpty()) {
                throw new InstanceInstallerValidationException(
                        InstanceInstallerValidationException.Reason.INCOMPATIBLE_LOADER_SELECTION,
                        kind + " conflicts with " + conflicts);
            }
        }
    }

    /// Ensures a newly selected API companion never runs before its newly selected parent loader.
    ///
    /// @param requestedPositions selected kinds indexed by their exact caller order
    private static void validateRequestedOrder(Map<GameLoaderKind, Integer> requestedPositions) {
        for (Map.Entry<GameLoaderKind, Integer> entry : requestedPositions.entrySet()) {
            Optional<GameLoaderKind> parent = GameLoaderCompatibilityMatrix.requiredParent(entry.getKey());
            if (parent.isEmpty()) {
                continue;
            }
            Integer parentPosition = requestedPositions.get(parent.get());
            if (parentPosition != null && parentPosition > entry.getValue()) {
                throw new InstanceInstallerValidationException(
                        InstanceInstallerValidationException.Reason.REQUIRED_PARENT_AFTER_COMPANION,
                        entry.getKey() + " appears before its requested parent " + parent.get());
            }
        }
    }

    /// Resolves a managed kind or reports that a generic Core remote version cannot be installed here.
    ///
    /// @param libraryId remote version library identifier
    /// @return matching loader kind
    private static GameLoaderKind requireManagedKind(String libraryId) {
        String normalizedLibraryId = requireNonBlankLibraryId(libraryId);
        @Nullable GameLoaderKind kind = KINDS_BY_LIBRARY_ID.get(normalizedLibraryId);
        return kind != null ? kind : throwUnsupportedLibraryId(normalizedLibraryId);
    }

    /// Creates a typed unsupported-library failure for use in expression form.
    ///
    /// @param libraryId unrecognized Core library identifier
    /// @return never returns normally
    private static GameLoaderKind throwUnsupportedLibraryId(String libraryId) {
        throw new InstanceInstallerValidationException(
                InstanceInstallerValidationException.Reason.UNSUPPORTED_LIBRARY_ID,
                "No loader compatibility kind is registered for " + libraryId);
    }

    /// Validates an exact Core library identifier without silently trimming it.
    ///
    /// @param libraryId requested Core identifier
    /// @return exact non-blank identifier
    private static String requireNonBlankLibraryId(String libraryId) {
        String candidate = Objects.requireNonNull(libraryId, "libraryId");
        if (candidate.isBlank()) {
            throw new IllegalArgumentException("libraryId must not be blank");
        }
        return candidate;
    }

    /// Creates the one-to-one mapping directly from the shared kind declarations.
    ///
    /// @return immutable Core-library-to-kind map
    private static @Unmodifiable Map<String, GameLoaderKind> createKindsByLibraryId() {
        Map<String, GameLoaderKind> kinds = new java.util.LinkedHashMap<>();
        for (GameLoaderKind kind : GameLoaderKind.values()) {
            @Nullable GameLoaderKind previous = kinds.put(kind.versionListId(), kind);
            if (previous != null) {
                throw new IllegalStateException("Duplicate loader library ID: " + kind.versionListId());
            }
        }
        return Map.copyOf(kinds);
    }
}
