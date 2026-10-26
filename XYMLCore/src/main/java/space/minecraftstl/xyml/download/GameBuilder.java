// SPDX-FileCopyrightText: Copyright (C) 2020 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.download;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import space.minecraftstl.xyml.game.GameInstanceID;
import space.minecraftstl.xyml.task.Task;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/// Builds one Minecraft environment from the requested base game and optional libraries.
///
/// Remote library selections retain the exact caller order and are never deduplicated by
/// [RemoteVersion#equals(Object)], because that legacy equality only compares the version text and
/// therefore cannot distinguish different loaders that publish the same version string.
@NotNullByDefault
public abstract class GameBuilder {

    /// Target instance identifier, or null until configured by the caller.
    protected @Nullable GameInstanceID name;

    /// Selected base Minecraft version identifier.
    protected String gameVersion = "";

    /// Legacy library versions keyed by their core-library identifier.
    protected final Map<String, String> toolVersions = new HashMap<>();

    /// Selected remote library installers in the exact caller-supplied order.
    protected final List<ComponentRemoteVersion> remoteVersions = new ArrayList<>();

    /// Returns the target instance identifier, or null before it is configured.
    public @Nullable GameInstanceID getName() {
        return name;
    }

    /// Sets the target instance identifier used under `.minecraft/versions`.
    ///
    /// @param name identifier of the new game instance
    /// @return this builder
    public GameBuilder name(GameInstanceID name) {
        this.name = Objects.requireNonNull(name);
        return this;
    }

    /// Sets the selected base Minecraft version identifier.
    ///
    /// @param version exact base Minecraft version identifier
    /// @return this builder
    public GameBuilder gameVersion(String version) {
        this.gameVersion = Objects.requireNonNull(version);
        return this;
    }

    /// Sets one legacy core-library version by identifier.
    ///
    /// The special `game` identifier updates the base Minecraft version; all other identifiers replace
    /// the previous value for that one identifier.
    ///
    /// @param id core-library identifier, such as `forge`, `liteloader`, or `optifine`
    /// @param version exact core-library version identifier
    /// @return this builder
    public GameBuilder version(String id, String version) {
        if ("game".equals(id)) {
            gameVersion(version);
        } else {
            toolVersions.put(Objects.requireNonNull(id, "id"), Objects.requireNonNull(version, "version"));
        }
        return this;
    }

    /// Adds one selected remote library installer without reordering or equality-based deduplication.
    ///
    /// @param remoteVersion selected installer metadata
    /// @return this builder
    public GameBuilder version(ComponentRemoteVersion remoteVersion) {
        remoteVersions.add(Objects.requireNonNull(remoteVersion, "remoteVersion"));
        return this;
    }

    /// Creates the stopped task that builds and persists the requested Minecraft environment.
    ///
    /// @return task that builds the whole Minecraft environment
    public abstract Task<?> buildAsync();
}
