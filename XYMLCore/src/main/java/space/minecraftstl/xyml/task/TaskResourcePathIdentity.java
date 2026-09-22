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
/// symbolic links and Windows junctions. Each declaration retains its real target identity and the canonical directory
/// entry of every alias component. The latter prevents a target write or replacement from running concurrently with an
/// operation on the directory that contains the alias. Missing leaves are reconstructed from the nearest existing
/// ancestor. Each snapshot is checked again after canonicalization, and a changing snapshot is retried a bounded number
/// of times. Any remaining uncertainty is represented by a global resource so an alias is never treated as disjoint.
///
/// Java 17 does not expose a portable stable filesystem handle or file identifier through [Path]. Consequently, a
/// path can still be replaced after the final check, and distinct hard-link names cannot be merged across independent
/// acquisitions. Those residual TOCTOU and hard-link cases require native file handles or filesystem-specific file IDs
/// to eliminate; this resolver deliberately does not claim a durable identity guarantee. A provider that reports an
/// arithmetic overflow while normalizing or traversing a path is treated as equally uncertain and falls back to the
/// global resource rather than allowing a partially resolved path to participate in arbitration.
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

            @Nullable List<Path> canonicalPaths = resolvePaths(path);
            if (canonicalPaths == null) {
                resolved.add(TaskResource.global());
            } else {
                for (Path canonicalPath : canonicalPaths) {
                    resolved.add(checkedResource.withPath(canonicalPath));
                }
            }
        }

        return TaskResource.normalize(resolved);
    }

    /// Resolves a path through its nearest existing ancestor, or returns null when identity remains uncertain.
    ///
    /// @param path normalized logical resource path
    /// @return stable canonical target and alias-entry snapshots, or null when none can be established
    private static @Nullable @Unmodifiable List<Path> resolvePaths(Path path) {
        try {
            Path normalizedPath = Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
            for (int attempt = 0; attempt < MAX_RESOLUTION_ATTEMPTS; attempt++) {
                @Nullable List<Path> resolved = resolvePathSnapshot(normalizedPath);
                if (resolved != null) {
                    return resolved;
                }
            }
            return null;
        } catch (ArithmeticException | InvalidPathException | SecurityException | UnsupportedOperationException ignored) {
            return null;
        }
    }

    /// Attempts one self-consistent canonical snapshot of a normalized path.
    ///
    /// @param normalizedPath normalized absolute path
    /// @return canonical target and alias entries, or null when the snapshot changed or could not be confirmed
    private static @Nullable @Unmodifiable List<Path> resolvePathSnapshot(Path normalizedPath) {
        try {
            @Nullable List<Path> aliasesBefore = resolveAliasEntries(normalizedPath);
            if (aliasesBefore == null) {
                return null;
            }
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
            if (!missingSuffix.isEmpty() && !missingSuffixRemainsAbsent(candidate, missingSuffix)) {
                return null;
            }

            Path confirmed = candidate.toRealPath();
            BasicFileAttributes confirmedAttributes = Files.readAttributes(candidate, BasicFileAttributes.class);
            if (!resolved.equals(confirmed)
                    || !sameSnapshot(attributesAfter, confirmedAttributes)
                    || !Files.isSameFile(candidate, resolved)) {
                return null;
            }
            if (!missingSuffix.isEmpty() && !missingSuffixRemainsAbsent(candidate, missingSuffix)) {
                return null;
            }
            @Nullable List<Path> aliasesAfter = resolveAliasEntries(normalizedPath);
            if (aliasesAfter == null || !aliasesBefore.equals(aliasesAfter)) {
                return null;
            }
            while (!missingSuffix.isEmpty()) {
                resolved = resolved.resolve(missingSuffix.pop());
            }
            ArrayList<Path> identities = new ArrayList<>(aliasesBefore);
            identities.add(resolved.toAbsolutePath().normalize());
            return List.copyOf(identities);
        } catch (IOException | SecurityException | UnsupportedOperationException ignored) {
            return null;
        }
    }

    /// Resolves the canonical directory entry of every symbolic-link or junction component in one lexical path.
    ///
    /// The component's parent is resolved separately from the component itself. A different result means the component
    /// redirects traversal, so both its directory entry and the final real target must participate in arbitration.
    /// Scanning stops at the first missing component because the remaining suffix cannot yet contain an alias.
    ///
    /// @param normalizedPath normalized absolute declaration path
    /// @return immutable alias-entry identities, or null when component existence is uncertain
    private static @Nullable @Unmodifiable List<Path> resolveAliasEntries(Path normalizedPath) throws IOException {
        @Nullable Path root = normalizedPath.getRoot();
        if (root == null) {
            return null;
        }
        Path current = root;
        ArrayList<Path> aliases = new ArrayList<>();
        for (Path component : normalizedPath) {
            current = current.resolve(component);
            if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                return Files.notExists(current, LinkOption.NOFOLLOW_LINKS) ? List.copyOf(aliases) : null;
            }

            @Nullable Path parent = current.getParent();
            if (parent == null) {
                continue;
            }
            Path directoryEntry = parent.toRealPath()
                    .resolve(component)
                    .toAbsolutePath()
                    .normalize();
            Path realComponent = current.toRealPath().toAbsolutePath().normalize();
            if (!directoryEntry.equals(realComponent)) {
                aliases.add(directoryEntry);
            }
        }
        return List.copyOf(aliases);
    }

    /// Confirms that every component below the canonical existing ancestor is still absent.
    ///
    /// Checking only the nearest missing component is insufficient: a concurrently created directory can make a
    /// deeper component visible as a symbolic link or junction after the first check. Iterating the complete suffix
    /// fails closed for that case and lets the bounded outer retry establish a fresh snapshot.
    ///
    /// @param candidate nearest existing lexical ancestor
    /// @param missingSuffix missing components in ancestor-to-leaf order
    /// @return whether no missing component has appeared
    private static boolean missingSuffixRemainsAbsent(Path candidate, Deque<Path> missingSuffix) {
        Path next = candidate;
        for (Path component : missingSuffix) {
            next = next.resolve(component);
            if (!Files.notExists(next, LinkOption.NOFOLLOW_LINKS)) {
                return false;
            }
        }
        return true;
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
