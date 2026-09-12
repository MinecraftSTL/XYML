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
package space.minecraftstl.xyml.download;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import space.minecraftstl.xyml.download.forge.ForgeBMCLVersionList;
import space.minecraftstl.xyml.download.liteloader.LiteLoaderBMCLVersionList;
import space.minecraftstl.xyml.download.neoforge.NeoForgeBMCLVersionList;
import space.minecraftstl.xyml.task.Task;
import space.minecraftstl.xyml.task.TaskResource;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies audited version-list compositions use non-owning orchestration resources.
@NotNullByDefault
public final class VersionListTaskResourceTest {
    /// Temporary path used for one explicit-resource control task.
    @TempDir
    private Path temporaryDirectory;

    /// Verifies load composition is non-owning while the returned legacy refresh remains conservative.
    @Test
    public void loadPreservesConservativeRefreshAndUsesOrchestrationRoot() {
        Task<?> conservativeRefresh = Task.runAsync(() -> {
        });
        StubVersionList conservativeList = new StubVersionList(conservativeRefresh);

        assertEquals(Set.of(TaskResource.conservative()), conservativeRefresh.getResources());
        assertEquals(
                Set.of(TaskResource.Kind.ORCHESTRATION),
                conservativeList.loadAsync("1.21").getResources().stream().map(TaskResource::getKind).collect(
                        java.util.stream.Collectors.toUnmodifiableSet()));

        TaskResource explicitResource = TaskResource.downloadTarget(temporaryDirectory.resolve("explicit.json"));
        Task<?> explicitRefresh = Task.runAsync(() -> {
        }).setResources(explicitResource);
        assertEquals(Set.of(explicitResource), explicitRefresh.getResources());
    }

    /// Verifies multiple-source fallback coordination is non-owning while its backend refresh keeps its declaration.
    @Test
    public void multipleSourceFallbackUsesOrchestrationWrapper() {
        Task<?> backendRefresh = Task.runAsync(() -> {
        });
        MultipleSourceVersionList combined = new MultipleSourceVersionList(new VersionList<?>[]{
                new StubVersionList(backendRefresh)
        });

        Task<?> wrapper = combined.refreshAsync("1.21");

        assertEquals(Set.of(TaskResource.Kind.ORCHESTRATION), wrapper.getResources().stream()
                .map(TaskResource::getKind)
                .collect(java.util.stream.Collectors.toUnmodifiableSet()));
        assertEquals(Set.of(TaskResource.conservative()), backendRefresh.getResources());
    }

    /// Defers catalog clearing and backend-task construction until the returned task actually starts.
    @Test
    public void multipleSourceRefreshDefersMutationUntilExecution() throws Exception {
        AtomicInteger refreshRequests = new AtomicInteger();
        StubVersionList backend = new StubVersionList(Task.runAsync(() -> {
        }), refreshRequests);
        MultipleSourceVersionList combined = new MultipleSourceVersionList(new VersionList<?>[]{backend});
        combined.versions.put(
                "1.21",
                new RemoteVersion("test", "1.21", "1.0", Instant.EPOCH, List.of()));

        Task<?> refreshTask = combined.refreshAsync("1.21");

        assertTrue(combined.isLoaded("1.21"));
        assertEquals(0, refreshRequests.get());

        assertTrue(refreshTask.executor().test());

        assertFalse(combined.isLoaded("1.21"));
        assertEquals(1, refreshRequests.get());
    }

    /// Verifies metadata continuations that only decode and update in-memory catalogs are non-owning roots.
    @Test
    public void auditedMetadataContinuationsUseOrchestrationResources() {
        MojangDownloadProvider provider = new MojangDownloadProvider();

        assertOrchestration(provider.getVersionListById("cleanroom").refreshAsync());
        assertOrchestration(provider.getVersionListById("forge").refreshAsync());
        assertOrchestration(provider.getVersionListById("game").refreshAsync());
        assertOrchestration(provider.getVersionListById("liteloader").refreshAsync("1.12.2"));
        assertOrchestration(provider.getVersionListById("neoforge").refreshAsync());
        assertOrchestration(provider.getVersionListById("optifine").refreshAsync());
        assertOrchestration(new ForgeBMCLVersionList("https://example.invalid").refreshAsync("1.20.1"));
        assertOrchestration(new NeoForgeBMCLVersionList("https://example.invalid").refreshAsync("1.21.1"));
        assertOrchestration(new LiteLoaderBMCLVersionList(
                new BMCLAPIDownloadProvider("https://example.invalid")).refreshAsync("1.12.2"));
        assertOrchestration(new DownloadProviderWrapper(provider)
                .getVersionListById("forge").refreshAsync("1.20.1"));
    }

    /// Verifies direct network refreshes use orchestration after their in-memory mutations are synchronized.
    @Test
    public void synchronizedDirectNetworkRefreshTasksUseOrchestration() {
        MojangDownloadProvider provider = new MojangDownloadProvider();

        assertOrchestration(provider.getVersionListById("fabric").refreshAsync());
        assertOrchestration(provider.getVersionListById("fabric-api").refreshAsync());
        assertOrchestration(provider.getVersionListById("legacyfabric").refreshAsync());
        assertOrchestration(provider.getVersionListById("legacyfabric-api").refreshAsync());
        assertOrchestration(provider.getVersionListById("quilt").refreshAsync());
        assertOrchestration(provider.getVersionListById("quilt-api").refreshAsync());
    }

    /// Verifies one task declares only the non-filesystem orchestration marker.
    ///
    /// @param task task to inspect
    private static void assertOrchestration(Task<?> task) {
        assertEquals(Set.of(TaskResource.Kind.ORCHESTRATION), task.getResources().stream()
                .map(TaskResource::getKind)
                .collect(java.util.stream.Collectors.toUnmodifiableSet()));
    }

    /// Version-list stub returning one stable refresh task without network access.
    @NotNullByDefault
    private static final class StubVersionList extends VersionList<RemoteVersion> {
        /// Stable refresh task returned by both refresh overloads.
        private final Task<?> refreshTask;

        /// Number of times a backend refresh task is requested.
        private final AtomicInteger refreshRequests;

        /// Creates a stub backed by one task.
        ///
        /// @param refreshTask stable task to return
        private StubVersionList(Task<?> refreshTask) {
            this(refreshTask, new AtomicInteger());
        }

        /// Creates a stub backed by one task and an invocation counter.
        ///
        /// @param refreshTask stable task to return
        /// @param refreshRequests counter incremented for each refresh request
        private StubVersionList(Task<?> refreshTask, AtomicInteger refreshRequests) {
            this.refreshTask = refreshTask;
            this.refreshRequests = refreshRequests;
        }

        /// Reports that this test list does not distinguish release types.
        ///
        /// @return false
        @Override
        public boolean hasType() {
            return false;
        }

        /// Returns the stable test refresh task.
        ///
        /// @return test refresh task
        @Override
        public Task<?> refreshAsync() {
            refreshRequests.incrementAndGet();
            return refreshTask;
        }
    }
}
