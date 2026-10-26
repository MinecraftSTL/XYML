// SPDX-FileCopyrightText: Copyright (C) 2022 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.download.legacyfabric;

import org.glavo.url.WebURL;
import space.minecraftstl.xyml.download.DefaultDependencyManager;
import space.minecraftstl.xyml.download.ComponentRemoteVersion;
import space.minecraftstl.xyml.game.GameComponentType;
import space.minecraftstl.xyml.game.GameInstanceManifest;
import space.minecraftstl.xyml.game.GameInstancePatch;
import space.minecraftstl.xyml.task.Task;
import space.minecraftstl.xyml.util.versioning.GameVersionNumber;
import org.jetbrains.annotations.NotNullByDefault;

import java.nio.file.Path;
import java.util.*;

@NotNullByDefault
public final class LegacyFabricRemoteVersion extends ComponentRemoteVersion {

    public static final WebURL LOADER_META_URL = WebURL.parse("https://meta.legacyfabric.net/v2/versions/loader");
    public static final WebURL GAME_META_URL = WebURL.parse("https://meta.legacyfabric.net/v2/versions/game");

    /// Constructor.
    ///
    /// @param gameVersion the Minecraft version that this remote version suits.
    /// @param selfVersion the version string of the remote version.
    /// @param urls        the installer or universal jar original URL.
    public LegacyFabricRemoteVersion(GameVersionNumber gameVersion, String selfVersion, List<String> urls) {
        super(GameComponentType.LEGACY_FABRIC, gameVersion, selfVersion, null, Type.UNCATEGORIZED, urls);
    }

    @Override
    public Task<GameInstancePatch> getInstallTask(DefaultDependencyManager dependencyManager, GameInstanceManifest baseManifest, Path modsDirectory) {
        return new LegacyFabricInstallTask(dependencyManager, baseManifest, this);
    }

}
