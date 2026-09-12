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
package space.minecraftstl.xyml.mcp;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import space.minecraftstl.xyml.task.Task;
import space.minecraftstl.xyml.task.TaskResource;

import javax.swing.SwingUtilities;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies delayed and fail-closed missing-dependency search task creation for MCP repair actions.
@NotNullByDefault
final class SwingMcpMissingDependencySearchTest {
    /// Resolves the runtime only during execution and avoids overwriting one shared page for multiple dependencies.
    @Test
    void resolvesAtExecutionAndOpensOnlyFirstDependencyWithoutSelector() throws Exception {
        AtomicInteger resolutions = new AtomicInteger();
        RecordingSearchRuntime runtime = new RecordingSearchRuntime(false);
        SwingMcpMissingDependencySearch search = SwingMcpMissingDependencySearch.forRuntimeResolver(
                () -> {
                    resolutions.incrementAndGet();
                    return runtime;
                },
                () -> true);

        Task<?> firstTask = search.createTask(List.of("fabric-api", "cloth-config"));
        Task<?> secondTask = search.createTask(List.of("fabric-api", "cloth-config"));

        assertEquals(0, resolutions.get());
        assertNotSame(firstTask, secondTask);
        assertEquals(Task.TaskState.READY, firstTask.getState());
        assertEquals(Task.TaskState.READY, secondTask.getState());
        assertEquals(
                List.of(TaskResource.Kind.ORCHESTRATION),
                firstTask.getResources().stream().map(TaskResource::getKind).toList());

        firstTask.execute();

        assertEquals(1, resolutions.get());
        assertEquals(List.of("fabric-api"), runtime.searches());
    }

    /// Fails the task explicitly when no Swing runtime exists at execution time.
    @Test
    void failsWhenRuntimeIsUnavailable() {
        AtomicInteger resolutions = new AtomicInteger();
        SwingMcpMissingDependencySearch search = new SwingMcpMissingDependencySearch(
                () -> {
                    resolutions.incrementAndGet();
                    return null;
                },
                () -> true);
        Task<?> task = search.createTask(List.of("fabric-api"));

        assertEquals(0, resolutions.get());
        IllegalStateException failure = assertThrows(IllegalStateException.class, task::execute);

        assertEquals("Launcher Swing application runtime is unavailable", failure.getMessage());
        assertEquals(1, resolutions.get());
    }

    /// Fails the task explicitly when the resolved Swing runtime has already closed.
    @Test
    void failsWhenRuntimeIsClosed() {
        RecordingSearchRuntime runtime = new RecordingSearchRuntime(true);
        SwingMcpMissingDependencySearch search =
                SwingMcpMissingDependencySearch.forRuntimeResolver(() -> runtime, () -> true);
        Task<?> task = search.createTask(List.of("fabric-api"));

        IllegalStateException failure = assertThrows(IllegalStateException.class, task::execute);

        assertEquals("Launcher Swing application runtime is closed", failure.getMessage());
        assertEquals(List.of(), runtime.searches());
    }

    /// Allows a read-only search before repair-write consent is granted.
    @Test
    void allowsReadOnlySearchBeforeRepairAgreement() throws Exception {
        AtomicBoolean executionAllowed = new AtomicBoolean();
        AtomicInteger resolutions = new AtomicInteger();
        RecordingSearchRuntime runtime = new RecordingSearchRuntime(false);
        SwingMcpMissingDependencySearch search = SwingMcpMissingDependencySearch.forRuntimeResolver(
                () -> {
                    resolutions.incrementAndGet();
                    return runtime;
                },
                executionAllowed::get);

        executionAllowed.set(false);
        search.createTask(List.of("fabric-api")).execute();

        assertEquals(1, resolutions.get());
        assertEquals(List.of("fabric-api"), runtime.searches());
    }

    /// Carries the analyzed game version through the execution-time runtime boundary.
    @Test
    void opensSearchWithAnalyzedGameVersion() throws Exception {
        RecordingSearchRuntime runtime = new RecordingSearchRuntime(false);
        SwingMcpMissingDependencySearch search =
                SwingMcpMissingDependencySearch.forRuntimeResolver(() -> runtime, () -> true);

        search.createTask(List.of("fabric-api"), "1.20.1").execute();

        assertEquals(List.of("fabric-api"), runtime.searches());
        assertEquals(List.of("1.20.1"), runtime.gameVersions());
    }

    /// Rejects a task retained past the owning crash-window or MCP-service lifetime.
    @Test
    void closedAdapterRejectsRetainedSearchTask() {
        List<String> searches = new ArrayList<>();
        SwingMcpMissingDependencySearch search = new SwingMcpMissingDependencySearch(searches::add);
        Task<?> task = search.createTask(List.of("fabric-api"));

        search.close();
        IllegalStateException failure = assertThrows(IllegalStateException.class, task::execute);

        assertEquals("Missing-dependency search is closed", failure.getMessage());
        assertTrue(searches.isEmpty());
    }

    /// Rejects absent and blank dependency identifiers before returning a task.
    @Test
    void rejectsInvalidDependencyIdentifiers() {
        SwingMcpMissingDependencySearch search =
                SwingMcpMissingDependencySearch.forRuntimeResolver(() -> null, () -> true);

        IllegalArgumentException empty = assertThrows(
                IllegalArgumentException.class,
                () -> search.createTask(List.of()));
        IllegalArgumentException blank = assertThrows(
                IllegalArgumentException.class,
                () -> search.createTask(List.of("   ")));

        assertEquals("dependencyIds must not be empty", empty.getMessage());
        assertEquals("dependencyIds must not contain blank identifiers", blank.getMessage());
    }

    /// Records searches while exposing an explicit closed state.
    @NotNullByDefault
    private static final class RecordingSearchRuntime implements SwingMcpMissingDependencySearch.SearchRuntime {
        /// Whether this runtime must reject new searches.
        private final boolean closed;

        /// Dependency identifiers passed to the search boundary.
        private final List<String> searches = new ArrayList<>();

        /// Analyzed versions passed beside each version-aware search, using an empty string for unknown.
        private final List<String> gameVersions = new ArrayList<>();

        /// Creates a recording runtime with the selected lifecycle state.
        ///
        /// @param closed whether this runtime is already closed
        private RecordingSearchRuntime(boolean closed) {
            this.closed = closed;
        }

        /// Returns the configured lifecycle state.
        ///
        /// @return true when this runtime is closed
        @Override
        public boolean isClosed() {
            return closed;
        }

        /// Records one requested dependency search.
        ///
        /// @param dependencyId dependency identifier used as the search query
        @Override
        public void openModSearch(String dependencyId) {
            assertTrue(SwingUtilities.isEventDispatchThread());
            searches.add(dependencyId);
        }

        /// Records one version-aware dependency search.
        ///
        /// @param dependencyId dependency identifier used as the search query
        /// @param gameVersion analyzed Minecraft version, or null when unavailable
        @Override
        public void openMissingDependencySearch(String dependencyId, @Nullable String gameVersion) {
            openModSearch(dependencyId);
            gameVersions.add(Objects.requireNonNullElse(gameVersion, ""));
        }

        /// Returns an immutable search snapshot.
        ///
        /// @return requested dependency identifiers
        private @Unmodifiable List<String> searches() {
            return List.copyOf(searches);
        }

        /// Returns the immutable analyzed-version snapshot.
        ///
        /// @return requested analyzed versions in search order
        private @Unmodifiable List<String> gameVersions() {
            return List.copyOf(gameVersions);
        }
    }
}
