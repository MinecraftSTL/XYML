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
package space.minecraftstl.xyml.ui.swing.page.instances.management.addonupdates;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import space.minecraftstl.xyml.addon.LocalAddonFile;
import space.minecraftstl.xyml.addon.LocalAddonManager;
import space.minecraftstl.xyml.addon.RemoteAddon;
import space.minecraftstl.xyml.download.DownloadProvider;
import space.minecraftstl.xyml.task.FileDownloadTask;
import space.minecraftstl.xyml.task.Schedulers;
import space.minecraftstl.xyml.task.Task;
import space.minecraftstl.xyml.util.StringUtils;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BooleanSupplier;

import static space.minecraftstl.xyml.util.logging.Logger.LOG;

/// Applies scanned add-on updates through staged Core downloads and serialized local-state transitions.
///
/// Task construction performs read-only path preflight but no network access or mutation. Started sibling
/// downloads can run concurrently, while every `setOld`, delete, publish, and restore transition is guarded by
/// one service lock so shared local-Mod collections are never mutated concurrently.
@NotNullByDefault
public final class RepositoryAddonUpdateApplicationService implements AddonUpdateApplicationService {
    /// Existing translation key used as the aggregate progress stage and counter.
    private static final String UPDATE_STAGE = "addon.check_update.confirm";

    /// Prefix identifying staging files owned exclusively by this service.
    private static final String STAGING_PREFIX = ".xyml-addon-update-";

    /// Stable provider used to produce ordered mirror and origin candidate URLs.
    private final DownloadProvider downloadProvider;

    /// Executor used for preparation, downloads, and task continuations.
    private final Executor ioExecutor;

    /// Deferred download-task factory, replaceable only by package tests.
    private final DownloadTaskFactory downloadTaskFactory;

    /// Package-test observer invoked immediately before a commit waits for the shared state lock.
    private final Runnable beforeCommitLock;

    /// Serializes local manager mutation and publication across old and newly constructed service instances.
    private static final ReentrantLock STATE_LOCK = new ReentrantLock();

    /// Creates a production service using the shared I/O scheduler.
    ///
    /// @param downloadProvider launcher-configured download provider
    public RepositoryAddonUpdateApplicationService(DownloadProvider downloadProvider) {
        this(
                downloadProvider,
                Schedulers.io(),
                RepositoryAddonUpdateApplicationService::createDownloadTask,
                () -> {
                });
    }

    /// Creates a service with deterministic execution and download-task boundaries for tests.
    ///
    /// @param downloadProvider provider used to transform remote artifact URLs
    /// @param ioExecutor executor used for preparation, downloads, and continuations
    /// @param downloadTaskFactory factory creating one deferred artifact download
    RepositoryAddonUpdateApplicationService(
            DownloadProvider downloadProvider,
            Executor ioExecutor,
            DownloadTaskFactory downloadTaskFactory) {
        this(downloadProvider, ioExecutor, downloadTaskFactory, () -> {
        });
    }

    /// Creates a service with deterministic commit-lock observation for concurrency tests.
    ///
    /// @param downloadProvider provider used to transform remote artifact URLs
    /// @param ioExecutor executor used for preparation, downloads, and continuations
    /// @param downloadTaskFactory factory creating one deferred artifact download
    /// @param beforeCommitLock observer invoked immediately before the shared commit lock is acquired
    RepositoryAddonUpdateApplicationService(
            DownloadProvider downloadProvider,
            Executor ioExecutor,
            DownloadTaskFactory downloadTaskFactory,
            Runnable beforeCommitLock) {
        this.downloadProvider = Objects.requireNonNull(downloadProvider, "downloadProvider");
        this.ioExecutor = Objects.requireNonNull(ioExecutor, "ioExecutor");
        this.downloadTaskFactory = Objects.requireNonNull(downloadTaskFactory, "downloadTaskFactory");
        this.beforeCommitLock = Objects.requireNonNull(beforeCommitLock, "beforeCommitLock");
    }

    /// Creates one stopped task graph for an exact ordered selection.
    ///
    /// @param updates exact selected update items
    /// @return stopped aggregate task with value-based partial failures
    @Override
    public Task<AddonUpdateApplicationResult> applyUpdates(Collection<AddonUpdateItem> updates) {
        BatchPlan batchPlan = planSelection(updates);
        List<OperationState> states = new ArrayList<>(batchPlan.updates().size());
        List<Task<UpdateOutcome>> operations = new ArrayList<>(batchPlan.updates().size());
        for (PlannedUpdate plannedUpdate : batchPlan.updates()) {
            OperationState state = new OperationState(plannedUpdate, batchPlan.protectedPaths());
            states.add(state);
            operations.add(createOperation(state));
        }

        ApplicationTask task = new ApplicationTask(batchPlan.selectedItems(), operations);
        task.onDone().register(event -> {
            if (event.isFailed()) {
                recoverCancelledOperations(states);
            }
        });
        return task;
    }

    /// Builds one operation that records ordinary per-item failures as a successful outcome value.
    ///
    /// @param state mutable lifecycle holder guarded by [#STATE_LOCK]
    /// @return stopped operation task
    private Task<UpdateOutcome> createOperation(OperationState state) {
        Task<PreparedUpdate> preparation = Task.supplyAsync(
                ioExecutor,
                () -> prepareUpdate(state));
        Task<@Nullable Void> download = preparation.thenComposeAsync(
                ioExecutor,
                (@Nullable PreparedUpdate prepared) -> {
                    PreparedUpdate exactPreparation = Objects.requireNonNull(
                            prepared,
                            "completed update preparation");
                    return downloadTaskFactory.create(
                            exactPreparation.candidates(),
                            exactPreparation.stagingPath(),
                            exactPreparation.plan().destinationOrThrow(),
                            exactPreparation.integrityCheck(),
                            exactPreparation.downloadName());
                });
        Task<@Nullable Void> completedDownload = download.thenRunAsync(
                ioExecutor,
                () -> markDownloadCompleted(state));
        return new UpdateOperationTask(state, preparation, completedDownload, ioExecutor)
                .withCounter(UPDATE_STAGE);
    }

