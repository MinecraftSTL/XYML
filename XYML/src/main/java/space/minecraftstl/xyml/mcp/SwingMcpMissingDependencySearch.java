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
import space.minecraftstl.xyml.addon.RemoteAddonRepository;
import space.minecraftstl.xyml.game.analyzer.LogAnalyzable;
import space.minecraftstl.xyml.task.Task;
import space.minecraftstl.xyml.task.Schedulers;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;
import space.minecraftstl.xyml.ui.swing.page.downloads.CoreRemoteAddonCatalogBackend;
import space.minecraftstl.xyml.ui.swing.page.downloads.RemoteAddonCatalogBackend;
import space.minecraftstl.xyml.ui.swing.page.downloads.RemoteAddonCatalogItem;
import space.minecraftstl.xyml.ui.swing.page.downloads.RemoteAddonCatalogKind;
import space.minecraftstl.xyml.ui.swing.page.downloads.RemoteAddonCatalogPage;
import space.minecraftstl.xyml.ui.swing.page.downloads.RemoteAddonCatalogQuery;
import space.minecraftstl.xyml.ui.swing.page.downloads.RemoteAddonCatalogSource;
import space.minecraftstl.xyml.ui.swing.runtime.SwingApplicationRuntime;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import static space.minecraftstl.xyml.util.logging.Logger.LOG;

/// Opens the launcher-owned Mods search page for an XYAT missing-dependency repair action.
///
/// Runtime resolution is deliberately deferred until the returned task executes because the MCP server starts before
/// the Swing runtime exists and may outlive one runtime generation. Every execution therefore observes the current
/// runtime instead of retaining a stale application window.
@NotNullByDefault
public final class SwingMcpMissingDependencySearch implements LogAnalyzable.MissingDependencySearch {
    /// Resolves the current search target when a repair task begins execution.
    private final RuntimeResolver runtimeResolver;

    /// Lazily created read-only provider-page cache. It is not created until an analyzer actually finds a dependency.
    private final Supplier<SwingMcpMissingDependencyCatalogCache> catalogCacheFactory;

    /// Serializes lazy cache creation across captured/latest-log analysis workers.
    private final Object catalogCacheLock = new Object();

    /// Shared cache instance, or null before the first prefetch and after explicit closure.
    private volatile @Nullable SwingMcpMissingDependencyCatalogCache catalogCache;

    /// Whether this adapter has released its read-only cache.
    private volatile boolean closed;

    /// Creates an adapter backed by the launcher's current Swing runtime supplier.
    ///
    /// @param runtimeSupplier supplies the current runtime, or null before startup and after shutdown
    /// @param executionAllowed retained for source compatibility; read-only search is allowed before repair consent
    public SwingMcpMissingDependencySearch(
            Supplier<@Nullable SwingApplicationRuntime> runtimeSupplier,
            BooleanSupplier executionAllowed) {
        this(
                createRuntimeResolver(runtimeSupplier),
                executionAllowed,
                () -> new SwingMcpMissingDependencyCatalogCache(
                        new CoreRemoteAddonCatalogBackend(),
                        Schedulers.io()));
    }

    /// Creates an adapter with an explicit runtime boundary for deterministic tests.
    ///
    /// @param runtimeResolver resolves the current search target at task execution time
    /// @param executionAllowed retained for source compatibility; read-only search is always permitted
    private SwingMcpMissingDependencySearch(RuntimeResolver runtimeResolver, BooleanSupplier executionAllowed) {
        this(
                runtimeResolver,
                executionAllowed,
                () -> new SwingMcpMissingDependencyCatalogCache(
                        new CoreRemoteAddonCatalogBackend(),
                        Schedulers.io()));
    }

    /// Creates an adapter with explicit runtime, provider, and worker boundaries for deterministic tests.
    ///
    /// The provider is used only for read-only catalog searches. No installation or replacement collaborator is
    /// retained by this adapter.
    ///
    /// @param runtimeResolver resolves the current search target at task execution time
    /// @param executionAllowed retained for source compatibility; read-only search is always permitted
    /// @param backend existing provider catalog backend
    /// @param executor worker used for blocking provider calls
    SwingMcpMissingDependencySearch(
            RuntimeResolver runtimeResolver,
            BooleanSupplier executionAllowed,
            RemoteAddonCatalogBackend backend,
            Executor executor) {
        this(
                runtimeResolver,
                executionAllowed,
                () -> new SwingMcpMissingDependencyCatalogCache(backend, executor));
    }

