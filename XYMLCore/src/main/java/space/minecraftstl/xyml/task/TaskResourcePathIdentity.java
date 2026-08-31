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
package space.minecraftstl.xyml.task;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/// Resolves filesystem aliases for task resources without performing I/O on task construction or UI threads.
///
/// Existing path components are resolved through [Path#toRealPath(java.nio.file.LinkOption...)], which follows both
/// symbolic links and Windows junctions. Missing leaves are reconstructed from the nearest existing ancestor. Any
/// uncertainty at the snapshot is represented by a global resource so an alias is never incorrectly treated as
/// disjoint. The filesystem may still change after the snapshot; callers must treat that residual TOCTOU window as a
/// known limitation rather than as a durable identity guarantee.
@NotNullByDefault
final class TaskResourcePathIdentity {
    /// Prevents construction of this stateless resolver.
    private TaskResourcePathIdentity() {
    }

    /// Resolves resource identities on the supplied executor.
    ///
    /// @param resources immutable logical declarations
    /// @param executor executor used for potentially blocking filesystem inspection
    /// @return future containing canonicalized declarations
    static CompletableFuture<@Unmodifiable List<TaskResource>> resolveAsync(
            @Unmodifiable Collection<TaskResource> resources,
            Executor executor) {
        Objects.requireNonNull(resources, "resources");
        Objects.requireNonNull(executor, "executor");
        @Unmodifiable List<TaskResource> snapshot = List.copyOf(resources);
        return CompletableFuture.supplyAsync(() -> resolve(snapshot), executor);
    }

    /// Resolves all path-backed declarations and minimizes the resulting immutable collection.
    ///
    /// @param resources logical declarations
    /// @return canonicalized declarations with conservative declarations resolved to the global resource
    static @Unmodifiable List<TaskResource> resolve(@Unmodifiable Collection<TaskResource> resources) {
        Objects.requireNonNull(resources, "resources");
        if (resources.isEmpty()) {
            throw new IllegalArgumentException("Task resource declarations cannot be empty");
        }

        ArrayList<TaskResource> resolved = new ArrayList<>(resources.size());
        for (TaskResource resource : resources) {
            TaskResource checkedResource = Objects.requireNonNull(resource, "resource");
            if (checkedResource.isConservative()) {
                resolved.add(TaskResource.global());
                continue;
            }

            @Nullable Path path = checkedResource.getPath();
            if (path == null) {
                resolved.add(checkedResource);
                continue;
            }

            Path canonicalPath = resolvePath(path);
            resolved.add(canonicalPath == null
                    ? TaskResource.global()
                    : checkedResource.withPath(canonicalPath));
        }

        return TaskResource.normalize(resolved);
    }

    /// Resolves a path through its nearest existing ancestor, or returns null when identity is uncertain.
    private static @Nullable Path resolvePath(Path path) {
        try {
            Path candidate = Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
            Deque<Path> missingSuffix = new ArrayDeque<>();
            while (!Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) {
                if (!Files.notExists(candidate, LinkOption.NOFOLLOW_LINKS)) {
                    return null;
                }

                Path fileName = candidate.getFileName();
                Path parent = candidate.getParent();
                if (fileName == null || parent == null) {
                    return null;
                }
                missingSuffix.push(fileName);
                candidate = parent;
            }

            Path resolved = candidate.toRealPath();
            if (!missingSuffix.isEmpty() && !Files.isDirectory(resolved)) {
                // A path cannot have descendants below an existing regular file. Treat the identity as unknown
                // instead of manufacturing a lexical key that could be mistaken for an independent resource.
                return null;
            }
            while (!missingSuffix.isEmpty()) {
                resolved = resolved.resolve(missingSuffix.pop());
            }
            return resolved.toAbsolutePath().normalize();
        } catch (IOException | InvalidPathException | SecurityException | UnsupportedOperationException ignored) {
            return null;
        }
    }
}
