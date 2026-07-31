/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2020  huangyuhui <huanghongxun2008@126.com> and contributors
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

import org.jetbrains.annotations.NotNullByDefault;
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
    /// Target instance identifier used for the generated instance directory.
    protected String name = "";

    /// Selected base Minecraft version identifier.
    protected String gameVersion = "";

    /// Legacy library versions keyed by their core-library identifier.
    protected final Map<String, String> toolVersions = new HashMap<>();

    /// Selected remote library installers in the exact caller-supplied order.
    protected final List<RemoteVersion> remoteVersions = new ArrayList<>();

    /// Returns the target instance identifier.
    ///
    /// @return exact target instance identifier
    public String getName() {
        return name;
    }

    /// Sets the target instance identifier used under `.minecraft/versions`.
    ///
    /// @param name identifier of the new game instance
    /// @return this builder
    public GameBuilder name(String name) {
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
    public GameBuilder version(RemoteVersion remoteVersion) {
        remoteVersions.add(Objects.requireNonNull(remoteVersion, "remoteVersion"));
        return this;
    }

    /// Creates the stopped task that builds and persists the requested Minecraft environment.
    ///
    /// @return task that builds the whole Minecraft environment
    public abstract Task<?> buildAsync();
}