    /// Creates one adapter from a runtime boundary and lazily-created catalog cache.
    ///
    /// @param runtimeResolver runtime resolver
    /// @param executionAllowed retained compatibility gate
    /// @param catalogCacheFactory cache factory invoked at first prefetch
    private SwingMcpMissingDependencySearch(
            RuntimeResolver runtimeResolver,
            BooleanSupplier executionAllowed,
            Supplier<SwingMcpMissingDependencyCatalogCache> catalogCacheFactory) {
        this.runtimeResolver = Objects.requireNonNull(runtimeResolver, "runtimeResolver");
        Objects.requireNonNull(executionAllowed, "executionAllowed");
        this.catalogCacheFactory = Objects.requireNonNull(catalogCacheFactory, "catalogCacheFactory");
    }

    /// Creates an adapter with a package-owned runtime boundary for deterministic tests.
    ///
    /// @param runtimeResolver resolves the current search target at task execution time
    /// @param executionAllowed retained for source compatibility; read-only search is always permitted
    /// @return missing-dependency search adapter
    static SwingMcpMissingDependencySearch forRuntimeResolver(
            RuntimeResolver runtimeResolver,
            BooleanSupplier executionAllowed) {
        return new SwingMcpMissingDependencySearch(runtimeResolver, executionAllowed);
    }

    /// Creates an adapter with explicit provider and worker boundaries for cache tests.
    ///
    /// @param runtimeResolver runtime resolver
    /// @param executionAllowed retained compatibility gate
    /// @param backend read-only provider backend
    /// @param executor worker executor
    /// @return configured adapter
    static SwingMcpMissingDependencySearch forRuntimeResolver(
            RuntimeResolver runtimeResolver,
            BooleanSupplier executionAllowed,
            RemoteAddonCatalogBackend backend,
            Executor executor) {
        return new SwingMcpMissingDependencySearch(runtimeResolver, executionAllowed, backend, executor);
    }

    /// Schedules bounded, read-only provider searches before a repair button is pressed.
    ///
    /// Provider failures are logged and removed from the cache; they do not invalidate the diagnosis or prevent the
    /// explicit search task from being retried later.
    ///
    /// @param dependencyIds validated dependency identifiers in diagnosis order
    /// @param gameVersion detected Minecraft version, or null when unavailable
    @Override
    public void prefetch(
            @Unmodifiable List<String> dependencyIds,
            @Nullable String gameVersion) {
        @Unmodifiable List<String> ids = normalizeDependencyIds(dependencyIds);
        if (closed) {
            return;
        }
        @Nullable SwingMcpMissingDependencyCatalogCache cache = catalogCacheIfOpen();
        if (cache == null) {
            return;
        }
        String version = gameVersion == null ? "" : gameVersion.trim();
        for (String dependencyId : ids) {
            RemoteAddonCatalogQuery query = new RemoteAddonCatalogQuery(
                    RemoteAddonCatalogKind.MOD,
                    RemoteAddonCatalogSource.MODRINTH,
                    dependencyId,
                    version,
                    null,
                    RemoteAddonRepository.SortType.POPULARITY,
                    0,
                    SwingMcpMissingDependencyCatalogCache.DEFAULT_PAGE_SIZE);
            cache.prefetch(query).whenComplete((
                    @Nullable RemoteAddonCatalogPage ignored,
                    @Nullable Throwable failure) -> {
                if (failure != null) {
                    LOG.warning("Unable to prefetch missing-dependency catalog entry " + dependencyId, failure);
                }
            });
        }
    }

