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
package space.minecraftstl.xyml.mcp;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import space.minecraftstl.xyml.addon.mod.LocalModFile;
import space.minecraftstl.xyml.addon.mod.ModManager;
import space.minecraftstl.xyml.auth.AuthInfo;
import space.minecraftstl.xyml.game.CrashReportAnalyzer;
import space.minecraftstl.xyml.game.GameInstanceID;
import space.minecraftstl.xyml.game.GameInstanceManifest;
import space.minecraftstl.xyml.game.JavaRuntimeRepairTaskFactory;
import space.minecraftstl.xyml.game.LaunchOptions;
import space.minecraftstl.xyml.game.XYMLGameRepository;
import space.minecraftstl.xyml.game.analyzer.LogAnalyzable;
import space.minecraftstl.xyml.java.JavaManager;
import space.minecraftstl.xyml.java.JavaRuntime;
import space.minecraftstl.xyml.launch.DefaultLauncher;
import space.minecraftstl.xyml.launch.ProcessListener;
import space.minecraftstl.xyml.setting.GameSettings;
import space.minecraftstl.xyml.setting.GameWindowType;
import space.minecraftstl.xyml.setting.JavaVersionType;
import space.minecraftstl.xyml.setting.SettingsManager;
import space.minecraftstl.xyml.setting.property.InheritableProperty;
import space.minecraftstl.xyml.task.Schedulers;
import space.minecraftstl.xyml.task.Task;
import space.minecraftstl.xyml.task.TaskResource;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;
import space.minecraftstl.xyml.ui.swing.page.instances.management.RepositoryInstanceLifecycleService;
import space.minecraftstl.xyml.util.FileSaver;
import space.minecraftstl.xyml.util.StringUtils;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static space.minecraftstl.xyml.util.logging.Logger.LOG;

/// Bridges the existing XYMLCore launcher services to MCP-safe structured operations.
///
/// This class deliberately contains no new game or mod-management algorithms. It delegates to the
/// repository, mod manager, Java manager, and launch monitor already used by XYML.
@NotNullByDefault
public final class XYMLMcpService implements XYMLMcpOperations, AutoCloseable {

    /// Maximum number of lines retained from one launch process.
    private static final int MAX_LOG_LINES = 20_000;

    /// Warning returned when optional manifest or settings context cannot be collected for XYAT.
    private static final String XYAT_CONTEXT_UNAVAILABLE_WARNING =
            "XYAT instance context is unavailable; analysis continued with log-only metadata.";

    /// Failure returned when launcher startup policy still blocks MCP repair side effects.
    private static final String REPAIR_ACTIONS_UNAVAILABLE_MESSAGE =
            "Crash repair actions are unavailable before startup agreements are accepted";

    /// URI matcher for the latest instance log.
    private static final Pattern LOG_RESOURCE = Pattern.compile(
            "^xyml://instances/([^/]+)/logs/latest\\.log$");

    /// URI matcher for an instance crash-report directory listing.
    private static final Pattern CRASH_DIRECTORY_RESOURCE = Pattern.compile(
            "^xyml://instances/([^/]+)/crash-reports/$");

    /// URI matcher for one instance crash-report file.
    private static final Pattern CRASH_REPORT_RESOURCE = Pattern.compile(
            "^xyml://instances/([^/]+)/crash-reports/([^/]+)$");

    /// Supported instance-resource shapes resolved after the run-directory settings snapshot is protected.
    @NotNullByDefault
    private enum ResourceKind {
        /// Latest game log.
        LOG,

        /// Crash-report directory listing.
        CRASH_DIRECTORY,

        /// One named crash-report file.
        CRASH_REPORT
    }

    /// Repository exposed by this server process.
    private final XYMLGameRepository repository;

    /// Existing repository lifecycle service used for staged instance mutations.
    private final RepositoryInstanceLifecycleService instanceLifecycle;

    /// Application-owned policy for every destructive MCP operation.
    private final McpDeletionConfirmation deletionConfirmation;

    /// Optional late-bound application search action for missing dependencies.
    private final @Nullable LogAnalyzable.MissingDependencySearch missingDependencySearch;

    /// Reports whether mandatory startup policy permits launcher-owned MCP repair side effects.
    private final BooleanSupplier repairActionsAllowed;

    /// Bounded analysis, plan, and repair-operation coordinator.
    private final XYMLMcpCrashRepairCoordinator crashRepairCoordinator;

    /// Last known process state for each repository-scoped instance.
    private final Map<LaunchKey, LaunchState> launchStates = new ConcurrentHashMap<>();

    /// Creates a service for one initialized XYML game repository.
    ///
    /// @param repository repository whose instances and settings are exposed
    /// @param deletionConfirmation launcher-owned confirmation policy for destructive operations
    public XYMLMcpService(
            XYMLGameRepository repository,
            McpDeletionConfirmation deletionConfirmation) {
        this(repository, deletionConfirmation, null);
    }

    /// Creates a service with an optional application search boundary for XYAT repairs.
    ///
    /// @param repository repository whose instances and settings are exposed
    /// @param deletionConfirmation launcher-owned confirmation policy for destructive operations
    /// @param missingDependencySearch late-bound missing-dependency search action, or null for analysis-only use
    public XYMLMcpService(
            XYMLGameRepository repository,
            McpDeletionConfirmation deletionConfirmation,
            @Nullable LogAnalyzable.MissingDependencySearch missingDependencySearch) {
        this(repository, deletionConfirmation, missingDependencySearch, () -> true);
    }

    /// Creates a service with application repair boundaries and the launcher's startup-policy gate.
    ///
    /// This gate is independent of per-operation confirmation. It prevents any repair side effect before mandatory
    /// startup agreements have enabled application interaction.
    ///
    /// @param repository repository whose instances and settings are exposed
    /// @param deletionConfirmation launcher-owned confirmation policy for destructive operations
    /// @param missingDependencySearch late-bound missing-dependency search action, or null for analysis-only use
    /// @param repairActionsAllowed reports whether startup policy permits repair side effects
    public XYMLMcpService(
            XYMLGameRepository repository,
            McpDeletionConfirmation deletionConfirmation,
            @Nullable LogAnalyzable.MissingDependencySearch missingDependencySearch,
            BooleanSupplier repairActionsAllowed) {
        this(
                repository,
                deletionConfirmation,
                missingDependencySearch,
                repairActionsAllowed,
                new XYMLMcpCrashRepairCoordinator());
    }

    /// Creates a service with an explicit crash-repair coordinator for deterministic integration tests.
    ///
    /// @param repository repository whose instances and settings are exposed
    /// @param deletionConfirmation launcher-owned confirmation policy for destructive operations
    /// @param missingDependencySearch late-bound missing-dependency search action, or null for analysis-only use
    /// @param crashRepairCoordinator bounded analysis and repair-operation coordinator
    XYMLMcpService(
            XYMLGameRepository repository,
            McpDeletionConfirmation deletionConfirmation,
            @Nullable LogAnalyzable.MissingDependencySearch missingDependencySearch,
            XYMLMcpCrashRepairCoordinator crashRepairCoordinator) {
        this(repository, deletionConfirmation, missingDependencySearch, () -> true, crashRepairCoordinator);
    }

