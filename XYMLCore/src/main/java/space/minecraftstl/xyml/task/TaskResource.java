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

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/// Immutable semantic key describing filesystem state occupied by a [Task].
///
/// Path-backed resources use normalized absolute paths without resolving symbolic links. Directory resources cover
/// their complete descendant tree, while file resources cover one exact path. Resource kinds remain visible in
/// diagnostics even though conflict detection is based on the represented filesystem range.
@NotNullByDefault
public final class TaskResource {
    /// Stable ordering used for atomic multi-resource acquisition.
    static final Comparator<TaskResource> ORDER = Comparator
            .comparingInt((TaskResource resource) -> resource.scope.order)
            .thenComparing(resource -> resource.kind.name())
            .thenComparing(TaskResource::comparisonText);

    /// Whether the default filesystem treats path names case-insensitively.
    private static final boolean CASE_INSENSITIVE_PATHS = File.separatorChar == '\\';

    /// Shared conservative declaration inherited by every task that has not selected a precise resource set.
    private static final TaskResource CONSERVATIVE = new TaskResource(Kind.CONSERVATIVE, Scope.CONSERVATIVE, null);

    /// Shared explicit process-wide resource.
    private static final TaskResource GLOBAL = new TaskResource(Kind.GLOBAL, Scope.GLOBAL, null);

    /// Semantic category retained for diagnostics and stable ordering.
    private final Kind kind;

    /// Filesystem coverage represented by this key.
    private final Scope scope;

    /// Normalized absolute path, or null for non-path resources.
    private final @Nullable Path path;

    /// Filesystem-comparison path with platform case semantics applied, or null for non-path resources.
    private final @Nullable Path comparisonPath;

    /// Creates one validated immutable resource key.
    ///
    /// @param kind semantic resource category
    /// @param scope represented filesystem coverage
    /// @param path normalized path, or null for conservative and global resources
    private TaskResource(Kind kind, Scope scope, @Nullable Path path) {
        this.kind = Objects.requireNonNull(kind, "kind");
        this.scope = Objects.requireNonNull(scope, "scope");
        this.path = path;
        this.comparisonPath = path == null ? null : comparisonPath(path);
    }

    /// Returns the default declaration used until a task is explicitly audited.
    ///
    /// A root task resolves this declaration to [#global()]. A nested task resolves it to the effective resource
    /// coverage already retained by its ancestor chain.
    ///
    /// @return shared conservative declaration
    public static TaskResource conservative() {
        return CONSERVATIVE;
    }

    /// Returns the explicit process-wide exclusive resource.
    ///
    /// @return shared global resource
    public static TaskResource global() {
        return GLOBAL;
    }

    /// Creates a resource covering one complete game-directory tree.
    ///
    /// @param directory game directory
    /// @return normalized directory resource
    public static TaskResource gameDirectory(Path directory) {
        return directory(Kind.GAME_DIRECTORY, directory);
    }

    /// Creates a resource covering one complete game-instance tree.
    ///
    /// @param directory game instance directory
    /// @return normalized instance resource
    public static TaskResource gameInstance(Path directory) {
        return directory(Kind.GAME_INSTANCE, directory);
    }

    /// Creates a resource covering one exact download destination.
    ///
    /// @param target download destination
    /// @return normalized exact-file resource
    public static TaskResource downloadTarget(Path target) {
        return file(Kind.DOWNLOAD_TARGET, target);
    }

    /// Creates a resource covering one managed Java runtime tree.
    ///
    /// @param directory Java runtime directory
    /// @return normalized runtime resource
    public static TaskResource javaRuntime(Path directory) {
        return directory(Kind.JAVA_RUNTIME, directory);
    }

    /// Creates a resource covering one launcher-upgrade tree.
    ///
    /// @param directory launcher upgrade directory
    /// @return normalized upgrade resource
    public static TaskResource launcherUpgrade(Path directory) {
        return directory(Kind.LAUNCHER_UPGRADE, directory);
    }

    /// Creates a resource covering one shared cache tree.
    ///
    /// @param directory cache directory
    /// @return normalized cache resource
    public static TaskResource cache(Path directory) {
        return directory(Kind.CACHE, directory);
    }

    /// Creates a resource covering one exact configuration file.
    ///
    /// @param file configuration file
    /// @return normalized exact-file resource
    public static TaskResource configuration(Path file) {
        return file(Kind.CONFIGURATION, file);
    }

    /// Creates a resource covering one exact input archive.
    ///
    /// @param file archive file
    /// @return normalized exact-file resource
    public static TaskResource archive(Path file) {
        return file(Kind.ARCHIVE, file);
    }

    /// Creates a resource covering one exact export destination.
    ///
    /// @param file export destination
    /// @return normalized exact-file resource
    public static TaskResource exportTarget(Path file) {
        return file(Kind.EXPORT_TARGET, file);
    }

    /// Returns the semantic category of this resource.
    ///
    /// @return resource category
    public Kind getKind() {
        return kind;
    }

    /// Returns the normalized absolute path represented by this resource, or null for non-path resources.
    ///
    /// @return normalized path or null
    public @Nullable Path getPath() {
        return path;
    }

    /// Returns whether this declaration is the conservative unresolved default.
    boolean isConservative() {
        return scope == Scope.CONSERVATIVE;
    }

