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
package space.minecraftstl.xyml.observable.collection;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/// Immutable description of old and new mappings for the keys affected by one observable-map mutation.
///
/// An absent key is represented by its omission from the corresponding map rather than by a null sentinel.
@NotNullByDefault
public final class MapChange<K, V> {
    /// Classifies how map contents changed.
    @NotNullByDefault
    public enum Kind {
        /// One key was inserted or its value was replaced.
        PUT,
        /// One or more mappings were removed.
        REMOVE,
        /// All previous mappings were removed.
        CLEAR,
        /// Multiple mappings were inserted or replaced as one operation.
        BATCH
    }

    /// Map that emitted the change.
    private final ObservableMap<K, V> source;

    /// Mutation classification.
    private final Kind kind;

    /// Keys affected by this operation in stable encounter order.
    private final @Unmodifiable Set<K> affectedKeys;

    /// Previous mappings for affected keys that were present before the operation.
    private final @Unmodifiable Map<K, V> previousEntries;

    /// Current mappings for affected keys that are present after the operation.
    private final @Unmodifiable Map<K, V> currentEntries;

    /// Creates an immutable map change from defensive ordered snapshots.
    MapChange(
            ObservableMap<K, V> source,
            Kind kind,
            Map<? extends K, ? extends V> previousEntries,
            Map<? extends K, ? extends V> currentEntries) {
        this.source = Objects.requireNonNull(source, "source");
        this.kind = Objects.requireNonNull(kind, "kind");
        this.previousEntries = immutableCopy(previousEntries);
        this.currentEntries = immutableCopy(currentEntries);
        LinkedHashSet<K> keys = new LinkedHashSet<>(this.currentEntries.keySet());
        keys.addAll(this.previousEntries.keySet());
        this.affectedKeys = Collections.unmodifiableSet(keys);
    }

    /// Returns the map that emitted this change.
    public ObservableMap<K, V> source() {
        return source;
    }

    /// Returns the mutation classification.
    public Kind kind() {
        return kind;
    }

    /// Returns affected keys in stable encounter order.
    public @Unmodifiable Set<K> affectedKeys() {
        return affectedKeys;
    }

    /// Returns previous mappings for affected keys that existed before the operation.
    public @Unmodifiable Map<K, V> previousEntries() {
        return previousEntries;
    }

    /// Returns current mappings for affected keys that exist after the operation.
    public @Unmodifiable Map<K, V> currentEntries() {
        return currentEntries;
    }

    /// Copies mappings while preserving key encounter order and preventing mutation.
    private static <K, V> @Unmodifiable Map<K, V> immutableCopy(Map<? extends K, ? extends V> entries) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(entries));
    }
}
