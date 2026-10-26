// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

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
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    /// The explicit permanent mode removes the directory without relying on the platform recycle bin.
    ///
    /// @throws IOException when the fixture cannot be created or removed
    @Test
    public void permanentDeletionDoesNotScheduleRefresh() throws IOException {
        TrackingRepository repository = new TrackingRepository(temporaryDirectory.resolve("permanent"));
        GameInstanceID instanceId = new GameInstanceID("instance");
        Path instanceDirectory = repository.getInstanceRoot(instanceId);
        Files.createDirectories(instanceDirectory);

        repository.removeInstancePermanentlyWithoutRefresh(instanceId);

        assertFalse(Files.exists(instanceDirectory));
        assertEquals(0, repository.refreshTaskCount.get());
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
