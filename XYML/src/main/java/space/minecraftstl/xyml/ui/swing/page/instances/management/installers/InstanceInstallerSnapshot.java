// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.instances.management.installers;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import space.minecraftstl.xyml.game.GameInstanceID;
import space.minecraftstl.xyml.ui.swing.page.downloads.loaders.GameLoaderKind;
import space.minecraftstl.xyml.game.GameComponentType;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/// Immutable asynchronous read result for loader and third-party installer state of one existing instance.
///
/// The optional Minecraft version is empty only when Core could not identify the instance's primary game
/// JAR. Remote loader installation intentionally rejects that state because Core version lists are scoped
/// to an exact Minecraft version.
///
/// @param instanceId stable existing instance identifier
/// @param gameVersion detected Minecraft version, when available
/// @param installedLoaders recognized loaders in stable historical kind order
/// @param otherRemovableLibraries unrecognized third-party libraries eligible for the removal workflow
@NotNullByDefault
public record InstanceInstallerSnapshot(
        GameInstanceID instanceId,
        Optional<String> gameVersion,
        @Unmodifiable List<InstanceInstallerEntry> installedLoaders,
        @Unmodifiable List<InstanceOtherLibraryEntry> otherRemovableLibraries) {
    /// Defensively snapshots all entries and rejects ambiguous duplicate loader kinds.
    public InstanceInstallerSnapshot {
        instanceId = Objects.requireNonNull(instanceId, "instanceId");
        gameVersion = Objects.requireNonNull(gameVersion, "gameVersion").map(
                value -> requireNonBlank(value, "gameVersion value"));
        installedLoaders = List.copyOf(Objects.requireNonNull(installedLoaders, "installedLoaders"));
        otherRemovableLibraries = List.copyOf(Objects.requireNonNull(
                otherRemovableLibraries,
                "otherRemovableLibraries"));

        EnumSet<GameLoaderKind> observedKinds = EnumSet.noneOf(GameLoaderKind.class);
        for (InstanceInstallerEntry entry : installedLoaders) {
            GameLoaderKind kind = Objects.requireNonNull(entry, "installedLoaders contains null").kind();
            if (!observedKinds.add(kind)) {
                throw new IllegalArgumentException("installedLoaders contains duplicate kind: " + kind);
            }
        }

        Set<String> observedLibraryIds = new HashSet<>();
        for (InstanceOtherLibraryEntry entry : otherRemovableLibraries) {
            String libraryId = Objects.requireNonNull(
                    entry,
                    "otherRemovableLibraries contains null").libraryId();
            if (!observedLibraryIds.add(libraryId)) {
                throw new IllegalArgumentException(
                        "otherRemovableLibraries contains duplicate library ID: " + libraryId);
            }
            if ("mcbbs".equals(libraryId)
                    || GameComponentType.fromPatchId(libraryId) != null) {
                throw new IllegalArgumentException(
                        "otherRemovableLibraries contains a protected or recognized library ID: " + libraryId);
            }
        }
    }

    /// Validates a non-blank identifier or detected version without rewriting user-visible text.
    ///
    /// @param value candidate value
    /// @param name parameter name used in diagnostics
    /// @return exact validated value
    private static String requireNonBlank(String value, String name) {
        String candidate = Objects.requireNonNull(value, name);
        if (candidate.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return candidate;
    }
}
