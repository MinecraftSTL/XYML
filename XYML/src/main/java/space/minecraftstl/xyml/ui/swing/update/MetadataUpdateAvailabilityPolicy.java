/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2026 huangyuhui <huanghongxun2008@126.com> and contributors
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
package space.minecraftstl.xyml.ui.swing.update;

import org.jetbrains.annotations.NotNullByDefault;
import space.minecraftstl.xyml.Metadata;
import space.minecraftstl.xyml.upgrade.RemoteVersion;
import space.minecraftstl.xyml.upgrade.UpdateChannel;
import space.minecraftstl.xyml.util.versioning.VersionNumber;

import java.util.Objects;

/// Preserves the launcher's established channel-aware update comparison without JavaFX bindings.
@NotNullByDefault
public final class MetadataUpdateAvailabilityPolicy implements UpdateAvailabilityPolicy {
    /// Version of the running launcher artifact.
    private final String currentVersion;

    /// Release channel of the running launcher artifact.
    private final UpdateChannel currentChannel;

    /// Whether the running artifact has nightly replacement semantics.
    private final boolean currentNightly;

    /// Creates a deterministic metadata comparison policy.
    ///
    /// @param currentVersion running launcher version
    /// @param currentChannel running launcher channel
    /// @param currentNightly whether every different version should replace the running nightly artifact
    public MetadataUpdateAvailabilityPolicy(
            String currentVersion,
            UpdateChannel currentChannel,
            boolean currentNightly) {
        this.currentVersion = Objects.requireNonNull(currentVersion, "currentVersion");
        this.currentChannel = Objects.requireNonNull(currentChannel, "currentChannel");
        this.currentNightly = currentNightly;
    }

    /// Creates a policy for the current launcher artifact metadata.
    ///
    /// @return production metadata policy
    public static MetadataUpdateAvailabilityPolicy production() {
        return new MetadataUpdateAvailabilityPolicy(
                Metadata.VERSION,
                UpdateChannel.getChannel(),
                Metadata.isNightly());
    }

    /// Applies force, channel, nightly, development-build, and semantic-version rules.
    ///
    /// @param remoteVersion fetched remote version
    /// @return whether the fetched version should be offered
    @Override
    public boolean isUpdateAvailable(RemoteVersion remoteVersion) {
        Objects.requireNonNull(remoteVersion, "remoteVersion");
        if (isDevelopmentVersion(currentVersion)) {
            return false;
        }
        if (remoteVersion.force()
                || currentNightly
                || remoteVersion.channel() == UpdateChannel.NIGHTLY
                || remoteVersion.channel() != currentChannel) {
            return !remoteVersion.version().equals(currentVersion);
        }
        return VersionNumber.compare(currentVersion, remoteVersion.version()) < 0;
    }

    /// Detects non-release version placeholders that must never receive automatic update offers.
    ///
    /// @param version launcher version string
    /// @return whether the version denotes a development build
    static boolean isDevelopmentVersion(String version) {
        Objects.requireNonNull(version, "version");
        return version.contains("@") || version.contains("SNAPSHOT");
    }
}