    /// Performs runtime collision checks and creates staging without mutating the selected local source.
    ///
    /// @param state operation lifecycle holder
    /// @return prepared staged download context
    /// @throws IOException when paths changed, became occupied, or cannot be staged safely
    private PreparedUpdate prepareUpdate(OperationState state) throws IOException {
        STATE_LOCK.lock();
        try {
            PlannedUpdate plan = state.plan();
            @Nullable String planningFailure = plan.planningFailure();
            if (planningFailure != null) {
                throw new IOException(planningFailure);
            }

            LocalAddonFile.AddonUpdate update = plan.updateItem().update();
            LocalAddonFile localAddonFile = update.localAddonFile();
            Path currentPath = localAddonFile.getFile().toAbsolutePath().normalize();
            if (!currentPath.equals(plan.sourcePath())) {
                throw new IOException("Add-on path changed after update scan: " + currentPath);
            }
            if (!pathExists(currentPath)) {
                throw new IOException("Selected add-on no longer exists: " + currentPath);
            }
            if (pathExists(plan.archivePath())) {
                throw new IOException("Old-file archive path is already occupied: " + plan.archivePath());
            }

            Path destination = plan.destinationOrThrow();
            if (!destination.equals(currentPath) && pathExists(destination)) {
                throw new IOException("Update destination became occupied: " + destination);
            }

            RemoteAddon.Version remoteVersion = update.targetVersion();
            RemoteAddon.File remoteFile = Objects.requireNonNull(
                    remoteVersion.file(),
                    "update.targetVersion.file");
            String remoteUrl = Objects.requireNonNull(remoteFile.url(), "remote file URL");
            @Unmodifiable List<URI> candidates = List.copyOf(
                    downloadProvider.injectURLWithCandidates(remoteUrl));
            if (candidates.isEmpty()) {
                throw new IOException("Download provider returned no candidates for " + remoteUrl);
            }
            @Nullable String remoteName = remoteVersion.name();
            String downloadName = remoteName == null || remoteName.isBlank()
                    ? Objects.requireNonNull(remoteFile.filename(), "remote file name")
                    : remoteName;

            Path stagingPath = createStagingPath(destination, state.protectedPaths());
            PreparedUpdate prepared = new PreparedUpdate(
                    plan,
                    localAddonFile,
                    stagingPath,
                    candidates,
                    remoteFile.getIntegrityCheck(),
                    downloadName,
                    isDisabledPath(currentPath));
            state.installPrepared(prepared);
            return prepared;
        } finally {
            STATE_LOCK.unlock();
        }
    }

    /// Creates an empty unique staging file beside the final destination without touching protected paths.
    ///
    /// @param destination validated final destination
    /// @param protectedPaths every selected source, generated archive, and final destination path
    /// @return unique owned staging path
    /// @throws IOException when staging cannot be created safely
    private static Path createStagingPath(
            Path destination,
            @Unmodifiable Set<Path> protectedPaths) throws IOException {
        @Nullable Path directory = destination.getParent();
        if (directory == null) {
            throw new IOException("Update destination has no parent directory: " + destination);
        }
        Path stagingPath = Files.createTempFile(
                directory,
                STAGING_PREFIX,
                ".part").toAbsolutePath().normalize();
        if (protectedPaths.contains(stagingPath) || stagingPath.equals(destination)) {
            Files.deleteIfExists(stagingPath);
            throw new IOException("Generated staging path conflicts with protected update paths: " + stagingPath);
        }
        return stagingPath;
    }

    /// Marks that the staged artifact download completed before final publication.
    ///
    /// @param state operation lifecycle holder
    private void markDownloadCompleted(OperationState state) {
        STATE_LOCK.lock();
        try {
            if (!state.isFinalized()) {
                state.markDownloadCompleted();
            }
        } finally {
            STATE_LOCK.unlock();
        }
    }

