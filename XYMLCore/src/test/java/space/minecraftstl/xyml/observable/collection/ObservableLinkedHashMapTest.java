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
import org.junit.jupiter.api.Test;
import space.minecraftstl.xyml.observable.Subscription;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests ordered mappings, old/new event values, observable views, cancellation, and null policy.
@NotNullByDefault
public final class ObservableLinkedHashMapTest {
    /// Verifies one-key insertion and replacement report absent and previous values without null sentinels.
    @Test
    public void reportsOldAndNewMappingsInKeyOrder() {
        ObservableLinkedHashMap<String, Integer> map = new ObservableLinkedHashMap<>();
        List<MapChange<String, Integer>> changes = new ArrayList<>();
        map.subscribe(changes::add);

        assertNull(map.put("first", 1));
        assertEquals(1, map.put("first", 2));
        assertEquals(2, map.put("first", 2));

        assertEquals(2, changes.size());
        MapChange<String, Integer> addition = changes.get(0);
        assertSame(map, addition.source());
        assertEquals(MapChange.Kind.PUT, addition.kind());
        assertEquals(List.of("first"), List.copyOf(addition.affectedKeys()));
        assertEquals(Map.of(), addition.previousEntries());
        assertEquals(Map.of("first", 1), addition.currentEntries());

        MapChange<String, Integer> replacement = changes.get(1);
        assertEquals(Map.of("first", 1), replacement.previousEntries());
        assertEquals(Map.of("first", 2), replacement.currentEntries());
        assertThrows(UnsupportedOperationException.class, () -> replacement.currentEntries().put("forbidden", 3));
    }

    /// Verifies replacement and removal events retain the actual stored key rather than an equal query object.
    @Test
    public void reportsStoredKeyIdentityForEqualKeyOperations() {
        String storedKey = new String("key");
        String equalKey = new String("key");
        ObservableLinkedHashMap<String, Integer> map = new ObservableLinkedHashMap<>();
        List<MapChange<String, Integer>> changes = new ArrayList<>();
        map.put(storedKey, 1);
        map.subscribe(changes::add);

        map.put(equalKey, 2);
        assertSame(storedKey, changes.get(0).affectedKeys().iterator().next());

        map.remove(equalKey);
        assertSame(storedKey, changes.get(1).affectedKeys().iterator().next());
    }

    /// Verifies put-all validates and publishes only logically changed mappings as one event.
    @Test
    public void batchesPutAllAndPreservesInsertionOrder() {
        LinkedHashMap<String, Integer> initial = new LinkedHashMap<>();
        initial.put("first", 1);
        initial.put("second", 2);
        ObservableLinkedHashMap<String, Integer> map = new ObservableLinkedHashMap<>(initial);
        List<MapChange<String, Integer>> changes = new ArrayList<>();
        map.subscribe(changes::add);

        LinkedHashMap<String, Integer> additions = new LinkedHashMap<>();
        additions.put("second", 2);
        additions.put("third", 3);
        additions.put("first", 4);
        map.putAll(additions);

        assertIterableEquals(List.of("first", "second", "third"), map.keySet());
        assertEquals(1, changes.size());
        MapChange<String, Integer> change = changes.get(0);
        assertEquals(MapChange.Kind.BATCH, change.kind());
        assertIterableEquals(List.of("third", "first"), change.affectedKeys());
        assertEquals(Map.of("first", 1), change.previousEntries());
        assertIterableEquals(List.of("third", "first"), change.currentEntries().keySet());
        assertEquals(Map.of("third", 3, "first", 4), change.currentEntries());
    }

    /// Verifies entry replacement and bulk mutations through map views remain observable.
    @Test
    public void observesEntryAndBulkViewMutations() {
        LinkedHashMap<String, Integer> initial = new LinkedHashMap<>();
        initial.put("a", 1);
        initial.put("b", 2);
        initial.put("c", 1);
        ObservableLinkedHashMap<String, Integer> map = new ObservableLinkedHashMap<>(initial);
        List<MapChange<String, Integer>> changes = new ArrayList<>();
        map.subscribe(changes::add);

        Map.Entry<String, Integer> firstEntry = map.entrySet().iterator().next();
        assertEquals(1, firstEntry.setValue(5));
        assertTrue(map.keySet().removeAll(List.of("a", "c")));
        assertTrue(map.values().removeAll(List.of(2)));

        assertTrue(map.isEmpty());
        assertEquals(3, changes.size());
        assertEquals(MapChange.Kind.PUT, changes.get(0).kind());
        assertEquals(Map.of("a", 1), changes.get(0).previousEntries());
        assertEquals(Map.of("a", 5), changes.get(0).currentEntries());
        assertIterableEquals(List.of("a", "c"), changes.get(1).previousEntries().keySet());
        assertEquals(Map.of("b", 2), changes.get(2).previousEntries());
    }

    /// Verifies cancellation, equal writes, and empty operations suppress notification.
    @Test
    public void supportsCancellationAndSuppressesNoOps() {
        ObservableLinkedHashMap<String, Integer> map = new ObservableLinkedHashMap<>();
        List<MapChange<String, Integer>> changes = new ArrayList<>();
        Subscription subscription = map.subscribe(changes::add);

        map.putAll(Map.of());
        map.clear();
        map.put("key", 1);
        map.put("key", 1);
        subscription.unsubscribe();
        map.put("later", 2);

        assertEquals(1, changes.size());
    }

    /// Verifies copied ownership and null-key/value rejection without partial batch changes.
    @Test
    public void ownsStorageAndRejectsNullWrites() {
        LinkedHashMap<String, Integer> source = new LinkedHashMap<>();
        source.put("kept", 1);
        ObservableLinkedHashMap<String, Integer> map = new ObservableLinkedHashMap<>(source);
        source.put("external", 2);

        assertIterableEquals(List.of("kept"), map.keySet());
        assertFalse(map.containsKey(null));
        assertNull(map.get(null));
        assertNull(map.remove(null));
        assertThrows(NullPointerException.class, () -> map.put(null, 2));
        assertThrows(NullPointerException.class, () -> map.put("null-value", null));

        LinkedHashMap<String, Integer> invalid = new LinkedHashMap<>();
        invalid.put("replacement", 2);
        invalid.put("invalid", null);
        assertThrows(NullPointerException.class, () -> map.putAll(invalid));
        assertEquals(Map.of("kept", 1), map);
    }
}
