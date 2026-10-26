// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

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
    /// Last test-only stable version created under the historical release model.
    private static final String LEGACY_TEST_STABLE_VERSION = "3.17.0";

    /// First stable version under the four-channel release model.
    private static final String FIRST_RELEASE_MODEL_STABLE_VERSION = "1.0.0";

    /// Version of the running launcher artifact.
    private final String currentVersion;

    /// Release channel of the running launcher artifact.
    private final UpdateChannel currentChannel;

    /// Creates a deterministic metadata comparison policy.
    ///
    /// @param currentVersion running launcher version
    /// @param currentChannel running launcher channel
    public MetadataUpdateAvailabilityPolicy(
            String currentVersion,
            UpdateChannel currentChannel) {
        this.currentVersion = Objects.requireNonNull(currentVersion, "currentVersion");
        this.currentChannel = Objects.requireNonNull(currentChannel, "currentChannel");
    }

    /// Creates a policy for the current launcher artifact metadata.
    ///
    /// @return production metadata policy
    public static MetadataUpdateAvailabilityPolicy production() {
        return new MetadataUpdateAvailabilityPolicy(
                Metadata.VERSION,
                UpdateChannel.getChannel());
    }

    /// Applies the one-time stable reset, force, channel, development-build, and numeric-version rules.
    ///
    /// @param remoteVersion fetched remote version
    /// @return whether the fetched version should be offered
    @Override
    public boolean isUpdateAvailable(RemoteVersion remoteVersion) {
        Objects.requireNonNull(remoteVersion, "remoteVersion");
        if (isDevelopmentVersion(currentVersion)) {
            return false;
        }
        if (isOneTimeStableVersionReset(remoteVersion)) {
            return true;
        }
        if (remoteVersion.force() || remoteVersion.channel() != currentChannel) {
            return !remoteVersion.version().equals(currentVersion);
        }
        return VersionNumber.compare(currentVersion, remoteVersion.version()) < 0;
    }

    /// Allows only the unreleased test build to cross the intentional stable-version reset.
    ///
    /// This exception is deliberately exact rather than a general compatibility layer. It must be removed after the
    /// limited `3.17.0` test environment has migrated to `1.0.0`.
    ///
    /// @param remoteVersion fetched remote version
    /// @return whether the remote release is the single permitted reset target
    private boolean isOneTimeStableVersionReset(RemoteVersion remoteVersion) {
        return currentChannel == UpdateChannel.STABLE
                && remoteVersion.channel() == UpdateChannel.STABLE
                && LEGACY_TEST_STABLE_VERSION.equals(currentVersion)
                && FIRST_RELEASE_MODEL_STABLE_VERSION.equals(remoteVersion.version());
    }

    /// Detects non-release version placeholders that must never receive automatic update offers.
    ///
    /// @param version launcher version string
    /// @return whether the version denotes a development build
    static boolean isDevelopmentVersion(String version) {
        Objects.requireNonNull(version, "version");
        return version.contains("@") || version.contains("SNAPSHOT") || version.endsWith(".");
    }
}
