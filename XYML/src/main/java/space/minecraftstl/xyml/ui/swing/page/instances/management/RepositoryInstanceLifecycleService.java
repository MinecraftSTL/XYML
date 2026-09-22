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
package space.minecraftstl.xyml.ui.swing.page.instances.management;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import space.minecraftstl.xyml.game.GameInstanceID;
import space.minecraftstl.xyml.game.GameRepository;
import space.minecraftstl.xyml.game.XYMLGameRepository;
import space.minecraftstl.xyml.setting.SettingsManager;
import space.minecraftstl.xyml.task.Schedulers;
import space.minecraftstl.xyml.task.Task;
import space.minecraftstl.xyml.task.TaskResource;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;
import space.minecraftstl.xyml.util.FileSaver;
import space.minecraftstl.xyml.util.io.DeletionMode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/// Adapts the established `GameRepository` and `XYMLGameRepository` lifecycle APIs for task-based callers.
///
/// Disk mutations deliberately delegate to the existing repository implementation: rename uses the
/// `GameRepository` contract, while duplicate and removal use XYML's instance-aware methods. Each
/// successful mutation refreshes the repository in a separately resourced task stage so consumers receive an
/// authoritative `RefreshedGameInstancesEvent` before the complete lifecycle task terminates.
@NotNullByDefault
public final class RepositoryInstanceLifecycleService implements InstanceLifecycleService {
    /// Immutable rename input captured during the short repository-metadata phase.
    ///
    /// @param source source identifier
    /// @param destination destination identifier
    /// @param affectedChildren direct child manifests rewritten by the rename
    @NotNullByDefault
    public record RenamePreparation(
            GameInstanceID source,
            GameInstanceID destination,
            @Unmodifiable Set<GameInstanceID> affectedChildren) {
        /// Creates a defensive rename preparation snapshot.
        public RenamePreparation {
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(destination, "destination");
            affectedChildren = Set.copyOf(Objects.requireNonNull(affectedChildren, "affectedChildren"));
        }
    }

    /// Repository owning the instance files and persistent selection state.
    private final XYMLGameRepository repository;

    /// Creates a lifecycle service backed by one real XYML repository.
    ///
    /// @param repository repository containing managed instances
    public RepositoryInstanceLifecycleService(XYMLGameRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository");
    }

    /// Returns whether a candidate satisfies XYML's existing portable instance-ID rule.
    ///
    /// @param destinationId candidate destination identifier
    /// @return whether the identifier is safe for an instance directory
    @Override
    public boolean isValidDestinationId(String destinationId) {
        return XYMLGameRepository.isValidInstanceId(Objects.requireNonNull(destinationId, "destinationId"));
    }

    /// Renames an existing instance through `GameRepository.renameInstance` and refreshes the index.
    ///
    /// @param sourceId stable existing source identifier
    /// @param destinationId validated target identifier
    /// @throws IOException when the target conflicts or rename reports failure
    @Override
    public void rename(GameInstanceID sourceId, GameInstanceID destinationId) throws IOException {
        renameWithoutRefresh(sourceId, destinationId);
        refreshRepository();
    }

