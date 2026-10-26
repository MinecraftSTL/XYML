// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.download;

import space.minecraftstl.xyml.game.GameComponentType;
import space.minecraftstl.xyml.game.GameInstanceManifest;
import space.minecraftstl.xyml.game.GameInstancePatch;
import space.minecraftstl.xyml.task.Task;
import space.minecraftstl.xyml.util.versioning.GameVersionNumber;

import java.nio.file.Path;
import java.util.List;

/// Minimal concrete component version used by download-layer tests.
public final class TestComponentRemoteVersion extends ComponentRemoteVersion {

    /// Creates test metadata for one component type and version.
    ///
    /// @param componentType component type
    /// @param gameVersion   target Minecraft version
    /// @param selfVersion   component version
    public TestComponentRemoteVersion(GameComponentType componentType, String gameVersion, String selfVersion) {
        super(componentType, GameVersionNumber.asGameVersion(gameVersion), selfVersion, null, Type.RELEASE, List.of());
    }

    /// Rejects installation because tests only inspect metadata.
    ///
    /// @throws UnsupportedOperationException always
    @Override
    public Task<GameInstancePatch> getInstallTask(
            DefaultDependencyManager dependencyManager,
            GameInstanceManifest baseManifest,
            Path modsDirectory) {
        throw new UnsupportedOperationException("Test component versions cannot be installed");
    }
}