    /// Returns whether this resource conflicts with another normalized resource.
    boolean conflictsWith(TaskResource other) {
        Objects.requireNonNull(other, "other");
        requireResolved();
        other.requireResolved();
        if (scope == Scope.GLOBAL || other.scope == Scope.GLOBAL) {
            return true;
        }

        Path thisPath = Objects.requireNonNull(comparisonPath, "comparisonPath");
        Path otherPath = Objects.requireNonNull(other.comparisonPath, "other comparisonPath");
        if (scope == Scope.DIRECTORY) {
            return otherPath.startsWith(thisPath);
        }
        if (other.scope == Scope.DIRECTORY) {
            return thisPath.startsWith(otherPath);
        }
        return thisPath.equals(otherPath);
    }

    /// Returns whether this resource fully protects the other resource's represented range.
    boolean covers(TaskResource other) {
        Objects.requireNonNull(other, "other");
        requireResolved();
        other.requireResolved();
        if (scope == Scope.GLOBAL) {
            return true;
        }
        if (other.scope == Scope.GLOBAL) {
            return false;
        }

        Path thisPath = Objects.requireNonNull(comparisonPath, "comparisonPath");
        Path otherPath = Objects.requireNonNull(other.comparisonPath, "other comparisonPath");
        if (scope == Scope.DIRECTORY) {
            return otherPath.startsWith(thisPath);
        }
        return other.scope == Scope.FILE && thisPath.equals(otherPath);
    }

    /// Normalizes, deduplicates, minimizes, and sorts one resolved resource collection.
    static @Unmodifiable List<TaskResource> normalize(Collection<TaskResource> resources) {
        Objects.requireNonNull(resources, "resources");
        if (resources.isEmpty()) {
            throw new IllegalArgumentException("Task resource declarations cannot be empty");
        }

        ArrayList<TaskResource> normalized = new ArrayList<>(resources.size());
        for (TaskResource resource : resources) {
            TaskResource checkedResource = Objects.requireNonNull(resource, "resource");
            checkedResource.requireResolved();
            if (normalized.stream().anyMatch(existing -> existing.covers(checkedResource))) {
                continue;
            }
            normalized.removeIf(checkedResource::covers);
            normalized.add(checkedResource);
        }
        normalized.sort(ORDER);
        return List.copyOf(normalized);
    }

    /// Creates a directory resource after normalizing its path.
    private static TaskResource directory(Kind kind, Path directory) {
        return new TaskResource(kind, Scope.DIRECTORY, normalizePath(directory));
    }

    /// Creates an exact-file resource after normalizing its path.
    private static TaskResource file(Kind kind, Path file) {
        return new TaskResource(kind, Scope.FILE, normalizePath(file));
    }

    /// Returns a normalized absolute path without performing filesystem I/O.
    private static Path normalizePath(Path path) {
        return Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
    }

    /// Applies the default filesystem's case semantics to a normalized path.
    private static Path comparisonPath(Path path) {
        if (!CASE_INSENSITIVE_PATHS) {
            return path;
        }
        return Path.of(path.toString().toLowerCase(Locale.ROOT));
    }

    /// Returns stable text for resource ordering.
    private String comparisonText() {
        return comparisonPath == null ? "" : comparisonPath.toString();
    }

    /// Rejects use of the unresolved conservative declaration inside the lock manager.
    private void requireResolved() {
        if (isConservative()) {
            throw new IllegalArgumentException("Conservative task resources must be resolved by their execution owner");
        }
    }

    /// Compares complete immutable resource identity.
    @Override
    public boolean equals(@Nullable Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof TaskResource resource)) {
            return false;
        }
        return kind == resource.kind
                && scope == resource.scope
                && Objects.equals(comparisonPath, resource.comparisonPath);
    }

    /// Returns a hash code consistent with [#equals(Object)].
    @Override
    public int hashCode() {
        return Objects.hash(kind, scope, comparisonPath);
    }

    /// Returns a diagnostic representation without exposing lock state.
    @Override
    public String toString() {
        return path == null ? "TaskResource[" + kind + "]" : "TaskResource[" + kind + ":" + path + "]";
    }

    /// Public semantic categories supported by the first resource-locking phase.
    @NotNullByDefault
    public enum Kind {
        /// Unresolved default inherited from an ancestor or resolved globally at a root.
        CONSERVATIVE,
        /// Explicit process-wide exclusion.
        GLOBAL,
        /// Complete game-directory tree.
        GAME_DIRECTORY,
        /// Complete game-instance tree.
        GAME_INSTANCE,
        /// Exact download destination.
        DOWNLOAD_TARGET,
        /// Complete managed Java runtime tree.
        JAVA_RUNTIME,
        /// Complete launcher-upgrade tree.
        LAUNCHER_UPGRADE,
        /// Complete shared-cache tree.
        CACHE,
        /// Exact configuration file.
        CONFIGURATION,
        /// Exact input archive.
        ARCHIVE,
        /// Exact export destination.
        EXPORT_TARGET
    }

    /// Internal filesystem coverage used for conflict and ordering rules.
    private enum Scope {
        /// Unresolved default declaration.
        CONSERVATIVE(0),
        /// Process-wide wildcard.
        GLOBAL(1),
        /// Complete directory tree.
        DIRECTORY(2),
        /// Exact filesystem path.
        FILE(3);

        /// Stable ordering rank.
        private final int order;

        /// Creates a scope with its stable ordering rank.
        Scope(int order) {
            this.order = order;
        }
    }
}
