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
package space.minecraftstl.xyml.game;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import space.minecraftstl.xyml.download.LibraryAnalyzer;
import space.minecraftstl.xyml.game.analyzer.LogAnalyzable;
import space.minecraftstl.xyml.game.analyzer.RepairCheckpoint;
import space.minecraftstl.xyml.java.JavaManager;
import space.minecraftstl.xyml.java.JavaRuntime;
import space.minecraftstl.xyml.setting.GameSettings;
import space.minecraftstl.xyml.setting.JavaVersionType;
import space.minecraftstl.xyml.task.Schedulers;
import space.minecraftstl.xyml.task.Task;
import space.minecraftstl.xyml.task.TaskResource;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;
import space.minecraftstl.xyml.util.DigestUtils;
import space.minecraftstl.xyml.util.FileSaver;
import space.minecraftstl.xyml.util.platform.Platform;
import space.minecraftstl.xyml.util.function.ExceptionalConsumer;
import space.minecraftstl.xyml.util.function.ExceptionalRunnable;
import space.minecraftstl.xyml.util.versioning.GameVersionNumber;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;

import static space.minecraftstl.xyml.util.i18n.I18n.i18n;

/// Builds application-level Java selection tasks for crash-analysis solvers.
@NotNullByDefault
public final class JavaRuntimeRepairTaskFactory {
    /// Candidate identifier used for the controlled acquisition path when no compatible runtime is installed.
    private static final String DOWNLOAD_CANDIDATE_ID = "download-compatible";

    /// Prevents construction of this static task factory.
    private JavaRuntimeRepairTaskFactory() {
    }

    /// Creates a reusable application repair boundary with read-only Java candidate discovery.
    ///
    /// The returned boundary is deliberately lazy: constructing it performs no runtime discovery, download, or
    /// settings mutation. Each task request receives a fresh task and re-resolves the selected candidate.
    ///
    /// @param repository repository owning the launched instance
    /// @param manifest launched instance manifest
    /// @return immutable-compatible Java repair boundary
    public static LogAnalyzable.JavaRuntimeRepair createRepair(
            XYMLGameRepository repository,
            GameInstanceManifest manifest) {
        return createRepair(repository, manifest, () -> null);
    }

    /// Creates a Java repair boundary with a fresh source validator for every task request.
    ///
    /// @param repository repository owning the launched instance and its settings
    /// @param manifest launched instance manifest
    /// @param validatorFactory creates a fresh stopped validator, or null when no validator is needed
    /// @return immutable-compatible Java repair boundary
    public static LogAnalyzable.JavaRuntimeRepair createRepair(
            XYMLGameRepository repository,
            GameInstanceManifest manifest,
            Supplier<@Nullable Task<?>> validatorFactory) {
        XYMLGameRepository checkedRepository = Objects.requireNonNull(repository, "repository");
        GameInstanceManifest checkedManifest = Objects.requireNonNull(manifest, "manifest");
        Supplier<@Nullable Task<?>> checkedValidatorFactory = Objects.requireNonNull(
                validatorFactory,
                "validatorFactory");
        return new LogAnalyzable.JavaRuntimeRepair() {
            /// {@inheritDoc}
            @Override
            public Task<?> createTask() {
                @Nullable Task<?> validator = checkedValidatorFactory.get();
                return validator == null
                        ? JavaRuntimeRepairTaskFactory.create(checkedRepository, checkedManifest)
                        : JavaRuntimeRepairTaskFactory.create(checkedRepository, checkedManifest, validator);
            }

            /// {@inheritDoc}
            @Override
            public @Unmodifiable List<LogAnalyzable.JavaRuntimeCandidate> candidates() {
                return listCandidates(checkedRepository, checkedManifest);
            }

            /// {@inheritDoc}
            @Override
            public Task<?> createTask(@Nullable String candidateId) {
                if (candidateId == null) {
                    return createTask();
                }
                @Nullable Task<?> validator = checkedValidatorFactory.get();
                return validator == null
                        ? JavaRuntimeRepairTaskFactory.createForCandidate(
                                checkedRepository,
                                checkedManifest,
                                candidateId)
                        : JavaRuntimeRepairTaskFactory.createForCandidate(
                                checkedRepository,
                                checkedManifest,
                                candidateId,
                                validator);
            }

            /// {@inheritDoc}
            @Override
            public Task<?> createTask(@Nullable String candidateId, RepairCheckpoint checkpoint) {
                Objects.requireNonNull(checkpoint, "checkpoint");
                // Runtime selection has no durable, reusable JavaRuntime snapshot. Re-enter the existing factory so
                // every retry performs a fresh preflight and lock acquisition while retaining already published files.
                return createTask(candidateId);
            }
        };
    }

