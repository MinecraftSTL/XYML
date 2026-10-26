// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.download;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import space.minecraftstl.xyml.game.GameComponentType;
import space.minecraftstl.xyml.game.GameInstanceManifest;
import space.minecraftstl.xyml.game.GameInstancePatch;
import space.minecraftstl.xyml.task.Task;
import space.minecraftstl.xyml.util.versioning.GameVersionNumber;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/// Minimal concrete [ComponentRemoteVersion] used by launcher-layer tests.
///
/// Production component versions are abstract and carry installer-specific payloads, so construction-only
/// launcher tests use this stand-in that only exposes identity metadata.
@NotNullByDefault
public final class TestComponentRemoteVersion extends ComponentRemoteVersion {
    /// Creates one test version from a component patch identifier.
    ///
    /// @param libraryId   component patch identifier
    /// @param gameVersion target Minecraft version
    /// @param selfVersion component version
    /// @param releaseDate release date, or null when the component has none
    /// @param urls        installer or universal JAR URLs
    public TestComponentRemoteVersion(
            String libraryId,
            String gameVersion,
            String selfVersion,
            @Nullable Instant releaseDate,
            List<String> urls) {
        this(resolveComponentType(libraryId), gameVersion, selfVersion, releaseDate, urls);
    }

    /// Creates one test version for an explicit component type.
    ///
    /// @param componentType component type
    /// @param gameVersion   target Minecraft version
    /// @param selfVersion   component version
    public TestComponentRemoteVersion(GameComponentType componentType, String gameVersion, String selfVersion) {
        this(componentType, gameVersion, selfVersion, null, List.of());
    }

    /// Creates one test version with explicit release metadata.
    ///
    /// @param componentType component type
    /// @param gameVersion   target Minecraft version
    /// @param selfVersion   component version
    /// @param releaseDate   release date, or null when the component has none
    /// @param urls          installer or universal JAR URLs
    private TestComponentRemoteVersion(
            GameComponentType componentType,
            String gameVersion,
            String selfVersion,
            @Nullable Instant releaseDate,
            List<String> urls) {
        super(
                componentType,
                GameVersionNumber.asGameVersion(gameVersion),
                selfVersion,
                releaseDate,
                Type.RELEASE,
                urls);
    }

    /// Rejects installation because launcher tests only inspect identity metadata.
    ///
    /// @throws UnsupportedOperationException always
    @Override
    public Task<GameInstancePatch> getInstallTask(
            DefaultDependencyManager dependencyManager,
            GameInstanceManifest baseManifest,
            Path modsDirectory) {
        throw new UnsupportedOperationException("Test component versions cannot be installed");
    }

    /// Resolves one component type without silently substituting another component.
    ///
    /// @param libraryId component patch identifier
    /// @return matching component type
    private static GameComponentType resolveComponentType(String libraryId) {
        String candidate = Objects.requireNonNull(libraryId, "libraryId");
        return Objects.requireNonNull(
                GameComponentType.fromPatchId(candidate),
                "Unknown component patch identifier: " + candidate);
    }
}
