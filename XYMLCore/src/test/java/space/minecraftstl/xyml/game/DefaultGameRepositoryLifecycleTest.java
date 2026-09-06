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
package space.minecraftstl.xyml.game;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import space.minecraftstl.xyml.task.Task;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies the refresh boundary of low-level instance deletion operations.
@NotNullByDefault
public final class DefaultGameRepositoryLifecycleTest {
    /// Temporary roots containing disposable repository fixtures.
    @TempDir
    private Path temporaryDirectory;

    /// The no-refresh variant completes the disk mutation without creating a refresh task.
    ///
    /// @throws IOException when the fixture cannot be created
    @Test
    public void deletionWithoutRefreshDoesNotScheduleRefresh() throws IOException {
        TrackingRepository repository = new TrackingRepository(temporaryDirectory.resolve("without-refresh"));
        GameInstanceID instanceId = new GameInstanceID("instance");
        Files.createDirectories(repository.getInstanceRoot(instanceId));

        assertTrue(repository.removeInstanceFromDiskWithoutRefresh(instanceId));

        assertEquals(0, repository.refreshTaskCount.get());
    }

    /// The established deletion entry point retains its asynchronous refresh side effect.
    ///
    /// @throws IOException when the fixture cannot be created
    @Test
    public void defaultDeletionStillSchedulesRefresh() throws IOException {
        TrackingRepository repository = new TrackingRepository(temporaryDirectory.resolve("with-refresh"));
        GameInstanceID instanceId = new GameInstanceID("instance");
        Files.createDirectories(repository.getInstanceRoot(instanceId));

        assertTrue(repository.removeInstanceFromDisk(instanceId));

        assertEquals(1, repository.refreshTaskCount.get());
    }

    /// Repository substitute recording refresh-task creation without scanning the fixture directory.
    @NotNullByDefault
    private static final class TrackingRepository extends DefaultGameRepository {
        /// Number of refresh tasks requested by the deletion API.
        private final AtomicInteger refreshTaskCount = new AtomicInteger();

        /// Creates a tracking repository at one disposable root.
        ///
        /// @param root repository root
        private TrackingRepository(Path root) {
            super(root);
        }

        /// Records refresh creation and returns an already successful task.
        ///
        /// @return completed refresh task
        @Override
        public Task<Void> refreshAsync() {
            refreshTaskCount.incrementAndGet();
            return Task.completed(null);
        }
    }
}