    /// Returns a read-only snapshot of compatible Java runtimes for one launch.
    ///
    /// Runtime discovery is intentionally non-blocking. Before the process-wide Java registry is initialized the
    /// snapshot contains one acquisition candidate; the eventual task still rechecks the registry before deciding
    /// whether a download is necessary.
    ///
    /// @param repository repository owning the launched instance
    /// @param manifest launched instance manifest
    /// @return immutable candidates in registry order with the best candidate marked recommended
    public static @Unmodifiable List<LogAnalyzable.JavaRuntimeCandidate> listCandidates(
            XYMLGameRepository repository,
            GameInstanceManifest manifest) {
        XYMLGameRepository checkedRepository = Objects.requireNonNull(repository, "repository");
        GameInstanceManifest checkedManifest = Objects.requireNonNull(manifest, "manifest");
        GameVersionNumber gameVersion = GameVersionNumber.asGameVersion(
                checkedRepository.getGameVersion(checkedManifest));
        GameJavaVersion target = targetJava(checkedManifest, gameVersion);
        @Nullable space.minecraftstl.xyml.java.JavaRuntimeSnapshot snapshot =
                JavaManager.getAllJavaSnapshotObservable().getValue();
        Collection<JavaRuntime> runtimes = snapshot == null || !snapshot.isInitialized()
                ? List.of()
                : snapshot.getRuntimes();
        List<JavaRuntime> compatible = new ArrayList<>();
        for (JavaRuntime runtime : runtimes) {
            if (selectTargetJava(List.of(runtime), gameVersion, checkedManifest, target) != null) {
                compatible.add(runtime);
            }
        }
        if (compatible.isEmpty()) {
            return List.of(new LogAnalyzable.JavaRuntimeCandidate(
                    DOWNLOAD_CANDIDATE_ID,
                    i18n("game.crash.java_candidate.download", target.majorVersion()),
                    true));
        }
        @Nullable JavaRuntime recommended = selectTargetJava(
                compatible,
                gameVersion,
                checkedManifest,
                target);
        List<LogAnalyzable.JavaRuntimeCandidate> result = new ArrayList<>();
        for (JavaRuntime runtime : compatible) {
            result.add(new LogAnalyzable.JavaRuntimeCandidate(
                    candidateId(runtime),
                    runtimeDisplayName(runtime),
                    runtime.equals(recommended)));
        }
        return List.copyOf(result);
    }

    /// Creates a stopped Java repair task bound to one candidate identifier.
    ///
    /// The identifier is checked again against the current read-only registry when the task runs. A stale or unknown
    /// identifier therefore fails before settings are changed and can be retried after refreshing the diagnosis.
    ///
    /// @param repository repository owning the launched instance and its settings
    /// @param manifest launched instance manifest
    /// @param candidateId selected candidate identifier
    /// @return stopped task that persists the selected runtime
    public static Task<@Nullable Void> createForCandidate(
            XYMLGameRepository repository,
            GameInstanceManifest manifest,
            String candidateId) {
        return createForCandidate(repository, manifest, candidateId, null);
    }

    /// Creates a candidate-bound repair task with a protected source validator.
    ///
    /// @param repository repository owning the launched instance and its settings
    /// @param manifest launched instance manifest
    /// @param candidateId selected candidate identifier
    /// @param persistenceValidator fresh source-validation task, or null when no validator is required
    /// @return stopped candidate-bound repair task
    public static Task<@Nullable Void> createForCandidate(
            XYMLGameRepository repository,
            GameInstanceManifest manifest,
            String candidateId,
            @Nullable Task<?> persistenceValidator) {
        String checkedCandidateId = Objects.requireNonNull(candidateId, "candidateId");
        if (checkedCandidateId.isBlank()) {
            throw new IllegalArgumentException("candidateId must not be blank");
        }
        return createInternal(
                repository,
                manifest,
                persistenceValidator,
                checkedCandidateId);
    }

    /// Creates a stopped task that selects a compatible launch runtime and persists the selection.
    ///
    /// The task does not run a separate uninstall step for the failed runtime. It first checks the complete XYML Java
    /// registry, downloads a recommended runtime only when no compatible candidate exists, and then evaluates the
    /// registry again before selecting the final runtime for the affected instance.
    ///
    /// @param repository repository owning the launched instance and its settings
    /// @param manifest launched instance manifest
    /// @return stopped task that preserves existing runtimes and selects a compatible Java runtime
    public static Task<@Nullable Void> create(
            XYMLGameRepository repository,
            GameInstanceManifest manifest) {
        return createInternal(repository, manifest, null, null);
    }

