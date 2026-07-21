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
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests ordered list state, immutable changes, batching, cancellation, and null policy.
@NotNullByDefault
public final class ObservableArrayListTest {
    /// Verifies contiguous edits preserve order and report precise affected ranges.
    @Test
    public void reportsContiguousChangesInListOrder() {
        ObservableArrayList<String> list = new ObservableArrayList<>(List.of("alpha", "charlie"));
        List<ListChange<String>> changes = new ArrayList<>();
        list.subscribe(changes::add);

        list.add(1, "bravo");
        list.removeRange(0, 2);

        assertIterableEquals(List.of("charlie"), list);
        assertEquals(2, changes.size());

        ListChange<String> addition = changes.get(0);
        assertSame(list, addition.source());
        assertEquals(ListChange.Kind.ADD, addition.kind());
        assertEquals(1, addition.fromIndex());
        assertEquals(2, addition.toIndex());
        assertEquals(List.of(), addition.previousItems());
        assertEquals(List.of("bravo"), addition.currentItems());

        ListChange<String> removal = changes.get(1);
        assertEquals(ListChange.Kind.REMOVE, removal.kind());
        assertEquals(0, removal.fromIndex());
        assertEquals(2, removal.toIndex());
        assertEquals(List.of("alpha", "bravo"), removal.previousItems());
        assertEquals(List.of(), removal.currentItems());
    }

    /// Verifies non-contiguous and complete replacements publish one semantic reset apiece.
    @Test
    public void batchesBulkReplacementAndSuppressesEqualReset() {
        ObservableArrayList<String> list = new ObservableArrayList<>(List.of("a", "b", "c", "d"));
        List<ListChange<String>> changes = new ArrayList<>();
        list.subscribe(changes::add);

        assertTrue(list.removeAll(List.of("b", "d")));
        assertFalse(list.setAll(List.of("a", "c")));
        assertTrue(list.setAll(List.of("d", "c", "b")));

        assertEquals(2, changes.size());
        ListChange<String> removal = changes.get(0);
        assertEquals(ListChange.Kind.RESET, removal.kind());
        assertEquals(0, removal.fromIndex());
        assertEquals(4, removal.toIndex());
        assertEquals(List.of("a", "b", "c", "d"), removal.previousItems());
        assertEquals(List.of("a", "c"), removal.currentItems());

        ListChange<String> replacement = changes.get(1);
        assertEquals(List.of("a", "c"), replacement.previousItems());
        assertEquals(List.of("d", "c", "b"), replacement.currentItems());
        assertIterableEquals(List.of("d", "c", "b"), list);
    }

    /// Verifies sort and replace-all avoid per-element event storms.
    @Test
    public void publishesOneEventForTransformingBatches() {
        ObservableArrayList<String> list = new ObservableArrayList<>(List.of("b", "a"));
        List<ListChange<String>> changes = new ArrayList<>();
        list.subscribe(changes::add);

        list.sort(null);
        list.replaceAll(String::toUpperCase);

        assertIterableEquals(List.of("A", "B"), list);
        assertEquals(2, changes.size());
        assertEquals(ListChange.Kind.RESET, changes.get(0).kind());
        assertEquals(ListChange.Kind.RESET, changes.get(1).kind());
    }

    /// Verifies cancellation, empty operations, and immutable event payloads.
    @Test
    public void supportsCancellationAndSuppressesEmptyOperations() {
        ObservableArrayList<String> list = new ObservableArrayList<>();
        List<ListChange<String>> changes = new ArrayList<>();
        Subscription subscription = list.subscribe(changes::add);

        assertFalse(list.addAll(List.of()));
        list.removeRange(0, 0);
        list.clear();
        list.add("first");
        subscription.unsubscribe();
        list.add("second");

        assertEquals(1, changes.size());
        assertThrows(UnsupportedOperationException.class, () -> changes.get(0).currentItems().add("forbidden"));
    }

    /// Verifies input is privately copied and null writes fail before changing list state.
    @Test
    public void ownsStorageAndRejectsNullWrites() {
        List<String> source = new ArrayList<>(List.of("kept"));
        ObservableArrayList<String> list = new ObservableArrayList<>(source);
        source.add("external");

        assertIterableEquals(List.of("kept"), list);
        assertFalse(list.contains(null));
        assertFalse(list.remove(null));
        assertThrows(NullPointerException.class, () -> list.add(null));
        assertThrows(NullPointerException.class, () -> list.setAll(Arrays.asList("replacement", null)));
        assertIterableEquals(List.of("kept"), list);
    }
}
