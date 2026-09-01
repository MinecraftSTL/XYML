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
import java.nio.file.attribute.BasicFileAttributes;
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
/// symbolic links and Windows junctions. Missing leaves are reconstructed from the nearest existing ancestor. Each
/// snapshot is checked again after canonicalization, including the first missing component, and a changing snapshot is
/// retried a bounded number of times. Any remaining uncertainty is represented by a global resource so an alias is
/// never incorrectly treated as disjoint.
///
/// Java 17 does not expose a portable stable filesystem handle or file identifier through [Path]. Consequently, a
/// path can still be replaced after the final check, and distinct hard-link names cannot be merged across independent
/// acquisitions. Those residual TOCTOU and hard-link cases require native file handles or filesystem-specific file IDs
/// to eliminate; this resolver deliberately does not claim a durable identity guarantee.
@NotNullByDefault
final class TaskResourcePathIdentity {
    /// Maximum stable-snapshot attempts before path identity falls back to the global resource.
    private static final int MAX_RESOLUTION_ATTEMPTS = 3;

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

            @Nullable Path canonicalPath = resolvePath(path);
            resolved.add(canonicalPath == null
                    ? TaskResource.global()
                    : checkedResource.withPath(canonicalPath));
        }

        return TaskResource.normalize(resolved);
    }

    /// Resolves a path through its nearest existing ancestor, or returns null when identity remains uncertain.
    ///
    /// @param path normalized logical resource path
    /// @return stable canonical snapshot, or null when no stable snapshot can be established
    private static @Nullable Path resolvePath(Path path) {
        try {
            Path normalizedPath = Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
            for (int attempt = 0; attempt < MAX_RESOLUTION_ATTEMPTS; attempt++) {
                @Nullable Path resolved = resolvePathSnapshot(normalizedPath);
                if (resolved != null) {
                    return resolved;
                }
            }
            return null;
        } catch (InvalidPathException | SecurityException | UnsupportedOperationException ignored) {
            return null;
        }
    }

    /// Attempts one self-consistent canonical snapshot of a normalized path.
    ///
    /// @param normalizedPath normalized absolute path
    /// @return canonical path, or null when the snapshot changed or could not be confirmed
    private static @Nullable Path resolvePathSnapshot(Path normalizedPath) {
        try {
            Path candidate = normalizedPath;
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

            BasicFileAttributes attributesBefore = Files.readAttributes(candidate, BasicFileAttributes.class);
            Path resolved = candidate.toRealPath();
            BasicFileAttributes attributesAfter = Files.readAttributes(candidate, BasicFileAttributes.class);
            if (!sameSnapshot(attributesBefore, attributesAfter)) {
                return null;
            }

            if (!missingSuffix.isEmpty() && !attributesAfter.isDirectory()) {
                // A path cannot have descendants below an existing regular file. Treat the identity as unknown
                // instead of manufacturing a lexical key that could be mistaken for an independent resource.
                return null;
            }
            if (!missingSuffix.isEmpty()) {
                Path firstMissingComponent = candidate.resolve(missingSuffix.peek());
                if (!Files.notExists(firstMissingComponent, LinkOption.NOFOLLOW_LINKS)) {
                    return null;
                }
            }

            Path confirmed = candidate.toRealPath();
            BasicFileAttributes confirmedAttributes = Files.readAttributes(candidate, BasicFileAttributes.class);
            if (!resolved.equals(confirmed)
                    || !sameSnapshot(attributesAfter, confirmedAttributes)
                    || !Files.isSameFile(candidate, resolved)) {
                return null;
            }
            if (!missingSuffix.isEmpty()
                    && !Files.notExists(candidate.resolve(missingSuffix.peek()), LinkOption.NOFOLLOW_LINKS)) {
                return null;
            }
            while (!missingSuffix.isEmpty()) {
                resolved = resolved.resolve(missingSuffix.pop());
            }
            return resolved.toAbsolutePath().normalize();
        } catch (IOException | SecurityException | UnsupportedOperationException ignored) {
            return null;
        }
    }

    /// Compares two followed attribute reads from one canonicalization attempt.
    ///
    /// File keys provide the strongest portable comparison when the filesystem exposes them. The remaining attributes
    /// are a conservative change detector for providers that return null file keys; they cannot close the final
    /// replacement window described by this class.
    ///
    /// @param before attributes read before canonicalization
    /// @param after attributes read after canonicalization
    /// @return whether both reads can describe the same filesystem object snapshot
    static boolean sameSnapshot(BasicFileAttributes before, BasicFileAttributes after) {
        @Nullable Object beforeKey = before.fileKey();
        @Nullable Object afterKey = after.fileKey();
        if (beforeKey != null || afterKey != null) {
            return Objects.equals(beforeKey, afterKey);
        }
        return before.isDirectory() == after.isDirectory()
                && before.isRegularFile() == after.isRegularFile()
                && before.isSymbolicLink() == after.isSymbolicLink()
                && before.isOther() == after.isOther()
                && before.size() == after.size()
                && before.creationTime().equals(after.creationTime())
                && before.lastModifiedTime().equals(after.lastModifiedTime());
    }
}
