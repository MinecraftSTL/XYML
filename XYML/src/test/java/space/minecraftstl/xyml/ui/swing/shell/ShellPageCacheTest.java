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
package space.minecraftstl.xyml.ui.swing.shell;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests lazy page creation and complete-factory validation without Swing.
@NotNullByDefault
public final class ShellPageCacheTest {
    /// A page factory runs once and the same value is reused after navigation away and back.
    @Test
    public void createsEachPageAtMostOnce() {
        AtomicInteger instanceCreations = new AtomicInteger();
        EnumMap<ShellPageId, ShellPageFactory<? extends Object>> factories = completeFactories();
        Object instancesPage = new Object();
        factories.put(ShellPageId.INSTANCES, () -> {
            instanceCreations.incrementAndGet();
            return instancesPage;
        });
        ShellPageCache<Object> cache = new ShellPageCache<>(factories);

        Object first = cache.getOrCreate(ShellPageId.INSTANCES);
        cache.getOrCreate(ShellPageId.SETTINGS);
        Object second = cache.getOrCreate(ShellPageId.INSTANCES);

        assertSame(instancesPage, first);
        assertSame(first, second);
        assertEquals(1, instanceCreations.get());
        assertEquals(2, cache.cachedPageCount());
        assertTrue(cache.isCached(ShellPageId.INSTANCES));
        assertFalse(cache.isCached(ShellPageId.ACCOUNTS));
    }

    /// Construction rejects a factory set that cannot serve every navigation destination.
    @Test
    public void rejectsMissingFactory() {
        Map<ShellPageId, ShellPageFactory<Object>> incomplete = Map.of(
                ShellPageId.INSTANCES, Object::new);

        assertThrows(IllegalArgumentException.class, () -> new ShellPageCache<>(incomplete));
    }

    /// Closing releases only created auto-closeable pages once and rejects later creation.
    @Test
    public void closesCreatedPagesExactlyOnce() {
        EnumMap<ShellPageId, ShellPageFactory<? extends CloseableValue>> factories =
                new EnumMap<>(ShellPageId.class);
        AtomicInteger closes = new AtomicInteger();
        for (ShellPageId page : ShellPageId.values()) {
            factories.put(page, () -> new CloseableValue(closes));
        }
        ShellPageCache<CloseableValue> cache = new ShellPageCache<>(factories);
        cache.getOrCreate(ShellPageId.INSTANCES);
        cache.getOrCreate(ShellPageId.DOWNLOADS);

        cache.close();
        cache.close();

        assertEquals(2, closes.get());
        assertEquals(0, cache.cachedPageCount());
        assertThrows(IllegalStateException.class, () -> cache.getOrCreate(ShellPageId.SETTINGS));
    }

    /// Creates a complete generic factory set for focused cache tests.
    ///
    /// @return one simple factory for every destination
    private static EnumMap<ShellPageId, ShellPageFactory<? extends Object>> completeFactories() {
        EnumMap<ShellPageId, ShellPageFactory<? extends Object>> factories =
                new EnumMap<>(ShellPageId.class);
        for (ShellPageId page : ShellPageId.values()) {
            factories.put(page, Object::new);
        }
        return factories;
    }

    /// Closeable generic cache value with an externally visible close count.
    @NotNullByDefault
    private static final class CloseableValue implements AutoCloseable {
        /// Counter incremented by the first cache close.
        private final AtomicInteger closes;

        /// Creates a closeable test value.
        ///
        /// @param closes shared close counter
        private CloseableValue(AtomicInteger closes) {
            this.closes = closes;
        }

        /// Records one close invocation.
        @Override
        public void close() {
            closes.incrementAndGet();
        }
    }
}
