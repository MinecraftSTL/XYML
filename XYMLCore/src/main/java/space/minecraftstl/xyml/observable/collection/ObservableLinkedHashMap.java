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
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import space.minecraftstl.xyml.observable.Subscription;

import java.util.AbstractCollection;
import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Predicate;

/// LinkedHashMap-backed implementation of [ObservableMap] with stable key order and observable mutable views.
///
/// Keys and values are validated before batch writes. Mutations through [#entrySet()], [#keySet()], and [#values()]
/// are reported, including one consolidated event for each bulk removal on those views.
@NotNullByDefault
public final class ObservableLinkedHashMap<K, V> extends AbstractMap<K, V> implements ObservableMap<K, V> {
    /// Mutable map storage owned exclusively by this instance.
    private final LinkedHashMap<K, V> entries;

    /// Synchronous listener registrations.
    private final CollectionChangeSupport<MapChange<K, V>> changeSupport = new CollectionChangeSupport<>();

    /// Stable mutable entry-set view.
    private final Set<Entry<K, V>> entrySetView = new EntrySetView();

    /// Stable mutable key-set view.
    private final Set<K> keySetView = new KeySetView();

    /// Stable mutable values view.
    private final Collection<V> valuesView = new ValuesView();

    /// Creates an empty insertion-ordered observable map.
    public ObservableLinkedHashMap() {
        this.entries = new LinkedHashMap<>();
    }

    /// Creates an observable map by copying mappings in key encounter order.
    public ObservableLinkedHashMap(Map<? extends K, ? extends V> entries) {
        this.entries = validatedCopy(entries);
    }

    /// Registers a synchronous map-change listener.
    @Override
    public Subscription subscribe(CollectionChangeListener<MapChange<K, V>> listener) {
        return changeSupport.subscribe(listener);
    }

    /// Returns the number of mappings.
    @Override
    public int size() {
        return entries.size();
    }

    /// Returns whether an equal key exists; null queries are treated as absent.
    @Override
    public boolean containsKey(@Nullable Object key) {
        return entries.containsKey(key);
    }

    /// Returns whether an equal value exists; null queries are treated as absent.
    @Override
    public boolean containsValue(@Nullable Object value) {
        return entries.containsValue(value);
    }

    /// Returns the mapped value or null when the queried key is absent.
    @Override
    public @Nullable V get(@Nullable Object key) {
        return entries.get(key);
    }

    /// Inserts or replaces one mapping and emits one event unless the mapped value is equal.
    @Override
    public @Nullable V put(K key, V value) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value");
        @Nullable K storedKey = findStoredKey(key);
        boolean present = storedKey != null;
        @Nullable V previous = present ? entries.get(storedKey) : null;
        if (present && Objects.equals(previous, value)) {
            return previous;
        }

