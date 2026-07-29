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
package space.minecraftstl.xyml.game.install;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import space.minecraftstl.xyml.download.GameBuilder;
import space.minecraftstl.xyml.download.RemoteVersion;
import space.minecraftstl.xyml.game.GameInstanceID;
import space.minecraftstl.xyml.task.Task;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests request-to-builder composition and isolation classification for repository installations.
@NotNullByDefault
public final class RepositoryGameInstallTaskFactoryTest {
    /// Remote installers are forwarded to the builder in order and loader selection is modded.
    @Test
    public void configuresOrderedRemoteInstallersAndRecognizesModLoaderIsolation() {
        RemoteVersion forge = remoteVersion("forge", "47.2.0");
        RemoteVersion fabric = remoteVersion("fabric", "47.2.0");
        GameInstallRequest request = new GameInstallRequest(
                "loader-instance",
                "1.21.1",
                List.of(forge, fabric));
        RecordingGameBuilder builder = new RecordingGameBuilder();

        GameInstanceID instanceId = new GameInstanceID(request.instanceName());
        RepositoryGameInstallTaskFactory.configureBuilder(builder, instanceId, request);

        assertEquals(instanceId, builder.getName());
        assertEquals("1.21.1", builder.gameVersion());
        @Unmodifiable List<RemoteVersion> selected = builder.remoteVersionsSnapshot();
        assertEquals(2, selected.size());
        assertSame(forge, selected.get(0));
        assertSame(fabric, selected.get(1));
        assertTrue(RepositoryGameInstallTaskFactory.isModded(request));
    }

    /// Vanilla-only and OptiFine-only requests retain the legacy non-modded isolation behavior.
    @Test
    public void preservesVanillaAndAuxiliaryOnlyIsolationBehavior() {
        GameInstallRequest vanillaRequest = new GameInstallRequest("vanilla-instance", "1.21.1");
        GameInstallRequest optiFineRequest = new GameInstallRequest(
                "optifine-instance",
                "1.21.1",
                List.of(remoteVersion("optifine", "HD_U_I6")));
        RecordingGameBuilder vanillaBuilder = new RecordingGameBuilder();

        RepositoryGameInstallTaskFactory.configureBuilder(
                vanillaBuilder,
                new GameInstanceID(vanillaRequest.instanceName()),
                vanillaRequest);

        assertTrue(vanillaBuilder.remoteVersionsSnapshot().isEmpty());
        assertFalse(RepositoryGameInstallTaskFactory.isModded(vanillaRequest));
        assertFalse(RepositoryGameInstallTaskFactory.isModded(optiFineRequest));
    }

    /// Creates minimal remote installer metadata for task-factory composition tests.
    ///
    /// @param libraryId selected installer identifier
    /// @param selfVersion selected installer version text
    /// @return remote installer metadata
    private static RemoteVersion remoteVersion(String libraryId, String selfVersion) {
        return new RemoteVersion(libraryId, "1.21.1", selfVersion, Instant.EPOCH, List.of());
    }

    /// Captures builder calls without constructing files or starting a network-backed task.
    @NotNullByDefault
    private static final class RecordingGameBuilder extends GameBuilder {
        /// Returns the selected base game-version identifier configured by the factory.
        ///
        /// @return exact configured base game-version identifier
        private String gameVersion() {
            return gameVersion;
        }

        /// Returns an immutable snapshot of remote installers configured by the factory.
        ///
        /// @return immutable ordered remote installers
        private @Unmodifiable List<RemoteVersion> remoteVersionsSnapshot() {
            return List.copyOf(remoteVersions);
        }

        /// Rejects task execution because these tests inspect only task construction configuration.
        ///
        /// @return never returns normally
        @Override
        public Task<?> buildAsync() {
            throw new UnsupportedOperationException("No task is required for task-factory configuration tests");
        }
    }
}
