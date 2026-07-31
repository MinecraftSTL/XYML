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

/// Verifies parity with the established launcher update-availability rules.
@NotNullByDefault
class MetadataUpdateAvailabilityPolicyTest {
    /// Uses semantic comparison for ordinary releases on the current channel.
    @Test
    void comparesStableVersionsSemantically() {
        MetadataUpdateAvailabilityPolicy policy = new MetadataUpdateAvailabilityPolicy(
                "3.6.1",
                UpdateChannel.STABLE,
                false);

        assertTrue(policy.isUpdateAvailable(remote("3.6.2", UpdateChannel.STABLE, false)));
        assertFalse(policy.isUpdateAvailable(remote("3.6.1", UpdateChannel.STABLE, false)));
        assertFalse(policy.isUpdateAvailable(remote("3.5.9", UpdateChannel.STABLE, false)));
    }

    /// Offers a different forced or cross-channel build regardless of semantic ordering.
    @Test
    void replacesDifferentForcedNightlyOrCrossChannelBuilds() {
        MetadataUpdateAvailabilityPolicy stable = new MetadataUpdateAvailabilityPolicy(
                "3.6.1",
                UpdateChannel.STABLE,
                false);

        assertTrue(stable.isUpdateAvailable(remote("3.5.0", UpdateChannel.STABLE, true)));
        assertTrue(stable.isUpdateAvailable(remote("3.5.0", UpdateChannel.NIGHTLY, false)));
        assertTrue(stable.isUpdateAvailable(remote("3.5.0", UpdateChannel.DEVELOPMENT, false)));
        assertFalse(stable.isUpdateAvailable(remote("3.6.1", UpdateChannel.NIGHTLY, false)));
    }

    /// Uses version inequality for a running nightly artifact.
    @Test
    void runningNightlyReplacesAnyDifferentVersion() {
        MetadataUpdateAvailabilityPolicy policy = new MetadataUpdateAvailabilityPolicy(
                "3.6.1.100",
                UpdateChannel.NIGHTLY,
                true);

        assertTrue(policy.isUpdateAvailable(remote("3.6.1.99", UpdateChannel.NIGHTLY, false)));
        assertFalse(policy.isUpdateAvailable(remote("3.6.1.100", UpdateChannel.NIGHTLY, false)));
    }

    /// Suppresses offers for source placeholders and snapshot development versions.
    @Test
    void suppressesDevelopmentPlaceholders() {
        MetadataUpdateAvailabilityPolicy placeholder = new MetadataUpdateAvailabilityPolicy(
                "@develop@",
                UpdateChannel.DEVELOPMENT,
                false);
        MetadataUpdateAvailabilityPolicy snapshot = new MetadataUpdateAvailabilityPolicy(
                "3.7.SNAPSHOT",
                UpdateChannel.DEVELOPMENT,
                false);

        assertFalse(placeholder.isUpdateAvailable(remote("99.0", UpdateChannel.STABLE, true)));
        assertFalse(snapshot.isUpdateAvailable(remote("99.0", UpdateChannel.STABLE, true)));
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
