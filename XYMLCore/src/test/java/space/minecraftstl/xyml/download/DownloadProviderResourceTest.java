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
package space.minecraftstl.xyml.download;

import com.google.gson.JsonParseException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import space.minecraftstl.xyml.download.forge.ForgeRemoteVersion;
import space.minecraftstl.xyml.download.neoforge.NeoForgeRemoteVersion;
import space.minecraftstl.xyml.download.optifine.OptiFineRemoteVersion;
import space.minecraftstl.xyml.game.DefaultGameRepository;
import space.minecraftstl.xyml.game.GameComponentType;
import space.minecraftstl.xyml.game.GameInstanceID;
import space.minecraftstl.xyml.game.GameInstanceManifest;
import space.minecraftstl.xyml.task.Task;
import space.minecraftstl.xyml.task.TaskExecutor;
import space.minecraftstl.xyml.task.TaskResource;
import space.minecraftstl.xyml.util.CacheRepository;
import space.minecraftstl.xyml.util.DigestUtils;
import space.minecraftstl.xyml.util.versioning.GameVersionNumber;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Exercises real version queries and game installation beneath precise repository resources using loopback HTTP.
@NotNullByDefault
@Timeout(30)
public final class DownloadProviderResourceTest {
    /// Isolated root for the repository and HTTP download cache of each test invocation.
    @TempDir
    private Path temporaryDirectory;

    /// Process-wide cache restored after each test, or null before setup captures it.
    private @Nullable CacheRepository previousCache;

    /// Loopback HTTP fixture, or null before setup creates it.
    private @Nullable HttpServer server;

    /// Provider routing every external metadata request to the loopback fixture.
    private FixtureProvider provider;

    /// Counts version-list requests so cache hits and explicit refreshes are observable.
    private final AtomicInteger versionListRequests = new AtomicInteger();

    /// Makes the fixture return malformed version-list JSON until cleared by the test.
    private final AtomicBoolean brokenCatalog = new AtomicBoolean();

    /// Counts prohibited external file requests, including requests from optional install phases.
    private final AtomicInteger externalDownloads = new AtomicInteger();

    /// Makes the fixture return malformed game-version JSON until cleared by the test.
    private final AtomicBoolean brokenVersion = new AtomicBoolean();

