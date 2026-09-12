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
package space.minecraftstl.xyml.download.java.disco;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import space.minecraftstl.xyml.download.DownloadProvider;
import space.minecraftstl.xyml.download.VersionList;
import space.minecraftstl.xyml.task.BoundedTextFetchTask;
import space.minecraftstl.xyml.task.Task;
import space.minecraftstl.xyml.task.TaskResource;
import space.minecraftstl.xyml.util.CacheRepository;
import space.minecraftstl.xyml.util.platform.Platform;

import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/// Verifies that Disco parsing hands off to the bounded metadata request's configured shared cache operation.
@NotNullByDefault
final class DiscoFetchJavaListTaskResourceTest {
    /// Cache root used to verify the configured fetch declaration without mutating the process-wide repository.
    @TempDir
    private @Nullable Path temporaryDirectory;

    /// Marks only the in-memory response parser as orchestration and retains precise ETag-cache protection.
    @Test
    void separatesParsingFromStatefulMetadataFetch() {
        DiscoFetchJavaListTask task = new DiscoFetchJavaListTask(
                new IdentityDownloadProvider(),
                DiscoJavaDistribution.LIBERICA,
                Platform.SYSTEM_PLATFORM);

        Task<?> dependent = task.getDependents().iterator().next();
        BoundedTextFetchTask fetchTask = assertInstanceOf(BoundedTextFetchTask.class, dependent);
        CacheRepository cacheRepository = new CacheRepository();
        cacheRepository.changeDirectory(Objects.requireNonNull(temporaryDirectory, "temporaryDirectory"));
        fetchTask.setCacheRepository(cacheRepository);

        assertEquals(
                List.of(TaskResource.Kind.ORCHESTRATION),
                task.getResources().stream().map(TaskResource::getKind).toList());
        assertEquals(
                List.of(TaskResource.Kind.CACHE_OPERATION),
                fetchTask.getResources().stream().map(TaskResource::getKind).toList());
    }

    /// Download provider preserving the Disco API URI for resource-only construction tests.
    @NotNullByDefault
    private static final class IdentityDownloadProvider implements DownloadProvider {
        /// Returns no game-version endpoints because this test constructs only the Disco request.
        ///
        /// @return empty endpoint list
        @Override
        public List<URI> getVersionListURLs() {
            return List.of();
        }

        /// Returns no asset candidates because this test constructs only the Disco request.
        ///
        /// @param assetObjectLocation unused asset location
        @Override
        public List<URI> getAssetObjectCandidates(String assetObjectLocation) {
            return List.of();
        }

        /// Preserves the supplied Disco API URI.
        ///
        /// @param baseURL original URI text
        /// @return the unchanged URI text
        @Override
        public String injectURL(String baseURL) {
            return baseURL;
        }

        /// Rejects unrelated version-list access.
        ///
        /// @param id requested list identifier
        /// @return never returns normally
        /// @throws IllegalArgumentException for every identifier
        @Override
        public VersionList<?> getVersionListById(String id) {
            throw new IllegalArgumentException(id);
        }

        /// Returns one permitted transfer for the construction-only provider.
        ///
        /// @return one permitted transfer
        @Override
        public int getConcurrency() {
            return 1;
        }
    }
}
