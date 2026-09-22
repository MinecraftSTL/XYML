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
import space.minecraftstl.xyml.game.analyzer.RepairTaskPhase;
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
import space.minecraftstl.xyml.ui.swing.runtime.MissingDependencySearchAction;
import space.minecraftstl.xyml.ui.swing.runtime.SwingApplicationRuntime;

import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.GraphicsEnvironment;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static space.minecraftstl.xyml.util.i18n.I18n.i18n;
import static space.minecraftstl.xyml.util.logging.Logger.LOG;

/// Opens the launcher-owned Mods search page for an XYAT missing-dependency repair action.
///
/// Runtime resolution is deliberately deferred until the returned task executes because the MCP server starts before
/// the Swing runtime exists and may outlive one runtime generation. Every execution therefore observes the current
/// runtime instead of retaining a stale application window.
@NotNullByDefault
public final class SwingMcpMissingDependencySearch implements LogAnalyzable.MissingDependencySearch, AutoCloseable {
    /// Maximum interval between cancellation checks while a provider request remains in flight.
    private static final long CATALOG_WAIT_MILLIS = 100L;

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

    /// Whether the first close caller has finished closing the detached catalog cache.
    private boolean closeFinished;

    /// Number of selectors that won the close race and may finish their already-claimed modal interaction.
    private int activeSelectionClaims;

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

    /// Creates an adapter for a crash window's existing Mods-page navigation action.
    ///
    /// Candidate discovery still uses the launcher's read-only catalog backend. The supplied action is invoked only
    /// by an explicitly executed search task and never by background prefetch.
    ///
    /// @param openModSearch action opening the Mods search page for one dependency identifier
    public SwingMcpMissingDependencySearch(Consumer<String> openModSearch) {
        this(
                (dependencyId, ignoredGameVersion) -> openModSearch.accept(dependencyId),
                SwingMcpMissingDependencySearch::selectDependencyWithDialog);
    }

    /// Creates a read-only adapter around a version-aware crash-window navigation action.
    ///
    /// @param openMissingDependencySearch action opening one dependency query with its analyzed version
    /// @return version-aware read-only search adapter
    public static SwingMcpMissingDependencySearch forMissingDependencySearchAction(
            MissingDependencySearchAction openMissingDependencySearch) {
        return new SwingMcpMissingDependencySearch(
                openMissingDependencySearch,
                SwingMcpMissingDependencySearch::selectDependencyWithDialog);
    }