    /// Configures a private cache and HTTP server without contacting external download services.
    ///
    /// @throws IOException if the loopback listener cannot be opened
    @BeforeEach
    public void setUp() throws IOException {
        previousCache = CacheRepository.getInstance();
        DefaultCacheRepository cache = new DefaultCacheRepository();
        cache.changeDirectory(temporaryDirectory.resolve("download-cache"));
        CacheRepository.setInstance(cache);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::respond);
        server.start();
        provider = new FixtureProvider("http://127.0.0.1:" + server.getAddress().getPort(), externalDownloads);
    }

    /// Stops the listener and restores the original cache even when a query fails.
    @AfterEach
    public void tearDown() {
        if (server != null) {
            server.stop(0);
        }
        if (previousCache != null) {
            CacheRepository.setInstance(previousCache);
        }
    }

    /// Every supported official catalog executes with an active repository-operation and instance ancestor.
    ///
    /// @param type component catalog to query with its real parser
    @ParameterizedTest
    @EnumSource(value = GameComponentType.class, names = "OPTIFINE", mode = EnumSource.Mode.EXCLUDE)
    public void officialCatalogsStayWithinPreciseAncestors(GameComponentType type) {
        @Nullable GameVersionNumber gameVersion = switch (type) {
            case GAME -> null;
            case CLEANROOM -> GameVersionNumber.asGameVersion("1.12.2");
            case LITELOADER -> GameVersionNumber.asGameVersion("1.7.10");
            default -> GameVersionNumber.asGameVersion("1.21.1");
        };
        Task<ComponentRemoteVersionList<?>> query = provider.getVersionsAsync(type, gameVersion, false);
        executeNested(query);
        assertNotNull(query.getResult());
    }

    /// Old NeoForge metadata uses the same precise resource contract as its modern catalog.
    @Test
    public void oldNeoForgeCatalogStaysWithinPreciseAncestors() {
        Task<ComponentRemoteVersionList<?>> query = provider.getVersionsAsync(
                GameComponentType.NEO_FORGE, GameVersionNumber.asGameVersion("1.20.1"), false);
        executeNested(query);
        assertEquals("47.1.0", query.getResult().get(0).getSelfVersion());
    }

    /// Mirror parsers can be invoked directly without expanding their ancestor's resource coverage.
    @Test
    public void mirrorCatalogsStayWithinPreciseAncestors() {
        GameVersionNumber gameVersion = GameVersionNumber.asGameVersion("1.21.1");
        executeNested(ForgeRemoteVersion.fetchBMCLAsync(provider, provider.baseUrl, gameVersion));
        executeNested(NeoForgeRemoteVersion.fetchBMCLAsync(provider.baseUrl, gameVersion));
        executeNested(OptiFineRemoteVersion.fetchBMCLAsync(provider, provider.baseUrl, gameVersion));
    }

    /// Cached lists avoid HTTP while explicit refresh replaces the in-memory snapshot with another parsed list.
    @Test
    public void cacheHitAndRefreshPreserveQuerySemantics() {
        Task<ComponentRemoteVersionList<?>> first = provider.getVersionsAsync(GameComponentType.GAME, null, false);
        executeNested(first);
        int initialRequests = versionListRequests.get();
        Task<ComponentRemoteVersionList<?>> cached = provider.getVersionsAsync(GameComponentType.GAME, null, false);
        executeNested(cached);
        assertSame(first.getResult(), cached.getResult());
        assertEquals(initialRequests, versionListRequests.get());

        Task<ComponentRemoteVersionList<?>> refreshed = provider.getVersionsAsync(GameComponentType.GAME, null, true);
        executeNested(refreshed);
        assertTrue(versionListRequests.get() > initialRequests);
        assertNotNull(refreshed.getResult().getRemoteVersion("1.21.1"));
    }

    /// A failed refresh propagates the parse failure and leaves the preceding cached snapshot available for recovery.
    @Test
    public void failedRefreshRetainsCacheAndAllowsRecovery() {
        Task<ComponentRemoteVersionList<?>> first = provider.getVersionsAsync(GameComponentType.GAME, null, false);
        executeNested(first);
        brokenCatalog.set(true);
        TaskExecutor failed = nested(provider.getVersionsAsync(GameComponentType.GAME, null, true)).executor();
        assertFalse(failed.test());
        assertInstanceOf(JsonParseException.class, failed.getFailure());
        Task<ComponentRemoteVersionList<?>> cached = provider.getVersionsAsync(GameComponentType.GAME, null, false);
        executeNested(cached);
        assertSame(first.getResult(), cached.getResult());

        brokenCatalog.set(false);
        executeNested(provider.getVersionsAsync(GameComponentType.GAME, null, true));
    }

    /// Custom fetch implementations retain their explicit write resources instead of being made lock-free by the cache.
    @Test
    public void cacheWrapperDoesNotReplaceCustomFetcherResources() {
        AtomicBoolean executed = new AtomicBoolean();
        DownloadProvider custom = new FilesystemProvider(temporaryDirectory.resolve("outside"), executed);
        TaskExecutor executor = nested(custom.getVersionsAsync(GameComponentType.GAME, null, false)).executor();
        assertFalse(executor.test());
        assertInstanceOf(IllegalStateException.class, executor.getFailure());
        assertTrue(Objects.requireNonNull(executor.getFailure()).getMessage().contains("ancestor coverage"));
        assertFalse(executed.get());
    }

    /// The real default builder installs two independent instances and publishes their manifests and checked JARs.
    ///
    /// @throws IOException if an installed artifact cannot be read
    @Test
    public void realBuilderInstallsColdAndCachedInstances() throws IOException {
        DefaultGameRepository repository = new DefaultGameRepository(temporaryDirectory.resolve("repository"));
        DefaultDependencyManager manager = new DefaultDependencyManager(
                repository, provider, (DefaultCacheRepository) CacheRepository.getInstance());
        installAndCheck(repository, manager, "first");
        installAndCheck(repository, manager, "second");
        assertTrue(repository.hasInstance(new GameInstanceID("first")));
        assertTrue(repository.hasInstance(new GameInstanceID("second")));
    }

    /// A failed real installation removes its new destination and a subsequent attempt can reuse the same name.
    ///
    /// @throws IOException if the successful retry's artifacts cannot be read
    @Test
    public void realBuilderCleansFailedInstallAndCanRetry() throws IOException {
        DefaultGameRepository repository = new DefaultGameRepository(temporaryDirectory.resolve("repository"));
        DefaultDependencyManager manager = new DefaultDependencyManager(
                repository, provider, (DefaultCacheRepository) CacheRepository.getInstance());
        GameInstanceID instanceId = new GameInstanceID("retry");
        brokenVersion.set(true);
        TaskExecutor failed = new DefaultGameBuilder(manager).name(instanceId).gameVersion("1.21.1")
                .buildAsync().executor();
        assertFalse(failed.test());
        assertInstanceOf(JsonParseException.class, failed.getFailure());
        assertFalse(repository.hasInstance(instanceId));
        assertFalse(Files.exists(repository.getInstanceRoot(instanceId)));

        brokenVersion.set(false);
        installAndCheck(repository, manager, instanceId.id());
    }

    /// Runs a real vanilla installation and checks both repository publication and the downloaded file bytes.
    ///
    /// @param repository destination repository
    /// @param manager actual download and install implementation
    /// @param name destination instance name
    /// @throws IOException if the installed JAR cannot be read
    private void installAndCheck(DefaultGameRepository repository, DefaultDependencyManager manager, String name)
            throws IOException {
        GameInstanceID instanceId = new GameInstanceID(name);
        TaskExecutor executor = new DefaultGameBuilder(manager).name(instanceId).gameVersion("1.21.1")
                .buildAsync().executor();
        assertTrue(executor.test(), () -> String.valueOf(executor.getFailure()));
        assertTrue(repository.hasInstance(instanceId));
        Path instanceRoot = repository.getInstanceRoot(instanceId);
        assertTrue(Files.isRegularFile(instanceRoot.resolve(name + ".json")));
        assertEquals("local-game-jar", Files.readString(instanceRoot.resolve(name + ".jar")));
        assertEquals(0, externalDownloads.get(), "Every installation request must remain on loopback HTTP");
        assertEquals("{\"objects\":{}}", Files.readString(
                repository.getIndexFile(instanceId, new GameInstanceManifest(instanceId).getAssetIndex().getId())));
    }

    /// Executes a stopped query beneath a deliberately mixed, incomplete filesystem boundary.
    ///
    /// @param task stopped query whose result and resource declarations remain intact
    private void executeNested(Task<?> task) {
        TaskExecutor executor = nested(task).executor();
        assertTrue(executor.test(), () -> String.valueOf(executor.getFailure()));
    }

    /// Retains repository coordination and one instance boundary while a dynamically returned child runs.
    ///
    /// @param task stopped child task
    /// @return stopped parent retaining the resource shape of a real install
    private Task<?> nested(Task<?> task) {
        Path repository = temporaryDirectory.resolve("repository");
        return Task.composeAsync(() -> task).setResources(
                TaskResource.repositoryOperation(repository),
                TaskResource.gameInstance(repository.resolve("versions/example")));
    }

    /// Serves deterministic fixture bytes and closes every exchange, including failure responses.
    ///
    /// @param exchange loopback request owned by this callback
    /// @throws IOException if the response cannot be written
    private void respond(HttpExchange exchange) throws IOException {
        try (exchange) {
            String body = switch (exchange.getRequestURI().getPath()) {
                case "/game" -> {
                    versionListRequests.incrementAndGet();
                    yield brokenCatalog.get() ? "not-json" : """
                            {"versions":[{"id":"1.21.1","type":"release","url":"%s/version",
                            "releaseTime":"2024-08-08T00:00:00Z"}]}
                            """.formatted(provider.baseUrl);
                }
                case "/version" -> brokenVersion.get() ? "not-json" : """
                        {"id":"1.21.1","mainClass":"fixture.Main","type":"release","libraries":[],
                        "arguments":{"game":[],"jvm":[]},
                        "downloads":{"client":{"url":"%s/client","sha1":"%s","size":14}},
                        "assetIndex":{"id":"fixture","url":"%s/assets","sha1":"%s","size":14}}
                        """.formatted(provider.baseUrl, DigestUtils.digestToString("SHA-1",
                                "local-game-jar".getBytes(StandardCharsets.UTF_8)), provider.baseUrl,
                                DigestUtils.digestToString("SHA-1", "{\"objects\":{}}".getBytes(StandardCharsets.UTF_8)));
                case "/client" -> "local-game-jar";
                case "/assets" -> "{\"objects\":{}}";
                case "/loader" -> "[{\"version\":\"0.16.0\"}]";
                case "/fabric-game" -> "[{\"version\":\"1.21.1\"}]";
                case "/forge" -> "{\"mcversion\":{},\"number\":{}}";
                case "/neoforge" -> "{\"isSnapshot\":false,\"versions\":[\"21.1.1\"]}";
                case "/old-neoforge" -> "{\"isSnapshot\":false,\"versions\":[\"1.20.1-47.1.0\"]}";
                case "/cleanroom" -> "[{\"name\":\"0.3.0-alpha\",\"created_at\":\"2024-01-01T00:00:00Z\"}]";
                default -> "[]";
            };
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
        }
    }

    /// Overrides only network destinations, leaving production fetch, parse, and cache behavior intact.
    @NotNullByDefault
    private static final class FixtureProvider extends DownloadProvider {
        /// Loopback fixture origin valid while the test owns its listener.
        private final String baseUrl;

        /// Caller-owned counter for unintended external downloads from optional install phases.
        private final AtomicInteger externalDownloads;

        /// Creates a provider bound to the already-started listener.
        ///
        /// @param baseUrl loopback HTTP origin
        /// @param externalDownloads caller-owned unexpected-request counter
        private FixtureProvider(String baseUrl, AtomicInteger externalDownloads) {
            this.baseUrl = baseUrl;
            this.externalDownloads = externalDownloads;
        }

        /// Returns the private game catalog endpoint.
        @Override
        public DownloadCandidates getGameVersionListCandidates() {
            return DownloadCandidates.of(baseUrl + "/game");
        }

        /// Returns separate loader and compatible-game metadata endpoints.
        @Override
        public FabricLikeVersionListCandidates getFabricVersionListCandidates() {
            return new FabricLikeVersionListCandidates(
                    DownloadCandidates.of(baseUrl + "/loader"), DownloadCandidates.of(baseUrl + "/fabric-game"));
        }

        /// Uses the same compatible metadata fixture for Legacy Fabric.
        @Override
        public FabricLikeVersionListCandidates getLegacyFabricVersionListCandidates() {
            return getFabricVersionListCandidates();
        }

        /// Uses the same compatible metadata fixture for Quilt.
        @Override
        public FabricLikeVersionListCandidates getQuiltVersionListCandidates() {
            return getFabricVersionListCandidates();
        }

        /// Returns the private official Forge catalog endpoint.
        @Override
        public DownloadCandidates getForgeVersionListCandidates() {
            return DownloadCandidates.of(baseUrl + "/forge");
        }

        /// Returns the private modern NeoForge catalog endpoint.
        @Override
        public DownloadCandidates getNeoForgeVersionListCandidates() {
            return DownloadCandidates.of(baseUrl + "/neoforge");
        }

        /// Returns the private Minecraft 1.20.1 NeoForge catalog endpoint.
        @Override
        public DownloadCandidates getNeoForgeOldVersionListCandidates() {
            return DownloadCandidates.of(baseUrl + "/old-neoforge");
        }

        /// Returns the private Cleanroom catalog endpoint.
        @Override
        public DownloadCandidates getCleanroomVersionListCandidates() {
            return DownloadCandidates.of(baseUrl + "/cleanroom");
        }

        /// Rejects unintended external file downloads while preserving the fixture's actual HTTP download tasks.
        @Override
        public DownloadCandidates getDownloadCandidates(String baseUrl) {
            // The current installer queries the unresolved manifest's legacy asset index during its optional phase.
            String legacyIndex = new GameInstanceManifest(new GameInstanceID("fixture")).getAssetIndex().getUrl();
            if (baseUrl.equals(legacyIndex)) {
                return DownloadCandidates.of(this.baseUrl + "/assets");
            }
            if (!baseUrl.startsWith(this.baseUrl + "/")) {
                externalDownloads.incrementAndGet();
                throw new IllegalArgumentException("Unexpected external fixture download");
            }
            return super.getDownloadCandidates(baseUrl);
        }

        /// Rejects unintended external file candidates while retaining loopback download behavior.
        @Override
        public DownloadCandidates getDownloadCandidates(List<String> urls) {
            for (String url : urls) {
                if (!url.startsWith(baseUrl + "/")) {
                    externalDownloads.incrementAndGet();
                    throw new IllegalArgumentException("Unexpected external fixture download");
                }
            }
            return super.getDownloadCandidates(urls);
        }

        /// Routes Modrinth metadata requests to an empty private catalog.
        @Override
        public DownloadCandidates getVersionListCandidates(String baseUrl) {
            return DownloadCandidates.of(this.baseUrl + "/modrinth");
        }
    }

    /// Declares a custom fetcher's filesystem side effect to verify the wrapper preserves that contract.
    @NotNullByDefault
    private static final class FilesystemProvider extends DownloadProvider {
        /// Filesystem root intentionally outside the test's retained repository resources.
        private final Path directory;

        /// Records execution only if resource acquisition succeeds.
        private final AtomicBoolean executed;

        /// Creates a fetcher with an explicit incompatible resource boundary.
        ///
        /// @param directory unrelated filesystem root
        /// @param executed caller-owned execution flag
        private FilesystemProvider(Path directory, AtomicBoolean executed) {
            this.directory = directory;
            this.executed = executed;
        }

        /// Returns an explicitly scoped custom task whose resource declaration must not be replaced.
        @Override
        protected Task<? extends ComponentRemoteVersionList<?>> fetchVersionsAsync(
                GameComponentType type, @Nullable GameVersionNumber gameVersion) {
            return Task.supplyAsync(() -> {
                executed.set(true);
                return ComponentRemoteVersionList.of(type);
            }).setResources(TaskResource.gameDirectory(directory));
        }
    }
}