    /// Creates a stopped Java repair task with a protected final source revalidation.
    ///
    /// The supplied validation task runs after any runtime download and immediately before settings mutation. Its
    /// concrete resources are combined with the instance settings resources and held continuously through validation,
    /// mutation, durable persistence, and queued-save drainage.
    ///
    /// @param repository repository owning the launched instance and its settings
    /// @param manifest launched instance manifest
    /// @param persistenceValidator fresh stopped task that revalidates the repair source before settings mutation
    /// @return stopped task that validates the source and selects a compatible Java runtime
    public static Task<@Nullable Void> create(
            XYMLGameRepository repository,
            GameInstanceManifest manifest,
            Task<?> persistenceValidator) {
        return createInternal(
                repository,
                manifest,
                Objects.requireNonNull(persistenceValidator, "persistenceValidator"),
                null);
    }

    /// Builds the Java selection pipeline with an optional final validation task.
    ///
    /// @param repository repository owning the launched instance and its settings
    /// @param manifest launched instance manifest
    /// @param persistenceValidator final protected validator, or null when the caller has no retained source
    /// @return stopped Java selection and persistence task
    private static Task<@Nullable Void> createInternal(
            XYMLGameRepository repository,
            GameInstanceManifest manifest,
            @Nullable Task<?> persistenceValidator,
            @Nullable String selectedCandidateId) {
        XYMLGameRepository checkedRepository = Objects.requireNonNull(repository, "repository");
        GameInstanceManifest checkedManifest = Objects.requireNonNull(manifest, "manifest");
        GameInstanceID instanceId = checkedManifest.id();
        Path repositoryDirectory = checkedRepository.getBaseDirectory().toAbsolutePath().normalize();
        Path instanceDirectory = checkedRepository.getInstanceRoot(instanceId).toAbsolutePath().normalize();
        Path settingsFile = checkedRepository.getInstanceGameSettingsFile(instanceId).toAbsolutePath().normalize();
        GameVersionNumber gameVersion = GameVersionNumber.asGameVersion(
                checkedRepository.getGameVersion(checkedManifest));

        Task<@Nullable Void> preflight = Task.runAsync(
                Schedulers.io(),
                () -> checkedRepository.callWithStableBaseDirectory(
                        repositoryDirectory,
                        () -> requireWritableSettings(checkedRepository, instanceId)))
                .setResources(
                        TaskResource.gameInstance(instanceDirectory),
                        TaskResource.configuration(settingsFile));
        Task<JavaRuntime> selection;
        if (selectedCandidateId == null || DOWNLOAD_CANDIDATE_ID.equals(selectedCandidateId)) {
            Function<GameJavaVersion, Task<@Nullable JavaRuntime>> selectionTaskFactory =
                    targetJava -> Task.supplyAsync(() -> selectTargetJava(
                            JavaManager.getAllJava(),
                            gameVersion,
                            checkedManifest,
                            targetJava)).asOrchestration();
            selection = preflight
                    .thenComposeAsync(() -> resolveCompatibleJava(
                            checkedManifest,
                            gameVersion,
                            selectionTaskFactory,
                            targetJava -> JavaManager.getDownloadJavaTask(
                                    checkedRepository.getDependency().getDownloadProvider(),
                                    Platform.SYSTEM_PLATFORM,
                                    targetJava)))
                    .asOrchestration();
        } else {
            selection = preflight
                    .thenComposeAsync(() -> Task.supplyAsync(() -> resolveCandidate(
                            checkedManifest,
                            gameVersion,
                            selectedCandidateId)))
                    .asOrchestration();
        }
        return createPersistenceTask(
                selection,
                instanceDirectory,
                settingsFile,
                persistenceValidator,
                selectedJava -> checkedRepository.callWithStableBaseDirectory(repositoryDirectory, () -> {
                    requireCurrentManifest(checkedRepository, checkedManifest);
                    persistJavaSelectionAndDrain(
                            () -> requireWritableSettings(checkedRepository, instanceId),
                            selectedJava,
                            () -> checkedRepository.saveGameSettingsSync(instanceId),
                            FileSaver::waitForAllSaves);
                    return null;
                }));
    }