    /// Commits a staged replacement without overwrite, then applies the configured old-file retention behavior.
    ///
    /// Cancellation remains effective through the final read-only path validation. Once the last cancellation
    /// check passes, archive and publication execute as one non-cancellable transaction under [#STATE_LOCK].
    ///
    /// @param state completed operation lifecycle
    /// @param cancellationRequested live cancellation state checked after acquiring the shared lock
    /// @return value-based success or rollback failure
    private UpdateOutcome commitUpdate(
            OperationState state,
            BooleanSupplier cancellationRequested) {
        BooleanSupplier liveCancellation = Objects.requireNonNull(
                cancellationRequested,
                "cancellationRequested");
        beforeCommitLock.run();
        STATE_LOCK.lock();
        try {
            @Nullable PreparedUpdate prepared = state.prepared();
            if (prepared == null) {
                state.markFinalized();
                return UpdateOutcome.failure(
                        state.plan().updateItem(),
                        "Completed add-on update preparation has no result");
            }
            if (!state.isDownloadCompleted() || !Files.isRegularFile(
                    prepared.stagingPath(),
                    LinkOption.NOFOLLOW_LINKS)) {
                String detail = recoverLocked(
                        state,
                        "Downloaded add-on staging file is missing or invalid");
                return UpdateOutcome.failure(state.plan().updateItem(), detail);
            }

            PlannedUpdate plan = prepared.plan();
            Path destination = Objects.requireNonNull(
                    prepared.plan().destinationOrNull(),
                    "prepared destination");
            Path currentPath = prepared.localAddonFile().getFile().toAbsolutePath().normalize();
            if (!currentPath.equals(plan.sourcePath()) || !pathExists(currentPath)) {
                String detail = recoverLocked(
                        state,
                        "Selected add-on changed before publication: " + currentPath);
                return UpdateOutcome.failure(state.plan().updateItem(), detail);
            }
            if (pathExists(plan.archivePath())) {
                String detail = recoverLocked(
                        state,
                        "Old-file archive path became occupied: " + plan.archivePath());
                return UpdateOutcome.failure(state.plan().updateItem(), detail);
            }
            if (!destination.equals(currentPath) && pathExists(destination)) {
                String detail = recoverLocked(
                        state,
                        "Update destination became occupied before publication: " + destination);
                return UpdateOutcome.failure(state.plan().updateItem(), detail);
            }

            if (liveCancellation.getAsBoolean()) {
                String detail = recoverLocked(
                        state,
                        "Add-on update was cancelled before publication");
                return UpdateOutcome.failure(state.plan().updateItem(), detail);
            }
            try {
                state.markArchiving();
                prepared.localAddonFile().setOld(true);
                state.markArchived();
                if (pathExists(destination)) {
                    throw new IOException(
                            "Update destination became occupied during publication: " + destination);
                }
                Files.move(prepared.stagingPath(), destination);
                state.markPublished();
                deleteOldFileWhenRequired(prepared.localAddonFile());
                state.markFinalized();
                return UpdateOutcome.success(state.plan().updateItem());
            } catch (Throwable publicationFailure) {
                String detail = describeFailure(publicationFailure);
                try {
                    detail = recoverLocked(state, detail);
                } catch (Throwable recoveryFailure) {
                    publicationFailure.addSuppressed(recoveryFailure);
                    if (recoveryFailure instanceof Error recoveryError) {
                        throw recoveryError;
                    }
                    detail = appendDetail(
                            detail,
                            "rollback failed: " + describeFailure(recoveryFailure));
                }
                if (publicationFailure instanceof Error publicationError) {
                    throw publicationError;
                }
                return UpdateOutcome.failure(state.plan().updateItem(), detail);
            }
        } finally {
            STATE_LOCK.unlock();
        }
    }

    /// Converts a failed preparation or download dependency to a value-based failure after rollback.
    ///
    /// @param state failed operation lifecycle
    /// @param failure dependency failure, or `null` for an invalid task state
    /// @return rolled-back value-based failure
    private UpdateOutcome failUpdate(OperationState state, @Nullable Exception failure) {
        STATE_LOCK.lock();
        try {
            String detail = failure == null
                    ? "Add-on update failed without an exception"
                    : describeFailure(failure);
            return UpdateOutcome.failure(
                    state.plan().updateItem(),
                    recoverLocked(state, detail));
        } finally {
            STATE_LOCK.unlock();
        }
    }

    /// Rolls back every unfinished operation before a cancelled or unexpectedly failed root task stops.
    ///
    /// @param states all exact operation lifecycle holders
    private void recoverCancelledOperations(List<OperationState> states) {
        STATE_LOCK.lock();
        try {
            for (OperationState state : states) {
                if (state.isFinalized()) {
                    continue;
                }
                String detail = recoverLocked(state, "Add-on update was cancelled");
                if (!"Add-on update was cancelled".equals(detail)) {
                    LOG.warning(state.plan().updateItem().fileName() + ": " + detail);
                }
            }
        } finally {
            STATE_LOCK.unlock();
        }
    }

    /// Removes only owned staged or defensively published artifacts, then restores the archived local source.
    ///
    /// This method must be called with [#STATE_LOCK] held. It never throws, allowing root cancellation handlers
    /// to finish before task-stop notification even after filesystem cleanup failures.
    ///
    /// @param state operation lifecycle holder
    /// @param originalDetail original failure detail
    /// @return original detail extended with any cleanup or restoration failure
    private static String recoverLocked(OperationState state, String originalDetail) {
        if (state.isFinalized()) {
            return originalDetail;
        }
        @Nullable PreparedUpdate prepared = state.prepared();
        if (prepared == null) {
            state.markFinalized();
            return originalDetail;
        }

        String detail = originalDetail;
        detail = deletePublishedDestination(state, prepared, detail);
        detail = deleteStagingFile(state, prepared, detail);

        boolean archived = state.isArchivingOrLater() || isObservedArchived(prepared);
        if (archived) {
            detail = restoreArchivedSource(prepared, detail);
        }
        state.markFinalized();
        return detail;
    }

    /// Deletes a final destination only when the lifecycle proves this operation completed publication.
    ///
    /// @param state operation lifecycle holder
    /// @param prepared complete prepared paths
    /// @param detail current failure detail
    /// @return detail extended after a refused or failed defensive deletion
    private static String deletePublishedDestination(
            OperationState state,
            PreparedUpdate prepared,
            String detail) {
        if (!state.isPublished()) {
            return detail;
        }
        Path destination = prepared.plan().destinationOrNull();
        if (destination == null || !pathExists(destination)) {
            return detail;
        }
        if (!isSafePublishedDestination(state, prepared, destination)) {
            return appendDetail(detail, "refused to delete an unverified published destination");
        }
        try {
            Files.deleteIfExists(destination);
            return detail;
        } catch (IOException | RuntimeException cleanupFailure) {
            return appendDetail(
                    detail,
                    "published destination cleanup failed: " + describeFailure(cleanupFailure));
        }
    }

    /// Confirms a defensive final-destination deletion cannot target an archive or another selected source.
    ///
    /// @param state operation lifecycle holder
    /// @param prepared complete prepared paths
    /// @param destination candidate published destination
    /// @return whether deletion is constrained to this operation's published artifact
    private static boolean isSafePublishedDestination(
            OperationState state,
            PreparedUpdate prepared,
            Path destination) {
        PlannedUpdate plan = prepared.plan();
        if (!destination.equals(plan.destinationOrNull()) || destination.equals(plan.archivePath())) {
            return false;
        }
        if (!destination.equals(plan.sourcePath())) {
            return true;
        }
        return state.isArchivedOrLater()
                && prepared.localAddonFile().getFile().toAbsolutePath().normalize().equals(plan.archivePath());
    }