    /// Returns candidates already fetched for one dependency without issuing another provider request.
    ///
    /// @param dependencyId dependency identifier
    /// @param gameVersion game-version filter, or null when unavailable
    /// @return immutable candidate snapshot, or empty while no successful page is cached
    public @Unmodifiable List<RemoteAddonCatalogItem> cachedCandidates(
            String dependencyId,
            @Nullable String gameVersion) {
        String id = normalizeDependencyId(dependencyId);
        if (closed) {
            return List.of();
        }
        SwingMcpMissingDependencyCatalogCache cache = catalogCache;
        if (cache == null) {
            return List.of();
        }
        String version = gameVersion == null ? "" : gameVersion.trim();
        RemoteAddonCatalogQuery query = new RemoteAddonCatalogQuery(
                RemoteAddonCatalogKind.MOD,
                RemoteAddonCatalogSource.MODRINTH,
                id,
                version,
                null,
                RemoteAddonRepository.SortType.POPULARITY,
                0,
                SwingMcpMissingDependencyCatalogCache.DEFAULT_PAGE_SIZE);
        Optional<RemoteAddonCatalogPage> page = cache.get(query);
        return page.map(RemoteAddonCatalogPage::items).orElseGet(List::of);
    }

    /// Closes the cache and drops retained provider metadata.
    public void close() {
        @Nullable SwingMcpMissingDependencyCatalogCache cache;
        synchronized (catalogCacheLock) {
            if (closed) {
                return;
            }
            closed = true;
            cache = catalogCache;
            catalogCache = null;
        }
        if (cache != null) {
            cache.close();
        }
    }

    /// Returns the lazily-created cache used by this adapter while the adapter remains open.
    private @Nullable SwingMcpMissingDependencyCatalogCache catalogCacheIfOpen() {
        synchronized (catalogCacheLock) {
            if (closed) {
                return null;
            }
            @Nullable SwingMcpMissingDependencyCatalogCache current = catalogCache;
            if (current == null) {
                current = Objects.requireNonNull(catalogCacheFactory.get(), "catalogCacheFactory returned null");
                catalogCache = current;
            }
            return current;
        }
    }

    /// Validates and snapshots a complete dependency list for a prefetch request.
    private static @Unmodifiable List<String> normalizeDependencyIds(List<String> dependencyIds) {
        @Unmodifiable List<String> checkedIds = List.copyOf(
                Objects.requireNonNull(dependencyIds, "dependencyIds"));
        if (checkedIds.isEmpty()) {
            throw new IllegalArgumentException("dependencyIds must not be empty");
        }
        List<String> normalized = new ArrayList<>(checkedIds.size());
        for (String dependencyId : checkedIds) {
            normalized.add(normalizeDependencyId(dependencyId));
        }
        return List.copyOf(normalized);
    }

    /// Validates one dependency identifier before constructing a provider query.
    private static String normalizeDependencyId(String dependencyId) {
        String normalized = Objects.requireNonNull(dependencyId, "dependencyId").trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("dependencyIds must not contain blank identifiers");
        }
        return normalized;
    }

    /// Creates a fresh stopped task that opens a search for every diagnosed dependency identifier.
    ///
    /// The task completes after the Swing runtime accepts the navigation command. Any subsequent catalog network query
    /// belongs to the UI runtime and does not write a launcher filesystem resource.
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
        List<String> normalizedIds = new ArrayList<>(checkedIds.size());
        for (String checkedId : checkedIds) {
            String dependencyId = Objects.requireNonNull(checkedId, "dependencyIds entry").trim();
            if (dependencyId.isEmpty()) {
                throw new IllegalArgumentException("dependencyIds must not contain blank identifiers");
            }
            normalizedIds.add(dependencyId);
        }
        @Unmodifiable List<String> taskIds = List.copyOf(normalizedIds);

        return Task.runAsync(() -> {
            @Nullable SearchRuntime runtime = runtimeResolver.resolve();
            if (runtime == null) {
                throw new IllegalStateException("Launcher Swing application runtime is unavailable");
            }
            if (runtime.isClosed()) {
                throw new IllegalStateException("Launcher Swing application runtime is closed");
            }
            for (String dependencyId : taskIds) {
                EdtDispatcher.executeAndWait(() -> runtime.openModSearch(dependencyId));
            }
        }).asOrchestration();
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