    /// Rejects a Java selection produced for an instance manifest that has since changed.
    ///
    /// @param repository repository owning the affected instance
    /// @param expectedManifest immutable manifest used to select the replacement runtime
    private static void requireCurrentManifest(
            XYMLGameRepository repository,
            GameInstanceManifest expectedManifest) {
        GameInstanceManifest currentManifest = repository
                .getResolvedInstanceManifest(expectedManifest.id())
                .launchManifest();
        if (!expectedManifest.equals(currentManifest)) {
            throw new IllegalStateException("Instance manifest changed before Java repair persistence");
        }
    }

    /// Creates the final independently resourced settings-persistence stage.
    ///
    /// The selection task runs before this stage acquires its instance resources, so a Java download keeps its own
    /// audited runtime, archive, cache, and configuration declaration instead of inheriting an unrelated instance
    /// owner. Both the complete instance tree and the exact settings path are declared so canonical path resolution
    /// retains a settings file that escapes through a symbolic-link or junction alias.
    ///
    /// @param selectionTask task yielding the selected compatible runtime
    /// @param instanceDirectory captured affected instance root
    /// @param settingsFile captured instance settings file
    /// @param persistence applies and durably persists the selected runtime
    /// @return stopped final persistence task
    static Task<@Nullable Void> createPersistenceTask(
            Task<JavaRuntime> selectionTask,
            Path instanceDirectory,
            Path settingsFile,
            ExceptionalConsumer<JavaRuntime, ?> persistence) {
        return createPersistenceTask(selectionTask, instanceDirectory, settingsFile, null, persistence);
    }

    /// Creates the final settings stage with an optional validator covered by the same resource lease.
    ///
    /// @param selectionTask task yielding the selected compatible runtime
    /// @param instanceDirectory captured affected instance root
    /// @param settingsFile captured instance settings file
    /// @param persistenceValidator final protected validator, or null when no retained source must be checked
    /// @param persistence applies and durably persists the selected runtime
    /// @return stopped selection-to-persistence orchestration task
    static Task<@Nullable Void> createPersistenceTask(
            Task<JavaRuntime> selectionTask,
            Path instanceDirectory,
            Path settingsFile,
            @Nullable Task<?> persistenceValidator,
            ExceptionalConsumer<JavaRuntime, ?> persistence) {
        Task<JavaRuntime> checkedSelectionTask = Objects.requireNonNull(selectionTask, "selectionTask");
        Path checkedInstanceDirectory = Objects.requireNonNull(instanceDirectory, "instanceDirectory")
                .toAbsolutePath().normalize();
        Path checkedSettingsFile = Objects.requireNonNull(settingsFile, "settingsFile")
                .toAbsolutePath().normalize();
        ExceptionalConsumer<JavaRuntime, ?> checkedPersistence = Objects.requireNonNull(persistence, "persistence");
        @Unmodifiable List<TaskResource> persistenceResources = persistenceResources(
                checkedInstanceDirectory,
                checkedSettingsFile,
                persistenceValidator);
        return checkedSelectionTask.thenComposeAsync((@Nullable JavaRuntime selectedJava) -> {
            JavaRuntime checkedSelectedJava = Objects.requireNonNull(selectedJava, "selected Java runtime");
            Task<@Nullable Void> persistenceTask;
            if (persistenceValidator == null) {
                persistenceTask = Task.runAsync(
                        "Persist repaired Java selection",
                        Schedulers.io(),
                        () -> checkedPersistence.accept(checkedSelectedJava));
            } else {
                persistenceTask = persistenceValidator.thenRunAsync(
                        "Persist repaired Java selection",
                        Schedulers.io(),
                        () -> checkedPersistence.accept(checkedSelectedJava));
            }
            TaskResource @Unmodifiable [] additional = persistenceResources.subList(1, persistenceResources.size())
                    .toArray(TaskResource[]::new);
            return persistenceTask.setResources(persistenceResources.get(0), additional);
        }).asOrchestration();
    }