    /// Deletes only the unique staging path created for this operation.
    ///
    /// @param state operation lifecycle holder
    /// @param prepared complete prepared paths
    /// @param detail current failure detail
    /// @return detail extended after a refused or failed staging deletion
    private static String deleteStagingFile(
            OperationState state,
            PreparedUpdate prepared,
            String detail) {
        Path stagingPath = prepared.stagingPath();
        if (!isSafeStagingPath(state, prepared, stagingPath)) {
            return appendDetail(detail, "refused to delete an unverified staging path");
        }
        try {
            Files.deleteIfExists(stagingPath);
            return detail;
        } catch (IOException | RuntimeException cleanupFailure) {
            return appendDetail(
                    detail,
                    "staging cleanup failed: " + describeFailure(cleanupFailure));
        }
    }

    /// Confirms a staging path is a unique sibling outside every protected source, archive, and destination.
    ///
    /// @param state operation lifecycle holder
    /// @param prepared complete prepared paths
    /// @param stagingPath candidate staging path
    /// @return whether deletion is constrained to this operation's staging artifact
    private static boolean isSafeStagingPath(
            OperationState state,
            PreparedUpdate prepared,
            Path stagingPath) {
        PlannedUpdate plan = prepared.plan();
        @Nullable Path fileName = stagingPath.getFileName();
        return fileName != null
                && fileName.toString().startsWith(STAGING_PREFIX)
                && Objects.equals(stagingPath.getParent(), plan.sourcePath().getParent())
                && !stagingPath.equals(plan.sourcePath())
                && !stagingPath.equals(plan.archivePath())
                && !stagingPath.equals(plan.destinationOrNull())
                && !state.protectedPaths().contains(stagingPath);
    }

    /// Restores an archived source without overwriting a path created by another process.
    ///
    /// When a partial `setOld(true)` moved the file but left the local object pointing at its original path,
    /// this method first moves the archive back without replacement, then lets the local manager reconcile its
    /// in-memory collection state.
    ///
    /// @param prepared complete prepared paths
    /// @param detail current failure detail
    /// @return detail extended after restoration failure
    private static String restoreArchivedSource(PreparedUpdate prepared, String detail) {
        PlannedUpdate plan = prepared.plan();
        Path sourcePath = plan.sourcePath();
        Path archivePath = plan.archivePath();
        Path objectPath = prepared.localAddonFile().getFile().toAbsolutePath().normalize();
        if (pathExists(sourcePath)) {
            if (objectPath.equals(sourcePath) && !pathExists(archivePath)) {
                return detail;
            }
            return appendDetail(
                    detail,
                    "restoration skipped because the original path remains occupied");
        }
        try {
            if (objectPath.equals(sourcePath) && pathExists(archivePath)) {
                Files.move(archivePath, sourcePath);
            }
            prepared.localAddonFile().setOld(false);
            if (prepared.disabled()) {
                prepared.localAddonFile().markDisabled();
            }
            return detail;
        } catch (IOException | RuntimeException restorationFailure) {
            return appendDetail(
                    detail,
                    "restoration failed: " + describeFailure(restorationFailure));
        }
    }

    /// Returns whether local state or filesystem evidence shows that the selected source was archived.
    ///
    /// @param prepared complete prepared paths
    /// @return whether restoration should be attempted
    private static boolean isObservedArchived(PreparedUpdate prepared) {
        Path currentPath = prepared.localAddonFile().getFile().toAbsolutePath().normalize();
        return currentPath.equals(prepared.plan().archivePath())
                || pathExists(prepared.plan().archivePath())
                && !pathExists(prepared.plan().sourcePath());
    }

    /// Deletes the archived original only for local add-on types that do not retain old versions.
    ///
    /// Cleanup failure does not invalidate replacement success; the obsolete local
    /// file is retained with a warning instead of corrupting the completed update.
    ///
    /// @param localAddonFile exact archived local add-on
    private static void deleteOldFileWhenRequired(LocalAddonFile localAddonFile) {
        if (localAddonFile.keepOldFiles()) {
            return;
        }
        try {
            localAddonFile.delete();
        } catch (IOException | RuntimeException cleanupFailure) {
            LOG.warning("Failed to delete outdated add-on: " + localAddonFile.getFile(), cleanupFailure);
        }
    }

    /// Creates the production Core file-download task with provider candidates and final-path validation.
    ///
    /// @param candidates ordered provider candidates
    /// @param stagingPath unique owned staging path
    /// @param validationPath final destination used to select ZIP/JAR validation behavior
    /// @param integrityCheck remote artifact checksum, or `null` when unavailable
    /// @param downloadName stable progress display name
    /// @return stopped Core download task
    private static Task<@Nullable Void> createDownloadTask(
            @Unmodifiable List<URI> candidates,
            Path stagingPath,
            Path validationPath,
            @Nullable FileDownloadTask.IntegrityCheck integrityCheck,
            String downloadName) {
        FileDownloadTask downloadTask = new FileDownloadTask(
                candidates,
                stagingPath,
                integrityCheck);
        downloadTask.setName(downloadName);
        downloadTask.addIntegrityCheckHandler((filePath, ignoredDestination) ->
                FileDownloadTask.ZIP_INTEGRITY_CHECK_HANDLER.checkIntegrity(
                        filePath,
                        validationPath));
        return downloadTask;
    }

