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
package space.minecraftstl.xyml.ui.swing.page.downloads.loaders;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import space.minecraftstl.xyml.util.versioning.GameVersionNumber;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/// Provides the loader availability and incompatibility rules used by the historical JavaFX installer.
///
/// A missing Minecraft version intentionally returns every known kind but does not authorize any
/// source refresh. Callers use that state only to render a disabled or preview-only choice surface.
@NotNullByDefault
public final class GameLoaderCompatibilityMatrix {
    /// Historical card order before a Minecraft version was selected.
    private static final @Unmodifiable List<GameLoaderKind> UNSELECTED_GAME_KINDS = List.of(
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
            GameLoaderKind.CLEANROOM);

    /// Historical card order for exactly Minecraft 1.12.2.
    private static final @Unmodifiable List<GameLoaderKind> MINECRAFT_1_12_2_KINDS = List.of(
            GameLoaderKind.FORGE,
            GameLoaderKind.CLEANROOM,
            GameLoaderKind.LITELOADER,
            GameLoaderKind.LEGACY_FABRIC,
            GameLoaderKind.LEGACY_FABRIC_API,
            GameLoaderKind.OPTIFINE);

    /// Historical card order for Minecraft versions up to and including 1.13.2.
    private static final @Unmodifiable List<GameLoaderKind> LEGACY_GAME_KINDS = List.of(
            GameLoaderKind.FORGE,
            GameLoaderKind.LITELOADER,
            GameLoaderKind.OPTIFINE,
            GameLoaderKind.LEGACY_FABRIC,
            GameLoaderKind.LEGACY_FABRIC_API);

    /// Historical card order for Minecraft versions newer than 1.13.2.
    private static final @Unmodifiable List<GameLoaderKind> MODERN_GAME_KINDS = List.of(
            GameLoaderKind.FORGE,
            GameLoaderKind.NEOFORGE,
            GameLoaderKind.OPTIFINE,
            GameLoaderKind.FABRIC,
            GameLoaderKind.FABRIC_API,
            GameLoaderKind.QUILT,
            GameLoaderKind.QUILT_API);

    /// Symmetric historical pairwise incompatibilities keyed by candidate loader kind.
    private static final @Unmodifiable Map<GameLoaderKind, @Unmodifiable Set<GameLoaderKind>>
            INCOMPATIBLE_KINDS = createIncompatibleKinds();

    /// Parent loaders that must be selected before their API companion can be installed.
    private static final @Unmodifiable Map<GameLoaderKind, GameLoaderKind> REQUIRED_PARENT_KINDS = Map.of(
            GameLoaderKind.FABRIC_API, GameLoaderKind.FABRIC,
            GameLoaderKind.QUILT_API, GameLoaderKind.QUILT,
            GameLoaderKind.LEGACY_FABRIC_API, GameLoaderKind.LEGACY_FABRIC);

    /// Prevents construction of a static rules holder.
    private GameLoaderCompatibilityMatrix() {
    }

    /// Returns the historical card set for one selected Minecraft version.
    ///
    /// @param gameVersion selected Minecraft version, or null before selection
    /// @return immutable kinds in historical display order
    public static @Unmodifiable List<GameLoaderKind> kindsForGameVersion(
            @Nullable String gameVersion) {
        if (gameVersion == null) {
            return UNSELECTED_GAME_KINDS;
        }
        String normalizedGameVersion = gameVersion.trim();
        if (normalizedGameVersion.isEmpty()) {
            throw new IllegalArgumentException("gameVersion must not be blank");
        }
        if ("1.12.2".equals(normalizedGameVersion)) {
            return MINECRAFT_1_12_2_KINDS;
        }
        if (GameVersionNumber.compare(normalizedGameVersion, "1.13.2") <= 0) {
            return LEGACY_GAME_KINDS;
        }
        return MODERN_GAME_KINDS;
    }

    /// Determines whether one kind is historically displayed for a selected version.
    ///
    /// @param kind loader kind to check
    /// @param gameVersion selected non-blank Minecraft version
    /// @return whether the loader was offered by the historical installer
    public static boolean isAvailableForGameVersion(GameLoaderKind kind, String gameVersion) {
        return kindsForGameVersion(Objects.requireNonNull(gameVersion, "gameVersion"))
                .contains(Objects.requireNonNull(kind, "kind"));
    }