    /// Builds the concrete resource union held across final validation and persistence.
    ///
    /// @param instanceDirectory captured affected instance root
    /// @param settingsFile captured instance settings file
    /// @param persistenceValidator final protected validator, or null when absent
    /// @return immutable non-empty resource declaration
    private static @Unmodifiable List<TaskResource> persistenceResources(
            Path instanceDirectory,
            Path settingsFile,
            @Nullable Task<?> persistenceValidator) {
        List<TaskResource> resources = new ArrayList<>();
        resources.add(TaskResource.gameInstance(instanceDirectory));
        resources.add(TaskResource.configuration(settingsFile));
        if (persistenceValidator != null) {
            if (persistenceValidator.getState() != Task.TaskState.READY) {
                throw new IllegalArgumentException("persistenceValidator must be in the ready state");
            }
            if (persistenceValidator.getResourceDeclarations().stream().anyMatch(resource ->
                    resource.getKind() == TaskResource.Kind.CONSERVATIVE
                            || resource.getKind() == TaskResource.Kind.ORCHESTRATION)) {
                throw new IllegalArgumentException("persistenceValidator must declare concrete protected resources");
            }
            resources.addAll(persistenceValidator.getResourceDeclarations());
        }
        return List.copyOf(resources);
    }

    /// Ensures a compatible runtime is registered, then reevaluates the registry for the final selection.
    ///
    /// The first selection decides only whether acquisition is necessary. A download result is never selected directly;
    /// the second selection observes the post-acquisition registry and remains authoritative even when it returns the
    /// same runtime that was previously selected.
    ///
    /// @param manifest launched instance manifest
    /// @param gameVersion parsed Minecraft version
    /// @param selectionTaskFactory creates a fresh target-aware task from the complete registered runtime snapshot
    /// @param downloadTaskFactory creates a task that downloads and registers the requested runtime
    /// @return stopped task yielding the compatible runtime selected after optional acquisition
    static Task<JavaRuntime> resolveCompatibleJava(
            GameInstanceManifest manifest,
            GameVersionNumber gameVersion,
            Function<GameJavaVersion, Task<@Nullable JavaRuntime>> selectionTaskFactory,
            Function<GameJavaVersion, Task<JavaRuntime>> downloadTaskFactory) {
        GameInstanceManifest checkedManifest = Objects.requireNonNull(manifest, "manifest");
        GameVersionNumber checkedGameVersion = Objects.requireNonNull(gameVersion, "gameVersion");
        Function<GameJavaVersion, Task<@Nullable JavaRuntime>> checkedSelectionTaskFactory =
                Objects.requireNonNull(selectionTaskFactory, "selectionTaskFactory");
        Function<GameJavaVersion, Task<JavaRuntime>> checkedDownloadTaskFactory =
                Objects.requireNonNull(downloadTaskFactory, "downloadTaskFactory");
        GameJavaVersion targetJava = targetJava(checkedManifest, checkedGameVersion);
        Task<@Nullable JavaRuntime> initialSelection = Objects.requireNonNull(
                checkedSelectionTaskFactory.apply(targetJava),
                "selection task factory result");
        Task<JavaRuntime> availability = initialSelection
                .thenComposeAsync((@Nullable JavaRuntime availableJava) -> {
                    if (availableJava != null) {
                        return Task.completed(availableJava);
                    }
                    return Objects.requireNonNull(
                            checkedDownloadTaskFactory.apply(targetJava),
                            "download task factory result");
                })
                .asOrchestration();
        return availability
                .thenComposeAsync(() -> Objects.requireNonNull(
                        checkedSelectionTaskFactory.apply(targetJava),
                        "selection task factory result"))
                .asOrchestration()
                .thenApplyAsync((@Nullable JavaRuntime selectedJava) -> {
                    if (selectedJava == null) {
                        throw new IllegalStateException(
                                "No compatible Java runtime is registered after acquisition");
                    }
                    return selectedJava;
                })
                .asOrchestration();
    }

    /// Selects a runtime that satisfies both launcher constraints and the Java target diagnosed for this repair.
    ///
    /// The ordinary launcher selector may intentionally fall back to a runtime that violates suggested constraints.
    /// A crash repair cannot use that fallback because doing so could persist an incompatible Java major. Ordinary
    /// targets require the diagnosed major exactly; Cleanroom targets accept any major at or above its mandatory minimum.
    ///
    /// @param javaRuntimes complete registered runtime snapshot
    /// @param gameVersion parsed Minecraft version
    /// @param manifest launched instance manifest
    /// @param targetJava managed Java family targeted by the diagnosis
    /// @return preferred matching runtime, or null when the registry has no valid candidate for the target requirement
    static @Nullable JavaRuntime selectTargetJava(
            Collection<JavaRuntime> javaRuntimes,
            GameVersionNumber gameVersion,
            GameInstanceManifest manifest,
            GameJavaVersion targetJava) {
        Collection<JavaRuntime> checkedRuntimes = Objects.requireNonNull(javaRuntimes, "javaRuntimes");
        GameVersionNumber checkedGameVersion = Objects.requireNonNull(gameVersion, "gameVersion");
        GameInstanceManifest checkedManifest = Objects.requireNonNull(manifest, "manifest");
        GameJavaVersion checkedTargetJava = Objects.requireNonNull(targetJava, "targetJava");
        boolean cleanroomMinimum = cleanroomTargetJava(checkedManifest, checkedGameVersion) != null;
        return JavaManager.findSuitableJava(
                checkedRuntimes.stream()
                        .filter(javaRuntime -> cleanroomMinimum
                                ? javaRuntime.getParsedVersion() >= checkedTargetJava.majorVersion()
                                : javaRuntime.getParsedVersion() == checkedTargetJava.majorVersion())
                        .toList(),
                checkedGameVersion,
                checkedManifest);
    }