    /// Performs two-pass batch path validation under the global local-state lock.
    ///
    /// @param updates caller-owned selected update collection
    /// @return immutable exact batch plan
    private BatchPlan planSelection(Collection<AddonUpdateItem> updates) {
        STATE_LOCK.lock();
        try {
            Collection<AddonUpdateItem> supplied = Objects.requireNonNull(updates, "updates");
            List<PlannedUpdate> plans = new ArrayList<>(supplied.size());
            Set<LocalAddonFile> localFiles = Collections.newSetFromMap(new IdentityHashMap<>());
            Set<Path> sourcePaths = new LinkedHashSet<>();
            for (AddonUpdateItem updateItem : supplied) {
                AddonUpdateItem exactItem = Objects.requireNonNull(
                        updateItem,
                        "updates contains null");
                if (!localFiles.add(exactItem.update().localAddonFile())) {
                    throw new IllegalArgumentException(
                            "updates contains the same local add-on more than once");
                }
                Path sourcePath = exactItem.localFile().toAbsolutePath().normalize();
                if (!sourcePaths.add(sourcePath)) {
                    throw new IllegalArgumentException(
                            "updates contains duplicate source path: " + sourcePath);
                }
                plans.add(planUpdate(exactItem, sourcePath));
            }

            Set<Path> archivePaths = new LinkedHashSet<>();
            for (PlannedUpdate plan : plans) {
                Path archivePath = plan.archivePath();
                if (!archivePaths.add(archivePath)) {
                    throw new IllegalArgumentException(
                            "updates contains conflicting archive path: " + archivePath);
                }
                if (sourcePaths.contains(archivePath)) {
                    throw new IllegalArgumentException(
                            "generated archive path conflicts with a selected source: " + archivePath);
                }
                if (pathExists(archivePath)) {
                    throw new IllegalArgumentException(
                            "generated archive path is already occupied: " + archivePath);
                }
            }

            Map<Path, PlannedUpdate> destinations = new HashMap<>();
            for (PlannedUpdate plan : plans) {
                @Nullable Path destination = plan.destinationOrNull();
                if (destination == null) {
                    continue;
                }
                @Nullable PlannedUpdate previous = destinations.putIfAbsent(destination, plan);
                if (previous != null) {
                    throw new IllegalArgumentException(
                            "updates contains conflicting destination: " + destination);
                }
                if (archivePaths.contains(destination)) {
                    throw new IllegalArgumentException(
                            "update destination conflicts with a generated archive: " + destination);
                }
                if (sourcePaths.contains(destination) && !destination.equals(plan.sourcePath())) {
                    throw new IllegalArgumentException(
                            "update destination conflicts with another selected source: " + destination);
                }
                if (!destination.equals(plan.sourcePath()) && pathExists(destination)) {
                    throw new IllegalArgumentException(
                            "update destination is already occupied: " + destination);
                }
            }

            Set<Path> protectedPaths = new LinkedHashSet<>(sourcePaths);
            protectedPaths.addAll(archivePaths);
            protectedPaths.addAll(destinations.keySet());
            return new BatchPlan(plans, protectedPaths);
        } finally {
            STATE_LOCK.unlock();
        }
    }

    /// Creates one planned source, archive, and optional destination tuple without mutating local state.
    ///
    /// @param updateItem exact selected update item
    /// @param sourcePath stable normalized scan-time source path
    /// @return immutable planned update
    private static PlannedUpdate planUpdate(
            AddonUpdateItem updateItem,
            Path sourcePath) {
        Path archivePath = resolveArchivePath(sourcePath);
        try {
            return PlannedUpdate.valid(
                    updateItem,
                    sourcePath,
                    archivePath,
                    resolveDestination(updateItem, sourcePath));
        } catch (IOException | RuntimeException planningFailure) {
            return PlannedUpdate.invalid(
                    updateItem,
                    sourcePath,
                    archivePath,
                    describeFailure(planningFailure));
        }
    }

    /// Resolves the exact archive path produced by [LocalAddonManager#setOld(LocalAddonFile, boolean)].
    ///
    /// @param sourcePath normalized selected source path
    /// @return normalized generated `.old` path
    private static Path resolveArchivePath(Path sourcePath) {
        @Nullable Path directory = sourcePath.getParent();
        @Nullable Path fileName = sourcePath.getFileName();
        if (directory == null || fileName == null) {
            throw new IllegalArgumentException("Add-on source has no usable parent or file name: " + sourcePath);
        }
        String archivedName = StringUtils.addSuffix(
                StringUtils.removeSuffix(
                        fileName.toString(),
                        LocalAddonManager.DISABLED_EXTENSION),
                LocalAddonManager.OLD_EXTENSION);
        return directory.resolve(archivedName).toAbsolutePath().normalize();
    }

    /// Resolves one selected original or remote file name inside the current managed directory.
    ///
    /// @param updateItem exact selected update item
    /// @param sourcePath normalized selected source path
    /// @return normalized final destination
    /// @throws IOException when the remote file name is blank or escapes the managed directory
    private static Path resolveDestination(
            AddonUpdateItem updateItem,
            Path sourcePath) throws IOException {
        @Nullable Path directory = sourcePath.getParent();
        @Nullable Path sourceFileName = sourcePath.getFileName();
        if (directory == null || sourceFileName == null) {
            throw new IOException("Add-on source has no usable parent or file name: " + sourcePath);
        }
        LocalAddonFile.AddonUpdate update = updateItem.update();
        String requestedFileName = update.useRemoteFileName()
                ? Objects.requireNonNull(
                        update.targetVersion().file().filename(),
                        "remote file name")
                : sourceFileName.toString();
        if (isDisabledPath(sourcePath)) {
            requestedFileName = StringUtils.addSuffix(
                    requestedFileName,
                    LocalAddonManager.DISABLED_EXTENSION);
        }
        return resolveSafeDestination(directory, requestedFileName);
    }