    /// Creates a two-phase rename task that serializes name resolution briefly and locks only affected instances
    /// during the filesystem mutation.
    ///
    /// @param sourceId stable existing source identifier
    /// @param destinationId validated target identifier
    /// @return unstarted task covering rename and terminal repository refresh
    @Override
    public Task<@Nullable Void> renameTask(GameInstanceID sourceId, GameInstanceID destinationId) {
        GameInstanceID source = Objects.requireNonNull(sourceId, "sourceId");
        GameInstanceID destination = Objects.requireNonNull(destinationId, "destinationId");
        PathSnapshot paths = paths(source, destination);
        AtomicBoolean committed = new AtomicBoolean();
        return Task.<@Nullable Void>composeAsync("Resolve instance rename", () -> {
            requireRepositoryDirectory(paths.repositoryDirectory());
            RenamePreparation preparation = repository.withStableBaseDirectory(
                    paths.repositoryDirectory(), () -> prepareRename(source, destination));
            List<TaskResource> resources = new ArrayList<>();
            resources.add(TaskResource.repositoryOperation(paths.repositoryDirectory()));
            resources.add(TaskResource.gameInstance(paths.sourceDirectory()));
            resources.add(TaskResource.gameInstance(paths.destinationDirectory()));
            preparation.affectedChildren().stream()
                    .map(child -> paths.repositoryDirectory().resolve("versions").resolve(child.id()))
                    .map(TaskResource::gameInstance)
                    .forEach(resources::add);
            Task<@Nullable Void> mutation = withResources(
                     Task.runAsync("Rename game instance", Schedulers.io(),
                             () -> {
                                 repository.withStableBaseDirectory(paths.repositoryDirectory(), () -> {
                                     renameWithoutRefresh(source, destination, preparation);
                                     committed.set(true);
                                 });
                             }),
                    resources);
            return refreshAfter(mutation, paths.repositoryDirectory(), destination, committed);
        }).setExecutor(Schedulers.io())
                .setResources(TaskResource.repositoryMetadata(paths.repositoryDirectory()))
                .releaseResourcesBeforeDependencies();
    }

    /// Captures and validates every direct child manifest that a rename must rewrite.
    ///
    /// @param sourceId stable existing source identifier
    /// @param destinationId validated target identifier
    /// @return immutable rename preparation snapshot
    /// @throws IOException when the source is missing or destination conflicts
    public RenamePreparation prepareRename(GameInstanceID sourceId, GameInstanceID destinationId) throws IOException {
        GameInstanceID source = Objects.requireNonNull(sourceId, "sourceId");
        GameInstanceID destination = Objects.requireNonNull(destinationId, "destinationId");
        if (source.equals(destination)) {
            throw new IOException("The new instance name must differ from the current name");
        }
        if (!repository.hasInstance(source)) {
            throw new IOException("The source instance does not exist");
        }
        requireDestinationAvailable(source, destination);
        Set<GameInstanceID> affectedChildren = repository.getInstanceManifests().stream()
                .filter(manifest -> source.equals(manifest.inheritsFrom()))
                .map(manifest -> manifest.id())
                .collect(Collectors.toUnmodifiableSet());
        return new RenamePreparation(source, destination, affectedChildren);
    }

    /// Applies a prepared rename after confirming that its affected instance set did not change while waiting.
    ///
    /// @param sourceId stable existing source identifier
    /// @param destinationId validated target identifier
    /// @param preparation immutable metadata snapshot used for resource acquisition
    /// @throws IOException when metadata changed, the target conflicts, or the rename reports failure
    public void renameWithoutRefresh(
            GameInstanceID sourceId,
            GameInstanceID destinationId,
            RenamePreparation preparation) throws IOException {
        GameInstanceID source = Objects.requireNonNull(sourceId, "sourceId");
        GameInstanceID destination = Objects.requireNonNull(destinationId, "destinationId");
        RenamePreparation captured = Objects.requireNonNull(preparation, "preparation");
        if (!source.equals(captured.source()) || !destination.equals(captured.destination())) {
            throw new IllegalArgumentException("Rename preparation belongs to a different operation");
        }
        RenamePreparation current = prepareRename(source, destination);
        if (!captured.affectedChildren().equals(current.affectedChildren())) {
            throw new IOException("Instance inheritance changed while waiting to rename");
        }
        renameWithoutRefresh(source, destination);
    }

    /// Renames an existing instance without refreshing the complete repository.
    ///
    /// This method is the precise disk-mutation phase for callers that must release instance resources before a
    /// separate repository-wide refresh.
    ///
    /// @param sourceId stable existing source identifier
    /// @param destinationId validated target identifier
    /// @throws IOException when the target conflicts or rename reports failure
    public void renameWithoutRefresh(GameInstanceID sourceId, GameInstanceID destinationId) throws IOException {
        GameInstanceID source = Objects.requireNonNull(sourceId, "sourceId");
        GameInstanceID destination = Objects.requireNonNull(destinationId, "destinationId");
        if (source.equals(destination)) {
            throw new IOException("The new instance name must differ from the current name");
        }
        requireDestinationAvailable(source, destination);
        GameRepository gameRepository = repository;
        if (!gameRepository.renameInstance(source, destination)) {
            throw new IOException("The instance could not be renamed");
        }
    }