    /// Returns all kinds mutually incompatible with one candidate.
    ///
    /// @param kind candidate loader kind
    /// @return immutable set of incompatible kinds
    public static @Unmodifiable Set<GameLoaderKind> incompatibleKinds(GameLoaderKind kind) {
        GameLoaderKind nonNullKind = Objects.requireNonNull(kind, "kind");
        return Objects.requireNonNull(
                INCOMPATIBLE_KINDS.get(nonNullKind),
                "missing incompatible kind set");
    }

    /// Returns the selected parent loader required by an API companion, when one exists.
    ///
    /// @param kind candidate loader or API companion
    /// @return required parent loader, or an empty value for independent selections
    public static Optional<GameLoaderKind> requiredParent(GameLoaderKind kind) {
        return Optional.ofNullable(REQUIRED_PARENT_KINDS.get(Objects.requireNonNull(kind, "kind")));
    }

    /// Determines whether the candidate's parent loader has already been selected.
    ///
    /// This check is separate from pairwise incompatibility because an API is compatible with its
    /// parent but cannot be installed before that parent loader's task.
    ///
    /// @param candidate loader or API companion to add
    /// @param selectedKinds current selected loader kinds
    /// @return whether the candidate has no parent requirement or its required parent is selected
    public static boolean hasRequiredParent(
            GameLoaderKind candidate,
            Collection<GameLoaderKind> selectedKinds) {
        GameLoaderKind nonNullCandidate = Objects.requireNonNull(candidate, "candidate");
        Collection<GameLoaderKind> nonNullSelectedKinds = Objects.requireNonNull(
                selectedKinds,
                "selectedKinds");
        Optional<GameLoaderKind> requiredParent = requiredParent(nonNullCandidate);
        return requiredParent.isEmpty() || nonNullSelectedKinds.contains(requiredParent.get());
    }

    /// Determines whether two different loader selections may coexist in the historical installer.
    ///
    /// @param first first loader kind
    /// @param second second loader kind
    /// @return whether the pair is permitted
    public static boolean areCompatible(GameLoaderKind first, GameLoaderKind second) {
        GameLoaderKind nonNullFirst = Objects.requireNonNull(first, "first");
        GameLoaderKind nonNullSecond = Objects.requireNonNull(second, "second");
        return !incompatibleKinds(nonNullFirst).contains(nonNullSecond);
    }

    /// Returns installed selections that prevent adding a candidate.
    ///
    /// @param candidate loader kind to add
    /// @param selectedKinds current selected kinds
    /// @return immutable conflicting selections
    public static @Unmodifiable Set<GameLoaderKind> conflictsWith(
            GameLoaderKind candidate,
            Collection<GameLoaderKind> selectedKinds) {
        Set<GameLoaderKind> incompatible = incompatibleKinds(candidate);
        EnumSet<GameLoaderKind> conflicts = EnumSet.noneOf(GameLoaderKind.class);
        for (GameLoaderKind selectedKind : Objects.requireNonNull(selectedKinds, "selectedKinds")) {
            if (incompatible.contains(Objects.requireNonNull(selectedKind, "selectedKinds contains null"))) {
                conflicts.add(selectedKind);
            }
        }
        return Set.copyOf(conflicts);
    }