    /// Resolves one untrusted remote file name without allowing it to escape the managed directory.
    ///
    /// @param directory current add-on directory
    /// @param fileName selected original or remote file name
    /// @return normalized destination directly inside the managed directory
    /// @throws IOException when the file name is blank, absolute, or contains path traversal
    private static Path resolveSafeDestination(Path directory, String fileName) throws IOException {
        Path normalizedDirectory = Objects.requireNonNull(directory, "directory")
                .toAbsolutePath().normalize();
        String requestedFileName = Objects.requireNonNull(fileName, "fileName");
        if (requestedFileName.isBlank()) {
            throw new IOException("Remote add-on file name is blank");
        }
        Path destination = normalizedDirectory.resolve(requestedFileName).normalize();
        if (!normalizedDirectory.equals(destination.getParent())) {
            throw new IOException("Remote add-on file name escapes its managed directory: " + requestedFileName);
        }
        return destination;
    }

    /// Returns whether one normalized local path carries the disabled suffix.
    ///
    /// @param path normalized local source path
    /// @return whether the source file name ends with the disabled suffix
    private static boolean isDisabledPath(Path path) {
        @Nullable Path fileName = path.getFileName();
        return fileName != null
                && fileName.toString().endsWith(LocalAddonManager.DISABLED_EXTENSION);
    }

    /// Returns whether a path exists without following a symbolic link at the final component.
    ///
    /// @param path path to inspect
    /// @return whether a filesystem entry occupies the path
    private static boolean pathExists(Path path) {
        return Files.exists(path, LinkOption.NOFOLLOW_LINKS);
    }

    /// Converts one task failure to concise non-blank UI text.
    ///
    /// @param failure task failure
    /// @return concise failure detail
    private static String describeFailure(Throwable failure) {
        Throwable current = Objects.requireNonNull(failure, "failure");
        while (current instanceof CompletionException && current.getCause() != null) {
            current = Objects.requireNonNull(current.getCause(), "completion cause");
        }
        @Nullable String detail = current.getMessage();
        return detail == null || detail.isBlank()
                ? current.getClass().getSimpleName()
                : detail;
    }

    /// Appends one recovery diagnostic to an existing non-blank failure detail.
    ///
    /// @param detail existing failure detail
    /// @param addition recovery diagnostic
    /// @return combined detail
    private static String appendDetail(String detail, String addition) {
        return Objects.requireNonNull(detail, "detail")
                + "; "
                + Objects.requireNonNull(addition, "addition");
    }

    /// Immutable whole-batch path plan whose protected set includes every source, archive, and valid destination.
    ///
    /// @param updates exact ordered planned updates
    /// @param protectedPaths every selected source, generated archive, and valid final destination path
    @NotNullByDefault
    private record BatchPlan(
            @Unmodifiable List<PlannedUpdate> updates,
            @Unmodifiable Set<Path> protectedPaths) {
        /// Defensively snapshots the batch plan.
        private BatchPlan {
            updates = List.copyOf(Objects.requireNonNull(updates, "updates"));
            protectedPaths = Set.copyOf(Objects.requireNonNull(protectedPaths, "protectedPaths"));
        }

        /// Returns exact selected items in caller order.
        ///
        /// @return immutable selected item list
        private @Unmodifiable List<AddonUpdateItem> selectedItems() {
            return updates.stream().map(PlannedUpdate::updateItem).toList();
        }
    }

    /// Immutable source, archive, and optional final destination plan for one exact update.
    ///
    /// @param updateItem exact selected update item
    /// @param sourcePath normalized scan-time source path
    /// @param archivePath normalized generated old-file path
    /// @param destination final destination, or `null` when planning failed
    /// @param planningFailure non-blank planning failure, or `null` for a valid destination
    @NotNullByDefault
    private record PlannedUpdate(
            AddonUpdateItem updateItem,
            Path sourcePath,
            Path archivePath,
            @Nullable Path destination,
            @Nullable String planningFailure) {
        /// Validates one exact valid or invalid planned update.
        private PlannedUpdate {
            updateItem = Objects.requireNonNull(updateItem, "updateItem");
            sourcePath = Objects.requireNonNull(sourcePath, "sourcePath");
            archivePath = Objects.requireNonNull(archivePath, "archivePath");
            if ((destination == null) == (planningFailure == null)) {
                throw new IllegalArgumentException(
                        "Exactly one of destination and planningFailure must be present");
            }
            if (planningFailure != null && planningFailure.isBlank()) {
                throw new IllegalArgumentException("planningFailure must not be blank");
            }
        }

        /// Creates one valid planned update.
        ///
        /// @param updateItem exact selected update item
        /// @param sourcePath normalized source path
        /// @param archivePath normalized archive path
        /// @param destination normalized final destination
        /// @return valid planned update
        private static PlannedUpdate valid(
                AddonUpdateItem updateItem,
                Path sourcePath,
                Path archivePath,
                Path destination) {
            return new PlannedUpdate(updateItem, sourcePath, archivePath, destination, null);
        }

        /// Creates one invalid planned update retained as a value-based task failure.
        ///
        /// @param updateItem exact selected update item
        /// @param sourcePath normalized source path
        /// @param archivePath normalized archive path
        /// @param planningFailure non-blank planning failure
        /// @return invalid planned update
        private static PlannedUpdate invalid(
                AddonUpdateItem updateItem,
                Path sourcePath,
                Path archivePath,
                String planningFailure) {
            return new PlannedUpdate(updateItem, sourcePath, archivePath, null, planningFailure);
        }

        /// Returns the valid destination or fails for an invalid planned update.
        ///
        /// @return valid final destination
        /// @throws IOException when destination planning failed
        private Path destinationOrThrow() throws IOException {
            if (destination == null) {
                throw new IOException(Objects.requireNonNull(planningFailure, "planningFailure"));
            }
            return destination;
        }

        /// Returns the final destination, or `null` for an invalid planned update.
        ///
        /// @return final destination or `null`
        private @Nullable Path destinationOrNull() {
            return destination;
        }
    }