    /// Resolves one candidate against a fresh runtime registry snapshot.
    ///
    /// @param repository repository owning the launched instance
    /// @param manifest launched instance manifest
    /// @param gameVersion parsed Minecraft version
    /// @param candidateId opaque candidate identifier captured by the caller
    /// @return selected compatible runtime
    /// @throws InterruptedException when runtime discovery is interrupted
    private static JavaRuntime resolveCandidate(
            GameInstanceManifest manifest,
            GameVersionNumber gameVersion,
            String selectedCandidateId) throws InterruptedException {
        GameJavaVersion target = targetJava(manifest, gameVersion);
        for (JavaRuntime runtime : JavaManager.getAllJava()) {
            if (selectedCandidateId.equals(candidateId(runtime))
                    && selectTargetJava(List.of(runtime), gameVersion, manifest, target) != null) {
                return runtime;
            }
        }
        throw new IllegalStateException("Selected Java runtime candidate is no longer available");
    }

    /// Produces an opaque stable identifier for one discovered runtime without exposing its local path.
    ///
    /// @param runtime discovered runtime
    /// @return stable candidate identifier
    private static String candidateId(JavaRuntime runtime) {
        String identity = runtime.getBinary().toAbsolutePath().normalize().toString();
        return "runtime-" + DigestUtils.digestToString(
                "SHA-256",
                identity.getBytes(StandardCharsets.UTF_8));
    }

    /// Produces a concise technical label for a runtime choice.
    ///
    /// @param runtime discovered runtime
    /// @return runtime label suitable for a selection control
    private static String runtimeDisplayName(JavaRuntime runtime) {
        return i18n(
                "game.crash.java_candidate.runtime",
                runtime.getVersion(),
                runtime.getArchitecture().getDisplayName());
    }

    /// Resolves the Java component to download when the registered runtime snapshot has no compatible candidate.
    ///
    /// @param manifest launched instance manifest
    /// @param gameVersion parsed Minecraft version
    /// @return Cleanroom minimum, manifest recommendation, vanilla minimum, or the Java 8 compatibility fallback
    private static GameJavaVersion targetJava(
            GameInstanceManifest manifest,
            GameVersionNumber gameVersion) {
        @Nullable GameJavaVersion cleanroomTargetJava = cleanroomTargetJava(manifest, gameVersion);
        if (cleanroomTargetJava != null) {
            return cleanroomTargetJava;
        }
        @Nullable GameJavaVersion targetJava = manifest.javaVersion();
        if (targetJava == null) {
            targetJava = GameJavaVersion.getMinimumJavaVersion(gameVersion);
        }
        return targetJava == null ? GameJavaVersion.JAVA_8 : targetJava;
    }

    /// Resolves the mandatory Cleanroom Java minimum when the instance declares a known Cleanroom version.
    ///
    /// @param manifest launched instance manifest
    /// @param gameVersion parsed Minecraft version
    /// @return Cleanroom Java minimum, or null when the constraint does not apply
    private static @Nullable GameJavaVersion cleanroomTargetJava(
            GameInstanceManifest manifest,
            GameVersionNumber gameVersion) {
        if (gameVersion.compareTo("1.12.2") < 0 || gameVersion.compareTo("1.12.999") > 0) {
            return null;
        }
        String cleanroomVersion = LibraryAnalyzer.analyze(manifest, gameVersion.toString())
                .getVersion(LibraryAnalyzer.LibraryType.CLEANROOM)
                .orElse("");
        return cleanroomVersion.isEmpty()
                ? null
                : GameJavaVersion.getCleanroomJavaVersion(cleanroomVersion);
    }

