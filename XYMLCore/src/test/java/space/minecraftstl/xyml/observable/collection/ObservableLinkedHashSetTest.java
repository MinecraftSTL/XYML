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
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests insertion order, actual membership deltas, batching, cancellation, and null policy.
@NotNullByDefault
public final class ObservableLinkedHashSetTest {
    /// Verifies additions retain encounter order and report only newly present elements.
    @Test
    public void preservesOrderAndReportsActualAdditions() {
        ObservableLinkedHashSet<String> set = new ObservableLinkedHashSet<>(List.of("first"));
        List<SetChange<String>> changes = new ArrayList<>();
        set.subscribe(changes::add);

        assertTrue(set.addAll(List.of("second", "first", "third")));

        assertIterableEquals(List.of("first", "second", "third"), set);
        assertEquals(1, changes.size());
        SetChange<String> change = changes.get(0);
        assertSame(set, change.source());
        assertEquals(SetChange.Kind.ADD, change.kind());
        assertEquals(Set.of(), change.removedElements());
        assertIterableEquals(List.of("second", "third"), change.addedElements());
        assertThrows(UnsupportedOperationException.class, () -> change.addedElements().add("forbidden"));
    }

    /// Verifies bulk and iterator removals report former insertion order without event storms.
    @Test
    public void batchesBulkRemovalAndReportsIteratorRemoval() {
        ObservableLinkedHashSet<String> set = new ObservableLinkedHashSet<>(List.of("a", "b", "c", "d"));
        List<SetChange<String>> changes = new ArrayList<>();
        set.subscribe(changes::add);

        assertTrue(set.removeAll(List.of("b", "d")));
        Iterator<String> iterator = set.iterator();
        assertEquals("a", iterator.next());
        iterator.remove();

        assertIterableEquals(List.of("c"), set);
        assertEquals(2, changes.size());
        assertEquals(SetChange.Kind.REMOVE, changes.get(0).kind());
        assertIterableEquals(List.of("b", "d"), changes.get(0).removedElements());
        assertEquals(Set.of("a"), changes.get(1).removedElements());
    }

    /// Verifies cancellation and logically empty operations do not publish changes.
    @Test
    public void supportsCancellationAndSuppressesNoOps() {
        ObservableLinkedHashSet<String> set = new ObservableLinkedHashSet<>();
        List<SetChange<String>> changes = new ArrayList<>();
        Subscription subscription = set.subscribe(changes::add);

        assertFalse(set.addAll(List.of()));
        assertFalse(set.remove("absent"));
        set.clear();
        assertTrue(set.add("present"));
        assertFalse(set.add("present"));
        subscription.unsubscribe();
        assertTrue(set.add("later"));

        assertEquals(1, changes.size());
    }

    /// Verifies copied ownership and rejection of null elements before mutation.
    @Test
    public void ownsStorageAndRejectsNullWrites() {
        LinkedHashSet<String> source = new LinkedHashSet<>(List.of("kept"));
        ObservableLinkedHashSet<String> set = new ObservableLinkedHashSet<>(source);
        source.add("external");

        assertIterableEquals(List.of("kept"), set);
        assertFalse(set.contains(null));
        assertFalse(set.remove(null));
        assertThrows(NullPointerException.class, () -> set.add(null));
        assertThrows(NullPointerException.class, () -> set.addAll(Arrays.asList("replacement", null)));
        assertIterableEquals(List.of("kept"), set);
    }
}