    /// Creates a version-aware callback adapter with one explicit dependency chooser.
    ///
    /// @param openMissingDependencySearch action opening one dependency query with its analyzed version
    /// @param selector dependency chooser
    private SwingMcpMissingDependencySearch(
            MissingDependencySearchAction openMissingDependencySearch,
            DependencySelector selector) {
        this(createRuntimeResolver(openMissingDependencySearch, selector),
                () -> true);
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

    /// Creates a callback-backed adapter with deterministic selection and provider boundaries for tests.
    ///
    /// @param openModSearch action opening the Mods search page
    /// @param selector dependency chooser
    /// @param backend read-only provider backend
    /// @param executor worker executor
    /// @return configured adapter
    static SwingMcpMissingDependencySearch forSearchAction(
            Consumer<String> openModSearch,
            DependencySelector selector,
            RemoteAddonCatalogBackend backend,
            Executor executor) {
        return new SwingMcpMissingDependencySearch(
                createRuntimeResolver(
                        (dependencyId, ignoredGameVersion) -> openModSearch.accept(dependencyId),
                        selector),
                () -> true,
                backend,
                executor);
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
        prefetchSequentially(cache, ids, version, 0);
    }

    /// Prefetches every dependency without allowing a long diagnosis to create an unbounded provider queue.
    ///
    /// The next exact query is submitted only after the prior query reaches a terminal state. Failed provider calls
    /// remain retryable and do not stop later dependency IDs from being discovered.
    ///
    /// @param cache open read-only catalog cache
    /// @param dependencyIds immutable normalized identifiers
    /// @param gameVersion normalized game-version filter
    /// @param index next identifier index
    private void prefetchSequentially(
            SwingMcpMissingDependencyCatalogCache cache,
            @Unmodifiable List<String> dependencyIds,
            String gameVersion,
            int index) {
        if (closed || index >= dependencyIds.size()) {
            return;
        }
        String dependencyId = dependencyIds.get(index);
        cache.prefetch(query(dependencyId, gameVersion)).whenCompleteAsync((
                @Nullable RemoteAddonCatalogPage ignored,
                @Nullable Throwable failure) -> {
            if (failure != null && !closed) {
                LOG.warning("Unable to prefetch missing-dependency catalog entry " + dependencyId, failure);
            }
            prefetchSequentially(cache, dependencyIds, gameVersion, index + 1);
        }, Schedulers.io());
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
        RemoteAddonCatalogQuery query = query(id, version);
        Optional<RemoteAddonCatalogPage> page = cache.get(query);
        return page.map(RemoteAddonCatalogPage::items).orElseGet(List::of);
    }

    /// Creates one exact bounded provider query shared by prefetch and explicit search consumption.
    ///
    /// @param dependencyId normalized dependency identifier
    /// @param gameVersion normalized game-version filter
    /// @return immutable catalog query
    private static RemoteAddonCatalogQuery query(String dependencyId, String gameVersion) {
        return new RemoteAddonCatalogQuery(
                RemoteAddonCatalogKind.MOD,
                RemoteAddonCatalogSource.MODRINTH,
                dependencyId,
                gameVersion,
                null,
                RemoteAddonRepository.SortType.POPULARITY,
                0,
                SwingMcpMissingDependencyCatalogCache.DEFAULT_PAGE_SIZE);
    }

    /// Closes the cache and drops retained provider metadata after any already-entered selector returns.
    ///
    /// A non-EDT caller waits uninterruptibly for an active selector so no new modal callback can begin after this
    /// method returns. An EDT caller cannot race the straight-line claim-to-callback path; a reentrant close from an
    /// already-visible modal interaction marks the adapter closed without deadlocking that same event thread.
    @Override
    public void close() {
        @Nullable SwingMcpMissingDependencyCatalogCache cache;
        boolean ownsClose;
        synchronized (catalogCacheLock) {
            ownsClose = !closed;
            if (ownsClose) {
                closed = true;
                cache = catalogCache;
                catalogCache = null;
            } else {
                cache = null;
            }
        }
        if (ownsClose) {
            try {
                if (cache != null) {
                    cache.close();
                }
            } finally {
                synchronized (catalogCacheLock) {
                    closeFinished = true;
                    catalogCacheLock.notifyAll();
                }
            }
        }
        if (!SwingUtilities.isEventDispatchThread()) {
            awaitCloseBarrier();
        }
    }

    /// Waits for the primary close and selectors which atomically claimed the adapter before closure.
    private void awaitCloseBarrier() {
        boolean interrupted = false;
        synchronized (catalogCacheLock) {
            while (!closeFinished || activeSelectionClaims > 0) {
                try {
                    catalogCacheLock.wait();
                } catch (InterruptedException exception) {
                    interrupted = true;
                }
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
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

    /// Creates a fresh stopped task using an unspecified game-version filter.
    ///
    /// @param dependencyIds immutable ordered missing-dependency identifiers
    /// @return fresh stopped search task
    /// @throws IllegalArgumentException if no non-blank dependency identifier is supplied
    @Override
    public Task<?> createTask(@Unmodifiable List<String> dependencyIds) {
        return createTask(dependencyIds, null);
    }

    /// Creates a fresh stopped task that consumes cached candidates before opening one selected dependency search.
    ///
    /// Callback-backed crash-window searches show every dependency ID with a compact cached-candidate summary. The
    /// user selects one result page to open, so multiple IDs cannot overwrite each other. Runtime-backed MCP actions
    /// open the first diagnosis-order ID deterministically rather than issuing several conflicting navigations.
    /// Provider lookup and page navigation are read-only and never install or replace game files.
    ///
    /// @param dependencyIds immutable ordered missing-dependency identifiers
    /// @param gameVersion analyzed Minecraft version, or null when unavailable
    /// @return fresh stopped search task
    /// @throws IllegalArgumentException if no non-blank dependency identifier is supplied
    @Override
    public Task<?> createTask(
            @Unmodifiable List<String> dependencyIds,
            @Nullable String gameVersion) {
        @Unmodifiable List<String> taskIds = normalizeDependencyIds(dependencyIds);
        String version = gameVersion == null ? "" : gameVersion.trim();

        return new MissingDependencySearchTask(this, taskIds, version).asOrchestration();
    }

    /// Executes one prepared search while publishing the actual selection and navigation phases.
    ///
    /// @param task phase-aware task performing this execution
    /// @param dependencyIds normalized dependency identifiers
    /// @param gameVersion normalized game-version filter
    private void executeSearch(
            MissingDependencySearchTask task,
            @Unmodifiable List<String> dependencyIds,
            String gameVersion) {
        task.throwIfCancelled();
        if (closed) {
            throw new IllegalStateException("Missing-dependency search is closed");
        }
        @Nullable SearchRuntime runtime = runtimeResolver.resolve();
        if (runtime == null) {
            throw new IllegalStateException("Launcher Swing application runtime is unavailable");
        }
        if (runtime instanceof DependencySelectingRuntime selectingRuntime) {
            @Unmodifiable List<DependencyChoice> choices = loadDependencyChoices(task, dependencyIds, gameVersion);
            task.transitionTo(RepairTaskPhase.AWAITING_SELECTION);
            AtomicReference<@Nullable DependencyChoice> selected = new AtomicReference<>();
            EdtDispatcher.executeAndWait(() -> selected.set(selectDependencyWhileActive(
                    task,
                    selectingRuntime,
                    choices)));
            task.throwIfCancelled();
            @Nullable DependencyChoice selection = selected.get();
            if (selection == null) {
                throw new CancellationException("Missing-dependency selection was cancelled");
            }
            task.transitionTo(RepairTaskPhase.RUNNING);
            EdtDispatcher.executeAndWait(() -> openMissingDependencySearchWhileActive(
                    runtime,
                    selection.dependencyId(),
                    gameVersion));
            return;
        }
        task.transitionTo(RepairTaskPhase.RUNNING);
        String dependencyId = dependencyIds.get(0);
        EdtDispatcher.executeAndWait(() -> openMissingDependencySearchWhileActive(
                runtime,
                dependencyId,
                gameVersion));
    }

    /// Resolves every dependency's exact cached query, joining in-flight read-only prefetches when necessary.
    ///
    /// Provider failures retain the dependency ID with no candidate summary so navigation remains available.
    ///
    /// @param dependencyIds normalized dependency identifiers
    /// @param gameVersion normalized game-version filter
    /// @return immutable dependency choices in diagnosis order
    private @Unmodifiable List<DependencyChoice> loadDependencyChoices(
            MissingDependencySearchTask task,
            @Unmodifiable List<String> dependencyIds,
            String gameVersion) {
        @Nullable SwingMcpMissingDependencyCatalogCache cache = catalogCacheIfOpen();
        List<DependencyChoice> choices = new ArrayList<>(dependencyIds.size());
        for (String dependencyId : dependencyIds) {
            List<String> candidateLabels = new ArrayList<>();
            if (cache != null) {
                try {
                    RemoteAddonCatalogPage page = awaitCatalogPage(
                            task,
                            cache.prefetch(query(dependencyId, gameVersion)));
                    for (RemoteAddonCatalogItem item : page.items()) {
                        candidateLabels.add(boundedCandidateLabel(item.displayText()));
                    }
                } catch (RuntimeException lookupFailure) {
                    if (closed || cancellationCause(lookupFailure) != null) {
                        @Nullable CancellationException cancellation = cancellationCause(lookupFailure);
                        if (cancellation != null) {
                            throw cancellation;
                        }
                        throw new CancellationException("Missing-dependency search was closed");
                    }
                    LOG.warning("Unable to consume missing-dependency catalog entry " + dependencyId, lookupFailure);
                }
            }
            choices.add(new DependencyChoice(dependencyId, candidateLabels));
        }
        return List.copyOf(choices);
    }

    /// Waits for one shared provider stage while retaining bounded cancellation responsiveness.
    ///
    /// The shared cache future is deliberately not cancelled when this task stops. Another retry may be joining the
    /// same provider request, and the cache can safely retain a successful read-only page for that later attempt.
    ///
    /// @param task current cancellable search task
    /// @param stage shared provider completion stage
    /// @return completed provider page
    private static RemoteAddonCatalogPage awaitCatalogPage(
            MissingDependencySearchTask task,
            CompletionStage<RemoteAddonCatalogPage> stage) {
        CompletableFuture<RemoteAddonCatalogPage> future = stage.toCompletableFuture();
        while (true) {
            task.throwIfCancelled();
            try {
                RemoteAddonCatalogPage page = future.get(CATALOG_WAIT_MILLIS, TimeUnit.MILLISECONDS);
                task.throwIfCancelled();
                return page;
            } catch (TimeoutException ignored) {
                // Recheck task cancellation on the next bounded interval.
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new CancellationException("Missing-dependency search task was interrupted");
            } catch (ExecutionException exception) {
                Throwable cause = Objects.requireNonNull(exception.getCause(), "provider failure cause");
                if (cause instanceof Error error) {
                    throw error;
                }
                if (cause instanceof RuntimeException runtime) {
                    throw runtime;
                }
                throw new CompletionException(cause);
            }
        }
    }

    /// Bounds one provider-owned display value before it enters a native selection dialog.
    ///
    /// @param value provider candidate display text
    /// @return compact display text
    private static String boundedCandidateLabel(String value) {
        String checkedValue = Objects.requireNonNull(value, "value");
        return checkedValue.length() <= 96 ? checkedValue : checkedValue.substring(0, 96) + "...";
    }

    /// Resolves cancellation through the one completion wrapper produced by `CompletableFuture.join()`.
    ///
    /// @param failure provider lookup failure
    /// @return cancellation cause, or null for an ordinary lookup failure
    private static @Nullable CancellationException cancellationCause(RuntimeException failure) {
        if (failure instanceof CancellationException cancellation) {
            return cancellation;
        }
        if (failure instanceof CompletionException completion
                && completion.getCause() instanceof CancellationException cancellation) {
            return cancellation;
        }
        return null;
    }

    /// Selects one dependency after atomically claiming the interaction against adapter closure.
    ///
    /// @param runtime callback-backed search runtime
    /// @param choices immutable dependency and candidate snapshots
    /// @return selected dependency, or null when cancelled
    private @Nullable DependencyChoice selectDependencyWhileActive(
            MissingDependencySearchTask task,
            DependencySelectingRuntime runtime,
            @Unmodifiable List<DependencyChoice> choices) {
        boolean runtimeClosed = runtime.isClosed();
        synchronized (catalogCacheLock) {
            if (closed) {
                throw new CancellationException("Missing-dependency search was closed");
            }
            if (runtimeClosed) {
                throw new IllegalStateException("Launcher Swing application runtime is closed");
            }
            activeSelectionClaims++;
        }
        try {
            return runtime.selectDependency(choices, task::cancellationRequested);
        } finally {
            synchronized (catalogCacheLock) {
                if (activeSelectionClaims <= 0) {
                    throw new IllegalStateException("Missing-dependency selection claim underflow");
                }
                activeSelectionClaims--;
                catalogCacheLock.notifyAll();
            }
        }
    }

    /// Opens one search while serializing it against adapter closure.
    ///
    /// @param runtime resolved current search runtime
    /// @param dependencyId dependency identifier used as the search query
    private void openMissingDependencySearchWhileActive(
            SearchRuntime runtime,
            String dependencyId,
            String gameVersion) {
        synchronized (catalogCacheLock) {
            if (closed) {
                throw new CancellationException("Missing-dependency search was closed");
            }
            if (runtime.isClosed()) {
                throw new IllegalStateException("Launcher Swing application runtime is closed");
            }
            runtime.openMissingDependencySearch(
                    dependencyId,
                    gameVersion.isEmpty() ? null : gameVersion);
        }
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

    /// Adapts a crash-window navigation action to the delayed search boundary.
    ///
    /// @param openMissingDependencySearch action opening the Mods search page with analyzed context
    /// @param selector dependency chooser
    /// @return resolver whose runtime remains valid for the owning window's lifetime
    private static RuntimeResolver createRuntimeResolver(
            MissingDependencySearchAction openMissingDependencySearch,
            DependencySelector selector) {
        MissingDependencySearchAction checkedAction = Objects.requireNonNull(
                openMissingDependencySearch,
                "openMissingDependencySearch");
        ConsumerSearchRuntime runtime = new ConsumerSearchRuntime(checkedAction, selector);
        return () -> runtime;
    }

    /// Selects one dependency from a candidate-enriched native Swing dialog.
    ///
    /// @param choices immutable dependency choices in diagnosis order
    /// @return selected dependency, or null when the user cancels
    private static @Nullable DependencyChoice selectDependencyWithDialog(
            @Unmodifiable List<DependencyChoice> choices) {
        return selectDependencyWithDialog(choices, () -> false);
    }

    /// Selects one dependency from a native dialog that closes after cooperative task cancellation.
    ///
    /// @param choices immutable dependency choices in diagnosis order
    /// @param cancellationRequested current task cancellation signal
    /// @return selected dependency, or null when the user cancels
    private static @Nullable DependencyChoice selectDependencyWithDialog(
            @Unmodifiable List<DependencyChoice> choices,
            BooleanSupplier cancellationRequested) {
        if (choices.isEmpty()) {
            throw new IllegalArgumentException("choices must not be empty");
        }
        if (choices.size() == 1 || GraphicsEnvironment.isHeadless()) {
            return choices.get(0);
        }
        BooleanSupplier checkedCancellation = Objects.requireNonNull(
                cancellationRequested,
                "cancellationRequested");
        JComboBox<DependencyChoice> choiceBox = new JComboBox<>(choices.toArray(DependencyChoice[]::new));
        JOptionPane optionPane = new JOptionPane(
                new Object[]{i18n("game.crash.search_missing_dependency.select"), choiceBox},
                JOptionPane.QUESTION_MESSAGE,
                JOptionPane.OK_CANCEL_OPTION);
        JDialog dialog = optionPane.createDialog(i18n("game.crash.search_missing_dependency"));
        Timer cancellationTimer = new Timer((int) CATALOG_WAIT_MILLIS, event -> {
            if (checkedCancellation.getAsBoolean()) {
                optionPane.setValue(JOptionPane.CANCEL_OPTION);
                dialog.dispose();
            }
        });
        try {
            cancellationTimer.start();
            dialog.setVisible(true);
        } finally {
            cancellationTimer.stop();
            dialog.dispose();
        }
        if (checkedCancellation.getAsBoolean()) {
            throw new CancellationException("Missing-dependency search task was cancelled");
        }
        if (!Objects.equals(optionPane.getValue(), JOptionPane.OK_OPTION)) {
            return null;
        }
        Object selected = choiceBox.getSelectedItem();
        return selected instanceof DependencyChoice choice ? choice : null;
    }

    /// Phase-aware read-only search task used by Swing and MCP presentation layers.
    @NotNullByDefault
    private static final class MissingDependencySearchTask extends Task<@Nullable Void> {
        /// Search adapter owning runtime, cache, and close coordination.
        private final SwingMcpMissingDependencySearch owner;

        /// Immutable normalized dependency identifiers.
        private final @Unmodifiable List<String> dependencyIds;

        /// Normalized game-version filter.
        private final String gameVersion;

        /// Current internal execution phase.
        private RepairTaskPhase phase = RepairTaskPhase.PREPARING;

        /// Creates a stopped task whose initial phase is visible before an executor attaches.
        ///
        /// @param owner owning search adapter
        /// @param dependencyIds immutable normalized identifiers
        /// @param gameVersion normalized game-version filter
        private MissingDependencySearchTask(
                SwingMcpMissingDependencySearch owner,
                @Unmodifiable List<String> dependencyIds,
                String gameVersion) {
            this.owner = Objects.requireNonNull(owner, "owner");
            this.dependencyIds = List.copyOf(Objects.requireNonNull(dependencyIds, "dependencyIds"));
            this.gameVersion = Objects.requireNonNull(gameVersion, "gameVersion");
            getProperties().put(RepairTaskPhase.TASK_PROPERTY, phase);
        }

        /// Performs bounded candidate preparation, explicit selection, and read-only navigation.
        @Override
        public void execute() {
            owner.executeSearch(this, dependencyIds, gameVersion);
        }

        /// Publishes one valid forward transition and aborts promptly when its executor was cancelled.
        ///
        /// @param next next task-internal phase
        private void transitionTo(RepairTaskPhase next) {
            RepairTaskPhase checkedNext = Objects.requireNonNull(next, "next");
            boolean valid = (phase == RepairTaskPhase.PREPARING
                    && (checkedNext == RepairTaskPhase.AWAITING_SELECTION
                    || checkedNext == RepairTaskPhase.RUNNING))
                    || (phase == RepairTaskPhase.AWAITING_SELECTION && checkedNext == RepairTaskPhase.RUNNING);
            if (!valid) {
                throw new IllegalStateException("Invalid missing-dependency search phase transition");
            }
            phase = checkedNext;
            getProperties().put(RepairTaskPhase.TASK_PROPERTY, phase);
            notifyPropertiesChanged();
            if (isCancelled()) {
                throw new CancellationException("Missing-dependency search task was cancelled");
            }
        }

        /// Throws the task's cooperative cancellation state without touching the shared provider future.
        private void throwIfCancelled() {
            if (isCancelled()) {
                throw new CancellationException("Missing-dependency search task was cancelled");
            }
        }

        /// Returns the current cooperative task cancellation state for native selection polling.
        ///
        /// @return true when the owning executor requested cancellation
        private boolean cancellationRequested() {
            return isCancelled();
        }
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

        /// Opens the Mods search page with one analyzed Minecraft-version constraint.
        ///
        /// The compatibility implementation delegates to the legacy ID-only route.
        ///
        /// @param dependencyId dependency identifier used as the search query
        /// @param gameVersion analyzed Minecraft version, or null when unavailable
        default void openMissingDependencySearch(String dependencyId, @Nullable String gameVersion) {
            openModSearch(dependencyId);
        }
    }

    /// Search runtime capable of presenting all dependency IDs without overwriting one shared results page.
    @NotNullByDefault
    interface DependencySelectingRuntime extends SearchRuntime {
        /// Selects one dependency before the runtime opens its shared results page.
        ///
        /// @param choices immutable dependency choices in diagnosis order
        /// @return selected dependency, or null when cancelled
        @Nullable DependencyChoice selectDependency(@Unmodifiable List<DependencyChoice> choices);

        /// Selects one dependency while allowing native interactions to observe task cancellation.
        ///
        /// The compatibility implementation delegates to selectors that complete under their own lifecycle.
        ///
        /// @param choices immutable dependency choices in diagnosis order
        /// @param cancellationRequested current task cancellation signal
        /// @return selected dependency, or null when cancelled
        default @Nullable DependencyChoice selectDependency(
                @Unmodifiable List<DependencyChoice> choices,
                BooleanSupplier cancellationRequested) {
            Objects.requireNonNull(cancellationRequested, "cancellationRequested");
            return selectDependency(choices);
        }
    }

    /// Selects one dependency from candidate-enriched read-only search choices.
    @NotNullByDefault
    @FunctionalInterface
    interface DependencySelector {
        /// Selects one dependency or cancels without navigation.
        ///
        /// @param choices immutable dependency choices in diagnosis order
        /// @return selected dependency, or null when cancelled
        @Nullable DependencyChoice select(@Unmodifiable List<DependencyChoice> choices);
    }

    /// Immutable dependency identifier and compact provider-candidate snapshot.
    ///
    /// @param dependencyId validated dependency identifier
    /// @param candidateLabels bounded candidate display labels
    @NotNullByDefault
    record DependencyChoice(
            String dependencyId,
            @Unmodifiable List<String> candidateLabels) {
        /// Validates and snapshots one choice.
        public DependencyChoice {
            dependencyId = normalizeDependencyId(dependencyId);
            candidateLabels = List.copyOf(Objects.requireNonNull(candidateLabels, "candidateLabels"));
        }

        /// Formats the dependency and its best cached candidate without allowing an unbounded dialog row.
        ///
        /// @return compact selection text
        @Override
        public String toString() {
            if (candidateLabels.isEmpty()) {
                return dependencyId;
            }
            String remaining = candidateLabels.size() == 1 ? "" : " (+" + (candidateLabels.size() - 1) + ")";
            return dependencyId + ": " + candidateLabels.get(0) + remaining;
        }
    }

    /// Delegates search navigation to a crash-window callback.
    @NotNullByDefault
    private static final class ConsumerSearchRuntime implements DependencySelectingRuntime {
        /// Search navigation action owned by the crash window.
        private final MissingDependencySearchAction delegate;

        /// Candidate-enriched dependency chooser.
        private final DependencySelector selector;

        /// Creates one callback-backed search runtime.
        ///
        /// @param delegate search navigation action
        /// @param selector candidate-enriched dependency chooser
        private ConsumerSearchRuntime(MissingDependencySearchAction delegate, DependencySelector selector) {
            this.delegate = Objects.requireNonNull(delegate, "delegate");
            this.selector = Objects.requireNonNull(selector, "selector");
        }

        /// Selects one dependency before opening the shared Mods results page.
        ///
        /// @param choices immutable dependency choices in diagnosis order
        /// @return selected dependency, or null when cancelled
        @Override
        public @Nullable DependencyChoice selectDependency(@Unmodifiable List<DependencyChoice> choices) {
            return selector.select(choices);
        }

        /// Reports that lifetime is governed by the owning search adapter.
        ///
        /// @return always false while a task can reach this boundary
        @Override
        public boolean isClosed() {
            return false;
        }

        /// Opens the Mods search page through the supplied callback.
        ///
        /// @param dependencyId dependency identifier used as the search query
        @Override
        public void openModSearch(String dependencyId) {
            delegate.open(dependencyId, null);
        }

        /// Opens one dependency query with its analyzed version.
        ///
        /// @param dependencyId dependency identifier used as the search query
        /// @param gameVersion analyzed Minecraft version, or null when unavailable
        @Override
        public void openMissingDependencySearch(String dependencyId, @Nullable String gameVersion) {
            delegate.open(dependencyId, gameVersion);
        }
    }

    /// Delegates the minimal search surface to one resolved production Swing runtime.
    @NotNullByDefault
    private static final class SwingSearchRuntime implements DependencySelectingRuntime {
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

        /// Presents all dependency IDs and cached candidates before navigating the shared Mods page.
        ///
        /// @param choices immutable dependency choices in diagnosis order
        /// @return selected dependency, or null when cancelled
        @Override
        public @Nullable DependencyChoice selectDependency(@Unmodifiable List<DependencyChoice> choices) {
            return selectDependencyWithDialog(choices);
        }

        /// Presents all dependency IDs while polling the owning task's cancellation state.
        ///
        /// @param choices immutable dependency choices in diagnosis order
        /// @param cancellationRequested current task cancellation signal
        /// @return selected dependency, or null when cancelled
        @Override
        public @Nullable DependencyChoice selectDependency(
                @Unmodifiable List<DependencyChoice> choices,
                BooleanSupplier cancellationRequested) {
            return selectDependencyWithDialog(choices, cancellationRequested);
        }

        /// Opens the delegated runtime's Mods search page.
        ///
        /// @param dependencyId dependency identifier used as the search query
        @Override
        public void openModSearch(String dependencyId) {
            delegate.openModSearch(dependencyId);
        }

        /// Opens the delegated runtime's version-aware missing-dependency search page.
        ///
        /// @param dependencyId dependency identifier used as the search query
        /// @param gameVersion analyzed Minecraft version, or null when unavailable
        @Override
        public void openMissingDependencySearch(String dependencyId, @Nullable String gameVersion) {
            delegate.openMissingDependencySearch(dependencyId, gameVersion);
        }
    }
}
