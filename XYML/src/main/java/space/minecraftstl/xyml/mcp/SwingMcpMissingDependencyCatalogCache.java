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
import space.minecraftstl.xyml.ui.swing.page.downloads.RemoteAddonCatalogBackend;
import space.minecraftstl.xyml.ui.swing.page.downloads.RemoteAddonCatalogPage;
import space.minecraftstl.xyml.ui.swing.page.downloads.RemoteAddonCatalogQuery;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/// Bounded, read-only cache for dependency catalog pages requested ahead of the repair button.
///
/// This cache deliberately accepts only the existing catalog backend and query value objects. It never owns an
/// install launcher, target resolver, or filesystem path. Failed requests are removed, so a later explicit search can
/// retry without treating a transient provider failure as a negative result.
@NotNullByDefault
public final class SwingMcpMissingDependencyCatalogCache implements AutoCloseable {
    /// Default number of result rows requested by a background dependency lookup.
    public static final int DEFAULT_PAGE_SIZE = 20;

    /// Default maximum number of completed or in-flight dependency queries retained by one cache.
    public static final int DEFAULT_MAXIMUM_ENTRIES = 128;

    /// Backend used only for provider search calls.
    private final RemoteAddonCatalogBackend backend;

    /// Caller-owned executor used for blocking provider requests.
    private final Executor executor;

    /// Maximum combined number of completed and in-flight entries retained at once.
    private final int maximumEntries;

    /// Serializes cache state and keeps insertion order deterministic.
    private final Object stateLock = new Object();

    /// Successful pages in access order.
    private final LinkedHashMap<RemoteAddonCatalogQuery, RemoteAddonCatalogPage> entries = new LinkedHashMap<>(
            16,
            0.75F,
            true);

    /// Requests currently being resolved; duplicate callers share one future.
    private final LinkedHashMap<RemoteAddonCatalogQuery, CompletableFuture<RemoteAddonCatalogPage>> inFlight =
            new LinkedHashMap<>();

    /// Whether no new request may be accepted.
    private boolean closed;

    /// Creates a bounded read-only catalog cache.
    ///
    /// @param backend existing catalog search gateway
    /// @param executor worker used for blocking provider calls
    /// @param maximumEntries positive upper bound for completed and in-flight requests
    public SwingMcpMissingDependencyCatalogCache(
            RemoteAddonCatalogBackend backend,
            Executor executor,
            int maximumEntries) {
        this.backend = Objects.requireNonNull(backend, "backend");
        this.executor = Objects.requireNonNull(executor, "executor");
        if (maximumEntries < 1) {
            throw new IllegalArgumentException("maximumEntries must be positive");
        }
        this.maximumEntries = maximumEntries;
    }

    /// Creates a cache using the standard bounded capacity.
    ///
    /// @param backend existing catalog search gateway
    /// @param executor worker used for blocking provider calls
    public SwingMcpMissingDependencyCatalogCache(
            RemoteAddonCatalogBackend backend,
            Executor executor) {
        this(backend, executor, DEFAULT_MAXIMUM_ENTRIES);
    }

    /// Starts one read-only provider search or joins an equivalent request already in flight.
    ///
    /// Only a successful immutable page enters the cache. A provider exception completes the returned stage
    /// exceptionally and removes its key, allowing a later invocation to retry.
    ///
    /// @param query exact catalog query, including provider and bounded page size
    /// @return stage containing the immutable provider page
    public CompletionStage<RemoteAddonCatalogPage> prefetch(RemoteAddonCatalogQuery query) {
        RemoteAddonCatalogQuery request = Objects.requireNonNull(query, "query");
        CompletableFuture<RemoteAddonCatalogPage> future;
        synchronized (stateLock) {
            if (closed) {
                return failedStage(new IllegalStateException("Dependency catalog cache is closed"));
            }
            @Nullable RemoteAddonCatalogPage cached = entries.get(request);
            if (cached != null) {
                return CompletableFuture.completedFuture(cached);
            }
            @Nullable CompletableFuture<RemoteAddonCatalogPage> existing = inFlight.get(request);
            if (existing != null) {
                return existing;
            }
            while (entries.size() + inFlight.size() >= maximumEntries && !entries.isEmpty()) {
                entries.remove(entries.keySet().iterator().next());
            }
            if (entries.size() + inFlight.size() >= maximumEntries) {
                return failedStage(new RejectedExecutionException(
                        "Dependency catalog cache capacity reached"));
            }
            future = new CompletableFuture<>();
            inFlight.put(request, future);
        }

        try {
            executor.execute(() -> load(request, future));
        } catch (RuntimeException schedulingFailure) {
            completeFailure(request, future, schedulingFailure);
        }
        return future;
    }

