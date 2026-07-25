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
import space.minecraftstl.xyml.observable.property.ObservableValue;
import space.minecraftstl.xyml.util.function.ExceptionalFunction;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.function.BiConsumer;

/// Optional-valued facade over [ObservableCache].
///
/// @param <K> cache key type
/// @param <V> present value type
/// @param <E> checked source exception type
@NotNullByDefault
public final class ObservableOptionalCache<K, V, E extends Exception> {
    /// Backing cache whose fallback is [Optional#empty()].
    private final ObservableCache<K, Optional<V>, E> backingCache;

    /// Creates an optional cache.
    ///
    /// @param source optional value source
    /// @param exceptionHandler asynchronous failure consumer
    /// @param executor automatic refresh executor
    public ObservableOptionalCache(
            ExceptionalFunction<K, Optional<V>, E> source,
            BiConsumer<K, Throwable> exceptionHandler,
            Executor executor) {
        backingCache = new ObservableCache<>(
                Objects.requireNonNull(source, "source"),
                Objects.requireNonNull(exceptionHandler, "exceptionHandler"),
                Optional.empty(),
                Objects.requireNonNull(executor, "executor"));
    }

    /// Returns a present cached value without loading it.
    ///
    /// @param key cache key
    /// @return cached present value
    public Optional<V> getImmediately(K key) {
        return backingCache.getImmediately(key).flatMap(value -> value);
    }

    /// Stores a present value.
    ///
    /// @param key cache key
    /// @param value value to store
    public void put(K key, V value) {
        backingCache.put(key, Optional.of(Objects.requireNonNull(value, "value")));
    }

    /// Returns a current optional value, loading synchronously when required.
    ///
    /// @param key cache key
    /// @return current optional value
    public Optional<V> get(K key) {
        return backingCache.get(key);
    }

    /// Loads and stores an optional value directly.
    ///
    /// @param key cache key
    /// @return loaded optional value
    /// @throws E when the source fails
    public Optional<V> getDirectly(K key) throws E {
        return backingCache.getDirectly(key);
    }

    /// Returns an automatically refreshing observable optional value.
    ///
    /// @param key cache key
    /// @return observable optional value
    public ObservableValue<Optional<V>> binding(K key) {
        return backingCache.binding(key);
    }

    /// Returns a quiet or automatically refreshing observable optional value.
    ///
    /// @param key cache key
    /// @param quiet whether observation must avoid initiating queries
    /// @return observable optional value
    public ObservableValue<Optional<V>> binding(K key, boolean quiet) {
        return backingCache.binding(key, quiet);
    }

    /// Marks one key stale.
    ///
    /// @param key cache key
    public void invalidate(K key) {
        backingCache.invalidate(key);
    }
}