    /// Immutable prepared context installed before the local source is archived.
    ///
    /// @param plan exact immutable path plan
    /// @param localAddonFile exact mutable local add-on object
    /// @param stagingPath unique owned staging path
    /// @param candidates immutable provider candidate URLs
    /// @param integrityCheck optional remote checksum
    /// @param downloadName progress display name
    /// @param disabled whether the original local file was disabled
    @NotNullByDefault
    private record PreparedUpdate(
            PlannedUpdate plan,
            LocalAddonFile localAddonFile,
            Path stagingPath,
            @Unmodifiable List<URI> candidates,
            @Nullable FileDownloadTask.IntegrityCheck integrityCheck,
            String downloadName,
            boolean disabled) {
        /// Validates and snapshots all prepared update state.
        private PreparedUpdate {
            plan = Objects.requireNonNull(plan, "plan");
            localAddonFile = Objects.requireNonNull(localAddonFile, "localAddonFile");
            stagingPath = Objects.requireNonNull(stagingPath, "stagingPath");
            candidates = List.copyOf(Objects.requireNonNull(candidates, "candidates"));
            downloadName = Objects.requireNonNull(downloadName, "downloadName");
        }
    }

    /// Local source transition phase retained across preparation, commit, cancellation, and rollback.
    @NotNullByDefault
    private enum LocalTransitionPhase {
        /// The selected source has not been mutated.
        UNTOUCHED,

        /// `setOld(true)` was entered but may have failed after a partial filesystem move.
        ARCHIVING,

        /// The local manager completed `setOld(true)`.
        ARCHIVED,

        /// The staged replacement was moved to its final destination.
        PUBLISHED,

        /// Commit or rollback reached a terminal state.
        FINALIZED
    }

    /// Mutable per-item lifecycle accessed only while [#STATE_LOCK] is held.
    @NotNullByDefault
    private static final class OperationState {
        /// Exact immutable operation path plan.
        private final PlannedUpdate plan;

        /// Immutable batch-protected source, archive, and final destination paths.
        private final @Unmodifiable Set<Path> protectedPaths;

        /// Prepared context installed before `setOld(true)`, or `null` before preparation.
        private @Nullable PreparedUpdate prepared;

        /// Current local source transition phase.
        private LocalTransitionPhase phase = LocalTransitionPhase.UNTOUCHED;

        /// Whether the staged download dependency completed successfully.
        private boolean downloadCompleted;

        /// Whether staging was moved to the final destination.
        private boolean published;

        /// Whether commit or rollback reached a terminal local state.
        private boolean finalized;

        /// Creates one operation lifecycle holder.
        ///
        /// @param plan exact immutable operation plan
        /// @param protectedPaths immutable batch-protected paths
        private OperationState(
                PlannedUpdate plan,
                @Unmodifiable Set<Path> protectedPaths) {
            this.plan = Objects.requireNonNull(plan, "plan");
            this.protectedPaths = Set.copyOf(Objects.requireNonNull(
                    protectedPaths,
                    "protectedPaths"));
        }

        /// Returns the immutable operation plan.
        ///
        /// @return operation plan
        private PlannedUpdate plan() {
            return plan;
        }

        /// Returns immutable batch-protected paths.
        ///
        /// @return protected paths
        private @Unmodifiable Set<Path> protectedPaths() {
            return protectedPaths;
        }

        /// Installs complete prepared state before any local mutation.
        ///
        /// @param prepared complete prepared state
        private void installPrepared(PreparedUpdate prepared) {
            this.prepared = Objects.requireNonNull(prepared, "prepared");
        }

        /// Returns prepared state, or `null` before preparation.
        ///
        /// @return prepared state or `null`
        private @Nullable PreparedUpdate prepared() {
            return prepared;
        }

        /// Marks entry into the possibly partially mutating archive transition.
        private void markArchiving() {
            phase = LocalTransitionPhase.ARCHIVING;
        }

        /// Marks successful old-file transition.
        private void markArchived() {
            phase = LocalTransitionPhase.ARCHIVED;
        }

        /// Returns whether archive mutation began and may require recovery.
        ///
        /// @return whether archive mutation began
        private boolean isArchivingOrLater() {
            return phase == LocalTransitionPhase.ARCHIVING
                    || phase == LocalTransitionPhase.ARCHIVED
                    || phase == LocalTransitionPhase.PUBLISHED;
        }

        /// Returns whether the local manager completed archiving before publication.
        ///
        /// @return whether archiving completed
        private boolean isArchivedOrLater() {
            return phase == LocalTransitionPhase.ARCHIVED
                    || phase == LocalTransitionPhase.PUBLISHED;
        }

        /// Marks successful staged download completion.
        private void markDownloadCompleted() {
            downloadCompleted = true;
        }

        /// Returns whether the staged download completed.
        ///
        /// @return staged download completion state
        private boolean isDownloadCompleted() {
            return downloadCompleted;
        }

        /// Marks successful final destination publication.
        private void markPublished() {
            published = true;
            phase = LocalTransitionPhase.PUBLISHED;
        }

        /// Returns whether final destination publication completed.
        ///
        /// @return publication completion state
        private boolean isPublished() {
            return published;
        }

        /// Marks terminal commit or rollback completion.
        private void markFinalized() {
            finalized = true;
            phase = LocalTransitionPhase.FINALIZED;
        }

        /// Returns whether terminal local-state handling completed.
        ///
        /// @return terminal lifecycle state
        private boolean isFinalized() {
            return finalized;
        }
    }

