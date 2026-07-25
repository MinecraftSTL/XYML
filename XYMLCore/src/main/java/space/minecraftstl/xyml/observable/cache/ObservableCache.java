/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2020 huangyuhui <huanghongxun2008@126.com> and contributors
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
package space.minecraftstl.xyml.observable.cache;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import space.minecraftstl.xyml.observable.property.ObservableValue;
import space.minecraftstl.xyml.observable.property.SimpleObjectProperty;
import space.minecraftstl.xyml.util.function.ExceptionalFunction;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BiConsumer;

import static space.minecraftstl.xyml.util.logging.Logger.LOG;

/// Caches asynchronously loaded values and exposes toolkit-neutral per-key observables.
///
/// Cache state is protected by one lock. Value notifications run synchronously on the thread that commits a value;
/// presentation consumers are responsible for dispatching to their own UI thread. Repeated queries for one key share
/// the same in-flight future.
///
/// @param <K> cache key type
/// @param <V> non-null cached value type
/// @param <E> checked source exception type
@NotNullByDefault
public final class ObservableCache<K, V, E extends Exception> {
    /// Serializes cache maps, invalidation flags, and in-flight query registration.
    private final ReentrantLock lock = new ReentrantLock();

    /// Loads one value on a cache miss or refresh.
    private final ExceptionalFunction<K, V, E> source;

    /// Receives asynchronous source failures.
    private final BiConsumer<K, Throwable> exceptionHandler;

    /// Value returned while no successful value exists.
    private final V fallbackValue;

    /// Executor used by observable auto-refresh queries.
    private final Executor executor;

    /// Successfully loaded values by key.
    private final Map<K, V> cache = new HashMap<>();

    /// Shared in-flight queries by key.
    private final Map<K, CompletableFuture<V>> pendingQueries = new HashMap<>();

    /// Keys whose cached values must be refreshed before being considered current.
    private final Map<K, Boolean> invalidatedKeys = new HashMap<>();

    /// Observable entries created by [#binding(Object)] or [#binding(Object, boolean)].
    private final Map<K, CacheEntry<V>> observableEntries = new HashMap<>();

