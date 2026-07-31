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
package space.minecraftstl.xyml.observable;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests strongly typed value-change publication and cancellation.
@NotNullByDefault
public final class ValueChangeSupportTest {
    /// Verifies nullable transitions, equal-value suppression, and explicit unsubscription.
    @Test
    public void publishesDistinctValuesUntilUnsubscribed() {
        Object source = new Object();
        ValueChangeSupport<String> support = new ValueChangeSupport<>(source);
        List<ValueChange<String>> changes = new ArrayList<>();
        Subscription subscription = support.subscribe(changes::add);

        assertTrue(support.hasSubscribers());
        assertFalse(support.fireChange(null, null));
        assertFalse(support.fireChange("same", "same"));
        assertTrue(support.fireChange(null, "ready"));

        assertEquals(1, changes.size());
        assertSame(source, changes.get(0).source());
        assertNull(changes.get(0).previousValue());
        assertEquals("ready", changes.get(0).currentValue());

        subscription.unsubscribe();

        assertFalse(support.hasSubscribers());
        assertTrue(support.fireChange("ready", "done"));
        assertEquals(1, changes.size());
    }

    /// Verifies that duplicate listener objects create independently cancellable registrations.
    @Test
    public void cancelsDuplicateRegistrationsIndependently() {
        ValueChangeSupport<Integer> support = new ValueChangeSupport<>(this);
        List<ValueChange<Integer>> changes = new ArrayList<>();
        ValueChangeListener<Integer> listener = changes::add;
        Subscription first = support.subscribe(listener);
        Subscription second = support.subscribe(listener);

        first.unsubscribe();
        support.fireChange(1, 2);

        assertEquals(1, changes.size());
        assertTrue(second.isSubscribed());

        second.unsubscribe();
        assertFalse(support.hasSubscribers());
    }
}