    /// Returns writable settings for the affected instance before any acquisition side effect begins.
    ///
    /// @param repository repository owning the instance settings
    /// @param instanceId affected instance identifier
    /// @return writable instance settings
    /// @throws IllegalStateException when the instance settings cannot be updated safely
    private static GameSettings.Instance requireWritableSettings(
            XYMLGameRepository repository,
            GameInstanceID instanceId) {
        if (repository.isInstanceGameSettingsReadOnly(instanceId)) {
            throw new IllegalStateException("Cannot update read-only game settings for " + instanceId);
        }
        GameSettings.@Nullable Instance setting = repository.getInstanceGameSettingsOrCreate(instanceId);
        if (setting == null) {
            throw new IllegalStateException("Cannot create game settings for " + instanceId);
        }
        return setting;
    }

    /// Applies and persists one Java selection, restoring the complete prior selection after persistence failure.
    ///
    /// UI-owned settings mutations run on the Swing event-dispatch thread, while the caller owns the persistence thread.
    /// Every failure is rethrown after rollback without converting [Error] values to ordinary exceptions.
    ///
    /// @param settingsSupplier obtains the affected writable instance settings on the EDT
    /// @param selectedJava compatible selected runtime
    /// @param persistence writes the updated settings on the caller's background thread
    /// @throws Exception when applying or persisting the selection fails
    static void persistJavaSelection(
            Supplier<GameSettings.Instance> settingsSupplier,
            JavaRuntime selectedJava,
            ExceptionalRunnable<?> persistence) throws Exception {
        Supplier<GameSettings.Instance> checkedSettingsSupplier = Objects.requireNonNull(
                settingsSupplier,
                "settingsSupplier");
        JavaRuntime checkedSelectedJava = Objects.requireNonNull(selectedJava, "selectedJava");
        ExceptionalRunnable<?> checkedPersistence = Objects.requireNonNull(persistence, "persistence");
        AtomicReference<@Nullable JavaSelectionSnapshot> snapshotReference = new AtomicReference<>();

        try {
            EdtDispatcher.executeAndWait(() -> {
                GameSettings.Instance setting = Objects.requireNonNull(
                        checkedSettingsSupplier.get(),
                        "settings supplier result");
                snapshotReference.set(JavaSelectionSnapshot.capture(setting));
                selectJava(setting, checkedSelectedJava);
            });
            checkedPersistence.run();
        } catch (Throwable failure) {
            @Nullable JavaSelectionSnapshot snapshot = snapshotReference.get();
            if (snapshot != null) {
                try {
                    EdtDispatcher.executeAndWait(snapshot::restore);
                } catch (Throwable rollbackFailure) {
                    failure.addSuppressed(rollbackFailure);
                }
            }
            rethrowPersistenceFailure(failure);
        }
    }

    /// Applies a Java selection and drains every queued settings write before returning or rethrowing.
    ///
    /// Property listeners may enqueue FileSaver writes both while applying the new selection and while rolling it back.
    /// The barrier therefore runs for success, [Exception], and [Error] paths. A later barrier failure is suppressed on
    /// the original failure, while an otherwise successful operation reports the barrier failure directly.
    ///
    /// @param settingsSupplier obtains the affected writable instance settings on the EDT
    /// @param selectedJava compatible selected runtime
    /// @param persistence writes the updated settings on the caller's background thread
    /// @param saveBarrier waits for queued settings writes to finish
    /// @throws Exception when applying, persisting, or draining the selection fails
    static void persistJavaSelectionAndDrain(
            Supplier<GameSettings.Instance> settingsSupplier,
            JavaRuntime selectedJava,
            ExceptionalRunnable<?> persistence,
            SaveBarrier saveBarrier) throws Exception {
        SaveBarrier checkedSaveBarrier = Objects.requireNonNull(saveBarrier, "saveBarrier");
        @Nullable Throwable failure = null;
        try {
            persistJavaSelection(settingsSupplier, selectedJava, persistence);
        } catch (Throwable operationFailure) {
            failure = operationFailure;
        }

        try {
            runSaveBarrierUninterruptibly(checkedSaveBarrier);
        } catch (Throwable saveFailure) {
            if (failure == null) {
                failure = saveFailure;
            } else if (failure != saveFailure) {
                failure.addSuppressed(saveFailure);
            }
        }
        if (failure != null) {
            rethrowPersistenceFailure(failure);
        }
    }

