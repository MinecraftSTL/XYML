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
package space.minecraftstl.xyml.ui.swing.page.downloads.loaders;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import space.minecraftstl.xyml.download.DownloadProvider;
import space.minecraftstl.xyml.download.RemoteVersion;
import space.minecraftstl.xyml.download.VersionList;
import space.minecraftstl.xyml.task.Task;

import java.net.URI;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/// Verifies the Core DownloadProvider source remains offline until one selected loader is explicitly refreshed.
@NotNullByDefault
final class DownloadProviderGameLoaderCatalogSourceTest {
    /// Calls only the selected Fabric list, preserves concrete RemoteVersion identity, and starts no work at construction.
    @Test
    void refreshesOnlyExplicitSelectedListAndPreservesConcreteRemoteVersion() {
        RecordingProvider provider = new RecordingProvider();
        RecordingVersionList fabricList = new RecordingVersionList();
        RemoteVersion fabricVersion = remoteVersion("fabric", "1.20.1", "0.16.0");
        fabricList.add("1.20.1", fabricVersion);
        provider.add("fabric", fabricList);
        AtomicInteger taskRuns = new AtomicInteger();

        DownloadProviderGameLoaderCatalogSource source =
                new DownloadProviderGameLoaderCatalogSource(
                        provider,
                        task -> {
                            taskRuns.incrementAndGet();
                            return CompletableFuture.completedFuture(null);
                        });

        assertAll(
                () -> assertEquals(0, provider.versionListRequests()),
                () -> assertEquals(0, fabricList.refreshRequests()),
                () -> assertEquals(0, taskRuns.get()));

        List<GameLoaderCatalogItem> items = source.refreshAsync(
                        new GameLoaderCatalogRequest("1.20.1", GameLoaderKind.FABRIC))
                .toCompletableFuture()
                .join();

        assertAll(
                () -> assertEquals(1, provider.versionListRequests()),
                () -> assertEquals(List.of("fabric"), provider.requestedListIds()),
                () -> assertEquals(1, fabricList.refreshRequests()),
                () -> assertEquals(List.of("1.20.1"), fabricList.requestedGameVersions()),
                () -> assertEquals(1, taskRuns.get()),
                () -> assertEquals(1, items.size()),
                () -> assertEquals(GameLoaderKind.FABRIC, items.get(0).kind()),
                () -> assertSame(fabricVersion, items.get(0).remoteVersion()));
    }

    /// Creates one Core remote-version fixture for source identity tests.
    ///
    /// @param libraryId Core version-list identifier
    /// @param gameVersion target Minecraft version
    /// @param selfVersion concrete remote loader version
    /// @return stable remote version fixture
    private static RemoteVersion remoteVersion(
            String libraryId,
            String gameVersion,
            String selfVersion) {
        return new RemoteVersion(
                libraryId,
                gameVersion,
                selfVersion,
                Instant.EPOCH,
                List.of("https://example.invalid/" + libraryId + ".jar"));
    }

    /// Provides only explicit version-list lookup behavior needed by the source test.
    @NotNullByDefault
    private static final class RecordingProvider implements DownloadProvider {
        /// Configured test lists by Core ID.
        private final Map<String, VersionList<?>> versionLists = new HashMap<>();

        /// List IDs requested by the source after explicit refresh actions.
        private final List<String> requestedListIds = new java.util.ArrayList<>();

        /// Adds one locally controlled version list.
        ///
        /// @param id Core version-list ID
        /// @param versionList controlled list
        private void add(String id, VersionList<?> versionList) {
            versionLists.put(id, versionList);
        }

        /// Returns the number of version-list lookups.
        ///
        /// @return explicit lookup count
        private int versionListRequests() {
            return requestedListIds.size();
        }

        /// Returns immutable requested list IDs in invocation order.
        ///
        /// @return immutable requested IDs
        private @Unmodifiable List<String> requestedListIds() {
            return List.copyOf(requestedListIds);
        }

        /// Returns no global version-list URLs because the test never calls them.
        ///
        /// @return empty immutable URL list
        @Override
        public @Unmodifiable List<URI> getVersionListURLs() {
            return List.of();
        }

        /// Returns no asset candidates because the test never resolves artifacts.
        ///
        /// @param assetObjectLocation unused asset location
        /// @return empty immutable URL list
        @Override
        public @Unmodifiable List<URI> getAssetObjectCandidates(String assetObjectLocation) {
            return List.of();
        }

        /// Preserves an unused URL in the test provider.
        ///
        /// @param baseURL unused source URL
        /// @return unchanged source URL
        @Override
        public String injectURL(String baseURL) {
            return baseURL;
        }

        /// Returns one configured list only after an explicit source request.
        ///
        /// @param id requested Core version-list ID
        /// @return configured controlled list
        @Override
        public VersionList<?> getVersionListById(String id) {
            requestedListIds.add(id);
            VersionList<?> versionList = versionLists.get(id);
            if (versionList == null) {
                throw new IllegalArgumentException("No recording version list for " + id);
            }
            return versionList;
        }

        /// Returns a harmless test concurrency limit.
        ///
        /// @return fixed positive concurrency
        @Override
        public int getConcurrency() {
            return 1;
        }
    }

    /// Supplies one mutable in-memory Core version list with refresh invocation recording.
    @NotNullByDefault
    private static final class RecordingVersionList extends VersionList<RemoteVersion> {
        /// Requested game versions in refresh invocation order.
        private final List<String> requestedGameVersions = new java.util.ArrayList<>();

        /// Adds one concrete remote version to the requested game-version bucket.
        ///
        /// @param gameVersion target Minecraft version
        /// @param remoteVersion exact Core remote version
        private void add(String gameVersion, RemoteVersion remoteVersion) {
            versions.putAll(gameVersion, List.of(remoteVersion));
        }

        /// Returns the number of explicit refresh calls.
        ///
        /// @return refresh invocation count
        private int refreshRequests() {
            return requestedGameVersions.size();
        }

        /// Returns immutable requested game-version values.
        ///
        /// @return immutable refresh game-version history
        private @Unmodifiable List<String> requestedGameVersions() {
            return List.copyOf(requestedGameVersions);
        }

        /// Declares this controlled list as a typed version list.
        ///
        /// @return true for test completeness
        @Override
        public boolean hasType() {
            return true;
        }

        /// Records a whole-list refresh request.
        ///
        /// @return completed test task
        @Override
        public Task<?> refreshAsync() {
            return Task.completed(null);
        }

        /// Records one selected-game refresh request without performing network I/O.
        ///
        /// @param gameVersion selected Minecraft version
        /// @return completed test task
        @Override
        public Task<?> refreshAsync(String gameVersion) {
            requestedGameVersions.add(gameVersion);
            return Task.completed(null);
        }
    }
}
