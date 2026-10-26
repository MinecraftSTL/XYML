// SPDX-FileCopyrightText: Copyright (C) 2020 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.download;

import space.minecraftstl.xyml.game.GameComponentType;
import space.minecraftstl.xyml.game.GameInstanceManifest;
import space.minecraftstl.xyml.game.GameInstancePatch;
import space.minecraftstl.xyml.task.Task;
import space.minecraftstl.xyml.util.ToStringBuilder;
import space.minecraftstl.xyml.util.versioning.GameVersionNumber;
import space.minecraftstl.xyml.util.versioning.VersionNumber;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/// The remote version.
///
/// @author huangyuhui
@NotNullByDefault
public abstract class ComponentRemoteVersion implements Comparable<ComponentRemoteVersion> {

    private final GameComponentType componentType;
    private final GameVersionNumber gameVersion;
    private final String selfVersion;
    private final @Nullable Instant releaseDate;
    private final @Unmodifiable List<String> urls;
    private final Type type;

    /// Constructor.
    ///
    /// @param gameVersion the Minecraft version that this remote version suits.
    /// @param selfVersion the version string of the remote version.
    /// @param urls        the installer or universal jar URL.
    public ComponentRemoteVersion(
            GameComponentType componentType,
            GameVersionNumber gameVersion,
            String selfVersion,
            @Nullable Instant releaseDate, Type type, List<String> urls) {
        this.componentType = Objects.requireNonNull(componentType);
        this.gameVersion = Objects.requireNonNull(gameVersion);
        this.selfVersion = Objects.requireNonNull(selfVersion);
        this.releaseDate = releaseDate;
        this.urls = List.copyOf(urls);
        this.type = Objects.requireNonNull(type);
    }

    public GameComponentType getComponentType() {
        return componentType;
    }

    public GameVersionNumber getGameVersion() {
        return gameVersion;
    }

    public String getSelfVersion() {
        return selfVersion;
    }

    public String getFullVersion() {
        return getSelfVersion();
    }

    public @Nullable Instant getReleaseDate() {
        return releaseDate;
    }

    public @Unmodifiable List<String> getUrls() {
        return urls;
    }

    public Type getVersionType() {
        return type;
    }

    /// Creates an installation task with an explicit mods directory for libraries that download into the
    /// instance run tree (for example Fabric/Quilt API).
    ///
    /// @param dependencyManager the dependency manager
    /// @param baseManifest       the manifest being installed into
    /// @param modsDirectory     the mods directory of the target instance run directory
    /// @return the installation task
    public abstract Task<GameInstancePatch> getInstallTask(
            DefaultDependencyManager dependencyManager,
            GameInstanceManifest baseManifest,
            Path modsDirectory);

    @Override
    public boolean equals(Object obj) {
        return obj instanceof ComponentRemoteVersion that && Objects.equals(selfVersion, that.selfVersion);
    }

    @Override
    public int hashCode() {
        return selfVersion.hashCode();
    }

    @Override
    public String toString() {
        return new ToStringBuilder(this)
                .append("selfVersion", selfVersion)
                .append("gameVersion", gameVersion)
                .toString();
    }

    @Override
    public int compareTo(ComponentRemoteVersion o) {
        if (this.getComponentType() != o.getComponentType()) {
            return this.getComponentType().compareTo(o.getComponentType());
        }

        // newer versions are smaller than older versions
        return VersionNumber.asVersion(o.selfVersion).compareTo(VersionNumber.asVersion(selfVersion));
    }

    public enum Type {
        UNCATEGORIZED,
        RELEASE,
        SNAPSHOT,
        OLD,
        PENDING,
        UNOBFUSCATED,
    }
}