    /// Creates the complete symmetric map from the old installer's explicit incompatibility declarations.
    ///
    /// @return immutable per-kind incompatibility sets
    private static @Unmodifiable Map<GameLoaderKind, @Unmodifiable Set<GameLoaderKind>>
        createIncompatibleKinds() {
        EnumMap<GameLoaderKind, EnumSet<GameLoaderKind>> mutable =
                new EnumMap<>(GameLoaderKind.class);
        for (GameLoaderKind kind : GameLoaderKind.values()) {
            mutable.put(kind, EnumSet.noneOf(GameLoaderKind.class));
        }

        mutuallyIncompatible(
                mutable,
                GameLoaderKind.FORGE,
                GameLoaderKind.FABRIC,
                GameLoaderKind.QUILT,
                GameLoaderKind.NEOFORGE,
                GameLoaderKind.CLEANROOM,
                GameLoaderKind.LEGACY_FABRIC);
        addIncompatibilities(
                mutable,
                GameLoaderKind.LITELOADER,
                GameLoaderKind.FABRIC,
                GameLoaderKind.QUILT,
                GameLoaderKind.NEOFORGE,
                GameLoaderKind.CLEANROOM,
                GameLoaderKind.LEGACY_FABRIC);
        addIncompatibilities(
                mutable,
                GameLoaderKind.OPTIFINE,
                GameLoaderKind.FABRIC,
                GameLoaderKind.QUILT,
                GameLoaderKind.NEOFORGE,
                GameLoaderKind.CLEANROOM,
                GameLoaderKind.LITELOADER,
                GameLoaderKind.LEGACY_FABRIC);
        addIncompatibilities(
                mutable,
                GameLoaderKind.FABRIC_API,
                GameLoaderKind.FORGE,
                GameLoaderKind.QUILT_API,
                GameLoaderKind.NEOFORGE,
                GameLoaderKind.LITELOADER,
                GameLoaderKind.OPTIFINE,
                GameLoaderKind.CLEANROOM,
                GameLoaderKind.LEGACY_FABRIC,
                GameLoaderKind.LEGACY_FABRIC_API);
        addIncompatibilities(
                mutable,
                GameLoaderKind.QUILT_API,
                GameLoaderKind.FORGE,
                GameLoaderKind.FABRIC,
                GameLoaderKind.FABRIC_API,
                GameLoaderKind.NEOFORGE,
                GameLoaderKind.LITELOADER,
                GameLoaderKind.OPTIFINE,
                GameLoaderKind.CLEANROOM,
                GameLoaderKind.LEGACY_FABRIC,
                GameLoaderKind.LEGACY_FABRIC_API);
        addIncompatibilities(
                mutable,
                GameLoaderKind.LEGACY_FABRIC_API,
                GameLoaderKind.FORGE,
                GameLoaderKind.FABRIC,
                GameLoaderKind.FABRIC_API,
                GameLoaderKind.NEOFORGE,
                GameLoaderKind.LITELOADER,
                GameLoaderKind.OPTIFINE,
                GameLoaderKind.CLEANROOM,
                GameLoaderKind.QUILT,
                GameLoaderKind.QUILT_API);

        EnumMap<GameLoaderKind, @Unmodifiable Set<GameLoaderKind>> immutable =
                new EnumMap<>(GameLoaderKind.class);
        for (Map.Entry<GameLoaderKind, EnumSet<GameLoaderKind>> entry : mutable.entrySet()) {
            immutable.put(entry.getKey(), Set.copyOf(entry.getValue()));
        }
        return Map.copyOf(immutable);
    }

    /// Adds every distinct pair of the supplied kinds as incompatible.
    ///
    /// @param incompatibleKinds mutable symmetric map being populated
    /// @param kinds mutually exclusive kinds
    private static void mutuallyIncompatible(
            Map<GameLoaderKind, EnumSet<GameLoaderKind>> incompatibleKinds,
            GameLoaderKind... kinds) {
        List<GameLoaderKind> kindList = List.of(kinds);
        for (GameLoaderKind kind : kindList) {
            List<GameLoaderKind> otherKinds = new ArrayList<>(kindList);
            otherKinds.remove(kind);
            addIncompatibilities(
                    incompatibleKinds,
                    kind,
                    otherKinds.toArray(GameLoaderKind[]::new));
        }
    }

    /// Adds the given kind and every other kind to each other's incompatibility sets.
    ///
    /// @param incompatibleKinds mutable symmetric map being populated
    /// @param kind selected kind
    /// @param otherKinds conflicting kinds
    private static void addIncompatibilities(
            Map<GameLoaderKind, EnumSet<GameLoaderKind>> incompatibleKinds,
            GameLoaderKind kind,
            GameLoaderKind... otherKinds) {
        EnumSet<GameLoaderKind> current = Objects.requireNonNull(
                incompatibleKinds.get(Objects.requireNonNull(kind, "kind")),
                "missing incompatible kind set");
        for (GameLoaderKind otherKind : otherKinds) {
            GameLoaderKind nonNullOtherKind = Objects.requireNonNull(otherKind, "otherKinds contains null");
            current.add(nonNullOtherKind);
            Objects.requireNonNull(
                    incompatibleKinds.get(nonNullOtherKind),
                    "missing incompatible kind set").add(kind);
        }
    }
}
