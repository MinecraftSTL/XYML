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
package space.minecraftstl.xyml.upgrade;

import org.jetbrains.annotations.NotNullByDefault;
import space.minecraftstl.xyml.Metadata;

/// Canonical XYML release channels ordered from most stable to most frequently updated.
@NotNullByDefault
public enum UpdateChannel {
    /// Stable release channel using three decimal version components.
    STABLE("stable", 3),

    /// Public beta channel using four decimal version components.
    BETA("beta", 4),

    /// Internal alpha channel using five decimal version components.
    ALPHA("alpha", 5),

    /// Development channel using six decimal version components.
    DEV("dev", 6);

    /// Canonical lowercase channel identifier used by update requests.
    private final String channelName;

    /// Exact decimal component count required by published versions in this channel.
    private final int versionComponentCount;

    /// Creates one immutable release channel definition.
    ///
    /// @param channelName canonical lowercase channel identifier
    /// @param versionComponentCount exact published version component count
    UpdateChannel(String channelName, int versionComponentCount) {
        this.channelName = channelName;
        this.versionComponentCount = versionComponentCount;
    }

    /// Resolves a canonical release channel identifier.
    ///
    /// @param channelName canonical lowercase channel identifier
    /// @return matching release channel
    /// @throws IllegalArgumentException when the identifier is unsupported
    public static UpdateChannel fromName(String channelName) {
        for (UpdateChannel channel : values()) {
            if (channel.channelName.equals(channelName)) {
                return channel;
            }
        }
        throw new IllegalArgumentException("Unsupported release channel: " + channelName);
    }

    /// Returns the release channel embedded in the running launcher artifact.
    ///
    /// @return current artifact release channel
    public static UpdateChannel getChannel() {
        return fromName(Metadata.BUILD_CHANNEL);
    }

    /// Returns the canonical lowercase channel identifier.
    ///
    /// @return channel identifier
    public String channelName() {
        return channelName;
    }

    /// Returns the exact decimal component count required by published versions.
    ///
    /// @return published version component count
    public int versionComponentCount() {
        return versionComponentCount;
    }
}