    /// Copies an instance through the repository's established copy routine and refreshes the index.
    ///
    /// @param sourceId stable existing source identifier
    /// @param destinationId validated target identifier
    /// @param copySaves whether source worlds should be copied
    /// @throws IOException when the source is missing, destination conflicts, or copying fails
    @Override
    public void duplicate(GameInstanceID sourceId, GameInstanceID destinationId, boolean copySaves) throws IOException {
        duplicateWithoutRefresh(sourceId, destinationId, copySaves);
        refreshRepository();
    }

    /// Creates a staged duplication task whose long copy phase locks only the source, destination, and captured run
    /// directory before a short terminal repository refresh.
    ///
    /// @param sourceId stable existing source identifier
    /// @param destinationId validated target identifier
    /// @param copySaves whether source worlds should be copied
    /// @return unstarted task covering duplication and terminal repository refresh
    @Override
    public Task<@Nullable Void> duplicateTask(
            GameInstanceID sourceId,
            GameInstanceID destinationId,
            boolean copySaves) {
        GameInstanceID source = Objects.requireNonNull(sourceId, "sourceId");
        GameInstanceID destination = Objects.requireNonNull(destinationId, "destinationId");
        PathSnapshot paths = paths(source, destination);
        AtomicBoolean committed = new AtomicBoolean();
        return Task.<@Nullable Void>composeAsync("Resolve instance duplication", () -> {
            requireRepositoryDirectory(paths.repositoryDirectory());
            XYMLGameRepository.InstanceDuplicationSnapshot snapshot = repository.withStableBaseDirectory(
                    paths.repositoryDirectory(), () -> prepareDuplicate(source));
            Task<@Nullable Void> mutation = Task.runAsync("Duplicate game instance", Schedulers.io(), () -> {
                repository.withStableBaseDirectory(paths.repositoryDirectory(), () -> {
                    duplicateWithoutRefresh(source, destination, copySaves, snapshot);
                    committed.set(true);
                });
            })
                    .setResources(
                            TaskResource.repositoryOperation(paths.repositoryDirectory()),
                            TaskResource.gameInstance(paths.sourceDirectory()),
                            TaskResource.gameInstance(paths.destinationDirectory()),
                            TaskResource.gameDirectory(snapshot.sourceRunDirectory()));
            return refreshAfter(mutation, paths.repositoryDirectory(), destination, committed);
        }).setExecutor(Schedulers.io())
                .setResources(
                        TaskResource.configuration(repository.getInstanceGameSettingsFile(source)),
                        TaskResource.configuration(SettingsManager.gameSettingsLocation()),
                        TaskResource.configuration(SettingsManager.settingsLocation()))
                .releaseResourcesBeforeDependencies();
    }

    /// Duplicates an existing instance without refreshing the complete repository.
    ///
    /// @param sourceId stable existing source identifier
    /// @param destinationId validated target identifier
    /// @param copySaves whether source worlds should be copied
    /// @throws IOException when the source is missing, destination conflicts, or copying fails
    public void duplicateWithoutRefresh(
            GameInstanceID sourceId,
            GameInstanceID destinationId,
            boolean copySaves) throws IOException {
        GameInstanceID source = Objects.requireNonNull(sourceId, "sourceId");
        GameInstanceID destination = Objects.requireNonNull(destinationId, "destinationId");
        if (source.equals(destination)) {
            throw new IOException("The duplicate instance name must differ from the source name");
        }
        requireDestinationAvailable(source, destination);
        repository.duplicateInstance(source, destination, copySaves);
    }