    /// Creates a service with explicit startup and coordinator boundaries for deterministic integration tests.
    ///
    /// @param repository repository whose instances and settings are exposed
    /// @param deletionConfirmation launcher-owned confirmation policy for destructive operations
    /// @param missingDependencySearch late-bound missing-dependency search action, or null for analysis-only use
    /// @param repairActionsAllowed reports whether startup policy permits repair side effects
    /// @param crashRepairCoordinator bounded analysis and repair-operation coordinator
    XYMLMcpService(
            XYMLGameRepository repository,
            McpDeletionConfirmation deletionConfirmation,
            @Nullable LogAnalyzable.MissingDependencySearch missingDependencySearch,
            BooleanSupplier repairActionsAllowed,
            XYMLMcpCrashRepairCoordinator crashRepairCoordinator) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.instanceLifecycle = new RepositoryInstanceLifecycleService(repository);
        this.deletionConfirmation = Objects.requireNonNull(deletionConfirmation, "deletionConfirmation");
        this.missingDependencySearch = missingDependencySearch;
        this.repairActionsAllowed = Objects.requireNonNull(repairActionsAllowed, "repairActionsAllowed");
        this.crashRepairCoordinator = Objects.requireNonNull(crashRepairCoordinator, "crashRepairCoordinator");
    }

    /// Returns all installed instances and their root directories.
    ///
    /// @return unstarted task producing immutable instance summaries
    @Override
    public Task<@Unmodifiable List<@Unmodifiable Map<String, Object>>> listInstances() {
        Path repositoryDirectory = repositoryDirectory();
        return repositoryTask("List MCP game instances", repositoryDirectory, () -> {
            withSettingsSaveBarrier(() -> {
                repository.refresh();
                EdtDispatcher.executeAndWait(repository::refreshSelectedInstance);
                return null;
            });
            List<@Unmodifiable Map<String, Object>> result = new ArrayList<>();
            for (GameInstanceManifest manifest : repository.getInstanceManifests()) {
                result.add(Map.of(
                        "id", manifest.id().id(),
                        "root", repository.getInstanceRoot(manifest.id()).toAbsolutePath().normalize().toString(),
                        "game_version", repository.getGameVersion(manifest).orElse("")));
            }
            return List.copyOf(result);
        },
                TaskResource.gameDirectory(repositoryDirectory),
                TaskResource.configuration(SettingsManager.settingsLocation()));
    }

    /// Returns the effective settings needed to diagnose or adjust one instance.
    ///
    /// @param instanceId instance identifier
    /// @return unstarted task producing an immutable effective-settings map
    @Override
    public Task<@Unmodifiable Map<String, Object>> getInstanceSettings(String instanceId) {
        GameInstanceID id = id(instanceId);
        Path repositoryDirectory = repositoryDirectory();
        Path instanceDirectory = instanceDirectory(repositoryDirectory, id);
        return repositoryTask("Read MCP instance settings", repositoryDirectory, () -> {
            requireInstanceDirectory(id, instanceDirectory);
            requireInstance(id);
            return instanceSettings(id);
        },
                TaskResource.configuration(instanceSettingsFile(repositoryDirectory, id)),
                TaskResource.configuration(SettingsManager.gameSettingsLocation()),
                TaskResource.configuration(SettingsManager.settingsLocation()));
    }

    /// Returns one immutable effective-settings snapshot while the caller owns its declared configuration resources.
    ///
    /// @param id instance identifier
    /// @return immutable effective-settings map
    /// @throws InterruptedException if a lazily repaired default setting cannot finish saving
    private @Unmodifiable Map<String, Object> instanceSettings(GameInstanceID id) throws InterruptedException {
        GameSettings.Effective effective = effectiveSettings(id);
        return Map.of(
                "instance_id", id.id(),
                "java_type", effective.getInheritable(GameSettings::javaTypeProperty).name(),
                "java_version", effective.getInheritable(GameSettings::customJavaVersionProperty),
                "java_path", effective.getInheritable(GameSettings::customJavaPathProperty),
                "min_memory_mb", nullableNumber(effective.getInheritable(GameSettings::minMemoryProperty)),
                "max_memory_mb", effective.getMaxMemory(),
                "jvm_options", effective.getInheritable(GameSettings::jvmOptionsProperty),
                "width", effective.getWidth(),
                "height", effective.getHeight(),
                "window_type", effective.getInheritable(GameSettings::windowTypeProperty).name());
    }

    /// Resolves effective settings and drains any default-preset repair scheduled by the legacy accessor.
    ///
    /// Callers must own both launcher-settings resources while invoking this method.
    ///
    /// @param id instance identifier
    /// @return effective settings snapshot
    /// @throws InterruptedException if a scheduled settings save cannot finish
    private GameSettings.Effective effectiveSettings(GameInstanceID id) throws InterruptedException {
        return withSettingsSaveBarrier(() -> repository.getEffectiveGameSettings(id));
    }

    /// Resolves a normalized running directory and drains any default-preset repair it schedules.
    ///
    /// Callers must own the instance, preset, and launcher-settings configuration resources.
    ///
    /// @param id instance identifier
    /// @return normalized absolute running directory
    /// @throws InterruptedException if a scheduled settings save cannot finish
    private Path resolvedRunDirectory(GameInstanceID id) throws InterruptedException {
        return withSettingsSaveBarrier(() -> repository.getRunDirectory(id).toAbsolutePath().normalize());
    }

    /// Resolves a normalized mods directory and drains any default-preset repair it schedules.
    ///
    /// Callers must own the instance, preset, and launcher-settings configuration resources.
    ///
    /// @param id instance identifier
    /// @return normalized absolute mods directory
    /// @throws InterruptedException if a scheduled settings save cannot finish
    private Path resolvedModsDirectory(GameInstanceID id) throws InterruptedException {
        return withSettingsSaveBarrier(() -> repository.getModsDirectory(id).toAbsolutePath().normalize());
    }

    /// Renames one installed instance and selects the renamed destination.
    ///
    /// @param sourceInstanceId existing instance identifier
    /// @param destinationInstanceId new instance identifier
    /// @return unstarted task producing an immutable rename outcome
    @Override
    public Task<@Unmodifiable Map<String, Object>> renameInstance(
            String sourceInstanceId,
            String destinationInstanceId) {
        GameInstanceID source = id(sourceInstanceId);
        GameInstanceID destination = destinationId(destinationInstanceId);
        Path repositoryDirectory = repositoryDirectory();
        Path sourceDirectory = instanceDirectory(repositoryDirectory, source);
        Path destinationDirectory = instanceDirectory(repositoryDirectory, destination);
        AtomicBoolean committed = new AtomicBoolean();
        @Unmodifiable Map<String, Object> result = Map.of(
                "renamed", true,
                "source_instance_id", source.id(),
                "instance_id", destination.id());
        return Task.<@Unmodifiable Map<String, Object>>composeAsync("Resolve MCP instance rename", () -> {
            requireRepositoryDirectory(repositoryDirectory);
            requireInstance(source);
            requireTrackedLaunchStopped(repositoryDirectory, source, "rename");
            RepositoryInstanceLifecycleService.RenamePreparation preparation =
                    repository.withStableBaseDirectory(repositoryDirectory,
                            () -> instanceLifecycle.prepareRename(source, destination));
            List<TaskResource> resources = new ArrayList<>();
            resources.add(TaskResource.repositoryOperation(repositoryDirectory));
            resources.add(TaskResource.gameInstance(sourceDirectory));
            resources.add(TaskResource.gameInstance(destinationDirectory));
            preparation.affectedChildren().stream()
                    .map(child -> instanceDirectory(repositoryDirectory, child))
                    .map(TaskResource::gameInstance)
                    .forEach(resources::add);
            TaskResource[] additional = resources.subList(1, resources.size()).toArray(TaskResource[]::new);
            Task<@Nullable Void> mutation = runTask("Rename MCP game instance", () -> {
                repository.withStableBaseDirectory(repositoryDirectory, () -> {
                    requireInstanceDirectory(source, sourceDirectory);
                    requireInstance(source);
                    requireTrackedLaunchStopped(repositoryDirectory, source, "rename");
                    instanceLifecycle.renameWithoutRefresh(source, destination, preparation);
                    committed.set(true);
                    @Nullable LaunchState previousState = launchStates.remove(new LaunchKey(repositoryDirectory, source));
                    if (previousState != null) {
                        launchStates.put(new LaunchKey(repositoryDirectory, destination), previousState);
                    }
                });
            }, resources.get(0), additional);
            return lifecycleResult(mutation, repositoryDirectory, destination, committed, result);
        }).setExecutor(Schedulers.io())
                .setResources(TaskResource.repositoryMetadata(repositoryDirectory))
                .releaseResourcesBeforeDependencies();
    }

    /// Duplicates one installed instance and selects the new destination.
    ///
    /// @param sourceInstanceId existing instance identifier
    /// @param destinationInstanceId new instance identifier
    /// @param copySaves whether saved worlds should be copied
    /// @return unstarted task producing an immutable duplication outcome
    @Override
    public Task<@Unmodifiable Map<String, Object>> duplicateInstance(
            String sourceInstanceId,
            String destinationInstanceId,
            boolean copySaves) {
        GameInstanceID source = id(sourceInstanceId);
        GameInstanceID destination = destinationId(destinationInstanceId);
        Path repositoryDirectory = repositoryDirectory();
        Path sourceDirectory = instanceDirectory(repositoryDirectory, source);
        Path destinationDirectory = instanceDirectory(repositoryDirectory, destination);
        AtomicBoolean committed = new AtomicBoolean();
        @Unmodifiable Map<String, Object> result = Map.of(
                "duplicated", true,
                "source_instance_id", source.id(),
                "instance_id", destination.id(),
                "copied_saves", copySaves);
        return Task.<@Unmodifiable Map<String, Object>>composeAsync("Resolve MCP instance duplication", () -> {
            requireRepositoryDirectory(repositoryDirectory);
            requireInstanceDirectory(source, sourceDirectory);
            requireInstance(source);
            requireTrackedLaunchStopped(repositoryDirectory, source, "duplicate");
            XYMLGameRepository.InstanceDuplicationSnapshot snapshot =
                    repository.withStableBaseDirectory(repositoryDirectory,
                            () -> instanceLifecycle.prepareDuplicate(source));
            Path sourceRunDirectory = snapshot.sourceRunDirectory();
            Task<@Nullable Void> mutation = runTask("Duplicate MCP game instance", () -> {
                repository.withStableBaseDirectory(repositoryDirectory, () -> {
                    requireInstanceDirectory(source, sourceDirectory);
                    requireInstance(source);
                    requireTrackedLaunchStopped(repositoryDirectory, source, "duplicate");
                    instanceLifecycle.duplicateWithoutRefresh(source, destination, copySaves, snapshot);
                    committed.set(true);
                });
            },
                    TaskResource.repositoryOperation(repositoryDirectory),
                    TaskResource.gameInstance(sourceDirectory),
                    TaskResource.gameInstance(destinationDirectory),
                    TaskResource.gameDirectory(sourceRunDirectory));
            return lifecycleResult(mutation, repositoryDirectory, destination, committed, result);
        }).setExecutor(Schedulers.io()).setResources(
                TaskResource.configuration(instanceSettingsFile(repositoryDirectory, source)),
                TaskResource.configuration(SettingsManager.gameSettingsLocation()),
                TaskResource.configuration(SettingsManager.settingsLocation()))
                .releaseResourcesBeforeDependencies();
    }

    /// Deletes one installed instance only after the configured launcher confirmation succeeds.
    ///
    /// @param instanceId existing instance identifier
    /// @return unstarted task producing an immutable approval and deletion outcome
    @Override
    public Task<@Unmodifiable Map<String, Object>> deleteInstance(String instanceId) {
        GameInstanceID target = id(instanceId);
        Path repositoryDirectory = repositoryDirectory();
        Path instanceDirectory = instanceDirectory(repositoryDirectory, target);
        Path removedDirectory = instanceDirectory.resolveSibling(instanceDirectory.getFileName() + "_removed");
        return Task.<@Unmodifiable Map<String, Object>>composeAsync("Resolve MCP instance deletion",
                () -> repository.callWithStableBaseDirectory(repositoryDirectory, () -> {
                    requireInstanceDirectory(target, instanceDirectory);
                    requireInstance(target);
                    requireTrackedLaunchStopped(repositoryDirectory, target, "delete");
                    AtomicBoolean approved = new AtomicBoolean();
                    AtomicBoolean committed = new AtomicBoolean();
                    Task<@Nullable Void> mutation = Task.<@Nullable Void>composeAsync(
                            "Confirm MCP game instance deletion", () -> {
                        boolean confirmed = withSettingsSaveBarrier(() -> deletionConfirmation.confirm(
                                McpDeletionConfirmation.DeletionRequest.instance(target)));
                        if (!confirmed) {
                            return Task.<@Nullable Void>completed(null).asOrchestration();
                        }
                        approved.set(true);
                        return repositoryTask("Delete MCP game instance", repositoryDirectory, () -> {
                            requireInstanceDirectory(target, instanceDirectory);
                            requireInstance(target);
                            requireTrackedLaunchStopped(repositoryDirectory, target, "delete");
                            instanceLifecycle.deleteWithoutRefresh(target);
                            committed.set(true);
                            launchStates.remove(new LaunchKey(repositoryDirectory, target));
                            return null;
                        },
                                TaskResource.repositoryOperation(repositoryDirectory),
                                TaskResource.gameInstance(instanceDirectory),
                                TaskResource.gameDirectory(removedDirectory));
                    }).setExecutor(Schedulers.io()).setResources(
                            TaskResource.repositoryOperation(repositoryDirectory),
                            TaskResource.gameInstance(instanceDirectory),
                            TaskResource.gameDirectory(removedDirectory));
                    Task<@Nullable Void> cleanup = mutation.whenTerminalWithResources(
                            Schedulers.io(),
                            ignoredFailure -> {
                                if (approved.get()) {
                                    instanceLifecycle.completeLifecycle(repositoryDirectory, null, committed.get());
                                }
                            },
                            TaskResource.gameDirectory(repositoryDirectory),
                            TaskResource.configuration(SettingsManager.settingsLocation()));
                    return cleanup.thenApplyAsync(Schedulers.io(), ignored -> Map.<String, Object>of(
                            "approved", approved.get(),
                            "deleted", committed.get(),
                            "instance_id", target.id())).asOrchestration();
                }))
                .setExecutor(Schedulers.io())
                .setResources(TaskResource.repositoryMetadata(repositoryDirectory))
                .releaseResourcesBeforeDependencies();
    }

    /// Returns the absolute mods directory for an instance.
    ///
    /// @param instanceId instance identifier
    /// @return unstarted task producing the absolute mods directory
    @Override
    public Task<String> getModsDirectory(String instanceId) {
        GameInstanceID id = id(instanceId);
        Path repositoryDirectory = repositoryDirectory();
        Path instanceDirectory = instanceDirectory(repositoryDirectory, id);
        return repositoryTask("Resolve MCP mods directory", repositoryDirectory, () -> {
            requireInstanceDirectory(id, instanceDirectory);
            requireInstance(id);
            return resolvedModsDirectory(id).toString();
        },
                TaskResource.configuration(instanceSettingsFile(repositoryDirectory, id)),
                TaskResource.configuration(SettingsManager.gameSettingsLocation()),
                TaskResource.configuration(SettingsManager.settingsLocation()));
    }

    /// Analyzes a supplied log or the latest instance log with CrashReportAnalyzer and XYAT.
    ///
    /// @param instanceId instance identifier
    /// @param logText optional raw log text
    /// @param crashReportPath optional crash-report file path
    /// @return unstarted task producing structured rule matches and an extracted crash report
    @Override
    public Task<@Unmodifiable Map<String, Object>> analyzeCrash(
            String instanceId,
            @Nullable String logText,
            @Nullable String crashReportPath) {
        GameInstanceID id = id(instanceId);
        Path repositoryDirectory = repositoryDirectory();
        Path instanceDirectory = instanceDirectory(repositoryDirectory, id);
        return Task.<@Unmodifiable Map<String, Object>>composeAsync("Resolve MCP crash inputs",
                () -> repository.callWithStableBaseDirectory(repositoryDirectory, () -> {
                    requireInstanceDirectory(id, instanceDirectory);
                    requireInstance(id);
                    Path runDirectory = resolvedRunDirectory(id);
                    List<String> contextWarnings = new ArrayList<>();
                    @Nullable LaunchState launchState = launchStates.get(new LaunchKey(repositoryDirectory, id));
                    ProcessListener.ExitType exitType = launchState != null && launchState.exitType != null
                            ? launchState.exitType
                            : ProcessListener.ExitType.APPLICATION_ERROR;
                    XYMLMcpCrashAnalysisSupport.Context context;
                    try {
                        context = XYMLMcpCrashAnalysisSupport.contextual(
                                repository,
                                repositoryDirectory,
                                instanceDirectory,
                                runDirectory,
                                id,
                                exitType,
                                effectiveSettings(id),
                                contextWarnings);
                    } catch (RuntimeException contextFailure) {
                        LOG.warning("Unable to collect optional XYAT instance context for " + id.id(), contextFailure);
                        contextWarnings.add(XYAT_CONTEXT_UNAVAILABLE_WARNING);
                        context = XYMLMcpCrashAnalysisSupport.Context.basic(
                                repositoryDirectory,
                                instanceDirectory,
                                runDirectory,
                                id,
                                exitType);
                    }
                    XYMLMcpCrashAnalysisSupport.Context capturedContext = context;
                    if (logText != null && crashReportPath == null) {
                        String suppliedLog = logText;
                        return Task.<@Unmodifiable Map<String, Object>>supplyAsync(
                                "Analyze supplied MCP crash text",
                                Schedulers.defaultScheduler(),
                                () -> analyzeCrashResult(
                                        capturedContext,
                                        suppliedLog,
                                        XYMLMcpCrashReportResolver.resolve(
                                                runDirectory.resolve("crash-reports"),
                                                suppliedLog,
                                                null,
                                                false),
                                        contextWarnings,
                                        false))
                                .asOrchestration();
                    }
                    Path crashDirectory = runDirectory.resolve("crash-reports");
                    return repositoryTask("Analyze MCP game crash", repositoryDirectory, () -> {
                        requireInstanceDirectory(id, instanceDirectory);
                        requireInstance(id);
                        String rawLog = logText != null ? logText : readLog(runDirectory);
                        XYMLMcpCrashReportResolver.Resolution resolution = XYMLMcpCrashReportResolver.resolve(
                                crashDirectory,
                                rawLog,
                                crashReportPath,
                                logText == null);
                        return analyzeCrashResult(
                                capturedContext,
                                rawLog,
                                resolution,
                                contextWarnings,
                                logText == null);
                    },
                            TaskResource.repositoryOperation(repositoryDirectory),
                            TaskResource.gameInstance(instanceDirectory),
                            TaskResource.gameDirectory(runDirectory));
                })).setExecutor(Schedulers.io()).setResources(
                TaskResource.gameInstance(instanceDirectory),
                TaskResource.configuration(instanceSettingsFile(repositoryDirectory, id)),
                TaskResource.configuration(SettingsManager.gameSettingsLocation()),
                TaskResource.configuration(SettingsManager.settingsLocation()))
                .releaseResourcesBeforeDependencies();
    }

    /// Plans one structured XYAT repair solution without executing its task.
    ///
    /// @param analysisId server-issued crash-analysis identifier
    /// @param solutionId solution identifier returned by that analysis
    /// @return immutable plan or non-executable explanation
    @Override
    public @Unmodifiable Map<String, Object> planCrashSolution(String analysisId, String solutionId) {
        return crashRepairCoordinator.plan(analysisId, solutionId);
    }

    /// Executes one fresh repair task from a one-time, revalidated plan.
    ///
    /// @param planId server-issued repair-plan identifier
    /// @return immutable asynchronous operation status
    @Override
    public @Unmodifiable Map<String, Object> executeCrashSolution(String planId) {
        return crashRepairCoordinator.execute(planId);
    }

    /// Returns the current state of one crash-repair operation.
    ///
    /// @param operationId server-issued repair-operation identifier
    /// @return immutable operation state
    @Override
    public @Unmodifiable Map<String, Object> getCrashRepairStatus(String operationId) {
        return crashRepairCoordinator.status(operationId);
    }

    /// Requests cooperative cancellation of one crash-repair operation.
    ///
    /// @param operationId server-issued repair-operation identifier
    /// @return immutable operation state and cancellation acceptance
    @Override
    public @Unmodifiable Map<String, Object> cancelCrashRepair(String operationId) {
        return crashRepairCoordinator.cancel(operationId);
    }

    /// Analyzes log text without requiring an initialized game repository.
    ///
    /// This overload is useful for offline diagnostics and tests that only need the existing
    /// CrashReportAnalyzer rules.
    ///
    /// @param logText raw log text
    /// @return structured rule matches and extracted crash report
    public static @Unmodifiable Map<String, Object> analyzeCrashText(String logText) {
        String checkedLog = Objects.requireNonNull(logText, "logText");
        @Nullable String report = CrashReportAnalyzer.extractCrashReport(checkedLog);
        Map<String, Object> result = new LinkedHashMap<>(XYMLMcpCrashAnalyzer.analyze(checkedLog, report));
        result.put("crash_report_source", report == null
                ? XYMLMcpCrashReportResolver.SOURCE_NONE
                : XYMLMcpCrashReportResolver.SOURCE_EMBEDDED);
        result.put("warnings", List.of());
        return Map.copyOf(result);
    }

    /// Reads a supported `xyml://` resource URI.
    ///
    /// @param uri resource URI
    /// @return unstarted task producing an immutable resource URI, MIME type, and text
    @Override
    public Task<@Unmodifiable Map<String, String>> readResource(String uri) {
        Matcher logMatcher = LOG_RESOURCE.matcher(uri);
        if (logMatcher.matches()) {
            GameInstanceID id = instanceIdFromUri(logMatcher.group(1));
            return resourceReadTask(uri, id, ResourceKind.LOG, null);
        }

        Matcher directoryMatcher = CRASH_DIRECTORY_RESOURCE.matcher(uri);
        if (directoryMatcher.matches()) {
            GameInstanceID id = instanceIdFromUri(directoryMatcher.group(1));
            return resourceReadTask(uri, id, ResourceKind.CRASH_DIRECTORY, null);
        }

        Matcher reportMatcher = CRASH_REPORT_RESOURCE.matcher(uri);
        if (reportMatcher.matches()) {
            GameInstanceID id = instanceIdFromUri(reportMatcher.group(1));
            return resourceReadTask(
                    uri,
                    id,
                    ResourceKind.CRASH_REPORT,
                    decodePathSegment(reportMatcher.group(2)));
        }
        throw new IllegalArgumentException("Unsupported XYML resource URI: " + uri);
    }

    /// Lists Java runtimes already discovered by JavaManager.
    ///
    /// @return immutable runtime summaries
    @Override
    public @Unmodifiable List<@Unmodifiable Map<String, Object>> listJavaRuntimes() throws InterruptedException {
        List<@Unmodifiable Map<String, Object>> result = new ArrayList<>();
        for (JavaRuntime runtime : JavaManager.getAllJava()) {
            result.add(Map.of(
                    "path", runtime.getBinary().toAbsolutePath().normalize().toString(),
                    "version", runtime.getVersion(),
                    "major", runtime.getParsedVersion(),
                    "bits", runtime.getBits().name(),
                    "managed", runtime.isManaged(),
                    "jdk", runtime.isJDK()));
        }
        return List.copyOf(result);
    }

    /// Lists locally installed mods and their enabled state.
    ///
    /// @param instanceId instance identifier
    /// @return unstarted task producing immutable mod summaries
    @Override
    public Task<@Unmodifiable List<@Unmodifiable Map<String, Object>>> listLocalMods(String instanceId) {
        GameInstanceID id = id(instanceId);
        Path repositoryDirectory = repositoryDirectory();
        Path instanceDirectory = instanceDirectory(repositoryDirectory, id);
        return Task.<@Unmodifiable List<@Unmodifiable Map<String, Object>>>composeAsync(
                "Resolve MCP local mods", () -> repository.callWithStableBaseDirectory(repositoryDirectory, () -> {
            requireInstanceDirectory(id, instanceDirectory);
            requireInstance(id);
            Path modsDirectory = resolvedModsDirectory(id);
            return repositoryTask("List MCP local mods", repositoryDirectory, () -> {
                requireInstanceDirectory(id, instanceDirectory);
                requireInstance(id);
                ModManager manager = new ModManager(repository, id, modsDirectory);
                List<@Unmodifiable Map<String, Object>> result = new ArrayList<>();
                for (LocalModFile mod : manager.getLocalFiles()) {
                    result.add(Map.of(
                            "id", mod.getId(),
                            "name", mod.getName(),
                            "version", mod.getVersion(),
                            "path", mod.getFile().toAbsolutePath().normalize().toString(),
                            "enabled", mod.isActive(),
                            "loader", mod.getModLoaderType().name()));
                }
                return List.copyOf(result);
            },
                    TaskResource.repositoryOperation(repositoryDirectory),
                    TaskResource.gameInstance(instanceDirectory),
                    TaskResource.gameDirectory(modsDirectory));
        })).setExecutor(Schedulers.io()).setResources(
                TaskResource.configuration(instanceSettingsFile(repositoryDirectory, id)),
                TaskResource.configuration(SettingsManager.gameSettingsLocation()),
                TaskResource.configuration(SettingsManager.settingsLocation()))
                .releaseResourcesBeforeDependencies();
    }

    /// Changes the Java selection to a numeric version or executable path.
    ///
    /// @param instanceId instance identifier
    /// @param javaVersion numeric Java version, or blank when `javaPath` is used
    /// @param javaPath executable path, or blank when `javaVersion` is used
    /// @param inherit whether all Java overrides should be removed
    /// @return unstarted task producing resulting effective settings
    @Override
    public Task<@Unmodifiable Map<String, Object>> setJavaVersion(
            String instanceId,
            @Nullable String javaVersion,
            @Nullable String javaPath,
            boolean inherit) {
        GameInstanceID id = id(instanceId);
        return settingsTask("Set MCP instance Java", id, setting -> {
            boolean hasVersion = javaVersion != null && !javaVersion.isBlank();
            boolean hasPath = javaPath != null && !javaPath.isBlank();
            @Nullable String normalizedJavaPath = null;
            @Nullable String normalizedJavaVersion = null;
            if (inherit) {
                if (hasVersion || hasPath) {
                    throw new IllegalArgumentException("Inherited Java settings cannot include a version or path");
                }
            } else if (hasVersion == hasPath) {
                throw new IllegalArgumentException("Exactly one of javaVersion or javaPath must be supplied");
            } else if (hasPath) {
                normalizedJavaPath = Path.of(Objects.requireNonNull(javaPath, "javaPath"))
                        .toAbsolutePath().normalize().toString();
            } else {
                normalizedJavaVersion = Integer.toString(parsePositive(javaVersion, "javaVersion"));
            }

            if (inherit) {
                setting.getOverrideProperties().remove(GameSettings.PROPERTY_JAVA_TYPE);
                setting.getOverrideProperties().remove(GameSettings.PROPERTY_CUSTOM_JAVA_VERSION);
                setting.getOverrideProperties().remove(GameSettings.PROPERTY_CUSTOM_JAVA_PATH);
            } else if (normalizedJavaPath != null) {
                setting.getOverrideProperties().add(GameSettings.PROPERTY_JAVA_TYPE);
                setting.javaTypeProperty().setValue(JavaVersionType.CUSTOM);
                setting.getOverrideProperties().add(GameSettings.PROPERTY_CUSTOM_JAVA_PATH);
                setting.customJavaPathProperty().setValue(normalizedJavaPath);
                setting.getOverrideProperties().remove(GameSettings.PROPERTY_CUSTOM_JAVA_VERSION);
            } else {
                setting.getOverrideProperties().add(GameSettings.PROPERTY_JAVA_TYPE);
                setting.javaTypeProperty().setValue(JavaVersionType.VERSION);
                setting.getOverrideProperties().add(GameSettings.PROPERTY_CUSTOM_JAVA_VERSION);
                setting.customJavaVersionProperty().setValue(
                        Objects.requireNonNull(normalizedJavaVersion, "normalizedJavaVersion"));
                setting.getOverrideProperties().remove(GameSettings.PROPERTY_CUSTOM_JAVA_PATH);
            }
        });
    }

    /// Changes minimum and maximum heap memory in MiB.
    ///
    /// @param instanceId instance identifier
    /// @param minMemory minimum heap, or null to leave unchanged
    /// @param maxMemory maximum heap, or null to leave unchanged
    /// @param inherit whether both heap overrides should be removed
    /// @return unstarted task producing resulting effective settings
    @Override
    public Task<@Unmodifiable Map<String, Object>> setMemory(
            String instanceId,
            @Nullable Integer minMemory,
            @Nullable Integer maxMemory,
            boolean inherit) {
        GameInstanceID id = id(instanceId);
        return settingsTask("Set MCP instance memory", id, setting -> {
            if (inherit) {
                if (minMemory != null || maxMemory != null) {
                    throw new IllegalArgumentException("Inherited memory settings cannot include heap values");
                }
                setting.getOverrideProperties().remove(GameSettings.PROPERTY_MIN_MEMORY);
                setting.getOverrideProperties().remove(GameSettings.PROPERTY_MAX_MEMORY);
            } else {
                if (minMemory == null && maxMemory == null) {
                    throw new IllegalArgumentException("At least one memory value must be supplied");
                }
                GameSettings.Effective effective = repository.getEffectiveGameSettings(id);
                validateMemoryUpdate(
                        minMemory,
                        maxMemory,
                        effective.getInheritable(GameSettings::minMemoryProperty),
                        effective.getMaxMemory());
                if (minMemory != null) {
                    applyMemoryOverride(
                            setting, GameSettings.PROPERTY_MIN_MEMORY, setting.minMemoryProperty(), minMemory);
                }
                if (maxMemory != null) {
                    applyMemoryOverride(
                            setting, GameSettings.PROPERTY_MAX_MEMORY, setting.maxMemoryProperty(), maxMemory);
                }
            }
        });
    }

    /// Validates a partial heap update against the effective values it leaves unchanged.
    ///
    /// @param minMemory requested minimum heap, or null to retain the effective minimum
    /// @param maxMemory requested maximum heap, or null to retain the effective maximum
    /// @param currentMinMemory current effective minimum heap, or null for the launcher default
    /// @param currentMaxMemory current effective maximum heap
    static void validateMemoryUpdate(
            @Nullable Integer minMemory,
            @Nullable Integer maxMemory,
            @Nullable Integer currentMinMemory,
            int currentMaxMemory) {
        if (minMemory != null && minMemory < 0 || maxMemory != null && maxMemory <= 0) {
            throw new IllegalArgumentException("Memory values must be positive (minimum may be zero)");
        }
        int resultingMin = minMemory != null
                ? minMemory : Objects.requireNonNullElse(currentMinMemory, 0);
        int resultingMax = maxMemory != null ? maxMemory : currentMaxMemory;
        if (resultingMin > resultingMax) {
            throw new IllegalArgumentException("minMemory must not exceed maxMemory");
        }
    }

    /// Applies one heap setting value while preserving the instance inheritance contract.
    ///
    /// A null value removes the instance override; it does not create an override whose value is
    /// null, because effective settings resolve null direct values to their property defaults.
    ///
    /// @param setting instance settings to update
    /// @param propertyName serialized override-property name
    /// @param property property receiving a non-null override value
    /// @param value requested value, or null to inherit
    static void applyMemoryOverride(
            GameSettings.Instance setting,
            String propertyName,
            InheritableProperty<@Nullable Integer> property,
            @Nullable Integer value) {
        if (value == null) {
            setting.getOverrideProperties().remove(propertyName);
        } else {
            setting.getOverrideProperties().add(propertyName);
            property.setValue(value);
        }
    }

    /// Changes the raw JVM options string.
    ///
    /// @param instanceId instance identifier
    /// @param options JVM options string, or null when inheritance is requested
    /// @param inherit whether the JVM-options override should be removed
    /// @return unstarted task producing resulting effective settings
    @Override
    public Task<@Unmodifiable Map<String, Object>> setJvmOptions(
            String instanceId,
            @Nullable String options,
            boolean inherit) {
        GameInstanceID id = id(instanceId);
        return settingsTask("Set MCP instance JVM options", id, setting -> {
            if (inherit) {
                if (options != null) {
                    throw new IllegalArgumentException("Inherited JVM options cannot include an options value");
                }
                setting.getOverrideProperties().remove(GameSettings.PROPERTY_JVM_OPTIONS);
            } else {
                String checkedOptions = Objects.requireNonNull(options, "options");
                setting.getOverrideProperties().add(GameSettings.PROPERTY_JVM_OPTIONS);
                setting.jvmOptionsProperty().setValue(checkedOptions);
            }
        });
    }

    /// Changes window dimensions and fullscreen state.
    ///
    /// @param instanceId instance identifier
    /// @param width optional width
    /// @param height optional height
    /// @param fullscreen optional fullscreen state
    /// @param inherit whether all window overrides should be removed
    /// @return unstarted task producing resulting effective settings
    @Override
    public Task<@Unmodifiable Map<String, Object>> setWindowOptions(
            String instanceId,
            @Nullable Integer width,
            @Nullable Integer height,
            @Nullable Boolean fullscreen,
            boolean inherit) {
        if (width != null && width < 0 || height != null && height < 0) {
            throw new IllegalArgumentException("Window dimensions must not be negative");
        }
        GameInstanceID id = id(instanceId);
        return settingsTask("Set MCP instance window", id, setting -> {
            if (inherit) {
                if (width != null || height != null || fullscreen != null) {
                    throw new IllegalArgumentException("Inherited window settings cannot include explicit values");
                }
                setting.getOverrideProperties().remove(GameSettings.PROPERTY_WIDTH);
                setting.getOverrideProperties().remove(GameSettings.PROPERTY_HEIGHT);
                setting.getOverrideProperties().remove(GameSettings.PROPERTY_WINDOW_TYPE);
            } else {
                if (width == null && height == null && fullscreen == null) {
                    throw new IllegalArgumentException("At least one window setting must be supplied");
                }
                if (width != null) {
                    setting.getOverrideProperties().add(GameSettings.PROPERTY_WIDTH);
                    setting.widthProperty().setValue(width.doubleValue());
                }
                if (height != null) {
                    setting.getOverrideProperties().add(GameSettings.PROPERTY_HEIGHT);
                    setting.heightProperty().setValue(height.doubleValue());
                }
                if (fullscreen != null) {
                    setting.getOverrideProperties().add(GameSettings.PROPERTY_WINDOW_TYPE);
                    setting.windowTypeProperty().setValue(
                            fullscreen ? GameWindowType.FULLSCREEN : GameWindowType.WINDOWED);
                }
            }
        });
    }

    /// Enables one mod file through ModManager's `.disabled` transition.
    ///
    /// @param instanceId target instance
    /// @param path mod file path
    /// @return unstarted task producing the resulting file path
    @Override
    public Task<String> enableMod(String instanceId, String path) {
        return transitionModTask("Enable MCP mod", id(instanceId), path, true);
    }

    /// Disables one mod file through ModManager's `.disabled` transition.
    ///
    /// @param instanceId target instance
    /// @param path mod file path
    /// @return unstarted task producing the resulting file path
    @Override
    public Task<String> disableMod(String instanceId, String path) {
        return transitionModTask("Disable MCP mod", id(instanceId), path, false);
    }

    /// Creates one precisely resourced mod enablement or disablement task.
    ///
    /// @param name task name
    /// @param id target instance identifier
    /// @param rawPath requested managed mod path
    /// @param enable whether to remove the disabled suffix
    /// @return unstarted task producing the resulting normalized path
    private Task<String> transitionModTask(String name, GameInstanceID id, String rawPath, boolean enable) {
        Path repositoryDirectory = repositoryDirectory();
        Path instanceDirectory = instanceDirectory(repositoryDirectory, id);
        return Task.<String>composeAsync("Resolve MCP mod transition",
                () -> repository.callWithStableBaseDirectory(repositoryDirectory, () -> {
            requireInstanceDirectory(id, instanceDirectory);
            requireInstance(id);
            Path modsDirectory = resolvedModsDirectory(id);
            Path path = requireManagedModCandidate(modsDirectory, normalizedModCandidate(rawPath));
            return repositoryTask(name, repositoryDirectory, () -> {
                requireInstanceDirectory(id, instanceDirectory);
                requireInstance(id);
                requireTrackedLaunchStopped(repositoryDirectory, id, enable ? "enable mods" : "disable mods");
                ModManager manager = new ModManager(repository, id, modsDirectory);
                return transitionMod(manager, path, enable).toString();
            },
                    TaskResource.repositoryOperation(repositoryDirectory),
                    TaskResource.gameInstance(instanceDirectory),
                    TaskResource.gameDirectory(modsDirectory));
        })).setExecutor(Schedulers.io()).setResources(
                TaskResource.configuration(instanceSettingsFile(repositoryDirectory, id)),
                TaskResource.configuration(SettingsManager.gameSettingsLocation()),
                TaskResource.configuration(SettingsManager.settingsLocation()))
                .releaseResourcesBeforeDependencies();
    }

    /// Removes selected local mod files after applying the launcher-owned confirmation policy.
    ///
    /// @param instanceId target instance
    /// @param paths files to remove
    /// @return unstarted task producing immutable approval state and removed paths
    @Override
    public Task<@Unmodifiable Map<String, Object>> removeMods(
            String instanceId,
            @Unmodifiable List<String> paths) {
        GameInstanceID id = id(instanceId);
        @Unmodifiable List<String> pathSnapshot = List.copyOf(paths);
        if (pathSnapshot.isEmpty()) {
            return Task.completed(Map.of("approved", true, "removed", List.of()));
        }
        @Unmodifiable List<Path> candidates = pathSnapshot.stream()
                .map(XYMLMcpService::normalizedModCandidate)
                .distinct()
                .toList();
        Path repositoryDirectory = repositoryDirectory();
        Path instanceDirectory = instanceDirectory(repositoryDirectory, id);
        return Task.<@Unmodifiable Map<String, Object>>composeAsync("Resolve MCP mod removal",
                () -> repository.callWithStableBaseDirectory(repositoryDirectory, () -> {
                    requireInstanceDirectory(id, instanceDirectory);
                    requireInstance(id);
                    Path modsDirectory = resolvedModsDirectory(id);
                    return Task.<@Unmodifiable Map<String, Object>>composeAsync("Confirm MCP mod removal", () -> {
                        requireTrackedLaunchStopped(repositoryDirectory, id, "remove mods");
                        ModManager currentManager = new ModManager(repository, id, modsDirectory);
                        Map<Path, LocalModFile> currentSelection =
                                selectedManagedMods(currentManager, modsDirectory, candidates);
                        boolean approved = withSettingsSaveBarrier(() -> deletionConfirmation.confirm(
                                McpDeletionConfirmation.DeletionRequest.mods(id, currentSelection.size())));
                        if (!approved) {
                            return Task.<@Unmodifiable Map<String, Object>>completed(
                                    Map.of("approved", false, "removed", List.of())).asOrchestration();
                        }
                        return repositoryTask("Remove MCP mods", repositoryDirectory, () -> {
                            requireInstanceDirectory(id, instanceDirectory);
                            requireInstance(id);
                            requireTrackedLaunchStopped(repositoryDirectory, id, "remove mods");
                            List<String> selectedPaths = currentSelection.keySet().stream()
                                    .map(Path::toString)
                                    .toList();
                            try {
                                currentManager.removeMods(currentSelection.values().toArray(LocalModFile[]::new));
                            } finally {
                                currentManager.invalidateCache();
                            }
                            return Map.of("approved", true, "removed", selectedPaths);
                        },
                                TaskResource.repositoryOperation(repositoryDirectory),
                                TaskResource.gameInstance(instanceDirectory),
                                TaskResource.gameDirectory(modsDirectory));
                    }).setExecutor(Schedulers.io()).setResources(
                            TaskResource.repositoryOperation(repositoryDirectory),
                            TaskResource.gameInstance(instanceDirectory),
                            TaskResource.gameDirectory(modsDirectory));
                })).setExecutor(Schedulers.io()).setResources(
                TaskResource.configuration(instanceSettingsFile(repositoryDirectory, id)),
                TaskResource.configuration(SettingsManager.gameSettingsLocation()),
                TaskResource.configuration(SettingsManager.settingsLocation()))
                .releaseResourcesBeforeDependencies();
    }

    /// Starts an instance with the launcher-generated options and captures monitor state.
    ///
    /// @param instanceId target instance
    /// @return unstarted task producing launch acceptance and process metadata
    @Override
    public Task<@Unmodifiable Map<String, Object>> launchGame(String instanceId) {
        GameInstanceID id = id(instanceId);
        Path repositoryDirectory = repositoryDirectory();
        Path instanceDirectory = instanceDirectory(repositoryDirectory, id);
        Task<@Unmodifiable Map<String, Object>> preparation = Task.composeAsync(
                "Prepare MCP game launch", () -> repository.callWithStableBaseDirectory(repositoryDirectory, () -> {
            requireRepositoryDirectory(repositoryDirectory);
            requireInstanceDirectory(id, instanceDirectory);
            requireInstance(id);
            LaunchKey launchKey = new LaunchKey(repositoryDirectory, id);
            @Nullable LaunchState existing = launchStates.get(launchKey);
            if (existing != null && existing.process != null && existing.process.isRunning()) {
                throw new IllegalStateException("A tracked launch is already running for this instance");
            }
            Path runDirectory = resolvedRunDirectory(id);
            Path librariesDirectory = repositoryDirectory.resolve("libraries");
            GameInstanceManifest manifest = repository.getResolvedInstanceManifest(id).launchManifest();
            GameInstanceID jarInstanceId = Objects.requireNonNullElse(manifest.jar(), manifest.id());
            Path jarInstanceDirectory = instanceDirectory(jarInstanceId);
            String assetId = manifest.getAssetIndex().getId();
            Path assetsDirectory = repository.getAssetDirectory(id, assetId).toAbsolutePath().normalize();
            @Nullable JavaRuntime selectedJava = effectiveSettings(id).getJava(
                    repository.getGameVersion(id)
                            .map(space.minecraftstl.xyml.util.versioning.GameVersionNumber::asGameVersion)
                            .orElse(null),
                    manifest);
            if (selectedJava == null) {
                throw new IllegalStateException("No compatible Java runtime was found");
            }
            JavaRuntime java = selectedJava;
            LaunchOptions options = withSettingsSaveBarrier(() -> repository.getLaunchOptions(
                    id, java, runDirectory, List.of(), List.of(), false)
                    .setDaemon(true)
                    .create());
            String wrapper = Objects.requireNonNullElse(options.getWrapper(), "");
            String preLaunchCommand = Objects.requireNonNullElse(options.getPreLaunchCommand(), "");
            String postExitCommand = Objects.requireNonNullElse(options.getPostExitCommand(), "");
            requireSupportedLaunchCommands(wrapper, preLaunchCommand, postExitCommand);
            Path nativesDirectory = nativeDirectory(repository, id, java, options);
            Task<@Unmodifiable Map<String, Object>> launch = repositoryTask(
                    "Launch MCP game", repositoryDirectory, () -> {
                requireRepositoryDirectory(repositoryDirectory);
                requireInstanceDirectory(id, instanceDirectory);
                requireInstance(id);
                @Nullable LaunchState current = launchStates.get(launchKey);
                if (current != null && current.process != null && current.process.isRunning()) {
                    throw new IllegalStateException("A tracked launch is already running for this instance");
                }
                GameInstanceManifest currentManifest = repository.getResolvedInstanceManifest(id).launchManifest();
                if (!manifest.equals(currentManifest)) {
                    throw new IllegalStateException("Instance manifest changed while waiting for resources");
                }
                GameInstanceID currentJarInstanceId =
                        Objects.requireNonNullElse(currentManifest.jar(), currentManifest.id());
                if (!jarInstanceId.equals(currentJarInstanceId)) {
                    throw new IllegalStateException("Inherited game JAR changed while waiting for resources");
                }
                requirePath(assetsDirectory,
                        repository.getAssetDirectory(id, currentManifest.getAssetIndex().getId()), "assets directory");
                LaunchState state = new LaunchState();
                DefaultLauncher launcher = new DefaultLauncher(repository, currentManifest,
                        offlineAuth(id), options, state, true);
                state.process = launcher.launch();
                launchStates.put(launchKey, state);
                return Map.of("instance_id", id.id(), "started", true,
                        "running", state.process.isRunning());
            }, launchResources(repositoryDirectory, instanceDirectory, jarInstanceDirectory,
                    runDirectory, librariesDirectory,
                    assetsDirectory, java, nativesDirectory));
            return launch;
        })).setExecutor(Schedulers.io())
                .setResources(
                        TaskResource.gameInstance(instanceDirectory),
                        TaskResource.configuration(SettingsManager.gameSettingsLocation()),
                        TaskResource.configuration(SettingsManager.settingsLocation()))
                .releaseResourcesBeforeDependencies();
        return preparation;
    }

    /// Stops a running instance process if one is tracked.
    ///
    /// @param instanceId target instance
    /// @return unstarted task producing whether a process was stopped
    @Override
    public Task<@Unmodifiable Map<String, Object>> stopGame(String instanceId) {
        GameInstanceID id = id(instanceId);
        Path repositoryDirectory = repositoryDirectory();
        Path instanceDirectory = instanceDirectory(repositoryDirectory, id);
        return repositoryTask("Stop MCP game", repositoryDirectory, () -> {
            requireInstanceDirectory(id, instanceDirectory);
            LaunchState state = launchStates.get(new LaunchKey(repositoryDirectory, id));
            if (state == null || state.process == null || !state.process.isRunning()) {
                return Map.of("instance_id", id.id(), "stopped", false);
            }
            state.process.stop();
            return Map.of("instance_id", id.id(), "stopped", true);
        }, TaskResource.gameInstance(instanceDirectory));
    }

    /// Returns current process state, exit code, and ExitType classification.
    ///
    /// @param instanceId target instance
    /// @return immutable launch state
    @Override
    public @Unmodifiable Map<String, Object> getLaunchStatus(String instanceId) {
        GameInstanceID id = id(instanceId);
        LaunchState state = launchStates.get(new LaunchKey(repositoryDirectory(), id));
        if (state == null) {
            return Map.of("instance_id", id.id(), "started", false, "running", false,
                    "exit_code", nullValue(), "exit_type", nullValue(), "logs", List.of());
        }
        @Nullable Integer exitCode = state.exitCode;
        @Nullable ProcessListener.ExitType exitType = state.exitType;
        return Map.of("instance_id", id.id(), "started", true,
                "running", state.process != null && state.process.isRunning(),
                "exit_code", exitCode == null ? nullValue() : exitCode,
                "exit_type", exitType == null ? nullValue() : exitType.name(),
                "logs", state.logsSnapshot());
    }

    /// Completes a staged instance lifecycle mutation with a short repository refresh and selection update.
    ///
    /// @param mutation precise disk-mutation stage
    /// @param repositoryDirectory captured repository root
    /// @param preferredSelection preferred post-refresh instance, or `null` after deletion
    /// @param committed whether the disk mutation returned successfully before terminal handling
    /// @param result immutable operation result
    /// @return unstarted task representing every lifecycle stage
    private Task<@Unmodifiable Map<String, Object>> lifecycleResult(
            Task<@Nullable Void> mutation,
            Path repositoryDirectory,
            @Nullable GameInstanceID preferredSelection,
            AtomicBoolean committed,
            @Unmodifiable Map<String, Object> result) {
        Task<@Nullable Void> cleanup = mutation.whenTerminalWithResources(Schedulers.io(), ignoredFailure -> {
            instanceLifecycle.completeLifecycle(repositoryDirectory, preferredSelection, committed.get());
        },
                TaskResource.gameDirectory(repositoryDirectory),
                TaskResource.configuration(SettingsManager.settingsLocation()));
        return cleanup.thenApplyAsync(Schedulers.io(), ignored -> result)
                .asOrchestration();
    }

    /// Drains every settings save queued before a barrier while preserving interruption semantics.
    ///
    /// An interrupt is remembered, cleared long enough to reach the FileSaver barrier, then restored and rethrown.
    /// This keeps asynchronous configuration writes inside the current task resource lease without hiding cancellation.
    ///
    /// @throws InterruptedException after the save barrier when the waiting thread was interrupted
    private static void waitForSettingsSaves() throws InterruptedException {
        @Nullable InterruptedException interruption = null;
        while (true) {
            try {
                FileSaver.waitForAllSaves();
                break;
            } catch (InterruptedException exception) {
                if (interruption == null) {
                    interruption = exception;
                } else {
                    interruption.addSuppressed(exception);
                }
                Thread.interrupted();
            }
        }
        if (interruption != null) {
            Thread.currentThread().interrupt();
            throw interruption;
        }
    }

    /// Drains queued settings saves after a primary failure without replacing that failure.
    ///
    /// @param primaryFailure failure that must remain primary
    private static void waitForSettingsSaves(Throwable primaryFailure) {
        try {
            waitForSettingsSaves();
        } catch (InterruptedException interruption) {
            primaryFailure.addSuppressed(interruption);
        }
    }

    /// Runs a setting-derived operation and retains its save barrier through every terminal path.
    ///
    /// @param operation operation that cannot throw a checked exception
    /// @param <T> result type
    /// @return operation result
    /// @throws InterruptedException if the save barrier is interrupted after completing its drain
    private static <T> T withSettingsSaveBarrier(Supplier<T> operation) throws InterruptedException {
        @Nullable Throwable primaryFailure = null;
        try {
            return operation.get();
        } catch (RuntimeException | Error failure) {
            primaryFailure = failure;
            throw failure;
        } finally {
            if (primaryFailure == null) {
                waitForSettingsSaves();
            } else {
                waitForSettingsSaves(primaryFailure);
            }
        }
    }

    /// Runs a checked setting-derived operation and retains its save barrier through every terminal path.
    ///
    /// @param operation operation that may throw a checked exception
    /// @param <T> result type
    /// @return operation result
    /// @throws Exception from the operation, or interruption after completing the save drain
    private static <T> T callWithSettingsSaveBarrier(Callable<T> operation) throws Exception {
        @Nullable Throwable primaryFailure = null;
        try {
            return operation.call();
        } catch (RuntimeException | Error failure) {
            primaryFailure = failure;
            throw failure;
        } catch (Exception failure) {
            primaryFailure = failure;
            throw failure;
        } finally {
            if (primaryFailure == null) {
                waitForSettingsSaves();
            } else {
                waitForSettingsSaves(primaryFailure);
            }
        }
    }

    /// Creates one instance-setting mutation whose delayed FileSaver write remains inside the task resource lifetime.
    ///
    /// @param name task name
    /// @param id target instance identifier
    /// @param mutation validated in-memory settings mutation
    /// @return unstarted task producing the resulting effective settings
    private Task<@Unmodifiable Map<String, Object>> settingsTask(
            String name,
            GameInstanceID id,
            Consumer<GameSettings.Instance> mutation) {
        Path repositoryDirectory = repositoryDirectory();
        Path instanceDirectory = instanceDirectory(repositoryDirectory, id);
        return repositoryTask(name, repositoryDirectory,
                () -> callWithSettingsSaveBarrier(() -> {
                    requireInstanceDirectory(id, instanceDirectory);
                    requireTrackedLaunchStopped(repositoryDirectory, id, "change settings");
                    GameSettings.Instance setting = writableSettings(id);
                    mutation.accept(setting);
                    repository.saveGameSettings(id);
                    return instanceSettings(id);
                }),
                TaskResource.configuration(instanceSettingsFile(repositoryDirectory, id)),
                TaskResource.configuration(SettingsManager.gameSettingsLocation()),
                TaskResource.configuration(SettingsManager.settingsLocation()));
    }

    /// Creates a protected read of one instance-owned log or crash-report resource.
    ///
    /// @param uri requested resource URI
    /// @param id target instance identifier
    /// @param kind supported resource shape
    /// @param reportName decoded report file name, or `null` for non-file resources
    /// @return unstarted task producing the resource response
    private Task<@Unmodifiable Map<String, String>> resourceReadTask(
            String uri,
            GameInstanceID id,
            ResourceKind kind,
            @Nullable String reportName) {
        Path repositoryDirectory = repositoryDirectory();
        Path instanceDirectory = instanceDirectory(repositoryDirectory, id);
        return Task.<@Unmodifiable Map<String, String>>composeAsync("Resolve MCP instance resource",
                () -> repository.callWithStableBaseDirectory(repositoryDirectory, () -> {
            requireInstanceDirectory(id, instanceDirectory);
            requireInstance(id);
            Path runDirectory = resolvedRunDirectory(id);
            Path contentDirectory = kind == ResourceKind.LOG
                    ? runDirectory.resolve("logs") : runDirectory.resolve("crash-reports");
            return repositoryTask("Read MCP instance resource", repositoryDirectory, () -> {
                requireInstanceDirectory(id, instanceDirectory);
                requireInstance(id);
                String text = switch (kind) {
                    case LOG -> readLog(runDirectory);
                    case CRASH_DIRECTORY -> listCrashReports(contentDirectory);
                    case CRASH_REPORT -> readCrashReport(
                            contentDirectory,
                            Objects.requireNonNull(reportName, "reportName"));
                };
                return textResource(uri, text);
                    },
                            TaskResource.repositoryOperation(repositoryDirectory),
                            TaskResource.gameInstance(instanceDirectory),
                            TaskResource.gameDirectory(kind == ResourceKind.LOG ? runDirectory : contentDirectory));
        })).setExecutor(Schedulers.io()).setResources(
                TaskResource.configuration(instanceSettingsFile(repositoryDirectory, id)),
                TaskResource.configuration(SettingsManager.gameSettingsLocation()),
                TaskResource.configuration(SettingsManager.settingsLocation()))
                .releaseResourcesBeforeDependencies();
    }

    /// Returns the complete resource set occupied until an MCP launch creates its process.
    ///
    /// @param repositoryDirectory captured repository directory
    /// @param instanceDirectory captured instance directory
    /// @param jarInstanceDirectory captured instance containing the effective inherited game JAR
    /// @param runDirectory captured effective run directory
    /// @param librariesDirectory captured shared library directory
    /// @param assetsDirectory captured shared assets directory
    /// @param java captured selected Java runtime
    /// @param nativesDirectory captured effective natives directory
    /// @return immutable precise resources
    static @Unmodifiable List<TaskResource> launchResources(
            Path repositoryDirectory,
            Path instanceDirectory,
            Path jarInstanceDirectory,
            Path runDirectory,
            Path librariesDirectory,
            Path assetsDirectory,
            JavaRuntime java,
            Path nativesDirectory) {
        return List.of(
                TaskResource.repositoryOperation(repositoryDirectory),
                TaskResource.gameInstance(instanceDirectory),
                TaskResource.gameInstance(jarInstanceDirectory),
                TaskResource.gameDirectory(runDirectory),
                TaskResource.gameDirectory(librariesDirectory),
                TaskResource.gameDirectory(assetsDirectory),
                TaskResource.gameDirectory(nativesDirectory),
                TaskResource.javaRuntime(javaRuntimeDirectory(java)));
    }

    /// Rejects arbitrary command hooks whose descendants could outlive the MCP launch Task resource lease.
    ///
    /// @param wrapper captured configured process wrapper
    /// @param preLaunchCommand captured configured pre-launch command
    /// @param postExitCommand captured configured post-exit command
    /// @throws IllegalStateException when an arbitrary command could escape Task resource arbitration
    static void requireSupportedLaunchCommands(
            String wrapper,
            String preLaunchCommand,
            String postExitCommand) {
        if (StringUtils.isNotBlank(wrapper)) {
            throw new IllegalStateException(
                    "MCP launch cannot run a configured wrapper outside the Task resource lifecycle");
        }
        if (StringUtils.isNotBlank(preLaunchCommand)) {
            throw new IllegalStateException(
                    "MCP launch cannot run a configured pre-launch command outside the Task resource lifecycle");
        }
        if (StringUtils.isNotBlank(postExitCommand)) {
            throw new IllegalStateException(
                    "MCP launch cannot run a configured post-exit command outside the Task resource lifecycle");
        }
    }

    /// Resolves the exact natives directory used by DefaultLauncher.
    ///
    /// @param repository repository providing the default instance-native directory
    /// @param id target instance identifier
    /// @param java selected Java runtime and platform
    /// @param options captured launch options
    /// @return normalized absolute natives directory
    private static Path nativeDirectory(
            XYMLGameRepository repository,
            GameInstanceID id,
            JavaRuntime java,
            LaunchOptions options) {
        @Nullable String configuredDirectory = options.getNativesDir();
        Path directory = StringUtils.isBlank(configuredDirectory)
                ? repository.getNativeDirectory(id, java.getPlatform())
                : Path.of(Objects.requireNonNull(configuredDirectory, "configuredDirectory"));
        return directory.toAbsolutePath().normalize();
    }

    /// Resolves the Java installation tree containing one executable.
    ///
    /// A conventional `bin/java` executable maps to its installation parent. Non-standard layouts fall back to the
    /// executable's immediate directory, while a root-only path remains its own conservative directory key.
    ///
    /// @param java selected Java runtime
    /// @return normalized absolute runtime directory
    private static Path javaRuntimeDirectory(JavaRuntime java) {
        Path binary = java.getBinary().toAbsolutePath().normalize();
        @Nullable Path binaryDirectory = binary.getParent();
        if (binaryDirectory == null) {
            return binary;
        }
        @Nullable Path directoryName = binaryDirectory.getFileName();
        @Nullable Path runtimeDirectory = binaryDirectory.getParent();
        if (directoryName != null && runtimeDirectory != null
                && "bin".equalsIgnoreCase(directoryName.toString())) {
            return runtimeDirectory;
        }
        return binaryDirectory;
    }

    /// Creates a named I/O task with an immutable resource declaration.
    ///
    /// @param name task name
    /// @param operation task operation
    /// @param first first occupied resource
    /// @param additional remaining occupied resources
    /// @param <T> result type
    /// @return unstarted configured task
    private static <T> Task<T> task(
            String name,
            Callable<@Nullable T> operation,
            TaskResource first,
            TaskResource... additional) {
        return Task.supplyAsync(name, Schedulers.io(), operation).setResources(first, additional);
    }

    /// Creates a named I/O task that cannot be redirected by a concurrent repository-root switch.
    ///
    /// @param name task name
    /// @param repositoryDirectory repository root captured with the resource declaration
    /// @param operation task operation using that repository root
    /// @param first first occupied resource
    /// @param additional remaining occupied resources
    /// @param <T> result type
    /// @return unstarted captured-root task
    private <T> Task<T> repositoryTask(
            String name,
            Path repositoryDirectory,
            Callable<@Nullable T> operation,
            TaskResource first,
            TaskResource... additional) {
        return task(name,
                () -> repository.callWithStableBaseDirectory(repositoryDirectory, operation),
                first,
                additional);
    }

    /// Creates a captured-root I/O task from a runtime-sized resource collection.
    ///
    /// @param name task name
    /// @param repositoryDirectory repository root captured with the resource declaration
    /// @param operation task operation using that repository root
    /// @param resources occupied resources
    /// @param <T> result type
    /// @return unstarted captured-root task
    private <T> Task<T> repositoryTask(
            String name,
            Path repositoryDirectory,
            Callable<@Nullable T> operation,
            List<TaskResource> resources) {
        return task(name,
                () -> repository.callWithStableBaseDirectory(repositoryDirectory, operation),
                resources);
    }

    /// Creates a named I/O task from a runtime-sized resource collection.
    ///
    /// @param name task name
    /// @param operation task operation
    /// @param resources occupied resources
    /// @param <T> result type
    /// @return unstarted configured task
    private static <T> Task<T> task(
            String name,
            Callable<@Nullable T> operation,
            List<TaskResource> resources) {
        if (resources.isEmpty()) {
            throw new IllegalArgumentException("MCP tasks must declare at least one resource");
        }
        TaskResource @Unmodifiable [] additional = resources.subList(1, resources.size())
                .toArray(TaskResource[]::new);
        return task(name, operation, resources.get(0), additional);
    }

    /// Creates a named result-less I/O task with an immutable resource declaration.
    ///
    /// @param name task name
    /// @param operation task operation
    /// @param first first occupied resource
    /// @param additional remaining occupied resources
    /// @return unstarted configured task
    private static Task<@Nullable Void> runTask(
            String name,
            space.minecraftstl.xyml.util.function.ExceptionalRunnable<?> operation,
            TaskResource first,
            TaskResource... additional) {
        return Task.runAsync(name, Schedulers.io(), operation).setResources(first, additional);
    }

    /// Returns the normalized repository directory captured for a task declaration.
    ///
    /// @return normalized absolute repository directory
    private Path repositoryDirectory() {
        return repository.getBaseDirectory().toAbsolutePath().normalize();
    }

    /// Returns the normalized instance directory captured for a task declaration.
    ///
    /// @param id instance identifier
    /// @return normalized absolute instance directory
    private Path instanceDirectory(GameInstanceID id) {
        return repository.getInstanceRoot(id).toAbsolutePath().normalize();
    }

    /// Derives an instance directory from one already captured repository root.
    ///
    /// @param repositoryDirectory normalized repository root
    /// @param id instance identifier
    /// @return normalized absolute instance directory under the captured root
    private static Path instanceDirectory(Path repositoryDirectory, GameInstanceID id) {
        return repositoryDirectory.resolve("versions").resolve(id.id()).toAbsolutePath().normalize();
    }

    /// Derives the instance-setting path from one already captured repository root.
    ///
    /// @param repositoryDirectory normalized repository root
    /// @param id instance identifier
    /// @return normalized absolute instance-setting path
    private static Path instanceSettingsFile(Path repositoryDirectory, GameInstanceID id) {
        return instanceDirectory(repositoryDirectory, id)
                .resolve(".xyml")
                .resolve("config")
                .resolve("instance-game-settings.json")
                .toAbsolutePath()
                .normalize();
    }

    /// Rejects a task when its repository moved after the resource declaration was captured.
    ///
    /// @param expected captured normalized repository directory
    private void requireRepositoryDirectory(Path expected) {
        requirePath(expected, repository.getBaseDirectory(), "repository directory");
    }

    /// Rejects a task when its instance path moved after the resource declaration was captured.
    ///
    /// @param id instance identifier
    /// @param expected captured normalized instance directory
    private void requireInstanceDirectory(GameInstanceID id, Path expected) {
        requirePath(expected, repository.getInstanceRoot(id), "instance directory");
    }

    /// Rejects a stale filesystem snapshot before a task performs I/O under an obsolete resource key.
    ///
    /// @param expected captured normalized path
    /// @param actual current path
    /// @param description path description for diagnostics
    private static void requirePath(Path expected, Path actual, String description) {
        Path normalizedActual = actual.toAbsolutePath().normalize();
        if (!expected.equals(normalizedActual)) {
            throw new IllegalStateException(description + " changed while waiting for resources");
        }
    }

    /// Resolves one identifier and verifies it is a valid XYML instance ID.
    private GameInstanceID id(String raw) {
        return new GameInstanceID(Objects.requireNonNull(raw, "instanceId"));
    }

    /// Resolves a filesystem-safe destination instance identifier.
    ///
    /// @param raw raw destination identifier
    /// @return validated destination identifier
    private GameInstanceID destinationId(String raw) {
        String checked = Objects.requireNonNull(raw, "destinationInstanceId");
        if (!instanceLifecycle.isValidDestinationId(checked)) {
            throw new IllegalArgumentException("Invalid destination instance identifier: " + checked);
        }
        return new GameInstanceID(checked);
    }

    /// Verifies that an instance exists before a mutating operation.
    private void requireInstance(GameInstanceID id) {
        if (!repository.hasInstance(id)) {
            throw new IllegalArgumentException("Unknown instance: " + id.id());
        }
    }

    /// Rejects lifecycle mutations while their source has a running MCP-tracked process.
    ///
    /// @param repositoryDirectory captured repository root
    /// @param id source instance identifier
    /// @param operation requested lifecycle operation
    private void requireTrackedLaunchStopped(
            Path repositoryDirectory,
            GameInstanceID id,
            String operation) {
        @Nullable LaunchState state = launchStates.get(new LaunchKey(repositoryDirectory, id));
        if (state != null && state.process != null && state.process.isRunning()) {
            throw new IllegalStateException("Cannot " + operation + " an instance while its tracked game is running");
        }
    }

    /// Loads writable instance settings or reports why they cannot be changed.
    private GameSettings.Instance writableSettings(GameInstanceID id) {
        requireInstance(id);
        @Nullable GameSettings.Instance setting = repository.getInstanceGameSettingsOrCreate(id);
        if (setting == null || repository.isInstanceGameSettingsReadOnly(id)) {
            throw new IllegalStateException("Instance settings are read-only: " + id.id());
        }
        return setting;
    }

    /// Combines the legacy crash-report analysis with one retained XYAT analysis session.
    ///
    /// @param context immutable settings and repository snapshot
    /// @param rawLog immutable analyzed log text
    /// @param resolution resolved crash-report input
    /// @param contextWarnings warnings collected while resolving optional context
    /// @param launcherOwnedLog whether the text came from the captured instance latest-log path
    /// @return immutable combined analysis response
    private @Unmodifiable Map<String, Object> analyzeCrashResult(
            XYMLMcpCrashAnalysisSupport.Context context,
            String rawLog,
            XYMLMcpCrashReportResolver.Resolution resolution,
            List<String> contextWarnings,
            boolean launcherOwnedLog) {
        String fingerprint = XYMLMcpCrashAnalysisSupport.fingerprint(rawLog);
        GameInstanceManifest manifest = context.manifest();
        XYMLMcpCrashRepairCoordinator.@Nullable SourceValidator sourceValidator = launcherOwnedLog
                ? () -> createLatestLogValidationTask(context, fingerprint)
                : null;
        @Nullable LogAnalyzable.JavaRuntimeRepair javaRepair = manifest == null ? null : () -> guardRepairTask(
                repairActionsAllowed,
                () -> sourceValidator == null
                        ? JavaRuntimeRepairTaskFactory.create(repository, manifest)
                        : JavaRuntimeRepairTaskFactory.create(repository, manifest, sourceValidator.createTask()));
        return XYMLMcpCrashAnalysisSupport.analyze(
                context,
                rawLog,
                resolution,
                List.copyOf(contextWarnings),
                launcherOwnedLog,
                crashRepairCoordinator,
                missingDependencySearch,
                javaRepair,
                sourceValidator);
    }

    /// Delays a repair task factory until execution and checks startup policy before creating the task.
    ///
    /// @param executionAllowed reports whether repair side effects are currently permitted
    /// @param taskFactory creates the underlying stopped repair task after policy acceptance
    /// @return stopped task that fails before task creation while startup policy blocks repairs
    static Task<?> guardRepairTask(BooleanSupplier executionAllowed, Supplier<Task<?>> taskFactory) {
        BooleanSupplier checkedExecutionAllowed = Objects.requireNonNull(executionAllowed, "executionAllowed");
        Supplier<Task<?>> checkedTaskFactory = Objects.requireNonNull(taskFactory, "taskFactory");
        return Task.composeAsync(() -> {
            if (!checkedExecutionAllowed.getAsBoolean()) {
                throw new IllegalStateException(REPAIR_ACTIONS_UNAVAILABLE_MESSAGE);
            }
            return Objects.requireNonNull(checkedTaskFactory.get(), "repair task factory result");
        }).asOrchestration();
    }

    /// Creates a precise task that revalidates the instance and latest log used for a repair plan.
    ///
    /// @param context immutable source context captured during analysis
    /// @param expectedFingerprint expected SHA-256 fingerprint
    /// @return fresh stopped source-validation task
    private Task<@Nullable Void> createLatestLogValidationTask(
            XYMLMcpCrashAnalysisSupport.Context context,
            String expectedFingerprint) {
        return XYMLMcpCrashSourceValidationTask.create(
                repository,
                context,
                expectedFingerprint,
                () -> requireTrackedLaunchStopped(context.repositoryDirectory(), context.instanceId(), "repair a crash"),
                () -> resolvedRunDirectory(context.instanceId()),
                instanceSettingsFile(context.repositoryDirectory(), context.instanceId()));
    }

    /// Creates a text resource result.
    ///
    /// @param uri resource URI
    /// @param text resource text
    /// @return immutable resource map
    private static @Unmodifiable Map<String, String> textResource(String uri, String text) {
        return Map.of("uri", uri, "mime_type", "text/plain", "text", text);
    }

    /// Lists direct regular files in one instance's crash-report directory.
    ///
    /// @param root captured crash-report directory
    /// @return one file name per line, or an empty string when the directory is absent
    /// @throws IOException if the directory cannot be listed
    private static String listCrashReports(Path root) throws IOException {
        if (!Files.isDirectory(root)) {
            return "";
        }
        try (Stream<Path> paths = Files.list(root)) {
            return paths.filter(Files::isRegularFile)
                    .map(path -> path.getFileName().toString())
                    .sorted(Comparator.naturalOrder())
                    .collect(Collectors.joining("\n"));
        }
    }

    /// Decodes and validates one URI path segment.
    ///
    /// @param raw encoded path segment
    /// @return decoded safe path segment
    private static String decodePathSegment(String raw) {
        final String decoded;
        try {
            decoded = URLDecoder.decode(raw.replace("+", "%2B"), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Invalid XYML resource URI", exception);
        }
        if (decoded.isBlank() || decoded.contains("/") || decoded.contains("\\")
                || ".".equals(decoded) || "..".equals(decoded)) {
            throw new IllegalArgumentException("Invalid XYML resource path segment");
        }
        return decoded;
    }

    /// Decodes an instance identifier embedded in a resource URI.
    ///
    /// @param raw encoded instance identifier
    /// @return validated instance identifier
    private static GameInstanceID instanceIdFromUri(String raw) {
        return new GameInstanceID(decodePathSegment(raw));
    }

    /// Reads one crash report after proving it belongs to the selected instance.
    ///
    /// @param root captured crash-report directory
    /// @param rawPath report file name or absolute path
    /// @return UTF-8 report text
    /// @throws IOException if the path escapes the instance or cannot be read
    private static String readCrashReport(Path root, String rawPath) throws IOException {
        return XYMLMcpCrashReportResolver.readReport(root, rawPath);
    }

    /// Reads an instance log from one captured run directory, returning an empty string when it does not exist.
    ///
    /// @param runDirectory captured run directory
    /// @return UTF-8 log text, or an empty string when neither log path exists
    /// @throws IOException if the selected log cannot be read
    private static String readLog(Path runDirectory) throws IOException {
        Path latest = runDirectory.resolve("logs/latest.log");
        Path path = Files.exists(latest) ? latest : runDirectory.resolve("latest.log");
        return readIfPresent(path);
    }

    /// Reads a file as UTF-8 when it is a regular file.
    private static String readIfPresent(Path path) throws IOException {
        return Files.isRegularFile(path) ? Files.readString(path, StandardCharsets.UTF_8) : "";
    }

    /// Releases retained analysis plans and requests cancellation of active repair tasks.
    @Override
    public void close() {
        crashRepairCoordinator.close();
    }

    /// Converts a nullable number to a JSON-safe value.
    private static Object nullableNumber(@Nullable Integer value) {
        return value == null ? nullValue() : value;
    }

    /// Returns a JSON-safe null sentinel accepted by MCP structured content.
    private static Object nullValue() {
        return "";
    }

    /// Parses a positive integer argument.
    private static int parsePositive(@Nullable String value, String name) {
        try {
            int result = Integer.parseInt(Objects.requireNonNull(value, name));
            if (result <= 0) {
                throw new NumberFormatException();
            }
            return result;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(name + " must be a positive integer", e);
        }
    }

    /// Resolves a mod path and applies an enable/disable transition within a captured manager directory.
    ///
    /// @param manager manager bound to the locked mods directory
    /// @param path normalized candidate path inside that directory
    /// @param enable whether to remove the disabled suffix
    /// @return normalized resulting path
    /// @throws IOException if managed metadata cannot be loaded or the transition fails
    private static Path transitionMod(ModManager manager, Path path, boolean enable) throws IOException {
        @Nullable LocalModFile mod = managedModIndex(manager).get(path);
        if (mod == null) {
            throw new IOException("Mod is not managed by this instance: " + path);
        }
        try {
            Path result = enable ? manager.enableMod(mod.getFile()) : manager.disableMod(mod.getFile());
            return result.toAbsolutePath().normalize();
        } finally {
            manager.invalidateCache();
        }
    }

    /// Normalizes one client-supplied mod path without consulting mutable repository settings.
    ///
    /// @param rawPath client-supplied mod path
    /// @return normalized absolute candidate path
    private static Path normalizedModCandidate(String rawPath) {
        return Path.of(Objects.requireNonNull(rawPath, "path")).toAbsolutePath().normalize();
    }

    /// Rejects a normalized path that is not a child of the captured mods directory.
    ///
    /// @param modsDirectory captured normalized mods directory
    /// @param path normalized client-supplied path
    /// @return the validated path
    private static Path requireManagedModCandidate(Path modsDirectory, Path path) {
        if (!path.startsWith(modsDirectory) || path.equals(modsDirectory)) {
            throw new IllegalArgumentException("Mod path is outside this instance's mods directory: " + path);
        }
        return path;
    }

    /// Resolves requested candidates to the current managed mod objects under one captured directory.
    ///
    /// @param manager manager bound to the captured mods directory
    /// @param modsDirectory captured normalized mods directory
    /// @param candidates normalized client-supplied candidates
    /// @return immutable insertion-ordered selected mod index
    /// @throws IOException if metadata cannot be read or a candidate is not currently managed
    private static @Unmodifiable Map<Path, LocalModFile> selectedManagedMods(
            ModManager manager,
            Path modsDirectory,
            @Unmodifiable List<Path> candidates) throws IOException {
        Map<Path, LocalModFile> managedMods = managedModIndex(manager);
        Map<Path, LocalModFile> selected = new LinkedHashMap<>();
        for (Path candidate : candidates) {
            Path managedPath = requireManagedModCandidate(modsDirectory, candidate);
            @Nullable LocalModFile mod = managedMods.get(managedPath);
            if (mod == null) {
                throw new IOException("Mod is not managed by this instance: " + managedPath);
            }
            selected.put(managedPath, mod);
        }
        return Collections.unmodifiableMap(selected);
    }

    /// Indexes only regular managed mod files whose real paths remain under the instance mods directory.
    ///
    /// @param manager instance mod manager
    /// @return immutable normalized-path index
    /// @throws IOException if managed mod files cannot be loaded or resolved
    private static @Unmodifiable Map<Path, LocalModFile> managedModIndex(ModManager manager) throws IOException {
        List<LocalModFile> localFiles = manager.getLocalFiles();
        if (localFiles.isEmpty()) {
            return Map.of();
        }
        Path root = manager.getDirectory().toAbsolutePath().normalize();
        Path realRoot = root.toRealPath();
        Map<Path, LocalModFile> result = new LinkedHashMap<>();
        for (LocalModFile mod : localFiles) {
            Path path = mod.getFile().toAbsolutePath().normalize();
            if (!path.startsWith(root) || !Files.isRegularFile(path)) {
                continue;
            }
            Path realPath = path.toRealPath();
            if (realPath.startsWith(realRoot)) {
                result.put(path, mod);
            }
        }
        return Map.copyOf(result);
    }

    /// Creates an offline account for a deterministic launch test.
    private static AuthInfo offlineAuth(GameInstanceID id) {
        UUID uuid = UUID.nameUUIDFromBytes(id.id().getBytes(StandardCharsets.UTF_8));
        return new AuthInfo("XYML-MCP", uuid, "xyml-mcp", AuthInfo.USER_TYPE_LEGACY, "{}");
    }

    /// Identifies one launch state without conflating equal instance IDs from different repository roots.
    ///
    /// @param repositoryDirectory normalized repository root captured for the launch
    /// @param instanceId instance identifier within that repository
    @NotNullByDefault
    private record LaunchKey(Path repositoryDirectory, GameInstanceID instanceId) {
        /// Normalizes the root and rejects absent components so the key remains immutable and stable.
        private LaunchKey {
            repositoryDirectory = Objects.requireNonNull(repositoryDirectory, "repositoryDirectory")
                    .toAbsolutePath()
                    .normalize();
            instanceId = Objects.requireNonNull(instanceId, "instanceId");
        }
    }

    /// Captures output and terminal state for one managed launch process.
    @NotNullByDefault
    private static final class LaunchState implements ProcessListener {
        /// Process created by DefaultLauncher, or null before launch returns.
        private volatile @Nullable space.minecraftstl.xyml.util.platform.ManagedProcess process;

        /// Captured output lines.
        private final List<String> logs = java.util.Collections.synchronizedList(new ArrayList<>());

        /// Last raw exit code.
        private volatile @Nullable Integer exitCode;

        /// Last classified exit type.
        private volatile @Nullable ProcessListener.ExitType exitType;

        /// Captures a decoded stdout or stderr line.
        @Override
        public void onLog(String log, boolean isErrorStream) {
            synchronized (logs) {
                if (logs.size() < MAX_LOG_LINES) {
                    logs.add((isErrorStream ? "[stderr] " : "") + log);
                }
            }
        }

        /// Returns an immutable snapshot of captured process output.
        ///
        /// @return immutable log snapshot
        private @Unmodifiable List<String> logsSnapshot() {
            synchronized (logs) {
                return List.copyOf(logs);
            }
        }

        /// Captures the classified process exit.
        @Override
        public void onExit(int exitCode, ProcessListener.ExitType exitType) {
            this.exitCode = exitCode;
            this.exitType = exitType;
        }
    }
}