    /// Represents one exact selected item and its optional value-based failure.
    ///
    /// @param updateItem exact selected item
    /// @param failure application failure, or `null` after success
    @NotNullByDefault
    private record UpdateOutcome(
            AddonUpdateItem updateItem,
            @Nullable AddonUpdateApplicationFailure failure) {
        /// Validates the exact selected item.
        private UpdateOutcome {
            updateItem = Objects.requireNonNull(updateItem, "updateItem");
        }

        /// Creates a successful per-item outcome.
        ///
        /// @param updateItem exact completed item
        /// @return successful outcome
        private static UpdateOutcome success(AddonUpdateItem updateItem) {
            return new UpdateOutcome(updateItem, null);
        }

        /// Creates a failed per-item outcome.
        ///
        /// @param updateItem exact failed item
        /// @param detail non-blank failure detail
        /// @return failed outcome
        private static UpdateOutcome failure(AddonUpdateItem updateItem, String detail) {
            return new UpdateOutcome(
                    updateItem,
                    new AddonUpdateApplicationFailure(updateItem, detail));
        }
    }

    /// Root task that preserves selection order while aggregating value-based operation outcomes.
    @NotNullByDefault
    private static final class ApplicationTask extends Task<AddonUpdateApplicationResult> {
        /// Immutable exact caller selection retained for diagnostics and size metadata.
        private final @Unmodifiable List<AddonUpdateItem> selectedUpdates;

        /// Immutable operation tasks in exact caller selection order.
        private final @Unmodifiable List<Task<UpdateOutcome>> operations;

        /// Creates a stopped aggregate task with launcher progress metadata.
        ///
        /// @param selectedUpdates exact caller selection
        /// @param operations ordered per-item operation tasks
        private ApplicationTask(
                @Unmodifiable List<AddonUpdateItem> selectedUpdates,
                List<Task<UpdateOutcome>> operations) {
            this.selectedUpdates = List.copyOf(Objects.requireNonNull(
                    selectedUpdates,
                    "selectedUpdates"));
            this.operations = List.copyOf(Objects.requireNonNull(operations, "operations"));
            if (this.selectedUpdates.size() != this.operations.size()) {
                throw new IllegalArgumentException("selected update and operation counts differ");
            }
            setStage(UPDATE_STAGE);
            setName(UPDATE_STAGE);
            getProperties().put("total", this.selectedUpdates.size());
        }

        /// Returns all per-item operations as aggregate prerequisites.
        ///
        /// @return immutable ordered operation tasks
        @Override
        public @Unmodifiable Collection<? extends Task<?>> getDependents() {
            return operations;
        }

        /// Partitions completed per-item outcomes while retaining original selection order.
        @Override
        public void execute() {
            List<AddonUpdateItem> successes = new ArrayList<>(operations.size());
            List<AddonUpdateApplicationFailure> failures = new ArrayList<>();
            for (Task<UpdateOutcome> operation : operations) {
                @Nullable UpdateOutcome outcome = operation.getResult();
                if (outcome == null) {
                    throw new IllegalStateException("Completed add-on update operation has no result");
                }
                @Nullable AddonUpdateApplicationFailure failure = outcome.failure();
                if (failure == null) {
                    successes.add(outcome.updateItem());
                } else {
                    failures.add(failure);
                }
            }
            setResult(new AddonUpdateApplicationResult(successes, failures));
        }
    }

    /// One operation task that commits success or rolls back a failed dependency under the shared lock.
    @NotNullByDefault
    private final class UpdateOperationTask extends Task<UpdateOutcome> {
        /// Exact mutable operation lifecycle.
        private final OperationState state;

        /// Preparation task retained for task-graph diagnostics.
        private final Task<PreparedUpdate> preparation;

        /// Full preparation and staged-download prerequisite chain.
        private final Task<@Nullable Void> download;

        /// Creates one stopped operation task.
        ///
        /// @param state exact mutable operation lifecycle
        /// @param preparation deferred preparation task
        /// @param download deferred preparation and staged-download chain
        /// @param ioExecutor executor for final commit or rollback
        private UpdateOperationTask(
                OperationState state,
                Task<PreparedUpdate> preparation,
                Task<@Nullable Void> download,
                Executor ioExecutor) {
            this.state = Objects.requireNonNull(state, "state");
            this.preparation = Objects.requireNonNull(preparation, "preparation");
            this.download = Objects.requireNonNull(download, "download");
            setExecutor(Objects.requireNonNull(ioExecutor, "ioExecutor"));
            setName(state.plan().updateItem().fileName());
        }

        /// Returns the preparation and staged-download chain as this operation's sole prerequisite.
        ///
        /// @return immutable singleton prerequisite
        @Override
        public @Unmodifiable Collection<? extends Task<?>> getDependents() {
            return List.of(download);
        }

        /// Allows the operation body to convert a failed prerequisite into a value-based failure.
        ///
        /// @return always `false`
        @Override
        public boolean isRelyingOnDependents() {
            return false;
        }

        /// Commits the staged artifact or rolls back ordinary dependency failure.
        @Override
        public void execute() {
            if (isCancelled()) {
                setResult(failUpdate(state, new CancellationException("Add-on update was cancelled")));
            } else if (isDependentsSucceeded()) {
                setResult(commitUpdate(state, this::isCancelled));
            } else {
                setResult(failUpdate(state, getException()));
            }
        }
    }

    /// Package test seam for deferred Core file-download construction.
    @FunctionalInterface
    @NotNullByDefault
    interface DownloadTaskFactory {
        /// Creates one stopped staged artifact download task.
        ///
        /// @param candidates ordered provider candidates
        /// @param stagingPath unique owned staging destination
        /// @param validationPath final destination used for structural validation
        /// @param integrityCheck optional remote checksum
        /// @param downloadName stable progress display name
        /// @return stopped staged download task
        Task<@Nullable Void> create(
                @Unmodifiable List<URI> candidates,
                Path stagingPath,
                Path validationPath,
                @Nullable FileDownloadTask.IntegrityCheck integrityCheck,
                String downloadName);
    }
}
