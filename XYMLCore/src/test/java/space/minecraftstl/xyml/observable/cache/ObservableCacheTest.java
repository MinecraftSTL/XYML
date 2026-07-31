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
package space.minecraftstl.xyml.observable.cache;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import space.minecraftstl.xyml.observable.Subscription;
import space.minecraftstl.xyml.observable.property.ObservableValue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/// Verifies cache loading, invalidation, quiet observation, and failure fallback without a UI toolkit.
@NotNullByDefault
final class ObservableCacheTest {
    /// A non-quiet binding loads immediately and refreshes after invalidation.
    @Test
    void autoRefreshesObservedValues() {
        AtomicInteger loads = new AtomicInteger();
        ObservableCache<String, String, RuntimeException> cache = new ObservableCache<>(
                key -> key + '-' + loads.incrementAndGet(),
                (key, failure) -> {
                    throw new AssertionError(failure);
                },
                "fallback",
                Runnable::run);

        ObservableValue<String> value = cache.binding("profile");
        List<String> observed = new ArrayList<>();
        Subscription subscription = value.subscribe(change -> observed.add(change.currentValue()));
        try {
            assertEquals("profile-1", value.getValue());

            cache.invalidate("profile");

            assertEquals("profile-2", value.getValue());
            assertEquals(List.of("profile-2"), observed);
            assertEquals(2, loads.get());
        } finally {
            subscription.unsubscribe();
        }
    }

    /// A quiet binding observes explicit commits but never initiates loading by itself.
    @Test
    void quietBindingDoesNotLoad() {
        AtomicInteger loads = new AtomicInteger();
        ObservableCache<String, String, RuntimeException> cache = new ObservableCache<>(
                key -> key + '-' + loads.incrementAndGet(),
                (key, failure) -> {
                    throw new AssertionError(failure);
                },
                "fallback",
                Runnable::run);
        ObservableValue<String> value = cache.binding("profile", true);
        List<String> observed = new ArrayList<>();
        Subscription subscription = value.subscribe(change -> observed.add(change.currentValue()));
        try {
            assertEquals("fallback", value.getValue());
            assertEquals(0, loads.get());

            cache.put("profile", "stored");
            cache.invalidate("profile");

            assertEquals("stored", value.getValue());
            assertEquals(List.of("stored"), observed);
            assertEquals(0, loads.get());
        } finally {
            subscription.unsubscribe();
        }
    }

    /// A failed automatic query preserves the fallback and reports the failure once.
    @Test
    void reportsFailureAndKeepsFallback() {
        List<String> failures = new ArrayList<>();
        ObservableCache<String, String, Exception> cache = new ObservableCache<>(
                key -> {
                    throw new Exception("unavailable");
                },
                (key, failure) -> failures.add(key + ':' + failure.getMessage()),
                "fallback",
                Runnable::run);

        ObservableValue<String> value = cache.binding("profile");

        assertEquals("fallback", value.getValue());
        assertEquals(List.of("profile:unavailable"), failures);
    }
}