    /// Captures setting-derived duplicate inputs before the long filesystem stage starts.
    ///
    /// @param sourceId stable existing source identifier
    /// @return immutable source-running-directory and settings snapshot
    /// @throws IOException when pending settings writes are interrupted
    public XYMLGameRepository.InstanceDuplicationSnapshot prepareDuplicate(GameInstanceID sourceId)
            throws IOException {
        return repository.prepareInstanceDuplication(Objects.requireNonNull(sourceId, "sourceId"));
    }

    /// Duplicates an existing instance from an immutable preparation snapshot without refreshing the repository.
    ///
    /// @param sourceId stable existing source identifier
    /// @param destinationId validated target identifier
    /// @param copySaves whether source worlds should be copied
    /// @param snapshot setting-derived inputs captured before filesystem resource acquisition
    /// @throws IOException when the source is missing, destination conflicts, or copying fails
    public void duplicateWithoutRefresh(
            GameInstanceID sourceId,
            GameInstanceID destinationId,
            boolean copySaves,
            XYMLGameRepository.InstanceDuplicationSnapshot snapshot) throws IOException {
        GameInstanceID source = Objects.requireNonNull(sourceId, "sourceId");
        GameInstanceID destination = Objects.requireNonNull(destinationId, "destinationId");
        if (source.equals(destination)) {
            throw new IOException("The duplicate instance name must differ from the source name");
        }
        requireDestinationAvailable(source, destination);
        repository.duplicateInstance(
                source, destination, copySaves, Objects.requireNonNull(snapshot, "snapshot"));
    }

    /// Removes an instance through XYML's recycle-bin-aware removal routine and refreshes the index.
    ///
    /// @param sourceId stable existing source identifier
    /// @throws IOException when removal reports failure
    @Override
    public void delete(GameInstanceID sourceId) throws IOException {
        deleteWithoutRefresh(sourceId);
        refreshRepository();
    }

    /// Creates a deletion task locking one instance and its recycle destination before a short terminal refresh.
    ///
    /// @param sourceId stable existing source identifier
    /// @return unstarted task covering deletion and terminal repository refresh
    @Override
    public Task<@Nullable Void> deleteTask(GameInstanceID sourceId) {
        return deleteTask(sourceId, DeletionMode.PERMANENT);
    }

    /// Creates a deletion task that uses the exact recycle-bin or permanent mode chosen by the caller.
    ///
    /// @param sourceId stable existing source identifier
    /// @param mode selected deletion behavior
    /// @return unstarted task covering deletion and terminal repository refresh
    @Override
    public Task<@Nullable Void> deleteTask(GameInstanceID sourceId, DeletionMode mode) {
        GameInstanceID source = Objects.requireNonNull(sourceId, "sourceId");
        DeletionMode requestedMode = Objects.requireNonNull(mode, "mode");
        PathSnapshot paths = paths(source, source);
        AtomicBoolean committed = new AtomicBoolean();
        Task<@Nullable Void> mutation = Task.runAsync("Delete game instance", Schedulers.io(), () -> {
            repository.withStableBaseDirectory(paths.repositoryDirectory(), () -> {
                deleteWithoutRefresh(source, requestedMode);
                committed.set(true);
            });
        })
                .setResources(
                        TaskResource.gameInstance(paths.sourceDirectory()),
                        TaskResource.gameDirectory(paths.sourceDirectory().resolveSibling(source.id() + "_removed")));
        return refreshAfter(mutation, paths.repositoryDirectory(), null, committed);
    }

    /// Removes an existing instance without refreshing the complete repository.
    ///
    /// @param sourceId stable existing source identifier
    /// @throws IOException when removal reports failure
    public void deleteWithoutRefresh(GameInstanceID sourceId) throws IOException {
        GameInstanceID source = Objects.requireNonNull(sourceId, "sourceId");
        if (!repository.removeInstanceFromDiskWithoutRefresh(source)) {
            throw new IOException("The instance could not be deleted");
        }
    }

