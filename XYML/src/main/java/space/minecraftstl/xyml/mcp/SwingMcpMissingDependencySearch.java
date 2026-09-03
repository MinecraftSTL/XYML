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
import space.minecraftstl.xyml.game.analyzer.LogAnalyzable;
import space.minecraftstl.xyml.task.Task;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;
import space.minecraftstl.xyml.ui.swing.runtime.SwingApplicationRuntime;

import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/// Opens the launcher-owned Mods search page for an XYAT missing-dependency repair action.
///
/// Runtime resolution is deliberately deferred until the returned task executes because the MCP server starts before
/// the Swing runtime exists and may outlive one runtime generation. Every execution therefore observes the current
/// runtime instead of retaining a stale application window.
@NotNullByDefault
public final class SwingMcpMissingDependencySearch implements LogAnalyzable.MissingDependencySearch {
    /// Resolves the current search target when a repair task begins execution.
    private final RuntimeResolver runtimeResolver;

    /// Reports whether mandatory startup policy permits launcher-owned MCP repair side effects.
    private final BooleanSupplier executionAllowed;

    /// Creates an adapter backed by the launcher's current Swing runtime supplier.
    ///
    /// @param runtimeSupplier supplies the current runtime, or null before startup and after shutdown
    /// @param executionAllowed reports whether the mandatory startup agreement has been accepted
    public SwingMcpMissingDependencySearch(
            Supplier<@Nullable SwingApplicationRuntime> runtimeSupplier,
            BooleanSupplier executionAllowed) {
        this(createRuntimeResolver(runtimeSupplier), executionAllowed);
    }

    /// Creates an adapter with an explicit runtime boundary for deterministic tests.
    ///
    /// @param runtimeResolver resolves the current search target at task execution time
    /// @param executionAllowed reports whether startup policy permits repair side effects
    private SwingMcpMissingDependencySearch(RuntimeResolver runtimeResolver, BooleanSupplier executionAllowed) {
        this.runtimeResolver = Objects.requireNonNull(runtimeResolver, "runtimeResolver");
        this.executionAllowed = Objects.requireNonNull(executionAllowed, "executionAllowed");
    }

    /// Creates an adapter with a package-owned runtime boundary for deterministic tests.
    ///
    /// @param runtimeResolver resolves the current search target at task execution time
    /// @param executionAllowed reports whether startup policy permits repair side effects
    /// @return missing-dependency search adapter
    static SwingMcpMissingDependencySearch forRuntimeResolver(
            RuntimeResolver runtimeResolver,
            BooleanSupplier executionAllowed) {
        return new SwingMcpMissingDependencySearch(runtimeResolver, executionAllowed);
    }

    /// Creates a fresh stopped task that opens a search for the first diagnosed dependency identifier.
    ///
    /// @param dependencyIds immutable ordered missing-dependency identifiers
    /// @return fresh stopped search task
    /// @throws IllegalArgumentException if no non-blank dependency identifier is supplied
    @Override
    public Task<?> createTask(@Unmodifiable List<String> dependencyIds) {
        @Unmodifiable List<String> checkedIds = List.copyOf(
                Objects.requireNonNull(dependencyIds, "dependencyIds"));
        if (checkedIds.isEmpty()) {
            throw new IllegalArgumentException("dependencyIds must not be empty");
        }
        String dependencyId = checkedIds.get(0).trim();
        if (dependencyId.isEmpty()) {
            throw new IllegalArgumentException("dependencyIds must start with a non-blank identifier");
        }

        return Task.runAsync(() -> {
            if (!executionAllowed.getAsBoolean()) {
                throw new IllegalStateException(
                        "Crash repair actions are unavailable before startup agreements are accepted");
            }
            @Nullable SearchRuntime runtime = runtimeResolver.resolve();
            if (runtime == null) {
                throw new IllegalStateException("Launcher Swing application runtime is unavailable");
            }
            if (runtime.isClosed()) {
                throw new IllegalStateException("Launcher Swing application runtime is closed");
            }
            EdtDispatcher.executeAndWait(() -> runtime.openModSearch(dependencyId));
        });
    }

    /// Adapts the production Swing runtime supplier without resolving it eagerly.
    ///
    /// @param runtimeSupplier supplies the current production runtime, or null
    /// @return delayed runtime resolver
    private static RuntimeResolver createRuntimeResolver(
            Supplier<@Nullable SwingApplicationRuntime> runtimeSupplier) {
        Supplier<@Nullable SwingApplicationRuntime> checkedSupplier =
                Objects.requireNonNull(runtimeSupplier, "runtimeSupplier");
        return () -> {
            @Nullable SwingApplicationRuntime runtime = checkedSupplier.get();
            return runtime == null ? null : new SwingSearchRuntime(runtime);
        };
    }

    /// Resolves a search-capable runtime at the exact moment a task executes.
    @NotNullByDefault
    @FunctionalInterface
    interface RuntimeResolver {
        /// Returns the current runtime search boundary, or null when the launcher UI is unavailable.
        ///
        /// @return current search boundary, or null
        @Nullable SearchRuntime resolve();
    }

    /// Minimal search-capable runtime surface used to isolate tests from native Swing construction.
    @NotNullByDefault
    interface SearchRuntime {
        /// Returns whether the represented runtime has started closing.
        ///
        /// @return true when no new search may be opened
        boolean isClosed();

        /// Opens the Mods search page for one validated dependency identifier.
        ///
        /// @param dependencyId dependency identifier used as the search query
        void openModSearch(String dependencyId);
    }

    /// Delegates the minimal search surface to one resolved production Swing runtime.
    @NotNullByDefault
    private static final class SwingSearchRuntime implements SearchRuntime {
        /// Production runtime resolved for this exact task execution.
        private final SwingApplicationRuntime delegate;

        /// Creates a search boundary around one production runtime generation.
        ///
        /// @param delegate resolved production runtime
        private SwingSearchRuntime(SwingApplicationRuntime delegate) {
            this.delegate = Objects.requireNonNull(delegate, "delegate");
        }

        /// Returns whether the delegated runtime has started closing.
        ///
        /// @return true when the runtime is closed
        @Override
        public boolean isClosed() {
            return delegate.isClosed();
        }

        /// Opens the delegated runtime's Mods search page.
        ///
        /// @param dependencyId dependency identifier used as the search query
        @Override
        public void openModSearch(String dependencyId) {
            delegate.openModSearch(dependencyId);
        }
    }
}