    /// Creates an observable cache.
    ///
    /// @param source value source
    /// @param exceptionHandler asynchronous failure consumer
    /// @param fallbackValue value used before the first successful load
    /// @param executor auto-refresh executor
    public ObservableCache(
            ExceptionalFunction<K, V, E> source,
            BiConsumer<K, Throwable> exceptionHandler,
            V fallbackValue,
            Executor executor) {
        this.source = Objects.requireNonNull(source, "source");
        this.exceptionHandler = Objects.requireNonNull(exceptionHandler, "exceptionHandler");
        this.fallbackValue = Objects.requireNonNull(fallbackValue, "fallbackValue");
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    /// Returns a cached value without loading it.
    ///
    /// @param key cache key
    /// @return cached value when present
    public Optional<V> getImmediately(K key) {
        lock.lock();
        try {
            return Optional.ofNullable(cache.get(Objects.requireNonNull(key, "key")));
        } finally {
            lock.unlock();
        }
    }

    /// Stores a value, clears its invalidation flag, and publishes it to an existing observable entry.
    ///
    /// @param key cache key
    /// @param value value to store
    public void put(K key, V value) {
        K validatedKey = Objects.requireNonNull(key, "key");
        V validatedValue = Objects.requireNonNull(value, "value");
        @Nullable CacheEntry<V> entry;
        lock.lock();
        try {
            cache.put(validatedKey, validatedValue);
            invalidatedKeys.remove(validatedKey);
            entry = observableEntries.get(validatedKey);
        } finally {
            lock.unlock();
        }
        publish(entry, validatedValue);
    }

    /// Returns a current value, loading synchronously when necessary and falling back after a load failure.
    ///
    /// @param key cache key
    /// @return current, stale, or fallback value in that order of preference
    public V get(K key) {
        K validatedKey = Objects.requireNonNull(key, "key");
        @Nullable V cached;
        lock.lock();
        try {
            cached = cache.get(validatedKey);
            if (cached != null && !invalidatedKeys.containsKey(validatedKey)) {
                return cached;
            }
        } finally {
            lock.unlock();
        }

        try {
            return query(validatedKey, Runnable::run).join();
        } catch (CompletionException | CancellationException ignored) {
            return cached == null ? fallbackValue : cached;
        }
    }

    /// Loads one value directly and commits it to the cache.
    ///
    /// @param key cache key
    /// @return loaded value
    /// @throws E when the source fails
    public V getDirectly(K key) throws E {
        K validatedKey = Objects.requireNonNull(key, "key");
        V result = Objects.requireNonNull(source.apply(validatedKey), "source result");
        put(validatedKey, result);
        return result;
    }

    /// Returns an observable value that automatically refreshes on misses and invalidation.
    ///
    /// @param key cache key
    /// @return stable observable entry for the key
    public ObservableValue<V> binding(K key) {
        return binding(key, false);
    }

    /// Returns a stable observable value for one key.
    ///
    /// A quiet binding observes values committed by other operations but does not initiate loading. A non-quiet
    /// binding initiates loading immediately when its key is missing or invalidated and on every later invalidation.
    ///
    /// @param key cache key
    /// @param quiet whether this binding must avoid initiating queries
    /// @return stable observable entry for the key
    public ObservableValue<V> binding(K key, boolean quiet) {
        K validatedKey = Objects.requireNonNull(key, "key");
        CacheEntry<V> entry;
        boolean refresh;
        lock.lock();
        try {
            @Nullable V cached = cache.get(validatedKey);
            entry = observableEntries.computeIfAbsent(
                    validatedKey,
                    ignored -> new CacheEntry<>(cached == null ? fallbackValue : cached));
            if (!quiet) {
                entry.enableAutoRefresh();
            }
            refresh = !quiet && (cached == null || invalidatedKeys.containsKey(validatedKey));
        } finally {
            lock.unlock();
        }
        if (refresh) {
            query(validatedKey, executor);
        }
        return entry.observable();
    }

    /// Marks a cached value stale and starts refresh when a non-quiet binding exists.
    ///
    /// @param key cache key
    public void invalidate(K key) {
        K validatedKey = Objects.requireNonNull(key, "key");
        boolean refresh = false;
        lock.lock();
        try {
            if (cache.containsKey(validatedKey)) {
                invalidatedKeys.put(validatedKey, Boolean.TRUE);
                @Nullable CacheEntry<V> entry = observableEntries.get(validatedKey);
                refresh = entry != null && entry.autoRefreshEnabled();
            }
        } finally {
            lock.unlock();
        }
        if (refresh) {
            query(validatedKey, executor);
        }
    }

    /// Returns or starts the shared query for one key.
    ///
    /// @param key cache key
    /// @param queryExecutor executor for the source call
    /// @return shared query future
    private CompletableFuture<V> query(K key, Executor queryExecutor) {
        CompletableFuture<V> future;
        lock.lock();
        try {
            @Nullable CompletableFuture<V> existing = pendingQueries.get(key);
            if (existing != null) {
                return existing;
            }
            future = new CompletableFuture<>();
            pendingQueries.put(key, future);
        } finally {
            lock.unlock();
        }

        queryExecutor.execute(() -> loadIntoFuture(key, future));
        return future;
    }

    /// Loads and commits one query result, completing the registered future on every path.
    ///
    /// @param key cache key
    /// @param future registered shared future
    private void loadIntoFuture(K key, CompletableFuture<V> future) {
        V result;
        try {
            result = Objects.requireNonNull(source.apply(key), "source result");
        } catch (Throwable failure) {
            lock.lock();
            try {
                pendingQueries.remove(key, future);
            } finally {
                lock.unlock();
            }
            try {
                exceptionHandler.accept(key, failure);
            } catch (Throwable handlerFailure) {
                failure.addSuppressed(handlerFailure);
            }
            future.completeExceptionally(failure);
            return;
        }

        @Nullable CacheEntry<V> entry;
        lock.lock();
        try {
            cache.put(key, result);
            invalidatedKeys.remove(key);
            pendingQueries.remove(key, future);
            entry = observableEntries.get(key);
        } finally {
            lock.unlock();
        }
        publish(entry, result);
        future.complete(result);
    }

    /// Publishes a committed cache value while isolating cache completion from listener failures.
    ///
    /// @param entry observable entry, or null when none was requested
    /// @param value committed value
    private static <V> void publish(@Nullable CacheEntry<V> entry, V value) {
        if (entry == null) {
            return;
        }
        try {
            entry.publish(value);
        } catch (RuntimeException listenerFailure) {
            LOG.warning("Observable cache listener failed", listenerFailure);
        }
    }

    /// Owns the neutral property and refresh policy for one observed key.
    ///
    /// @param <V> value type
    @NotNullByDefault
    private static final class CacheEntry<V> {
        /// Observable value published to consumers.
        private final SimpleObjectProperty<V> property;

        /// Whether invalidation should initiate a refresh.
        private boolean autoRefresh;

        /// Creates an entry with its initial cache or fallback value.
        ///
        /// @param initialValue initial value
        private CacheEntry(V initialValue) {
            property = new SimpleObjectProperty<>(Objects.requireNonNull(initialValue, "initialValue"));
        }

        /// Enables automatic refresh permanently for this key.
        private void enableAutoRefresh() {
            autoRefresh = true;
        }

        /// Returns whether automatic refresh is enabled.
        private boolean autoRefreshEnabled() {
            return autoRefresh;
        }

        /// Returns the observable property through its read-only interface.
        private ObservableValue<V> observable() {
            return property;
        }

        /// Publishes one committed value.
        ///
        /// @param value committed value
        private void publish(V value) {
            property.setValue(value);
        }
    }
}
