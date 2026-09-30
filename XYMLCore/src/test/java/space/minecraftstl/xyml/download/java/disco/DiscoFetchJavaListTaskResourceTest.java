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
import space.minecraftstl.xyml.task.BoundedTextFetchTask;
import space.minecraftstl.xyml.task.Task;
import space.minecraftstl.xyml.task.TaskResource;
import space.minecraftstl.xyml.util.CacheRepository;
import space.minecraftstl.xyml.util.platform.Platform;

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
                new DownloadProvider(),
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
}
