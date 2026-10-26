// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.setting;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import space.minecraftstl.xyml.download.ComponentRemoteVersionList;
import space.minecraftstl.xyml.game.GameComponentType;
import space.minecraftstl.xyml.task.Task;
import space.minecraftstl.xyml.task.TaskExecutor;
import space.minecraftstl.xyml.task.TaskResource;
import space.minecraftstl.xyml.util.CacheRepository;
import space.minecraftstl.xyml.util.versioning.GameVersionNumber;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies the launcher mirror-selection graph beneath the same retained resources as instance installation.
@NotNullByDefault
@Timeout(30)
public final class LauncherDownloadProviderResourceTest {
    /// Private root for HTTP cache files and the semantic repository resources.
    @TempDir
    private Path temporaryDirectory;

    /// Mirror selection executes only the successful primary catalog and then reuses the in-memory snapshot.
    ///
    /// @param type mirrored component whose actual launcher query graph is exercised
    /// @throws IOException if the loopback listener cannot be opened
    @ParameterizedTest
    @EnumSource(value = GameComponentType.class, names = {"FORGE", "NEO_FORGE", "OPTIFINE"})
    public void mirrorQueriesRetainPreciseResourcesAndCacheResults(GameComponentType type) throws IOException {
        CacheRepository previousCache = CacheRepository.getInstance();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger requests = new AtomicInteger();
        try {
            CacheRepository cache = new CacheRepository();
            cache.changeDirectory(temporaryDirectory.resolve("download-cache"));
            CacheRepository.setInstance(cache);
            server.createContext("/", exchange -> respond(exchange, requests));
            server.start();
            LauncherDownloadProvider provider = new LauncherDownloadProvider(
                    "http://127.0.0.1:" + server.getAddress().getPort());
            provider.setVersionListSource(DownloadSource.MIRROR);
            GameVersionNumber gameVersion = GameVersionNumber.asGameVersion("1.21.1");
            Task<ComponentRemoteVersionList<?>> first = provider.getVersionsAsync(type, gameVersion, false);
            executeNested(first);
            assertTrue(first.getResult().isEmpty());
            assertEquals(1, requests.get());

            Task<ComponentRemoteVersionList<?>> cached = provider.getVersionsAsync(type, gameVersion, false);
            executeNested(cached);
            assertSame(first.getResult(), cached.getResult());
            assertEquals(1, requests.get());
        } finally {
            server.stop(0);
            CacheRepository.setInstance(previousCache);
        }
    }

    /// Primary failure selects a resource-safe secondary query and publishes its result.
    ///
    /// @throws ReflectiveOperationException if the private coordinator cannot be constructed
    @Test
    public void fallbackFailureAndRecoveryRetainPreciseResources() throws ReflectiveOperationException {
        Task<String> failed = Task.<String>supplyAsync(() -> {
            throw new IOException("fixture primary failure");
        }).asOrchestration();
        Task<String> fallback = Task.supplyAsync(() -> "recovered").asOrchestration();
        Task<?> recovered = fallbackTask(failed, fallback);
        executeNested(recovered);
        assertEquals("recovered", recovered.getResult());

        Task<String> bothFailed = Task.<String>supplyAsync(() -> {
            throw new IOException("fixture fallback failure");
        }).asOrchestration();
        TaskExecutor executor = nested(fallbackTask(failed, bothFailed)).executor();
        assertFalse(executor.test());
        assertInstanceOf(IOException.class, executor.getFailure());
        executeNested(fallbackTask(failed, Task.supplyAsync(() -> "retried").asOrchestration()));
    }

    /// Constructs the actual private coordinator without broadening its production visibility or using external URLs.
    ///
    /// @param primary stopped primary query
    /// @param secondary stopped secondary query
    /// @return actual launcher fallback coordinator
    /// @throws ReflectiveOperationException if the private coordinator cannot be constructed
    private static Task<?> fallbackTask(Task<String> primary, Task<String> secondary)
            throws ReflectiveOperationException {
        Class<?> type = Class.forName(LauncherDownloadProvider.class.getName() + "$FallbackTask");
        Constructor<?> constructor = type.getDeclaredConstructor(Task.class, Task.class);
        constructor.setAccessible(true);
        return (Task<?>) constructor.newInstance(primary, secondary);
    }

    /// Runs a child catalog under retained repository coordination and one narrow instance directory.
    ///
    /// @param query stopped catalog query
    private void executeNested(Task<?> query) {
        TaskExecutor executor = nested(query).executor();
        assertTrue(executor.test(), () -> String.valueOf(executor.getFailure()));
    }

    /// Creates a parent retaining the mixed resource shape of an instance install.
    ///
    /// @param query stopped query or fallback coordinator
    /// @return stopped parent retaining its resources while the child executes
    private Task<?> nested(Task<?> query) {
        Path repository = temporaryDirectory.resolve("repository");
        return Task.composeAsync(() -> query).setResources(
                TaskResource.repositoryOperation(repository),
                TaskResource.gameInstance(repository.resolve("versions/example")));
    }

    /// Serves a valid empty mirror list while tracking whether fallback or cache-hit paths make extra requests.
    ///
    /// @param exchange callback-owned request closed before returning
    /// @param requests shared thread-safe request counter
    /// @throws IOException if the response cannot be written
    private static void respond(HttpExchange exchange, AtomicInteger requests) throws IOException {
        try (exchange) {
            requests.incrementAndGet();
            byte[] body = "[]".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
        }
    }
}
