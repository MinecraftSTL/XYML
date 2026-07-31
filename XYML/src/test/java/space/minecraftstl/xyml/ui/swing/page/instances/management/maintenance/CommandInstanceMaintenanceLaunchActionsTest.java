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
package space.minecraftstl.xyml.ui.swing.page.instances.management.maintenance;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import space.minecraftstl.xyml.game.launch.LaunchRequest;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;

import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies that maintenance launch actions preserve stable identities and distinguish test mode.
@NotNullByDefault
final class CommandInstanceMaintenanceLaunchActionsTest {
    /// Marks only process launch as test mode while preserving every captured stable identifier.
    @Test
    void testLaunchUsesExplicitTestModeRequest() {
        LaunchRequest ordinary = new LaunchRequest("account", "directory", "instance");
        AtomicReference<@Nullable LaunchRequest> launched = new AtomicReference<>();
        IllegalStateException sentinel = new IllegalStateException("stop after capture");
        CommandInstanceMaintenanceLaunchActions actions = new CommandInstanceMaintenanceLaunchActions(
                () -> ordinary,
                request -> {
                    launched.set(request);
                    throw sentinel;
                },
                (request, scriptFile) -> CompletableFuture.completedFuture(scriptFile));

        AtomicReference<@Nullable Throwable> thrown = new AtomicReference<>();
        EdtDispatcher.executeAndWait(() -> thrown.set(assertThrows(
                IllegalStateException.class,
                actions::testLaunch)));

        assertSame(sentinel, thrown.get());
        LaunchRequest request = Objects.requireNonNull(launched.get());
        assertEquals(ordinary.accountId(), request.accountId());
        assertEquals(ordinary.gameDirectoryId(), request.gameDirectoryId());
        assertEquals(ordinary.instanceId(), request.instanceId());
        assertTrue(request.testMode());
    }

    /// Keeps script generation on the ordinary request path and returns the exact command completion.
    @Test
    void scriptExportUsesOrdinaryRequestAndExactDestination() {
        LaunchRequest ordinary = new LaunchRequest("account", "directory", "instance");
        Path destination = Path.of("build", "maintenance-test", "launch.ps1").toAbsolutePath().normalize();
        AtomicReference<@Nullable LaunchRequest> exportedRequest = new AtomicReference<>();
        AtomicReference<@Nullable Path> exportedPath = new AtomicReference<>();
        CommandInstanceMaintenanceLaunchActions actions = new CommandInstanceMaintenanceLaunchActions(
                () -> ordinary,
                request -> {
                    throw new AssertionError("test launch should not run");
                },
                (request, scriptFile) -> {
                    exportedRequest.set(request);
                    exportedPath.set(scriptFile);
                    return CompletableFuture.completedFuture(scriptFile);
                });

        AtomicReference<@Nullable Path> result = new AtomicReference<>();
        EdtDispatcher.executeAndWait(() -> result.set(
                actions.exportLaunchScript(destination).toCompletableFuture().join()));

        assertSame(ordinary, exportedRequest.get());
        assertEquals(destination, exportedPath.get());
        assertEquals(destination, result.get());
        assertFalse(ordinary.testMode());
    }
}
