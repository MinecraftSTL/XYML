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
/// Path-backed declarations use normalized absolute paths at construction time; the lock manager additionally resolves
/// existing symbolic-link and junction components before arbitration. Directory resources cover their complete
/// descendant tree, while file resources cover one exact path. Resource kinds remain visible in diagnostics even though
/// conflict detection is based on the represented filesystem range.
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

    /// Shared marker for a pure graph-orchestration phase.
    private static final TaskResource ORCHESTRATION =
            new TaskResource(Kind.ORCHESTRATION, Scope.ORCHESTRATION, null);

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
    /// A root task resolves this declaration to [#global()]. A nested task inherits the nearest complete filesystem
    /// boundary. A repository metadata/operation declaration is eligible only when a game-directory declaration covers
    /// that repository; mixed coordination and narrow resources otherwise fall back to [#global()]. If no complete
    /// boundary exists, the nested task also resolves globally so an unknown write never becomes lock-free. The lock
    /// manager rejects that global expansion while a narrower ancestor remains active; an audited parent must retain a
    /// complete boundary or hand off before the child starts.
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

    /// Returns a non-filesystem marker for an audited phase with no filesystem side effects.
    ///
    /// This marker does not conflict with precise filesystem resources. It may be used for graph coordination,
    /// immutable parsing, read-only computation, or internally synchronized memory updates, but never for filesystem
    /// writes or untracked asynchronous work. It still conflicts with [#global()] through the process-wide exclusion
    /// rule.
    ///
    /// @return shared orchestration marker
    static TaskResource orchestration() {
        return ORCHESTRATION;
    }

    /// Creates a resource covering one complete game-directory tree.
    ///
    /// @param directory game directory
    /// @return normalized directory resource
    public static TaskResource gameDirectory(Path directory) {
        return directory(Kind.GAME_DIRECTORY, directory);
    }

    /// Creates a resource serializing instance-name and repository-catalog resolution for one repository.
    ///
    /// This key deliberately does not cover instance directories. A task may hold it while resolving a destination
    /// instance and then hand it off to a precise instance resource, allowing the subsequent instance operation to
    /// overlap resolution in another instance while shared game-directory writes remain protected separately.
    ///
    /// @param directory repository base directory
    /// @return normalized repository-metadata resource
    public static TaskResource repositoryMetadata(Path directory) {
        return new TaskResource(Kind.REPOSITORY_METADATA, Scope.REPOSITORY_METADATA, normalizePath(directory));
    }

    /// Creates a shared operation domain for one game repository.
    ///
    /// Unrelated owners may hold this resource concurrently, including while another task briefly resolves repository
    /// metadata. It conflicts only with a repository-wide game-directory operation and permits nested tasks to declare
    /// precise resources inside the repository. Instance, asset, library, and target-file resources therefore decide
    /// conflicts between the actual filesystem stages.
    ///
    /// @param directory repository base directory
    /// @return normalized shared repository-operation resource
    public static TaskResource repositoryOperation(Path directory) {
        return new TaskResource(Kind.REPOSITORY_OPERATION, Scope.REPOSITORY_OPERATION, normalizePath(directory));
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

    /// Creates a resource covering one exact managed add-on file.
    ///
    /// @param file managed add-on source, archive, or destination
    /// @return normalized exact-file resource
    public static TaskResource addonFile(Path file) {
        return file(Kind.ADDON_FILE, file);
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

    /// Creates an exclusive operation domain for one cache tree.
    ///
    /// Operations in the same or overlapping cache tree serialize because legacy cache file and index updates are not
    /// one atomic transaction. Different cache trees remain independent. Unlike [#cache(Path)], this scope is not a
    /// complete filesystem boundary and therefore cannot make an unknown conservative child safe; arbitrary cache
    /// maintenance must keep using [#cache(Path)].
    ///
    /// @param directory cache directory used throughout task execution
    /// @return normalized exclusive cache-operation resource
    public static TaskResource cacheOperation(Path directory) {
        return new TaskResource(Kind.CACHE_OPERATION, Scope.CACHE_OPERATION, normalizePath(directory));
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

    /// Creates an equivalent resource with a canonical filesystem path for lock-manager use.
    ///
    /// The logical kind and coverage shape are preserved; callers should keep the original resource for diagnostics.
    ///
    /// @param canonicalPath canonical or fail-closed normalized path
    /// @return resource with the supplied path
    TaskResource withPath(Path canonicalPath) {
        if (path == null) {
            throw new IllegalStateException("Non-path task resources cannot be assigned a path");
        }
        return new TaskResource(kind, scope, normalizePath(canonicalPath));
    }

    /// Returns whether this declaration is the conservative unresolved default.
    boolean isConservative() {
        return scope == Scope.CONSERVATIVE;
    }

    /// Returns whether this resource is an exclusive filesystem boundary suitable for unknown nested writes.
    ///
    /// Logical repository coordination scopes deliberately return false: they coordinate a known metadata or
    /// operation phase, but do not promise to protect arbitrary filesystem writes made by a conservative child.
    boolean isExclusiveCoverage() {
        return scope == Scope.GLOBAL || scope == Scope.DIRECTORY;
    }

    /// Returns whether this declaration is a complete boundary for an unknown nested write.
    ///
    /// The nearest explicit directory boundary wins. An exact file is never sufficient: an unknown descendant may
    /// write a sibling or a sidecar next to that file, which the exact-file key would not protect. A parent that
    /// declares several unrelated files or directories still falls back globally. Cache-operation and
    /// repository-coordination scopes are excluded because they describe a particular transaction or coordination
    /// phase rather than a complete branch boundary.
    ///
    /// @return whether this resource can safely bound an unknown descendant
    boolean isCompleteBoundary() {
        return scope == Scope.GLOBAL || scope == Scope.DIRECTORY;
    }

    /// Returns whether this resource conflicts with another normalized resource.
    boolean conflictsWith(TaskResource other) {
        Objects.requireNonNull(other, "other");
        requireResolved();
        other.requireResolved();
        if (scope == Scope.GLOBAL || other.scope == Scope.GLOBAL) {
            return true;
        }

        if (scope == Scope.ORCHESTRATION || other.scope == Scope.ORCHESTRATION) {
            return false;
        }

        if (isRepositoryScope() || other.isRepositoryScope()) {
            return repositoryScopeConflicts(other);
        }
        if (isCacheOperationScope() || other.isCacheOperationScope()) {
            return cacheOperationConflicts(other);
        }

        Path thisPath = Objects.requireNonNull(comparisonPath, "comparisonPath");
        Path otherPath = Objects.requireNonNull(other.comparisonPath, "other comparisonPath");
        if (scope == Scope.DIRECTORY || other.scope == Scope.DIRECTORY) {
            // Both operands may be complete directory trees.  Check both containment directions so conflict
            // detection remains symmetric regardless of declaration order.
            return thisPath.startsWith(otherPath) || otherPath.startsWith(thisPath);
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

        if (scope == Scope.ORCHESTRATION || other.scope == Scope.ORCHESTRATION) {
            return scope == Scope.ORCHESTRATION && other.scope == Scope.ORCHESTRATION;
        }

        if (isRepositoryScope() || other.isRepositoryScope()) {
            return scope == other.scope
                    && Objects.requireNonNull(comparisonPath, "comparisonPath")
                    .equals(other.comparisonPath);
        }
        if (isCacheOperationScope() || other.isCacheOperationScope()) {
            if (scope == Scope.CACHE_OPERATION && other.scope == Scope.CACHE_OPERATION) {
                return Objects.requireNonNull(comparisonPath, "comparisonPath")
                        .equals(other.comparisonPath);
            }
            if (scope != Scope.DIRECTORY) {
                return false;
            }
            Path thisPath = Objects.requireNonNull(comparisonPath, "comparisonPath");
            Path otherPath = Objects.requireNonNull(other.comparisonPath, "other comparisonPath");
            return otherPath.startsWith(thisPath);
        }

        Path thisPath = Objects.requireNonNull(comparisonPath, "comparisonPath");
        Path otherPath = Objects.requireNonNull(other.comparisonPath, "other comparisonPath");
        if (scope == Scope.DIRECTORY) {
            return otherPath.startsWith(thisPath);
        }
        return other.scope == Scope.FILE && thisPath.equals(otherPath);
    }

    /// Returns whether this declaration permits a nested task to acquire the supplied resource.
    ///
    /// Shared repository-operation owners permit precise descendants inside their repository without claiming those
    /// descendants themselves. This keeps exact child resources in normalized requests while preventing arbitrary
    /// lock expansion outside the audited repository boundary. Disjoint cache-operation branching is validated
    /// against the ancestor's complete resource set by the lock manager, rather than by one key in isolation.
    boolean permitsNested(TaskResource other) {
        Objects.requireNonNull(other, "other");
        // An orchestration node has no filesystem coverage. It can be nested beneath any owner as a marker, but it
        // cannot by itself authorize an arbitrary filesystem child; the owner-level check handles a pure orchestration
        // parent separately so a marker cannot mask a narrower directory held by an older ancestor.
        if (other.scope == Scope.ORCHESTRATION) {
            return true;
        }
        if (covers(other)) {
            return true;
        }
        if (scope == Scope.ORCHESTRATION && other.scope == Scope.ORCHESTRATION) {
            return true;
        }
        if (scope == Scope.DIRECTORY && kind == Kind.GAME_DIRECTORY && other.isRepositoryScope()) {
            Path directoryPath = Objects.requireNonNull(comparisonPath, "directory comparisonPath");
            Path repositoryPath = Objects.requireNonNull(other.comparisonPath, "repository comparisonPath");
            return repositoryPath.startsWith(directoryPath);
        }
        if (scope != Scope.REPOSITORY_OPERATION || other.path == null) {
            return false;
        }
        if (conflictsWith(other)) {
            return false;
        }
        Path repositoryPath = Objects.requireNonNull(comparisonPath, "repository comparisonPath");
        Path nestedPath = Objects.requireNonNull(other.comparisonPath, "nested comparisonPath");
        if (other.scope == Scope.REPOSITORY_METADATA) {
            return repositoryPath.equals(nestedPath);
        }
        if (other.isRepositoryScope()) {
            return false;
        }
        return nestedPath.startsWith(repositoryPath);
    }

    /// Normalizes, deduplicates, minimizes, and sorts one resolved resource collection.
    static @Unmodifiable List<TaskResource> normalize(Collection<TaskResource> resources) {
        Objects.requireNonNull(resources, "resources");
        if (resources.isEmpty()) {
            throw new IllegalArgumentException("Task resource declarations cannot be empty");
        }

        ArrayList<TaskResource> candidates = new ArrayList<>(resources.size());
        for (TaskResource resource : resources) {
            TaskResource checkedResource = Objects.requireNonNull(resource, "resource");
            checkedResource.requireResolved();
            candidates.add(checkedResource);
        }
        candidates.sort(ORDER);

        ArrayList<TaskResource> normalized = new ArrayList<>(candidates.size());
        for (TaskResource checkedResource : candidates) {
            if (normalized.stream().anyMatch(existing -> coversForNormalization(existing, checkedResource))) {
                continue;
            }
            normalized.removeIf(existing -> coversForNormalization(checkedResource, existing));
            normalized.add(checkedResource);
        }
        normalized.sort(ORDER);
        return List.copyOf(normalized);
    }

    /// Creates a defensive, stable-order declaration snapshot without eliminating lexical descendants.
    ///
    /// Coverage minimization is intentionally deferred until [TaskResourcePathIdentity] has resolved symbolic links
    /// and junctions. Removing a lexical child here could discard a path whose real target lies outside its apparent
    /// parent directory. The lock manager uses this snapshot as its input and performs the final minimization only on
    /// canonical identities.
    ///
    /// @param resources logical declarations
    /// @return immutable, deduplicated declarations in stable order
    static @Unmodifiable List<TaskResource> snapshotDeclarations(Collection<TaskResource> resources) {
        Objects.requireNonNull(resources, "resources");
        if (resources.isEmpty()) {
            throw new IllegalArgumentException("Task resource declarations cannot be empty");
        }

        ArrayList<TaskResource> snapshot = new ArrayList<>(resources.size());
        for (TaskResource resource : resources) {
            snapshot.add(Objects.requireNonNull(resource, "resource"));
        }
        snapshot.sort(ORDER);
        return List.copyOf(new java.util.LinkedHashSet<>(snapshot));
    }

    /// Returns whether one resource may replace another during normalization without losing kind-sensitive semantics.
    ///
    /// A game-directory declaration is also the permission boundary for repository coordination children. A broader
    /// directory with another semantic kind may cover the same path range, but replacing the game-directory marker
    /// would make that nested permission unavailable. Global coverage remains a true replacement for every resource.
    private static boolean coversForNormalization(TaskResource covering, TaskResource covered) {
        if (covering.scope == Scope.GLOBAL) {
            return true;
        }
        if (!covering.covers(covered)) {
            return false;
        }
        if (covered.kind == Kind.GAME_DIRECTORY) {
            // GAME_DIRECTORY is also the permission boundary for repository coordination descendants. Keep it even
            // when another directory kind covers the same range so normalization cannot erase that capability.
            return covering.kind == Kind.GAME_DIRECTORY;
        }
        if (covering.kind != covered.kind && covering.covers(covered) && covered.covers(covering)) {
            // Equal filesystem ranges can still carry different coordination or diagnostic semantics (for example,
            // DOWNLOAD_TARGET versus ADDON_FILE). Preserve both declarations instead of treating kind as cosmetic.
            return false;
        }
        return true;
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

    /// Returns whether this resource is one of the logical repository coordination scopes.
    private boolean isRepositoryScope() {
        return scope == Scope.REPOSITORY_METADATA || scope == Scope.REPOSITORY_OPERATION;
    }

    /// Returns whether this resource is an exclusive cache transaction.
    private boolean isCacheOperationScope() {
        return scope == Scope.CACHE_OPERATION;
    }

    /// Returns whether a logical repository request conflicts with another semantic resource.
    private boolean repositoryScopeConflicts(TaskResource other) {
        if (isRepositoryScope() && other.isRepositoryScope()) {
            if (scope == Scope.REPOSITORY_OPERATION && other.scope == Scope.REPOSITORY_OPERATION) {
                return false;
            }
            if (scope != other.scope) {
                return false;
            }
            Path thisPath = Objects.requireNonNull(comparisonPath, "comparisonPath");
            Path otherPath = Objects.requireNonNull(other.comparisonPath, "other comparisonPath");
            return thisPath.startsWith(otherPath) || otherPath.startsWith(thisPath);
        }

        TaskResource repositoryResource = isRepositoryScope() ? this : other;
        TaskResource candidate = isRepositoryScope() ? other : this;
        if (candidate.kind != Kind.GAME_DIRECTORY) {
            return false;
        }

        Path repositoryPath = Objects.requireNonNull(
                repositoryResource.comparisonPath,
                "repository comparisonPath");
        Path candidatePath = Objects.requireNonNull(candidate.comparisonPath, "candidate comparisonPath");
        return repositoryPath.startsWith(candidatePath);
    }

    /// Returns whether a cache operation conflicts with an overlapping cache or filesystem resource.
    private boolean cacheOperationConflicts(TaskResource other) {
        if (isCacheOperationScope() && other.isCacheOperationScope()) {
            Path thisPath = Objects.requireNonNull(comparisonPath, "cache comparisonPath");
            Path otherPath = Objects.requireNonNull(other.comparisonPath, "other cache comparisonPath");
            return thisPath.startsWith(otherPath) || otherPath.startsWith(thisPath);
        }

        TaskResource cacheResource = isCacheOperationScope() ? this : other;
        TaskResource candidate = isCacheOperationScope() ? other : this;
        Path cachePath = Objects.requireNonNull(cacheResource.comparisonPath, "cache comparisonPath");
        Path candidatePath = Objects.requireNonNull(candidate.comparisonPath, "candidate comparisonPath");
        if (candidate.scope == Scope.DIRECTORY) {
            return cachePath.startsWith(candidatePath) || candidatePath.startsWith(cachePath);
        }
        return candidate.scope == Scope.FILE && candidatePath.startsWith(cachePath);
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
        /// Pure task-graph orchestration marker with no filesystem coverage.
        ORCHESTRATION,
        /// Complete game-directory tree.
        GAME_DIRECTORY,
        /// Repository instance-catalog and destination-name resolution scope.
        REPOSITORY_METADATA,
        /// Shared execution domain for independent operations in one repository.
        REPOSITORY_OPERATION,
        /// Complete game-instance tree.
        GAME_INSTANCE,
        /// Exact download destination.
        DOWNLOAD_TARGET,
        /// Exact managed add-on source, archive, or destination.
        ADDON_FILE,
        /// Complete managed Java runtime tree.
        JAVA_RUNTIME,
        /// Complete launcher-upgrade tree.
        LAUNCHER_UPGRADE,
        /// Complete shared-cache tree.
        CACHE,
        /// Exclusive transaction in one cache tree.
        CACHE_OPERATION,
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
        /// Pure graph-orchestration marker with no filesystem path.
        ORCHESTRATION(2),
        /// Complete directory tree.
        DIRECTORY(2),
        /// Repository metadata scope keyed by one normalized repository path.
        REPOSITORY_METADATA(2),
        /// Shared repository operation scope keyed by one normalized repository path.
        REPOSITORY_OPERATION(2),
        /// Exclusive cache transaction keyed by one normalized cache path.
        CACHE_OPERATION(2),
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