        entries.put(key, value);
        K affectedKey = present ? Objects.requireNonNull(storedKey) : key;
        @Unmodifiable Map<K, V> previousEntries =
                present ? Map.of(affectedKey, Objects.requireNonNull(previous)) : Map.of();
        changeSupport.fire(new MapChange<>(this, MapChange.Kind.PUT, previousEntries, Map.of(affectedKey, value)));
        return previous;
    }

    /// Copies and applies all logically changed mappings with one batch event.
    @Override
    public void putAll(Map<? extends K, ? extends V> addedEntries) {
        LinkedHashMap<K, V> validated = validatedCopy(addedEntries);
        LinkedHashMap<K, V> previous = new LinkedHashMap<>();
        LinkedHashMap<K, V> current = new LinkedHashMap<>();
        for (Entry<K, V> entry : validated.entrySet()) {
            K key = entry.getKey();
            V value = entry.getValue();
            @Nullable K storedKey = findStoredKey(key);
            boolean present = storedKey != null;
            @Nullable V oldValue = present ? entries.get(storedKey) : null;
            if (present && Objects.equals(oldValue, value)) {
                continue;
            }
            K affectedKey = present ? Objects.requireNonNull(storedKey) : key;
            if (present) {
                previous.put(affectedKey, Objects.requireNonNull(oldValue));
            }
            current.put(affectedKey, value);
        }
        if (current.isEmpty()) {
            return;
        }

        entries.putAll(current);
        changeSupport.fire(new MapChange<>(this, MapChange.Kind.BATCH, previous, current));
    }

    /// Removes one mapping and returns its previous value, or null when absent.
    @Override
    public @Nullable V remove(@Nullable Object key) {
        @Nullable K storedKey = findStoredKey(key);
        if (storedKey == null) {
            return null;
        }

        V previous = Objects.requireNonNull(entries.remove(storedKey));
        changeSupport.fire(new MapChange<>(this, MapChange.Kind.REMOVE, Map.of(storedKey, previous), Map.of()));
        return previous;
    }

    /// Clears all mappings with one event, or does nothing when already empty.
    @Override
    public void clear() {
        if (entries.isEmpty()) {
            return;
        }

        LinkedHashMap<K, V> previous = new LinkedHashMap<>(entries);
        entries.clear();
        changeSupport.fire(new MapChange<>(this, MapChange.Kind.CLEAR, previous, Map.of()));
    }

    /// Replaces all mapped values atomically after validating every function result.
    @Override
    public void replaceAll(BiFunction<? super K, ? super V, ? extends V> function) {
        Objects.requireNonNull(function, "function");
        LinkedHashMap<K, V> replacements = new LinkedHashMap<>();
        LinkedHashMap<K, V> previous = new LinkedHashMap<>();
        LinkedHashMap<K, V> current = new LinkedHashMap<>();
        for (Entry<K, V> entry : entries.entrySet()) {
            K key = entry.getKey();
            V oldValue = entry.getValue();
            V newValue = Objects.requireNonNull(function.apply(key, oldValue), "function result");
            replacements.put(key, newValue);
            if (!Objects.equals(oldValue, newValue)) {
                previous.put(key, oldValue);
                current.put(key, newValue);
            }
        }
        if (current.isEmpty()) {
            return;
        }

        for (Entry<K, V> replacement : replacements.entrySet()) {
            entries.put(replacement.getKey(), replacement.getValue());
        }
        changeSupport.fire(new MapChange<>(this, MapChange.Kind.BATCH, previous, current));
    }

    /// Returns the stable observable entry-set view.
    @Override
    public Set<Entry<K, V>> entrySet() {
        return entrySetView;
    }

    /// Returns the stable observable key-set view.
    @Override
    public Set<K> keySet() {
        return keySetView;
    }

    /// Returns the stable observable values view.
    @Override
    public Collection<V> values() {
        return valuesView;
    }

    /// Removes preselected keys in backing-map order and emits at most one event.
    private boolean removeKeys(Collection<? extends K> keys) {
        if (keys.isEmpty()) {
            return false;
        }
        LinkedHashSet<K> selected = new LinkedHashSet<>(keys);
        LinkedHashMap<K, V> previous = new LinkedHashMap<>();
        for (Entry<K, V> entry : entries.entrySet()) {
            if (selected.contains(entry.getKey())) {
                previous.put(entry.getKey(), entry.getValue());
            }
        }
        if (previous.isEmpty()) {
            return false;
        }

        entries.keySet().removeAll(previous.keySet());
        changeSupport.fire(new MapChange<>(this, MapChange.Kind.REMOVE, previous, Map.of()));
        return true;
    }

    /// Selects mappings from immutable entry snapshots before performing one consolidated removal.
    private boolean removeMatching(Predicate<? super Entry<K, V>> predicate) {
        Objects.requireNonNull(predicate, "predicate");
        LinkedHashSet<K> keys = new LinkedHashSet<>();
        for (Entry<K, V> entry : entries.entrySet()) {
            Entry<K, V> snapshot = new SimpleImmutableEntry<>(entry);
            if (predicate.test(snapshot)) {
                keys.add(entry.getKey());
            }
        }
        return removeKeys(keys);
    }

    /// Returns the actual stored key equal to a candidate, or null when no mapping exists.
    private @Nullable K findStoredKey(@Nullable Object candidate) {
        if (candidate == null) {
            return null;
        }
        for (K storedKey : entries.keySet()) {
            if (Objects.equals(storedKey, candidate)) {
                return storedKey;
            }
        }
        return null;
    }

    /// Publishes removal of one known mapping.
    private void fireRemoved(K key, V value) {
        changeSupport.fire(new MapChange<>(this, MapChange.Kind.REMOVE, Map.of(key, value), Map.of()));
    }

    /// Copies and validates all input mappings while preserving key encounter order.
    private static <K, V> LinkedHashMap<K, V> validatedCopy(Map<? extends K, ? extends V> source) {
        Objects.requireNonNull(source, "source");
        LinkedHashMap<K, V> copy = new LinkedHashMap<>();
        for (Entry<? extends K, ? extends V> entry : source.entrySet()) {
            K key = Objects.requireNonNull(entry.getKey(), "source key");
            V value = Objects.requireNonNull(entry.getValue(), "source value");
            copy.put(key, value);
        }
        return copy;
    }

    /// Mutable entry-set view backed by the observable map.
    @NotNullByDefault
    private final class EntrySetView extends AbstractSet<Entry<K, V>> {
        /// Returns an iterator whose removal and entry replacement are observable.
        @Override
        public Iterator<Entry<K, V>> iterator() {
            return new EntryIterator(entries.entrySet().iterator());
        }

        /// Returns the number of mappings.
        @Override
        public int size() {
            return entries.size();
        }

        /// Returns whether an equal mapping exists.
        @Override
        public boolean contains(@Nullable Object candidate) {
            return entries.entrySet().contains(candidate);
        }

        /// Removes an equal mapping when present.
        @Override
        public boolean remove(@Nullable Object candidate) {
            if (!(candidate instanceof Entry<?, ?> entry)) {
                return false;
            }
            @Nullable Object key = entry.getKey();
            if (!entries.containsKey(key) || !Objects.equals(entries.get(key), entry.getValue())) {
                return false;
            }
            ObservableLinkedHashMap.this.remove(key);
            return true;
        }

        /// Removes all mappings equal to a candidate with one consolidated event.
        @Override
        public boolean removeAll(Collection<? extends @Nullable Object> candidates) {
            Objects.requireNonNull(candidates, "candidates");
            return removeMatching(candidates::contains);
        }

        /// Retains only mappings equal to at least one candidate with one consolidated event.
        @Override
        public boolean retainAll(Collection<? extends @Nullable Object> candidates) {
            Objects.requireNonNull(candidates, "candidates");
            return removeMatching(entry -> !candidates.contains(entry));
        }

        /// Removes all predicate matches with one consolidated event.
        @Override
        public boolean removeIf(Predicate<? super Entry<K, V>> predicate) {
            return removeMatching(predicate);
        }

        /// Clears the backing map with one clear event.
        @Override
        public void clear() {
            ObservableLinkedHashMap.this.clear();
        }
    }

    /// Mutable key-set view backed by the observable map.
    @NotNullByDefault
    private final class KeySetView extends AbstractSet<K> {
        /// Returns an iterator whose removal is observable.
        @Override
        public Iterator<K> iterator() {
            return new KeyIterator(entries.entrySet().iterator());
        }

        /// Returns the number of keys.
        @Override
        public int size() {
            return entries.size();
        }

        /// Returns whether an equal key exists.
        @Override
        public boolean contains(@Nullable Object candidate) {
            return entries.containsKey(candidate);
        }

        /// Removes an equal key when present.
        @Override
        public boolean remove(@Nullable Object candidate) {
            if (!entries.containsKey(candidate)) {
                return false;
            }
            ObservableLinkedHashMap.this.remove(candidate);
            return true;
        }

        /// Removes all keys equal to a candidate with one consolidated event.
        @Override
        public boolean removeAll(Collection<? extends @Nullable Object> candidates) {
            Objects.requireNonNull(candidates, "candidates");
            return removeMatching(entry -> candidates.contains(entry.getKey()));
        }

        /// Retains only keys equal to at least one candidate with one consolidated event.
        @Override
        public boolean retainAll(Collection<? extends @Nullable Object> candidates) {
            Objects.requireNonNull(candidates, "candidates");
            return removeMatching(entry -> !candidates.contains(entry.getKey()));
        }

        /// Removes all predicate-matching keys with one consolidated event.
        @Override
        public boolean removeIf(Predicate<? super K> predicate) {
            Objects.requireNonNull(predicate, "predicate");
            return removeMatching(entry -> predicate.test(entry.getKey()));
        }

        /// Clears the backing map with one clear event.
        @Override
        public void clear() {
            ObservableLinkedHashMap.this.clear();
        }
    }

    /// Mutable values view backed by the observable map.
    @NotNullByDefault
    private final class ValuesView extends AbstractCollection<V> {
        /// Returns an iterator whose removal is observable.
        @Override
        public Iterator<V> iterator() {
            return new ValueIterator(entries.entrySet().iterator());
        }

        /// Returns the number of values.
        @Override
        public int size() {
            return entries.size();
        }

        /// Returns whether an equal value exists.
        @Override
        public boolean contains(@Nullable Object candidate) {
            return entries.containsValue(candidate);
        }

        /// Removes the first mapping with an equal value.
        @Override
        public boolean remove(@Nullable Object candidate) {
            Iterator<Entry<K, V>> iterator = entries.entrySet().iterator();
            while (iterator.hasNext()) {
                Entry<K, V> entry = iterator.next();
                if (Objects.equals(entry.getValue(), candidate)) {
                    K key = entry.getKey();
                    V value = entry.getValue();
                    iterator.remove();
                    fireRemoved(key, value);
                    return true;
                }
            }
            return false;
        }

        /// Removes all values equal to a candidate with one consolidated event.
        @Override
        public boolean removeAll(Collection<? extends @Nullable Object> candidates) {
            Objects.requireNonNull(candidates, "candidates");
            return removeMatching(entry -> candidates.contains(entry.getValue()));
        }

        /// Retains only values equal to at least one candidate with one consolidated event.
        @Override
        public boolean retainAll(Collection<? extends @Nullable Object> candidates) {
            Objects.requireNonNull(candidates, "candidates");
            return removeMatching(entry -> !candidates.contains(entry.getValue()));
        }

        /// Removes all predicate-matching values with one consolidated event.
        @Override
        public boolean removeIf(Predicate<? super V> predicate) {
            Objects.requireNonNull(predicate, "predicate");
            return removeMatching(entry -> predicate.test(entry.getValue()));
        }

        /// Clears the backing map with one clear event.
        @Override
        public void clear() {
            ObservableLinkedHashMap.this.clear();
        }
    }

    /// Iterator over observable map entries.
    @NotNullByDefault
    private final class EntryIterator implements Iterator<Entry<K, V>> {
        /// Iterator over backing entries.
        private final Iterator<Entry<K, V>> delegate;

        /// Most recently returned backing entry, or null when removal is unavailable.
        private @Nullable Entry<K, V> current;

        /// Creates an observable entry iterator.
        private EntryIterator(Iterator<Entry<K, V>> delegate) {
            this.delegate = Objects.requireNonNull(delegate, "delegate");
        }

        /// Returns whether another mapping is available.
        @Override
        public boolean hasNext() {
            return delegate.hasNext();
        }

        /// Returns the next observable mutable entry.
        @Override
        public Entry<K, V> next() {
            Entry<K, V> entry = delegate.next();
            current = entry;
            return new ObservableEntry(entry);
        }

        /// Removes the current mapping and emits one removal event.
        @Override
        public void remove() {
            @Nullable Entry<K, V> entry = current;
            if (entry == null) {
                throw new IllegalStateException("next() must be called once before remove()");
            }
            K key = entry.getKey();
            V value = entry.getValue();
            delegate.remove();
            current = null;
            fireRemoved(key, value);
        }
    }

    /// Iterator over observable map keys.
    @NotNullByDefault
    private final class KeyIterator implements Iterator<K> {
        /// Iterator over backing entries.
        private final Iterator<Entry<K, V>> delegate;

        /// Most recently returned backing entry, or null when removal is unavailable.
        private @Nullable Entry<K, V> current;

        /// Creates an observable key iterator.
        private KeyIterator(Iterator<Entry<K, V>> delegate) {
            this.delegate = Objects.requireNonNull(delegate, "delegate");
        }

        /// Returns whether another key is available.
        @Override
        public boolean hasNext() {
            return delegate.hasNext();
        }

        /// Returns the next key.
        @Override
        public K next() {
            Entry<K, V> entry = delegate.next();
            current = entry;
            return entry.getKey();
        }

        /// Removes the current mapping and emits one removal event.
        @Override
        public void remove() {
            @Nullable Entry<K, V> entry = current;
            if (entry == null) {
                throw new IllegalStateException("next() must be called once before remove()");
            }
            K key = entry.getKey();
            V value = entry.getValue();
            delegate.remove();
            current = null;
            fireRemoved(key, value);
        }
    }

    /// Iterator over observable map values.
    @NotNullByDefault
    private final class ValueIterator implements Iterator<V> {
        /// Iterator over backing entries.
        private final Iterator<Entry<K, V>> delegate;

        /// Most recently returned backing entry, or null when removal is unavailable.
        private @Nullable Entry<K, V> current;

        /// Creates an observable value iterator.
        private ValueIterator(Iterator<Entry<K, V>> delegate) {
            this.delegate = Objects.requireNonNull(delegate, "delegate");
        }

        /// Returns whether another value is available.
        @Override
        public boolean hasNext() {
            return delegate.hasNext();
        }

        /// Returns the next value.
        @Override
        public V next() {
            Entry<K, V> entry = delegate.next();
            current = entry;
            return entry.getValue();
        }

        /// Removes the current mapping and emits one removal event.
        @Override
        public void remove() {
            @Nullable Entry<K, V> entry = current;
            if (entry == null) {
                throw new IllegalStateException("next() must be called once before remove()");
            }
            K key = entry.getKey();
            V value = entry.getValue();
            delegate.remove();
            current = null;
            fireRemoved(key, value);
        }
    }

    /// Mutable entry adapter that reports value replacement.
    @NotNullByDefault
    private final class ObservableEntry implements Entry<K, V> {
        /// Backing map entry represented by this adapter.
        private final Entry<K, V> delegate;

        /// Creates an observable entry adapter.
        private ObservableEntry(Entry<K, V> delegate) {
            this.delegate = Objects.requireNonNull(delegate, "delegate");
        }

        /// Returns the mapping key.
        @Override
        public K getKey() {
            return delegate.getKey();
        }

        /// Returns the current mapped value.
        @Override
        public V getValue() {
            return delegate.getValue();
        }

        /// Replaces the mapped value and emits one put event unless it is equal.
        @Override
        public V setValue(V value) {
            Objects.requireNonNull(value, "value");
            K key = delegate.getKey();
            if (!entries.containsKey(key)) {
                throw new IllegalStateException("The entry is no longer present in this map");
            }
            V previous = delegate.getValue();
            if (Objects.equals(previous, value)) {
                return previous;
            }

            delegate.setValue(value);
            changeSupport.fire(new MapChange<>(thisSource(), MapChange.Kind.PUT, Map.of(key, previous), Map.of(key, value)));
            return previous;
        }

        /// Returns whether another object represents the same key and value.
        @Override
        public boolean equals(@Nullable Object candidate) {
            if (!(candidate instanceof Entry<?, ?> entry)) {
                return false;
            }
            return Objects.equals(getKey(), entry.getKey()) && Objects.equals(getValue(), entry.getValue());
        }

        /// Returns the standard map-entry hash code.
        @Override
        public int hashCode() {
            return Objects.hashCode(getKey()) ^ Objects.hashCode(getValue());
        }

        /// Returns the standard key-value entry representation.
        @Override
        public String toString() {
            return getKey() + "=" + getValue();
        }

        /// Returns the enclosing observable map with its precise generic type.
        private ObservableLinkedHashMap<K, V> thisSource() {
            return ObservableLinkedHashMap.this;
        }
    }
}