    /// Removes an existing instance without repository refresh using the selected deletion mode.
    ///
    /// @param sourceId stable existing source identifier
    /// @param mode selected deletion behavior
    /// @throws IOException when the selected removal fails
    public void deleteWithoutRefresh(GameInstanceID sourceId, DeletionMode mode) throws IOException {
        GameInstanceID source = Objects.requireNonNull(sourceId, "sourceId");
        DeletionMode requestedMode = Objects.requireNonNull(mode, "mode");
        if (requestedMode == DeletionMode.RECYCLE_BIN_FIRST) {
            repository.removeInstanceToTrashWithoutRefresh(source);
        } else {
            repository.removeInstancePermanentlyWithoutRefresh(source);
        }
    }

    /// Rebuilds repository manifests and instance settings after a completed disk mutation.
    public void refreshRepository() {
        repository.refresh();
    }

    /// Refreshes the repository, reconciles selection on the EDT, and drains the resulting settings save.
    ///
    /// Every cleanup stage is attempted even if an earlier one fails. The first cleanup failure remains primary and
    /// later failures are suppressed so repository and selection recovery is best-effort without swallowing errors.
    ///
    /// @param repositoryDirectory repository root captured when the lifecycle task was created
    /// @param preferredSelection preferred destination after a committed rename or duplicate, or null
    /// @param committed whether the disk mutation returned successfully
    /// @throws Exception when a cleanup stage fails with a checked exception
    public void completeLifecycle(
            Path repositoryDirectory,
            @Nullable GameInstanceID preferredSelection,
            boolean committed) throws Exception {
        try {
            requireRepositoryDirectory(repositoryDirectory);
        } catch (RuntimeException | Error rootFailure) {
            try {
                FileSaver.waitForAllSaves();
            } catch (Throwable saveFailure) {
                rootFailure.addSuppressed(saveFailure);
            }
            throw rootFailure;
        }
        @Nullable Throwable cleanupFailure = null;
        try {
            repository.withStableBaseDirectory(repositoryDirectory, this::refreshRepository);
        } catch (Throwable failure) {
            cleanupFailure = failure;
        }
        try {
            @Nullable GameInstanceID selected = committed ? preferredSelection : null;
            EdtDispatcher.executeAndWait(() -> {
                try {
                    repository.withStableBaseDirectory(
                            repositoryDirectory,
                            () -> reconcileSelection(selected));
                } catch (IOException impossible) {
                    throw new UncheckedIOException("Unable to reconcile instance selection", impossible);
                }
            });
        } catch (Throwable failure) {
            cleanupFailure = retainCleanupFailure(cleanupFailure, failure);
        }
        try {
            FileSaver.waitForAllSaves();
        } catch (Throwable failure) {
            cleanupFailure = retainCleanupFailure(cleanupFailure, failure);
        }
        rethrowCleanupFailure(cleanupFailure);
    }

    /// Reconciles the repository's persisted selection on the Swing event dispatch thread.
    ///
    /// @param preferredId renamed or duplicated instance ID, or `null` after deletion
    @Override
    public void reconcileSelection(@Nullable GameInstanceID preferredId) {
        if (preferredId != null && repository.hasInstance(preferredId)) {
            repository.setSelectedInstance(preferredId);
        } else {
            repository.refreshSelectedInstance();
        }
    }

    /// Rejects a destination already known by the loaded repository before a destructive mutation begins.
    ///
    /// @param source current source identifier
    /// @param destination requested destination identifier
    /// @throws IOException when another instance already owns the destination ID
    private void requireDestinationAvailable(GameInstanceID source, GameInstanceID destination) throws IOException {
        if (!source.equals(destination) && repository.instanceIdConflicts(destination)) {
            throw new IOException("An instance with that name already exists");
        }
    }