    /// Returns a successful page currently cached for an exact query.
    ///
    /// @param query exact catalog query
    /// @return immutable page, or empty while the request is absent or still in flight
    public Optional<RemoteAddonCatalogPage> get(RemoteAddonCatalogQuery query) {
        RemoteAddonCatalogQuery request = Objects.requireNonNull(query, "query");
        synchronized (stateLock) {
            return Optional.ofNullable(entries.get(request));
        }
    }

    /// Returns the number of successful pages currently retained.
    ///
    /// @return completed page count
    public int size() {
        synchronized (stateLock) {
            return entries.size();
        }
    }

    /// Removes all completed pages and invalidates in-flight results.
    ///
    /// The backend's blocking I/O is not interrupted, but callers waiting on invalidated stages receive cancellation
    /// and late responses are discarded. A later call to [#prefetch] may request the same page again.
    public void clear() {
        List<CompletableFuture<RemoteAddonCatalogPage>> pending;
        synchronized (stateLock) {
            entries.clear();
            pending = new ArrayList<>(inFlight.values());
            inFlight.clear();
        }
        CancellationException cancellation = new CancellationException("Dependency catalog cache was cleared");
        for (CompletableFuture<RemoteAddonCatalogPage> future : pending) {
            future.completeExceptionally(cancellation);
        }
    }

    /// Prevents new requests, drops completed pages, and completes in-flight stages as cancelled.
    @Override
    public void close() {
        List<CompletableFuture<RemoteAddonCatalogPage>> pending;
        synchronized (stateLock) {
            if (closed) {
                return;
            }
            closed = true;
            entries.clear();
            pending = new ArrayList<>(inFlight.values());
            inFlight.clear();
        }
        CancellationException cancellation = new CancellationException("Dependency catalog cache is closed");
        for (CompletableFuture<RemoteAddonCatalogPage> future : pending) {
            future.completeExceptionally(cancellation);
        }
    }

    /// Resolves one provider query away from the caller thread.
    ///
    /// @param query immutable provider query
    /// @param future shared completion stage for this query
    private void load(
            RemoteAddonCatalogQuery query,
            CompletableFuture<RemoteAddonCatalogPage> future) {
        try {
            RemoteAddonCatalogPage page = Objects.requireNonNull(
                    backend.search(query),
                    "catalog backend returned null");
            if (page.pageOffset() != query.pageOffset()) {
                throw new IOException("Catalog backend returned a page with an unexpected offset");
            }
            boolean accepted;
            synchronized (stateLock) {
                accepted = inFlight.remove(query, future);
                if (accepted && !closed) {
                    entries.put(query, page);
                    while (entries.size() > maximumEntries) {
                        entries.remove(entries.keySet().iterator().next());
                    }
                }
            }
            if (accepted) {
                future.complete(page);
            }
        } catch (IOException | RuntimeException failure) {
            completeFailure(query, future, failure);
        }
    }

    /// Removes a failed request and completes its shared stage exceptionally.
    ///
    /// @param query failed query
    /// @param future shared stage
    /// @param failure provider or scheduling failure
    private void completeFailure(
            RemoteAddonCatalogQuery query,
            CompletableFuture<RemoteAddonCatalogPage> future,
            Throwable failure) {
        synchronized (stateLock) {
            inFlight.remove(query, future);
        }
        future.completeExceptionally(Objects.requireNonNull(failure, "failure"));
    }

    /// Creates an already failed stage without relying on JDK-specific convenience methods.
    ///
    /// @param failure failure to publish
    /// @return failed completion stage
    private static CompletionStage<RemoteAddonCatalogPage> failedStage(Throwable failure) {
        CompletableFuture<RemoteAddonCatalogPage> result = new CompletableFuture<>();
        result.completeExceptionally(Objects.requireNonNull(failure, "failure"));
        return result;
    }
}
