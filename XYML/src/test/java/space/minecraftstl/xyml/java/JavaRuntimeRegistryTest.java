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
package space.minecraftstl.xyml.java;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import space.minecraftstl.xyml.observable.Subscription;
import space.minecraftstl.xyml.util.platform.Platform;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies toolkit-neutral Java-runtime initialization, publication, and refresh concurrency semantics.
@NotNullByDefault
class JavaRuntimeRegistryTest {
    /// Publishes one sorted immutable snapshot after initialization, including an initialized empty result.
    @Test
    void initializationPublishesSortedImmutableSnapshot() throws Exception {
        JavaRuntimeRegistry registry = new JavaRuntimeRegistry();
        JavaRuntime java21 = runtime("C:/java/21/bin/java.exe", "21.0.2", false);
        JavaRuntime managed17 = runtime("C:/java/managed-17/bin/java.exe", "17.0.12", true);
        JavaRuntime java8 = runtime("C:/java/8/bin/java.exe", "1.8.0_422", false);
        List<JavaRuntimeSnapshot> changes = new ArrayList<>();
        try (Subscription ignored = registry.snapshotProperty().subscribe(change -> {
            @Nullable JavaRuntimeSnapshot snapshot = change.currentValue();
            if (snapshot != null) {
                changes.add(snapshot);
            }
        })) {
            JavaRuntimeSnapshot loading = registry.snapshotProperty().getValue();
            assertFalse(loading.isInitialized());
            assertTrue(loading.getRuntimes().isEmpty());

            registry.initialize(Map.of(
                    java21.getBinary(), java21,
                    managed17.getBinary(), managed17,
                    java8.getBinary(), java8));

            assertTrue(registry.isInitialized());
            assertEquals(List.of(managed17, java8, java21), registry.awaitRuntimes());
            assertEquals(1, changes.size());
            JavaRuntimeSnapshot initialized = changes.get(0);
            assertTrue(initialized.isInitialized());
            assertEquals(1L, initialized.getRevision());
            assertThrows(UnsupportedOperationException.class, () -> initialized.getRuntimes().add(java8));
        }

        JavaRuntimeRegistry emptyRegistry = new JavaRuntimeRegistry();
        emptyRegistry.initialize(Map.of());
        assertTrue(emptyRegistry.snapshotProperty().getValue().isInitialized());
        assertTrue(emptyRegistry.awaitRuntimes().isEmpty());
    }

    /// Publishes distinct additions and removals, then stops delivery through the returned subscription.
    @Test
    void subscriptionCanBeRemovedAfterRuntimeUpdates() throws Exception {
        JavaRuntimeRegistry registry = new JavaRuntimeRegistry();
        registry.initialize(Map.of());
        JavaRuntime java17 = runtime("C:/java/17/bin/java.exe", "17.0.12", false);
        AtomicInteger changes = new AtomicInteger();
        Subscription subscription = registry.snapshotProperty().subscribe(ignored -> changes.incrementAndGet());

        assertTrue(registry.add(java17));
        assertFalse(registry.add(java17));
        assertEquals(1, changes.get());
        assertEquals(java17, registry.awaitRuntime(java17.getBinary()));

        subscription.unsubscribe();
        assertTrue(registry.remove(java17.getBinary()));
        assertFalse(registry.remove(java17.getBinary()));
        assertEquals(1, changes.get());
        assertTrue(registry.awaitRuntimes().isEmpty());
    }

    /// Replays explicit mutations over a refresh result so a concurrent scan cannot roll them back.
    @Test
    void refreshReplaysConcurrentAdditionsAndRemovals() throws Exception {
        JavaRuntimeRegistry registry = new JavaRuntimeRegistry();
        JavaRuntime removed = runtime("C:/java/removed/bin/java.exe", "8", false);
        JavaRuntime old = runtime("C:/java/old/bin/java.exe", "11", false);
        JavaRuntime added = runtime("C:/java/added/bin/java.exe", "17", false);
        JavaRuntime discovered = runtime("C:/java/discovered/bin/java.exe", "21", false);
        registry.initialize(Map.of(removed.getBinary(), removed, old.getBinary(), old));

        JavaRuntimeRegistry.RefreshTicket refresh = registry.beginRefresh();
        assertTrue(registry.remove(removed.getBinary()));
        assertTrue(registry.add(added));

        assertTrue(registry.completeRefresh(refresh, Map.of(
                removed.getBinary(), removed,
                discovered.getBinary(), discovered)));
        assertEquals(List.of(added, discovered), registry.awaitRuntimes());
    }

    /// Lets only the newest overlapping refresh commit its scan result.
    @Test
    void staleRefreshCannotOverwriteNewerRefresh() throws Exception {
        JavaRuntimeRegistry registry = new JavaRuntimeRegistry();
        JavaRuntime initial = runtime("C:/java/initial/bin/java.exe", "8", false);
        JavaRuntime stale = runtime("C:/java/stale/bin/java.exe", "11", false);
        JavaRuntime current = runtime("C:/java/current/bin/java.exe", "17", false);
        registry.initialize(Map.of(initial.getBinary(), initial));

        JavaRuntimeRegistry.RefreshTicket first = registry.beginRefresh();
        JavaRuntimeRegistry.RefreshTicket second = registry.beginRefresh();

        assertFalse(registry.completeRefresh(first, Map.of(stale.getBinary(), stale)));
        assertTrue(registry.completeRefresh(second, Map.of(current.getBinary(), current)));
        assertEquals(List.of(current), registry.awaitRuntimes());
        assertFalse(registry.completeRefresh(first, Map.of(stale.getBinary(), stale)));
        assertEquals(List.of(current), registry.awaitRuntimes());
    }

    /// Creates one deterministic Java-runtime fixture.
    ///
    /// @param binary executable path
    /// @param version Java version text
    /// @param managed whether the launcher manages this runtime
    /// @return runtime fixture
    private static JavaRuntime runtime(String binary, String version, boolean managed) {
        return new JavaRuntime(
                Path.of(binary),
                new JavaInfo(Platform.WINDOWS_X86_64, version, "Test Vendor"),
                managed,
                true);
    }
}