    /// Reaches a save barrier despite interruption, then restores and reports the first interruption.
    ///
    /// @param saveBarrier barrier operation to retry after interruption
    /// @throws Exception when the barrier fails or the completed wait observed interruption
    private static void runSaveBarrierUninterruptibly(SaveBarrier saveBarrier) throws Exception {
        @Nullable InterruptedException interruption = null;
        try {
            while (true) {
                try {
                    saveBarrier.await();
                    break;
                } catch (InterruptedException current) {
                    if (interruption == null) {
                        interruption = current;
                    } else if (interruption != current) {
                        interruption.addSuppressed(current);
                    }
                    Thread.interrupted();
                }
            }
        } catch (Throwable barrierFailure) {
            if (interruption != null) {
                Thread.currentThread().interrupt();
                if (barrierFailure != interruption) {
                    barrierFailure.addSuppressed(interruption);
                }
            }
            rethrowPersistenceFailure(barrierFailure);
        }
        if (interruption != null) {
            Thread.currentThread().interrupt();
            throw interruption;
        }
    }

    /// Checked settings-save barrier whose interruption can be handled without erasing its precise type.
    @FunctionalInterface
    @NotNullByDefault
    interface SaveBarrier {
        /// Waits until every settings save queued before this call has reached a terminal state.
        ///
        /// @throws Exception when waiting is interrupted or the barrier otherwise fails
        void await() throws Exception;
    }

    /// Applies a compatible Java selection to writable instance settings.
    ///
    /// @param setting affected writable instance settings
    /// @param selectedJava compatible selected runtime
    private static void selectJava(GameSettings.Instance setting, JavaRuntime selectedJava) {
        setting.getOverrideProperties().add(GameSettings.PROPERTY_JAVA_TYPE);
        setting.getOverrideProperties().add(GameSettings.PROPERTY_DETECTED_JAVA);
        setting.javaTypeProperty().setValue(JavaVersionType.DETECTED);
        setting.detectedJavaProperty().setValue(GameSettings.DetectedJava.of(selectedJava));
    }

    /// Rethrows a persistence or rollback failure without converting [Error] values to ordinary exceptions.
    ///
    /// @param failure original operation failure
    /// @throws Exception when the operation failed with an ordinary exception
    private static void rethrowPersistenceFailure(Throwable failure) throws Exception {
        if (failure instanceof Error error) {
            throw error;
        }
        if (failure instanceof Exception exception) {
            throw exception;
        }
        throw new AssertionError("Unexpected Java selection persistence failure", failure);
    }

    /// Complete mutable selection state needed to roll back a failed persistence attempt.
    ///
    /// @param setting affected instance settings
    /// @param javaTypeOverridden whether the instance originally overrode the Java selection mode
    /// @param detectedJavaOverridden whether the instance originally overrode the detected runtime reference
    /// @param javaType original direct Java selection mode
    /// @param detectedJava original direct detected runtime reference
    @NotNullByDefault
    private record JavaSelectionSnapshot(
            GameSettings.Instance setting,
            boolean javaTypeOverridden,
            boolean detectedJavaOverridden,
            JavaVersionType javaType,
            GameSettings.DetectedJava detectedJava) {
        /// Captures the current direct Java selection and override ownership.
        ///
        /// @param setting affected instance settings
        /// @return complete rollback snapshot
        private static JavaSelectionSnapshot capture(GameSettings.Instance setting) {
            GameSettings.Instance checkedSetting = Objects.requireNonNull(setting, "setting");
            return new JavaSelectionSnapshot(
                    checkedSetting,
                    checkedSetting.getOverrideProperties().contains(GameSettings.PROPERTY_JAVA_TYPE),
                    checkedSetting.getOverrideProperties().contains(GameSettings.PROPERTY_DETECTED_JAVA),
                    Objects.requireNonNull(checkedSetting.javaTypeProperty().getValue(), "javaType"),
                    Objects.requireNonNull(checkedSetting.detectedJavaProperty().getValue(), "detectedJava"));
        }

        /// Restores the captured direct values and exact override membership.
        private void restore() {
            setting.javaTypeProperty().setValue(javaType);
            setting.detectedJavaProperty().setValue(detectedJava);
            restoreOverride(GameSettings.PROPERTY_JAVA_TYPE, javaTypeOverridden);
            restoreOverride(GameSettings.PROPERTY_DETECTED_JAVA, detectedJavaOverridden);
        }

        /// Restores one override marker to its captured membership.
        ///
        /// @param propertyName setting property name
        /// @param overridden captured membership
        private void restoreOverride(String propertyName, boolean overridden) {
            if (overridden) {
                setting.getOverrideProperties().add(propertyName);
            } else {
                setting.getOverrideProperties().remove(propertyName);
            }
        }
    }
}
