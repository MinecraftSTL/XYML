// SPDX-FileCopyrightText: Copyright (C) 2021 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.download.fabric;

import space.minecraftstl.xyml.download.DefaultDependencyManager;
import space.minecraftstl.xyml.download.ComponentRemoteVersion;
import space.minecraftstl.xyml.game.GameComponentType;
import space.minecraftstl.xyml.game.GameInstanceManifest;
import space.minecraftstl.xyml.game.GameInstancePatch;
import space.minecraftstl.xyml.addon.RemoteAddon;
import space.minecraftstl.xyml.task.Task;
import space.minecraftstl.xyml.util.versioning.GameVersionNumber;
import org.jetbrains.annotations.NotNullByDefault;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

@NotNullByDefault
public final class FabricAPIRemoteVersion extends ComponentRemoteVersion {

    /// @see <a href="https://modrinth.com/mod/fabric-api">Fabric API - Modrinth</a>
    public static final String MODRINTH_ID = "fabric-api";

    private final String fullVersion;
    private final RemoteAddon.Version version;

    /// Constructor.
    ///
    /// @param gameVersion the Minecraft version that this remote version suits.
    /// @param selfVersion the version string of the remote version.
    /// @param urls        the installer or universal jar original URL.
    public FabricAPIRemoteVersion(GameVersionNumber gameVersion, String selfVersion, String fullVersion, Instant datePublished, RemoteAddon.Version version, List<String> urls) {
        super(GameComponentType.FABRIC_API, gameVersion, selfVersion, datePublished, Type.UNCATEGORIZED, urls);

        this.fullVersion = fullVersion;
        this.version = version;
    }

    @Override
    public String getFullVersion() {
        return fullVersion;
    }

    public RemoteAddon.Version getVersion() {
        return version;
    }

    @Override
    public Task<GameInstancePatch> getInstallTask(
            DefaultDependencyManager dependencyManager,
            GameInstanceManifest baseManifest,
            Path modsDirectory) {
        return new FabricAPIInstallTask(dependencyManager, baseManifest, this, modsDirectory);
    }

    @Override
    public int compareTo(ComponentRemoteVersion o) {
        if (!(o instanceof FabricAPIRemoteVersion)) return 0;
        return -this.getReleaseDate().compareTo(o.getReleaseDate());
    }
}