    /// Adds a non-empty dynamically prepared resource list to one task.
    ///
    /// @param task task receiving the declarations
    /// @param resources non-empty resource list
    /// @param <T> task result type
    /// @return the supplied task
    private static <T> Task<T> withResources(Task<T> task, List<TaskResource> resources) {
        if (resources.isEmpty()) {
            throw new IllegalArgumentException("Lifecycle mutation resources cannot be empty");
        }
        TaskResource[] additional = resources.subList(1, resources.size()).toArray(TaskResource[]::new);
        return task.setResources(resources.get(0), additional);
    }

    /// Appends an independently resourced repository refresh after every mutation terminal outcome.
    ///
    /// @param mutation precise instance mutation
    /// @param repositoryDirectory captured repository root
    /// @param preferredSelection preferred destination after a committed rename or duplicate, or null
    /// @param committed whether the disk mutation returned successfully
    /// @return terminal refresh coordinator
    private Task<@Nullable Void> refreshAfter(
            Task<@Nullable Void> mutation,
            Path repositoryDirectory,
            @Nullable GameInstanceID preferredSelection,
            AtomicBoolean committed) {
        return mutation.whenTerminalWithResources(
                Schedulers.io(),
                ignoredFailure -> completeLifecycle(repositoryDirectory, preferredSelection, committed.get()),
                TaskResource.gameDirectory(repositoryDirectory),
                TaskResource.configuration(SettingsManager.settingsLocation()))
                .asOrchestration();
    }

    /// Retains the first cleanup failure and attaches every later distinct failure as suppressed context.
    ///
    /// @param current current primary cleanup failure, or null
    /// @param additional later cleanup failure
    /// @return primary cleanup failure
    private static Throwable retainCleanupFailure(
            @Nullable Throwable current,
            Throwable additional) {
        Throwable checkedAdditional = Objects.requireNonNull(additional, "additional");
        if (current == null) {
            return checkedAdditional;
        }
        if (current != checkedAdditional) {
            current.addSuppressed(checkedAdditional);
        }
        return current;
    }

    /// Rethrows a cleanup failure while preserving Exception and Error identity.
    ///
    /// @param failure cleanup failure, or null after complete recovery
    /// @throws Exception when cleanup failed with a checked exception
    private static void rethrowCleanupFailure(@Nullable Throwable failure) throws Exception {
        if (failure instanceof Exception exception) {
            throw exception;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        if (failure != null) {
            throw new IllegalStateException("Unsupported lifecycle cleanup failure", failure);
        }
    }

    /// Rejects use of paths captured from a repository root that changed while a task was waiting.
    ///
    /// @param expectedDirectory normalized repository root captured during task construction
    private void requireRepositoryDirectory(Path expectedDirectory) {
        Path currentDirectory = repository.getBaseDirectory().toAbsolutePath().normalize();
        if (!expectedDirectory.equals(currentDirectory)) {
            throw new IllegalStateException("Game repository directory changed while waiting for resources");
        }
    }

    /// Captures stable repository, source, and destination paths before task construction.
    ///
    /// @param source source instance identifier
    /// @param destination destination instance identifier
    /// @return immutable normalized lifecycle paths
    private PathSnapshot paths(GameInstanceID source, GameInstanceID destination) {
        Path repositoryDirectory = repository.getBaseDirectory().toAbsolutePath().normalize();
        return new PathSnapshot(
                repositoryDirectory,
                repositoryDirectory.resolve("versions").resolve(source.id()),
                repositoryDirectory.resolve("versions").resolve(destination.id()));
    }

    /// Immutable paths used by a lifecycle task graph.
    ///
    /// @param repositoryDirectory normalized repository root
    /// @param sourceDirectory normalized source instance root
    /// @param destinationDirectory normalized destination instance root
    @NotNullByDefault
    private record PathSnapshot(
            Path repositoryDirectory,
            Path sourceDirectory,
            Path destinationDirectory) {
        /// Creates a validated path snapshot.
        private PathSnapshot {
            Objects.requireNonNull(repositoryDirectory, "repositoryDirectory");
            Objects.requireNonNull(sourceDirectory, "sourceDirectory");
            Objects.requireNonNull(destinationDirectory, "destinationDirectory");
        }
    }

}
