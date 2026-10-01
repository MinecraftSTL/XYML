/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2026  huangyuhui <huanghongxun2008@126.com> and contributors
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
