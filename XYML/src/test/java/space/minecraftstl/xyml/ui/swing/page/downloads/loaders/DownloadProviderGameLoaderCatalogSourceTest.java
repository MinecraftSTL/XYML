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
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import space.minecraftstl.xyml.download.ComponentRemoteVersion;
import space.minecraftstl.xyml.download.ComponentRemoteVersionList;
import space.minecraftstl.xyml.download.DownloadProvider;
import space.minecraftstl.xyml.download.TestComponentRemoteVersion;
import space.minecraftstl.xyml.game.GameComponentType;
import space.minecraftstl.xyml.task.Task;
import space.minecraftstl.xyml.util.versioning.GameVersionNumber;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Verifies the Core DownloadProvider loader source stays offline until one selected loader is explicitly refreshed.
@NotNullByDefault
final class DownloadProviderGameLoaderCatalogSourceTest {
    /// Queries only the selected loader task and preserves the exact concrete remote version instance.
    @Test
    void refreshesOnlyExplicitSelectedListAndPreservesConcreteRemoteVersion() {
        RecordingProvider provider = new RecordingProvider();
        ComponentRemoteVersion fabricVersion =
                new TestComponentRemoteVersion(GameComponentType.FABRIC, "1.20.1", "0.16.0");
        provider.add(GameComponentType.FABRIC, List.of(fabricVersion));
        AtomicInteger taskRuns = new AtomicInteger();

        DownloadProviderGameLoaderCatalogSource source = new DownloadProviderGameLoaderCatalogSource(
                provider,
                task -> {
                    taskRuns.incrementAndGet();
                    return CompletableFuture.completedFuture(null);
                });

        assertAll(
                () -> assertEquals(0, provider.requestedTypes().size()),
                () -> assertEquals(0, taskRuns.get()));

        List<GameLoaderCatalogItem> items = source.refreshAsync(
                        new GameLoaderCatalogRequest("1.20.1", GameLoaderKind.FABRIC))
                .toCompletableFuture()
                .join();

        assertAll(
                () -> assertEquals(List.of("fabric"), provider.requestedTypes()),
                () -> assertEquals(List.of("1.20.1"), provider.requestedGameVersions()),
                () -> assertEquals(1, taskRuns.get()),
                () -> assertEquals(1, items.size()),
                () -> assertEquals(GameLoaderKind.FABRIC, items.get(0).kind()),
                () -> assertSame(fabricVersion, items.get(0).remoteVersion()));
    }

    /// Fails the refresh stage without starting a task when the provider rejects the request.
    @Test
    void failsTheStageWhenTheProviderThrows() {
        IllegalStateException failure = new IllegalStateException("loader list rejected");
        DownloadProvider provider = new DownloadProvider() {
            /// Rejects every loader list request with the configured failure.
            ///
            /// @param type        requested component type
            /// @param gameVersion requested game version
            /// @param refresh     whether a refresh was requested
            /// @return never returns normally
            @Override
            public @Unmodifiable Task<ComponentRemoteVersionList<?>> getVersionsAsync(
                    GameComponentType type, @Nullable GameVersionNumber gameVersion, boolean refresh) {
                throw failure;
            }
        };
        AtomicInteger taskRuns = new AtomicInteger();

        DownloadProviderGameLoaderCatalogSource source = new DownloadProviderGameLoaderCatalogSource(
                provider,
                task -> {
                    taskRuns.incrementAndGet();
                    return CompletableFuture.completedFuture(null);
                });

        CompletionException completionFailure = assertThrows(
                CompletionException.class,
                () -> source.refreshAsync(new GameLoaderCatalogRequest("1.20.1", GameLoaderKind.FABRIC))
                        .toCompletableFuture()
                        .join());
        assertAll(
                () -> assertSame(failure, completionFailure.getCause()),
                () -> assertEquals(0, taskRuns.get()));
    }

    /// Download provider returning one exact fetched row list per requested component type.
    @NotNullByDefault
    private static final class RecordingProvider extends DownloadProvider {
        /// Configured rows by component type.
        private final Map<GameComponentType, List<ComponentRemoteVersion>> rows =
                new EnumMap<>(GameComponentType.class);

        /// Requested component identifiers in invocation order.
        private final List<String> requestedTypes = new ArrayList<>();

        /// Requested game versions in invocation order.
        private final List<String> requestedGameVersions = new ArrayList<>();

        /// Adds one locally controlled row list.
        ///
        /// @param type     requested component type
        /// @param versions fetched rows
        private void add(GameComponentType type, List<ComponentRemoteVersion> versions) {
            rows.put(type, List.copyOf(versions));
        }

        /// Records the request and returns one stopped task exposing the fixed rows.
        ///
        /// @param type        requested component type
        /// @param gameVersion requested game version
        /// @param refresh     whether a refresh was requested
        /// @return stopped task exposing the configured rows
        @Override
        public @Unmodifiable Task<ComponentRemoteVersionList<?>> getVersionsAsync(
                GameComponentType type, @Nullable GameVersionNumber gameVersion, boolean refresh) {
            requestedTypes.add(type.getPatchId());
            requestedGameVersions.add(gameVersion == null ? "" : gameVersion.toString());
            List<ComponentRemoteVersion> versions = rows.get(type);
            if (versions == null) {
                throw new IllegalArgumentException("No recording rows for " + type);
            }
            return new RowsTask(type, versions);
        }

        /// Returns immutable requested component identifiers.
        ///
        /// @return requested identifiers
        private @Unmodifiable List<String> requestedTypes() {
            return List.copyOf(requestedTypes);
        }

        /// Returns immutable requested game versions.
        ///
        /// @return requested game versions
        private @Unmodifiable List<String> requestedGameVersions() {
            return List.copyOf(requestedGameVersions);
        }
    }

    /// Stopped task that exposes one fixed row snapshot to the loader catalog source.
    @NotNullByDefault
    private static final class RowsTask extends Task<ComponentRemoteVersionList<?>> {
        /// Creates one stopped task for a fixed row snapshot.
        ///
        /// @param type requested component type
        /// @param rows fetched rows
        RowsTask(GameComponentType type, List<ComponentRemoteVersion> rows) {
            setResult(ComponentRemoteVersionList.of(type, new TreeSet<>(rows)));
        }

        /// Performs no work because the test runner owns the terminal event.
        @Override
        public void execute() {
            // The test runner never runs the task.
        }
    }
}
