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
import org.junit.jupiter.api.Test;
import space.minecraftstl.xyml.task.FileDownloadTask.IntegrityCheck;
import space.minecraftstl.xyml.upgrade.RemoteVersion;
import space.minecraftstl.xyml.upgrade.UpdateChannel;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies channel-aware update ordering and the single stable-version reset exception.
@NotNullByDefault
class MetadataUpdateAvailabilityPolicyTest {
    /// Uses numeric comparison for ordinary releases on the current channel.
    @Test
    void comparesVersionsNumericallyWithinChannel() {
        MetadataUpdateAvailabilityPolicy policy = new MetadataUpdateAvailabilityPolicy(
                "1.0.0",
                UpdateChannel.STABLE);
        MetadataUpdateAvailabilityPolicy betaPolicy = new MetadataUpdateAvailabilityPolicy(
                "1.0.0.1",
                UpdateChannel.BETA);

        assertTrue(policy.isUpdateAvailable(remote("1.0.1", UpdateChannel.STABLE, false)));
        assertFalse(policy.isUpdateAvailable(remote("1.0.0", UpdateChannel.STABLE, false)));
        assertFalse(policy.isUpdateAvailable(remote("0.9.9", UpdateChannel.STABLE, false)));
        assertTrue(betaPolicy.isUpdateAvailable(remote("1.0.0.2", UpdateChannel.BETA, false)));
        assertFalse(betaPolicy.isUpdateAvailable(remote("1.0.0.0", UpdateChannel.BETA, false)));
    }

    /// Offers a different forced or cross-channel build regardless of numeric ordering.
    @Test
    void replacesDifferentForcedOrCrossChannelBuilds() {
        MetadataUpdateAvailabilityPolicy stable = new MetadataUpdateAvailabilityPolicy(
                "1.0.1",
                UpdateChannel.STABLE);

        assertTrue(stable.isUpdateAvailable(remote("1.0.0", UpdateChannel.STABLE, true)));
        assertTrue(stable.isUpdateAvailable(remote("1.0.0.1", UpdateChannel.BETA, false)));
        assertTrue(stable.isUpdateAvailable(remote("1.0.0.0.1", UpdateChannel.ALPHA, false)));
        assertTrue(stable.isUpdateAvailable(remote("1.0.0.0.0.1", UpdateChannel.DEV, false)));
        assertFalse(stable.isUpdateAvailable(remote("1.0.1", UpdateChannel.BETA, false)));
    }

    /// Allows only the exact unreleased stable test build to cross the intentional version reset.
    @Test
    void permitsOnlyExactOneTimeStableReset() {
        MetadataUpdateAvailabilityPolicy exactLegacy = new MetadataUpdateAvailabilityPolicy(
                "3.17.0",
                UpdateChannel.STABLE);
        MetadataUpdateAvailabilityPolicy otherLegacy = new MetadataUpdateAvailabilityPolicy(
                "3.17.1",
                UpdateChannel.STABLE);
        MetadataUpdateAvailabilityPolicy legacyBeta = new MetadataUpdateAvailabilityPolicy(
                "3.17.0",
                UpdateChannel.BETA);

        assertTrue(exactLegacy.isUpdateAvailable(remote("1.0.0", UpdateChannel.STABLE, false)));
        assertFalse(exactLegacy.isUpdateAvailable(remote("1.0.1", UpdateChannel.STABLE, false)));
        assertFalse(otherLegacy.isUpdateAvailable(remote("1.0.0", UpdateChannel.STABLE, false)));
        assertFalse(legacyBeta.isUpdateAvailable(remote("1.0.0", UpdateChannel.BETA, false)));
    }

    /// Suppresses offers for source placeholders and local feature-build versions.
    @Test
    void suppressesDevelopmentPlaceholders() {
        MetadataUpdateAvailabilityPolicy placeholder = new MetadataUpdateAvailabilityPolicy(
                "@develop@",
                UpdateChannel.DEV);
        MetadataUpdateAvailabilityPolicy featureBuild = new MetadataUpdateAvailabilityPolicy(
                "1.0.0.0.0.0.",
                UpdateChannel.DEV);

        assertFalse(placeholder.isUpdateAvailable(remote("99.0", UpdateChannel.STABLE, true)));
        assertFalse(featureBuild.isUpdateAvailable(remote("99.0", UpdateChannel.STABLE, true)));
    }

    /// Builds one deterministic remote-version fixture.
    ///
    /// @param version remote version string
    /// @param channel remote channel
    /// @param force whether the release is mandatory
    /// @return remote-version fixture
    private static RemoteVersion remote(String version, UpdateChannel channel, boolean force) {
        return new RemoteVersion(
                channel,
                version,
                "https://example.test/xyml.jar",
                RemoteVersion.Type.JAR,
                new IntegrityCheck("SHA-1", "0123456789abcdef"),
                false,
                force);
    }
}
