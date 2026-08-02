/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2021  huangyuhui <huanghongxun2008@126.com> and contributors
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
import space.minecraftstl.xyml.auth.*;
import space.minecraftstl.xyml.auth.offline.OfflineAccount;
import space.minecraftstl.xyml.download.DefaultDependencyManager;
import space.minecraftstl.xyml.download.DownloadProvider;
import space.minecraftstl.xyml.download.LibraryAnalyzer;
import space.minecraftstl.xyml.download.MaintainTask;
import space.minecraftstl.xyml.download.game.*;
import space.minecraftstl.xyml.java.JavaManager;
import space.minecraftstl.xyml.java.JavaRuntime;
import space.minecraftstl.xyml.launch.*;
import space.minecraftstl.xyml.modpack.ModpackConfiguration;
import space.minecraftstl.xyml.modpack.ModpackProvider;
import space.minecraftstl.xyml.setting.GameSettings;
import space.minecraftstl.xyml.setting.JavaVersionType;
import space.minecraftstl.xyml.setting.LauncherVisibility;
import space.minecraftstl.xyml.task.*;
import space.minecraftstl.xyml.ui.launch.LaunchInteraction;
import space.minecraftstl.xyml.ui.launch.LaunchInteractionPrompt;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;
import space.minecraftstl.xyml.ui.swing.crash.SwingGameCrashWindow;
import space.minecraftstl.xyml.ui.swing.log.SwingGameLogWindow;
import space.minecraftstl.xyml.ui.swing.page.accounts.AccountReauthentication;
import space.minecraftstl.xyml.util.*;
import space.minecraftstl.xyml.util.io.FileUtils;
import space.minecraftstl.xyml.util.platform.*;
import space.minecraftstl.xyml.util.platform.windows.WinReg;
import space.minecraftstl.xyml.util.versioning.GameVersionNumber;
import space.minecraftstl.xyml.util.versioning.VersionNumber;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static space.minecraftstl.xyml.setting.SettingsManager.settings;
import static space.minecraftstl.xyml.setting.SettingsManager.state;
import static space.minecraftstl.xyml.util.DataSizeUnit.MEGABYTES;
import static space.minecraftstl.xyml.util.i18n.I18n.i18n;
import static space.minecraftstl.xyml.util.logging.Logger.LOG;
import static space.minecraftstl.xyml.util.logging.Logger.TIME_FORMATTER;
import static space.minecraftstl.xyml.util.platform.Platform.SYSTEM_PLATFORM;

/// Prepares production game launches through explicit Swing interaction boundaries.
@NotNullByDefault
public final class LauncherHelper {

    /// Persistent tip key for the LWJGL memory-util launch recommendation.
    private static final String LWJGL_3_4_1_TIP = "lwjgl3.4.1-ffm";

    /// Per-user DirectX registry key containing executable-specific GPU preferences.
    private static final String WINDOWS_GPU_PREFERENCES_KEY =
            "Software\\Microsoft\\DirectX\\UserGpuPreferences";

    /// DirectX preference value selecting the high-performance GPU.
    private static final String HIGH_PERFORMANCE_GPU_PREFERENCE = "GpuPreference=2;";

    /// Repository supplying the selected instance and launch dependencies.
    private final XYMLGameRepository repository;

    /// Account used when the launch task reaches authentication.
    private final Account account;

    /// Stable instance identifier selected for this helper.
    private final GameInstanceID selectedInstanceId;

    /// Effective settings captured for the selected instance.
    private final GameSettings.Effective setting;

    /// Configured launcher behavior after process creation.
    private LauncherVisibility launcherVisibility;

    /// Whether the process listener should open its native Swing log window.
    private boolean showLogs;

    /// Log retention limit captured from launcher settings before process monitoring starts.
    private final int logLineLimit;

    /// Optional quick-play destination applied to launch options.
    private @Nullable QuickPlayOption quickPlayOption;

    /// Whether offline skin support is disabled for the generated launch options.
    private boolean disableOfflineSkin = false;

    /// Completes after the process listener has finished log and crash bookkeeping.
    private final CompletableFuture<@Nullable Void> processLifecycleCompletion =
            new CompletableFuture<>();

    /// Required production Swing interaction services.
    private final ProductionInteractions productionInteractions;

    /// Creates a production helper with explicit Swing launch and account-recovery boundaries.
    ///
    /// @param repository repository containing the selected instance
    /// @param account account used for launch authentication
    /// @param selectedInstanceId stable selected instance identifier
    /// @param launchInteraction production launch-decision presenter
    /// @param accountReauthentication production credential-expiry recovery service
    public LauncherHelper(
            XYMLGameRepository repository,
            Account account,
            GameInstanceID selectedInstanceId,
            LaunchInteraction launchInteraction,
            AccountReauthentication accountReauthentication) {
        this.repository = Objects.requireNonNull(repository);
        this.account = Objects.requireNonNull(account);
        this.selectedInstanceId = Objects.requireNonNull(selectedInstanceId);
        this.productionInteractions = new ProductionInteractions(
                launchInteraction,
                accountReauthentication);
        this.setting = repository.getEffectiveGameSettings(selectedInstanceId);
        this.launcherVisibility = setting.getInheritable(GameSettings::launcherVisibilityProperty);
        this.showLogs = setting.getInheritable(GameSettings::showLogsProperty);
        this.logLineLimit = Log.getLogLines();
    }

    /// Returns the launcher visibility captured from the effective instance settings.
    ///
    /// The Swing launch-task factory captures this value and implements the policy outside this helper.
    ///
    /// @return currently configured launcher visibility
    public LauncherVisibility getLauncherVisibility() {
        return launcherVisibility;
    }

    /// Returns the non-blocking signal completed after process-exit bookkeeping finishes.
    ///
    /// Swing visibility policy waits for this signal instead of raw `Process#onExit()` so closing or
    /// reopening the launcher cannot race log draining, abnormal-launch marking, or crash diagnostics.
    ///
    /// @return shared process-listener completion
    public CompletionStage<@Nullable Void> processLifecycleCompletion() {
        return processLifecycleCompletion;
    }

    /// Applies test-launch behavior before task construction.
    ///
    /// Test launches always retain the launcher window and show the native log window, regardless of the
    /// instance's ordinary visibility and log preferences. Calling this method repeatedly is harmless.
    public void setTestMode() {
        launcherVisibility = LauncherVisibility.KEEP;
        showLogs = true;
    }

    /// Sets the optional quick-play destination for the next task.
    ///
    /// @param quickPlayOption quick-play destination
    public void setQuickPlayOption(QuickPlayOption quickPlayOption) {
        this.quickPlayOption = Objects.requireNonNull(quickPlayOption, "quickPlayOption");
    }

    /// Disables the offline-skin integration for the next task.
    public void setDisableOfflineSkin() {
        disableOfflineSkin = true;
    }

    /// Builds a stopped task for the production Swing launch service.
    ///
    /// Construction retains synchronous metadata normalization and analysis performed by
    /// [MaintainTask#maintain(GameRepository, GameInstanceManifest)] and [#checkGameState(XYMLGameRepository,
    /// GameSettings.Effective, GameInstanceManifest)]. The returned task owns the remaining preparation, process creation,
    /// process registration, and process lifecycle monitoring. This method does not start an executor; callers must
    /// invoke it away from the Swing event-dispatch thread.
    ///
    /// @return not-yet-started task whose successful result is the actual managed game process
    public Task<ManagedProcess> createLaunchTask() {
        LOG.info("Launching game instance: " + selectedInstanceId);
        return createGameLaunchTask();
    }

    /// Builds a stopped task that writes a standalone script after the ordinary launch preparation succeeds.
    ///
    /// The task deliberately retains account authentication, dependency verification, native preparation, and
    /// production interaction behavior from a normal launch. Only the final process-creation action changes to
    /// [XYMLGameLauncher#makeLaunchScript(Path)], so exported scripts receive the same resolved launch options.
    ///
    /// @param scriptFile destination script path selected by the user
    /// @return not-yet-started task that completes with the normalized written script path
    public Task<Path> createLaunchScriptTask(Path scriptFile) {
        Path destination = Objects.requireNonNull(scriptFile, "scriptFile").toAbsolutePath().normalize();
        LOG.info("Creating launch script for game instance: " + selectedInstanceId);
        return applyLaunchProgressPolicy(
                createLaunchPreparation(true).thenComposeAsync((@Nullable XYMLGameLauncher launcher) ->
                        Task.supplyAsync(() -> {
                            Objects.requireNonNull(launcher, "prepared launcher").makeLaunchScript(destination);
                            return destination;
                        })));
    }

    /// Builds the production game-preparation task and decorates its process result for ownership tracking.
    ///
    /// @return stopped launch task that returns the created managed process
    private Task<ManagedProcess> createGameLaunchTask() {
        Task<ManagedProcess> processTask = createLaunchPreparation(false)
                .thenComposeAsync((@Nullable XYMLGameLauncher launcher) ->
                Task.supplyAsync(Objects.requireNonNull(launcher, "prepared launcher")::launch));
        return applyLaunchProgressPolicy(decorateGameLaunchTask(processTask));
    }

    /// Performs metadata normalization and builds the remaining chain through an unstarted game launcher.
    ///
    /// @param makeLaunchScript whether the produced launcher will write a standalone script rather than start a game
    /// @return stopped task that creates the configured launcher
    private Task<XYMLGameLauncher> createLaunchPreparation(boolean makeLaunchScript) {
        // https://github.com/HMCL-dev/HMCL/pull/4121
        PROCESSES.removeIf(it -> it.get() == null);

        DefaultDependencyManager dependencyManager = repository.getDependency();
        AtomicReference<GameInstanceManifest> manifest = new AtomicReference<>(MaintainTask.maintain(
                repository,
                repository.getResolvedInstanceManifest(selectedInstanceId).launchManifest()));
        Optional<String> gameVersion = repository.getGameVersion(manifest.get());
        boolean integrityCheck = repository.unmarkInstanceLaunchedAbnormally(selectedInstanceId);
        List<String> javaAgents = new ArrayList<>(0);
        List<String> javaArguments = new ArrayList<>(0);

        AtomicReference<@Nullable JavaRuntime> javaVersionRef = new AtomicReference<>();

        Task<XYMLGameLauncher> launcherTask = checkGameState(repository, setting, manifest.get())
                .thenComposeAsync((@Nullable JavaRuntime java) -> {
                    javaVersionRef.set(Objects.requireNonNull(java));
                    manifest.set(NativePatcher.patchNative(
                            repository,
                            manifest.get(),
                            gameVersion.orElse(null),
                            java,
                            setting,
                            javaArguments));
                    if (setting.getInheritable(GameSettings::notCheckGameProperty))
                        return null;
                    return Task.allOf(
                            dependencyManager.checkGameCompletionAsync(manifest.get(), integrityCheck),
                            Task.composeAsync(() -> {
                                try {
                                    ModpackConfiguration<?> configuration = ModpackHelper.readModpackConfiguration(
                                            repository.getModpackConfiguration(selectedInstanceId));
                                    @Nullable ModpackProvider provider = ModpackHelper.getProviderByType(configuration.getType());
                                    if (provider == null) return null;
                                    else return provider.createCompletionTask(dependencyManager, selectedInstanceId);
                                } catch (IOException e) {
                                    return null;
                                }
                            }),
                            Task.composeAsync(() -> {
                                if (OperatingSystem.CURRENT_OS != OperatingSystem.WINDOWS
                                        || !(setting.getRenderer(GameVersionNumber.asGameVersion(gameVersion)) instanceof Renderer.Driver renderer)
                                        || renderer.mesaDriverName() == null)
                                    return null;

                                @Nullable Library lib = NativePatcher.getWindowsMesaLoader(
                                        java,
                                        renderer,
                                        OperatingSystem.SYSTEM_VERSION);
                                if (lib == null)
                                    return null;
                                Path file = dependencyManager.getGameRepository().getLibraryFile(manifest.get(), lib);
                                if (file.toAbsolutePath().toString().indexOf('=') >= 0) {
                                    LOG.warning("Invalid character '=' in the libraries directory path, unable to attach software renderer loader");
                                    return null;
                                }

                                String agent = FileUtils.getAbsolutePath(file) + "=" + renderer.mesaDriverName();

                                if (GameLibrariesTask.shouldDownloadLibrary(repository, manifest.get(), lib, integrityCheck)) {
                                    return new LibraryDownloadTask(dependencyManager, file, lib)
                                            .thenRunAsync(() -> javaAgents.add(agent));
                                } else {
                                    javaAgents.add(agent);
                                    return null;
                                }
                            })
                    );
                }).withStage("launch.state.dependencies")
                .thenComposeAsync(() -> gameVersion
                        .map(value -> new GameVerificationFixTask(dependencyManager, value, manifest.get()))
                        .orElse(null))
                .thenComposeAsync(() -> {
                    if (setting.getInheritable(GameSettings::allowAutoAgentProperty)
                            || setting.getInheritable(GameSettings::noJVMOptionsProperty)
                            || setting.getInheritable(GameSettings::noOptimizingJVMOptionsProperty)
                            || Boolean.TRUE.equals(state().getShownTips().get(LWJGL_3_4_1_TIP))
                            || !NativePatcher.needPatchMemoryUtil(
                                    manifest.get(),
                                    Objects.requireNonNull(javaVersionRef.get(), "selected Java runtime").getParsedVersion())) {
                        return Task.completed(null);
                    }
                    LaunchInteractionPrompt prompt = new LaunchInteractionPrompt(
                            i18n("launch.advice.lwjgl_3_4_1.title"),
                            i18n("launch.advice.lwjgl_3_4_1"),
                            LaunchInteractionPrompt.Severity.QUESTION,
                            List.of(
                                    new LaunchInteractionPrompt.Option(
                                            LaunchInteractionPrompt.Action.ENABLE_RECOMMENDED_SETTING,
                                            i18n("button.yes")),
                                    new LaunchInteractionPrompt.Option(
                                            LaunchInteractionPrompt.Action.CONTINUE,
                                            i18n("button.no"))),
                            LaunchInteractionPrompt.Action.CONTINUE,
                            LaunchInteractionPrompt.Action.CONTINUE);
                    return presentProductionPrompt(prompt).thenApplyAsync(
                            Schedulers.ui(),
                            (@Nullable LaunchInteractionPrompt.Action selectedAction) -> {
                                LaunchInteractionPrompt.Action action = Objects.requireNonNull(
                                        selectedAction,
                                        "launch interaction action");
                                state().getShownTips().put(LWJGL_3_4_1_TIP, true);
                                if (action == LaunchInteractionPrompt.Action.ENABLE_RECOMMENDED_SETTING) {
                                    enableAutoAgentForCurrentSetting();
                                }
                                return null;
                            });
                })
                .thenComposeAsync(() -> logIn(account).withStage("launch.state.logging_in"))
                .thenComposeAsync((@Nullable AuthInfo authInfo) -> Task.supplyAsync(() -> {
                    JavaRuntime selectedJava = Objects.requireNonNull(
                            javaVersionRef.get(),
                            "selected Java runtime");
                    LaunchOptions.Builder launchOptionsBuilder = repository.getLaunchOptions(
                            selectedInstanceId,
                            selectedJava,
                            repository.getBaseDirectory(),
                            javaAgents,
                            javaArguments,
                            makeLaunchScript);
                    if (disableOfflineSkin) {
                        launchOptionsBuilder.setDaemon(false);
                    }
                    if (quickPlayOption != null) {
                        launchOptionsBuilder.setQuickPlayOption(quickPlayOption);
                    }
                    if (OperatingSystem.CURRENT_OS == OperatingSystem.WINDOWS
                            && setting.getInheritable(GameSettings::highPerformanceProperty)) {
                        applyHighPerformanceGpuPreference(WinReg.INSTANCE, selectedJava.getBinary());
                    }

                    LaunchOptions launchOptions = launchOptionsBuilder.create();

                    LOG.info("Here's the structure of game mod directory:\n"
                            + FileUtils.printFileStructure(repository.getModsDirectory(selectedInstanceId), 10));

                    return new XYMLGameLauncher(
                            repository,
                            manifest.get(),
                            authInfo,
                            launchOptions,
                            launcherVisibility == LauncherVisibility.CLOSE
                                    ? null // Unnecessary to start listening to game process output when close launcher immediately after game launched.
                                    : new XYMLProcessListener(repository, manifest.get(), authInfo, launchOptions)
                    );
                }));
        return launcherTask;
    }

    /// Writes the DirectX high-performance preference when this Java executable has no explicit preference yet.
    ///
    /// Registry failures are logged and deliberately do not prevent game launch.
    ///
    /// @param registry available Windows registry bridge, or `null`
    /// @param javaBinary selected Java executable
    static void applyHighPerformanceGpuPreference(@Nullable WinReg registry, Path javaBinary) {
        if (registry == null) {
            return;
        }
        String javaPath = FileUtils.getAbsolutePath(Objects.requireNonNull(javaBinary, "javaBinary"));
        try {
            @Nullable Object current = registry.queryValue(
                    WinReg.HKEY.HKEY_CURRENT_USER,
                    WINDOWS_GPU_PREFERENCES_KEY,
                    javaPath);
            if (current != null) {
                LOG.info("GPU preference for " + javaPath + " already exists: " + current);
                return;
            }
            if (registry.setValue(
                    WinReg.HKEY.HKEY_CURRENT_USER,
                    WINDOWS_GPU_PREFERENCES_KEY,
                    javaPath,
                    HIGH_PERFORMANCE_GPU_PREFERENCE)) {
                LOG.info("Successfully applied high performance GPU preference for java: " + javaPath);
            } else {
                LOG.warning("Failed to apply high performance GPU preference for java: " + javaPath);
            }
        } catch (Exception failure) {
            LOG.warning("Failed to apply high performance GPU preference", failure);
        }
    }

    /// Adds production process ownership without applying presentation visibility policy.
    ///
    /// Constructing this wrapper does not run or start the supplied task. A successful execution always returns the
    /// exact managed process produced by `processTask` and records it in [#PROCESSES].
    ///
    /// @param processTask stopped task that creates the process
    /// @return stopped task that preserves the exact managed-process result
    static Task<ManagedProcess> decorateGameLaunchTask(Task<ManagedProcess> processTask) {
        Objects.requireNonNull(processTask, "processTask");
        return processTask.thenApplyAsync((@Nullable ManagedProcess result) -> {
            ManagedProcess process = Objects.requireNonNull(result, "launch task returned no managed process");
            PROCESSES.add(new WeakReference<>(process));
            return process;
        });
    }

    /// Applies production launch stage metadata without waiting for presentation readiness.
    ///
    /// Launch sessions complete as soon as the real managed process exists, matching
    /// [space.minecraftstl.xyml.game.launch.LaunchSession#completion()].
    ///
    /// @param task launch operation to present
    /// @return stopped task preserving the wrapped task's exact result
    static <T> Task<T> applyLaunchProgressPolicy(Task<T> task) {
        Objects.requireNonNull(task, "task");
        return task
                .withStage("launch.state.waiting_launching")
                .withStagesHints(
                        new Task.StagesHint("launch.state.java"),
                        new Task.StagesHint(
                                "launch.state.dependencies",
                                List.of("xyml.install.assets", "xyml.install.libraries", "xyml.modpack.download")),
                        new Task.StagesHint("launch.state.logging_in"),
                        new Task.StagesHint("launch.state.waiting_launching"));
    }

    /// Publishes one mutable process-log batch synchronously before clearing it for reuse.
    ///
    /// The EDT barrier guarantees that a subsequent process-listener join observes every retained line
    /// before crash diagnostics copy the shared history. `SwingGameLogWindow` copies the batch while the
    /// caller remains blocked, so clearing after dispatch cannot mutate queued presentation data.
    ///
    /// @param window exact native log window sharing the listener history
    /// @param batch caller-owned mutable batch to publish and clear
    static void publishSwingLogBatch(
            SwingGameLogWindow window,
            List<Log> batch) {
        Objects.requireNonNull(window, "window");
        Objects.requireNonNull(batch, "batch");
        EdtDispatcher.executeAndWait(() -> window.logLines(batch));
        batch.clear();
    }

    /// Presents one production launch decision and adapts its completion to the existing task graph.
    ///
    /// @param prompt immutable localized launch prompt
    /// @return stopped task resolving to the selected semantic action
    private Task<LaunchInteractionPrompt.Action> presentProductionPrompt(
            LaunchInteractionPrompt prompt) {
        Objects.requireNonNull(prompt, "prompt");
        CompletableFuture<LaunchInteractionPrompt.Action> completion = new CompletableFuture<>();
        try {
            productionInteractions().launchInteraction().present(prompt).whenComplete((
                    @Nullable LaunchInteractionPrompt.Action action,
                    @Nullable Throwable failure) -> {
                if (failure == null) {
                    completion.complete(Objects.requireNonNull(action, "launch interaction returned null"));
                } else {
                    completion.completeExceptionally(failure);
                }
            });
        } catch (RuntimeException | Error failure) {
            completion.completeExceptionally(failure);
        }
        return Task.fromCompletableFuture(completion);
    }

    /// Returns the required production services.
    ///
    /// @return production interaction services
    private ProductionInteractions productionInteractions() {
        return productionInteractions;
    }

    /// Creates a stopped task already cancelled with a stable launch reason.
    ///
    /// @param reason diagnostic cancellation reason
    /// @param <T> expected task result type
    /// @return cancelled stopped task
    private static <T> Task<T> cancelledTask(String reason) {
        return Task.fromCompletableFuture(CompletableFuture.failedFuture(
                new CancellationException(Objects.requireNonNull(reason, "reason"))));
    }

    /// Presents a production yes/no confirmation with cancellation as its safe default.
    ///
    /// @param title localized dialog title
    /// @param message localized decision detail
    /// @param severity toolkit-neutral visual severity
    /// @param acceptedAction semantic result of explicit acceptance
    /// @return stopped task resolving to acceptance or cancellation
    private Task<LaunchInteractionPrompt.Action> presentProductionConfirmation(
            String title,
            String message,
            LaunchInteractionPrompt.Severity severity,
            LaunchInteractionPrompt.Action acceptedAction) {
        return presentProductionPrompt(LaunchInteractionPrompt.confirmation(
                title,
                message,
                severity,
                acceptedAction,
                i18n("button.yes"),
                i18n("button.no")));
    }

    /// Presents a blocking production error before cancelling the current task branch.
    ///
    /// @param message localized error detail
    /// @param reason diagnostic cancellation reason
    /// @param <T> expected result type of the cancelled branch
    /// @return acknowledgement followed by cancellation
    private <T> Task<T> presentProductionErrorAndCancel(String message, String reason) {
        LaunchInteractionPrompt prompt = LaunchInteractionPrompt.acknowledgement(
                i18n("message.error"),
                message,
                LaunchInteractionPrompt.Severity.ERROR,
                i18n("button.ok"));
        return presentProductionPrompt(prompt).thenComposeAsync(
                (@Nullable LaunchInteractionPrompt.Action ignored) -> cancelledTask(reason));
    }

    /// Requests explicit fallback to the launcher's Java runtime.
    ///
    /// @return selected default runtime or a cancelled task
    private Task<JavaRuntime> chooseProductionDefaultJava() {
        return presentProductionConfirmation(
                i18n("message.warning"),
                i18n("launch.failed.no_accepted_java"),
                LaunchInteractionPrompt.Severity.WARNING,
                LaunchInteractionPrompt.Action.USE_DEFAULT_JAVA)
                .thenComposeAsync(Schedulers.ui(), (@Nullable LaunchInteractionPrompt.Action selected) -> {
                    if (selected == LaunchInteractionPrompt.Action.USE_DEFAULT_JAVA) {
                        return Task.completed(JavaRuntime.getDefault());
                    }
                    return cancelledTask("No accepted Java runtime");
                });
    }

    /// Requests the installed Java runtime recommended by compatibility analysis.
    ///
    /// @param suggestedJava compatible installed runtime
    /// @return selected runtime or a cancelled task
    private Task<JavaRuntime> chooseProductionRecommendedJava(JavaRuntime suggestedJava) {
        Objects.requireNonNull(suggestedJava, "suggestedJava");
        return presentProductionConfirmation(
                i18n("message.warning"),
                i18n("launch.advice.java.auto"),
                LaunchInteractionPrompt.Severity.WARNING,
                LaunchInteractionPrompt.Action.USE_RECOMMENDED_JAVA)
                .thenComposeAsync(Schedulers.ui(), (@Nullable LaunchInteractionPrompt.Action selected) -> {
                    if (selected == LaunchInteractionPrompt.Action.USE_RECOMMENDED_JAVA) {
                        setting.setJavaAutoSelected();
                        return Task.completed(suggestedJava);
                    }
                    return cancelledTask("Recommended Java runtime was declined");
                });
    }

    /// Requests permission and then executes the Java download inside the launch task graph.
    ///
    /// @param javaVersion runtime family required by the selected game
    /// @param repository repository supplying the configured download provider
    /// @return downloaded runtime or a cancelled task after rejection
    private Task<JavaRuntime> downloadProductionJava(
            GameJavaVersion javaVersion,
            XYMLGameRepository repository) {
        Objects.requireNonNull(javaVersion, "javaVersion");
        Objects.requireNonNull(repository, "repository");
        return presentProductionConfirmation(
                i18n("message.warning"),
                i18n("launch.advice.require_newer_java_version", javaVersion.majorVersion()),
                LaunchInteractionPrompt.Severity.QUESTION,
                LaunchInteractionPrompt.Action.DOWNLOAD_REQUIRED_JAVA)
                .thenComposeAsync(Schedulers.ui(), (@Nullable LaunchInteractionPrompt.Action selected) -> {
                    if (selected != LaunchInteractionPrompt.Action.DOWNLOAD_REQUIRED_JAVA) {
                        return cancelledTask("Required Java download was declined");
                    }
                    DownloadProvider downloadProvider = repository.getDependency().getDownloadProvider();
                    return JavaManager.getDownloadJavaTask(
                            downloadProvider,
                            SYSTEM_PLATFORM,
                            javaVersion);
                });
    }

    /// Falls back to the launcher's Java runtime only after a requested download fails.
    ///
    /// @param downloadTask required Java download task
    /// @return downloaded runtime, explicitly selected default runtime, or cancellation
    private Task<JavaRuntime> recoverProductionJavaDownload(Task<JavaRuntime> downloadTask) {
        Objects.requireNonNull(downloadTask, "downloadTask");
        return downloadTask.wrapResult().thenComposeAsync(
                Schedulers.ui(),
                (@Nullable Result<@Nullable JavaRuntime> nullableResult) -> {
                    Result<@Nullable JavaRuntime> result = Objects.requireNonNull(
                            nullableResult,
                            "Java download result");
                    if (result.isSuccess()) {
                        return Task.completed(Objects.requireNonNull(
                                result.getOrNull(),
                                "downloaded Java runtime"));
                    }
                    LOG.warning("Failed to download Java", Objects.requireNonNull(
                            result.getException(),
                            "Java download failure"));
                    return chooseProductionDefaultJava();
                });
    }

    /// Requests continuation with the currently selected runtime after non-blocking advice.
    ///
    /// @param java current runtime
    /// @param message localized compatibility guidance
    /// @return current runtime or cancellation
    private Task<JavaRuntime> confirmProductionJavaAdvice(
            JavaRuntime java,
            String message) {
        Objects.requireNonNull(java, "java");
        return presentProductionConfirmation(
                i18n("message.warning"),
                message,
                LaunchInteractionPrompt.Severity.WARNING,
                LaunchInteractionPrompt.Action.CONTINUE)
                .thenComposeAsync((@Nullable LaunchInteractionPrompt.Action selected) ->
                        selected == LaunchInteractionPrompt.Action.CONTINUE
                                ? Task.completed(java)
                                : cancelledTask("Java compatibility advice was declined"));
    }

    /// Explicit production-only launch and account recovery dependencies.
    ///
    /// @param launchInteraction native launch-decision boundary
    /// @param accountReauthentication stable-ID account recovery boundary
    @NotNullByDefault
    private record ProductionInteractions(
            LaunchInteraction launchInteraction,
            AccountReauthentication accountReauthentication) {
        /// Validates both production boundaries before launch state is captured.
        private ProductionInteractions {
            Objects.requireNonNull(launchInteraction, "launchInteraction");
            Objects.requireNonNull(accountReauthentication, "accountReauthentication");
        }
    }

    /// Selects or acquires a compatible Java runtime through the active presentation boundary.
    ///
    /// @param repository repository supplying game metadata and dependencies
    /// @param setting effective launch settings
    /// @param manifest resolved instance manifest to inspect
    /// @return stopped task that produces an accepted Java runtime
    private Task<JavaRuntime> checkGameState(
            XYMLGameRepository repository,
            GameSettings.Effective setting,
            GameInstanceManifest manifest) {
        LibraryAnalyzer analyzer = LibraryAnalyzer.analyze(
                manifest,
                repository.getGameVersion(manifest).orElse(null));
        GameVersionNumber gameVersion = GameVersionNumber.asGameVersion(analyzer.getVersion(LibraryAnalyzer.LibraryType.MINECRAFT));

        Task<@Nullable JavaRuntime> getJavaTask = Task.supplyAsync(() -> {
            try {
                return setting.getJava(gameVersion, manifest);
            } catch (InterruptedException e) {
                throw new CancellationException();
            }
        });
        Task<JavaRuntime> task;
        JavaVersionType javaVersionType = setting.getInheritable(GameSettings::javaTypeProperty);
        if (setting.getInheritable(GameSettings::notCheckJVMProperty)) {
            task = getJavaTask.thenApplyAsync((@Nullable JavaRuntime java) ->
                    Lang.requireNonNullElse(java, JavaRuntime.getDefault()));
        } else if (javaVersionType == JavaVersionType.AUTO || javaVersionType == JavaVersionType.VERSION) {
            task = getJavaTask.thenComposeAsync(Schedulers.ui(), (@Nullable JavaRuntime java) -> {
                if (java != null) {
                    return Task.completed(java);
                }

                List<GameJavaVersion> supportedVersions = GameJavaVersion.getSupportedVersions(SYSTEM_PLATFORM);

                @Nullable GameJavaVersion targetJavaVersion = null;
                if (javaVersionType == JavaVersionType.VERSION) {
                    try {
                        int targetJavaVersionMajor = Integer.parseInt(setting.getInheritable(GameSettings::customJavaVersionProperty));
                        @Nullable GameJavaVersion minimumJavaVersion = null;
                        if (gameVersion.compareTo("1.12.2") == 0) {
                            Optional<String> cleanroomVersion = analyzer.getVersion(LibraryAnalyzer.LibraryType.CLEANROOM);
                            if (cleanroomVersion.isPresent()) {
                                minimumJavaVersion = GameJavaVersion.getCleanroomJavaVersion(cleanroomVersion.get());
                            }
                        }

                        if (minimumJavaVersion == null)
                            minimumJavaVersion = GameJavaVersion.getMinimumJavaVersion(gameVersion);

                        if (minimumJavaVersion != null && targetJavaVersionMajor < minimumJavaVersion.majorVersion()) {
                            return presentProductionErrorAndCancel(
                                    i18n("launch.failed.java_version_too_low"),
                                    "Configured Java version is too low");
                        }

                        targetJavaVersion = GameJavaVersion.get(targetJavaVersionMajor);
                    } catch (NumberFormatException ignored) {
                    }
                } else {
                    if (gameVersion.compareTo("1.12.2") == 0) {
                        Optional<String> cleanroomVersion = analyzer.getVersion(LibraryAnalyzer.LibraryType.CLEANROOM);
                        if (cleanroomVersion.isPresent()) {
                            targetJavaVersion = GameJavaVersion.getCleanroomJavaVersion(cleanroomVersion.get());
                        }
                    }

                    if (targetJavaVersion == null)
                        targetJavaVersion = manifest.javaVersion();
                }

                if (targetJavaVersion != null && supportedVersions.contains(targetJavaVersion)) {
                    return recoverProductionJavaDownload(
                            downloadProductionJava(targetJavaVersion, repository));
                }
                return chooseProductionDefaultJava();
            });
        } else {
            task = getJavaTask.thenComposeAsync((@Nullable JavaRuntime java) -> {
                Set<JavaVersionConstraint> violatedMandatoryConstraints = EnumSet.noneOf(JavaVersionConstraint.class);
                Set<JavaVersionConstraint> violatedSuggestedConstraints = EnumSet.noneOf(JavaVersionConstraint.class);

                if (java != null) {
                    for (JavaVersionConstraint constraint : JavaVersionConstraint.ALL) {
                        if (constraint.appliesToVersion(gameVersion, manifest, java, analyzer)) {
                            if (!constraint.checkJava(gameVersion, manifest, java, analyzer)) {
                                if (constraint.isMandatory()) {
                                    violatedMandatoryConstraints.add(constraint);
                                } else {
                                    violatedSuggestedConstraints.add(constraint);
                                }
                            }
                        }
                    }
                }

                if (java == null || !violatedMandatoryConstraints.isEmpty()) {
                    @Nullable JavaRuntime suggestedJava = JavaManager.findSuitableJava(gameVersion, manifest);
                    if (suggestedJava != null) {
                        return chooseProductionRecommendedJava(suggestedJava);
                    } else if (java == null) {
                        return presentProductionErrorAndCancel(
                                i18n("launch.invalid_java"),
                                "Configured Java path is invalid");
                    } else {
                        @Nullable GameJavaVersion gameJavaVersion;
                        if (violatedMandatoryConstraints.contains(JavaVersionConstraint.CLEANROOM)) {
                            String cleanroomVersion = analyzer.getVersion(LibraryAnalyzer.LibraryType.CLEANROOM)
                                    .orElse("");

                            gameJavaVersion = !cleanroomVersion.isEmpty()
                                    ? GameJavaVersion.getCleanroomJavaVersion(cleanroomVersion)
                                    : GameJavaVersion.JAVA_21;
                        } else if (violatedMandatoryConstraints.contains(JavaVersionConstraint.GAME_JSON))
                            gameJavaVersion = manifest.javaVersion();
                        else if (violatedMandatoryConstraints.contains(JavaVersionConstraint.VANILLA))
                            gameJavaVersion = GameJavaVersion.getMinimumJavaVersion(gameVersion);
                        else
                            gameJavaVersion = null;

                        if (gameJavaVersion != null) {
                            return downloadProductionJava(gameJavaVersion, repository)
                                    .thenApplyAsync(
                                            Schedulers.ui(),
                                            (@Nullable JavaRuntime downloadedJava) -> {
                                                setting.setJavaAutoSelected();
                                                return Objects.requireNonNull(
                                                        downloadedJava,
                                                        "downloaded Java runtime");
                                            });
                        }

                        if (violatedMandatoryConstraints.contains(JavaVersionConstraint.VANILLA_LINUX_JAVA_8)) {
                            if (!setting.getInheritable(GameSettings::useCustomNativesProperty)) {
                                return presentProductionErrorAndCancel(
                                        i18n("launch.advice.vanilla_linux_java_8"),
                                        "Selected Java is incompatible with Linux natives");
                            } else {
                                violatedMandatoryConstraints.remove(JavaVersionConstraint.VANILLA_LINUX_JAVA_8);
                            }
                        }

                        if (violatedMandatoryConstraints.contains(JavaVersionConstraint.LAUNCH_WRAPPER)) {
                            return presentProductionErrorAndCancel(
                                    i18n("launch.advice.java9")
                                            + "\n"
                                            + i18n("launch.advice.uncorrected"),
                                    "Selected Java is incompatible with the launch wrapper");
                        }

                        if (!violatedMandatoryConstraints.isEmpty()) {
                            return presentProductionErrorAndCancel(
                                    i18n("launch.advice.unknown")
                                            + "\n"
                                            + violatedMandatoryConstraints,
                                    "Selected Java violates mandatory constraints");
                        }
                    }
                }

                List<String> suggestions = new ArrayList<>();

                if (Architecture.SYSTEM_ARCH == Architecture.X86_64 && java.getPlatform().getArchitecture() == Architecture.X86) {
                    suggestions.add(i18n("launch.advice.different_platform"));
                }

                // 32-bit JVM cannot make use of too much memory.
                if (java.getBits() == Bits.BIT_32 && !setting.getInheritable(GameSettings::autoMemoryProperty) && setting.getMaxMemory() > 1.5 * 1024) {
                    // 1.5 * 1024 is an inaccurate number.
                    // Actual memory limit depends on operating system and memory.
                    suggestions.add(i18n("launch.advice.too_large_memory_for_32bit"));
                }

                for (JavaVersionConstraint violatedSuggestedConstraint : violatedSuggestedConstraints) {
                    switch (violatedSuggestedConstraint) {
                        case MODDED_JAVA_7:
                            suggestions.add(i18n("launch.advice.java.modded_java_7"));
                            break;
                        case MODDED_JAVA_8:
                            // Minecraft>=1.7.10+Forge accepts Java 8
                            if (java.getParsedVersion() < 8)
                                suggestions.add(i18n("launch.advice.newer_java"));
                            else
                                suggestions.add(i18n("launch.advice.modded_java", 8, gameVersion));
                            break;
                        case MODDED_JAVA_16:
                            // Minecraft<=1.17.1+Forge[37.0.0,37.0.60) not compatible with Java 17
                            @Nullable String forgePatchVersion = analyzer.getVersion(LibraryAnalyzer.LibraryType.FORGE).orElse(null);
                            if (forgePatchVersion != null && VersionNumber.compare(forgePatchVersion, "37.0.60") < 0)
                                suggestions.add(i18n("launch.advice.forge37_0_60"));
                            else
                                suggestions.add(i18n("launch.advice.modded_java", 16, gameVersion));
                            break;
                        case MODDED_JAVA_17:
                            suggestions.add(i18n("launch.advice.modded_java", 17, gameVersion));
                            break;
                        case MODDED_JAVA_21:
                            suggestions.add(i18n("launch.advice.modded_java", 21, gameVersion));
                            break;
                        case CLEANROOM: {
                            String cleanroomVersion = analyzer.getVersion(LibraryAnalyzer.LibraryType.CLEANROOM).orElse("");
                            if (!cleanroomVersion.isEmpty())
                                suggestions.add(i18n("launch.advice.cleanroom", GameJavaVersion.getCleanroomJavaVersion(cleanroomVersion).majorVersion(), cleanroomVersion));
                            else
                                suggestions.add(i18n("launch.advice.cleanroom", 21, ""));
                            break;
                        }
                        case VANILLA_JAVA_8_51:
                            suggestions.add(i18n("launch.advice.java8_51_1_13"));
                            break;
                        case MODLAUNCHER_8:
                            suggestions.add(i18n("launch.advice.modlauncher8"));
                            break;
                        case VANILLA_X86:
                            if (!setting.getInheritable(GameSettings::useCustomNativesProperty)
                                    && Platform.isSupportedTranslationX86_64()) {
                                suggestions.add(i18n("launch.advice.vanilla_x86.translation"));
                            }
                            break;
                        default:
                            suggestions.add(violatedSuggestedConstraint.name());
                    }
                }

                // Cannot allocate too much memory exceeding free space.
                long totalMemorySizeMB = (long) MEGABYTES.convertFromBytes(SystemInfo.getTotalMemorySize());
                if (totalMemorySizeMB > 0 && !setting.getInheritable(GameSettings::autoMemoryProperty) && totalMemorySizeMB < setting.getMaxMemory()) {
                    suggestions.add(i18n("launch.advice.not_enough_space", totalMemorySizeMB));
                }

                @Nullable VersionNumber forgeVersion = analyzer.getVersion(LibraryAnalyzer.LibraryType.FORGE)
                        .map(VersionNumber::asVersion)
                        .orElse(null);

                // Forge 2760~2773 will crash game with LiteLoader.
                boolean hasForge2760 = forgeVersion != null && (forgeVersion.compareTo("1.12.2-14.23.5.2760") >= 0) && (forgeVersion.compareTo("1.12.2-14.23.5.2773") < 0);
                boolean hasLiteLoader = manifest.getLibraries().stream()
                        .anyMatch(it -> it.is("com.mumfrey", "liteloader"));
                if (hasForge2760 && hasLiteLoader && gameVersion.compareTo("1.12.2") == 0) {
                    suggestions.add(i18n("launch.advice.forge2760_liteloader"));
                }

                // OptiFine 1.14.4 is not compatible with Forge 28.2.2 and later versions.
                boolean hasForge28_2_2 = forgeVersion != null && (forgeVersion.compareTo("1.14.4-28.2.2") >= 0);
                boolean hasOptiFine = manifest.getLibraries().stream()
                        .anyMatch(it -> it.is("optifine", "OptiFine"));
                if (hasForge28_2_2 && hasOptiFine && gameVersion.compareTo("1.14.4") == 0) {
                    suggestions.add(i18n("launch.advice.forge28_2_2_optifine"));
                }

                JavaRuntime acceptedJava = Objects.requireNonNull(java, "selected Java runtime");
                if (suggestions.isEmpty()) {
                    return Task.completed(acceptedJava);
                }

                String message;
                if (suggestions.size() == 1) {
                    message = i18n("launch.advice", suggestions.get(0));
                } else {
                    message = i18n(
                            "launch.advice.multi",
                            suggestions.stream()
                                    .map(it -> "- " + it)
                                    .collect(Collectors.joining("\n")));
                }

                return confirmProductionJavaAdvice(acceptedJava, message);
            });
        }

        return task.withStage("launch.state.java");
    }

    /// Builds the authentication task and presents retry or offline fallback through Swing interactions.
    ///
    /// @param account account to authenticate
    /// @return stopped task that produces launch authentication data
    private Task<@Nullable AuthInfo> logIn(Account account) {
        return Task.composeAsync(() -> {
            try {
                if (disableOfflineSkin && account instanceof OfflineAccount offlineAccount)
                    return Task.completed(offlineAccount.logInWithoutSkin());
                else
                    return Task.completed(account.logIn());
            } catch (CredentialExpiredException e) {
                LOG.info("Credential has expired", e);
                return reauthenticateForLaunch(
                        account,
                        productionInteractions().accountReauthentication());
            } catch (AuthenticationException e) {
                LOG.warning("Authentication failed, try skipping refresh", e);
                LaunchInteractionPrompt prompt = LaunchInteractionPrompt.authenticationRecovery(
                        i18n("account.failed"),
                        i18n("account.failed.server_disconnected"),
                        i18n("account.login.skip"),
                        i18n("account.login.retry"),
                        i18n("button.cancel"));
                return presentProductionPrompt(prompt).thenComposeAsync(
                        (@Nullable LaunchInteractionPrompt.Action selected) ->
                                resolveProductionAuthenticationRecovery(
                                        account,
                                        Objects.requireNonNull(
                                                selected,
                                                "authentication recovery action"),
                                        () -> logIn(account)));
            }
        });
    }

    /// Adapts stable-ID account reauthentication into the existing stopped launch task graph.
    ///
    /// @param account persisted account whose credentials expired
    /// @param accountReauthentication production credential recovery boundary
    /// @return stopped task resolving to the exact recovered authentication data
    static Task<AuthInfo> reauthenticateForLaunch(
            Account account,
            AccountReauthentication accountReauthentication) {
        Objects.requireNonNull(account, "account");
        Objects.requireNonNull(accountReauthentication, "accountReauthentication");
        CompletableFuture<AuthInfo> completion = new CompletableFuture<>();
        try {
            String accountId = account.getAccountID().toString();
            accountReauthentication.reauthenticate(accountId).whenComplete((
                    @Nullable AuthInfo authInfo,
                    @Nullable Throwable failure) -> {
                if (failure == null) {
                    completion.complete(Objects.requireNonNull(
                            authInfo,
                            "account reauthentication returned null"));
                } else {
                    completion.completeExceptionally(failure);
                }
            });
        } catch (RuntimeException | Error failure) {
            completion.completeExceptionally(failure);
        }
        return Task.fromCompletableFuture(completion);
    }

    /// Maps one production authentication-recovery selection without exposing Swing to account operations.
    ///
    /// @param account selected persisted account
    /// @param selected semantic recovery selection
    /// @param retryTaskSupplier deferred retry of the complete login operation
    /// @return stopped offline, retry, or cancelled task
    static Task<AuthInfo> resolveProductionAuthenticationRecovery(
            Account account,
            LaunchInteractionPrompt.Action selected,
            Supplier<Task<@Nullable AuthInfo>> retryTaskSupplier) {
        Objects.requireNonNull(account, "account");
        Objects.requireNonNull(selected, "selected");
        Objects.requireNonNull(retryTaskSupplier, "retryTaskSupplier");
        return switch (selected) {
            case PLAY_OFFLINE -> Task.supplyAsync(account::playOffline);
            case RETRY_AUTHENTICATION -> Objects.requireNonNull(
                    retryTaskSupplier.get(),
                    "retryTaskSupplier returned null");
            case CANCEL -> cancelledTask("Authentication recovery was cancelled");
            default -> throw new IllegalArgumentException(
                    "Unexpected authentication recovery action: " + selected);
        };
    }

    /// Persists automatic agent enablement at the effective setting's active override level.
    private void enableAutoAgentForCurrentSetting() {
        @Nullable GameSettings.Instance instance = setting.getInstance();
        if (instance != null
                && instance.getOverrideProperties().contains(GameSettings.PROPERTY_ALLOW_AUTO_AGENT)) {
            instance.allowAutoAgentProperty().setValue(true);
        } else {
            setting.getPreset().allowAutoAgentProperty().setValue(true);
        }
    }

    /// Observes one managed process, captures logs, and reports abnormal exits.
    @NotNullByDefault
    private final class XYMLProcessListener implements ProcessListener {

        /// Serializes launch-detection and bounded-log updates.
        private final ReentrantLock lock = new ReentrantLock();

        /// Repository owning the launched instance.
        private final XYMLGameRepository repository;

        /// Resolved launched instance manifest.
        private final GameInstanceManifest manifest;

        /// Immutable launch options used to diagnose a crash.
        private final LaunchOptions launchOptions;

        /// Managed process after [#setProcess(ManagedProcess)], or null before attachment.
        private @Nullable ManagedProcess process;

        /// Native Swing log window, or null when log display is disabled or not yet initialized.
        private @Nullable SwingGameLogWindow logWindow;

        /// Mutable bounded log history retained for crash diagnostics.
        private final CircularArrayList<Log> logs;

        /// Access token removed from captured log lines, or null when authentication has no token.
        private final @Nullable String forbiddenAccessToken;

        /// Log batching thread, or null while the native log window is disabled or uninitialized.
        private @Nullable Thread submitLogThread;

        /// Log batching queue, or null while the native log window is disabled or uninitialized.
        private @Nullable LinkedBlockingQueue<Log> logBuffer;

        /// Creates a listener dedicated to one prepared launch.
        ///
        /// @param repository repository owning the launched instance
        /// @param manifest resolved launched instance manifest
        /// @param authInfo authentication data, or null when the launcher permits an unauthenticated launch
        /// @param launchOptions immutable launch configuration
        public XYMLProcessListener(
                XYMLGameRepository repository,
                GameInstanceManifest manifest,
                @Nullable AuthInfo authInfo,
                LaunchOptions launchOptions) {
            this.repository = repository;
            this.manifest = manifest;
            this.launchOptions = launchOptions;
            this.forbiddenAccessToken = authInfo != null ? authInfo.getAccessToken() : null;
            this.logs = new CircularArrayList<>(logLineLimit + 1);
        }

        /// Attaches the managed process and initializes the optional native log window.
        ///
        /// @param process newly created managed process
        @Override
        public void setProcess(ManagedProcess process) {
            this.process = process;

            String command = new CommandBuilder().addAll(process.getCommands()).toString();

            LOG.info("Launched process: " + command);

            @Nullable String classpath = process.getClasspath();
            if (classpath != null) {
                LOG.info("Process ClassPath: " + classpath);
            }

            if (showLogs) {
                logWindow = createSwingLogWindow();
                logWindow.show();

                logBuffer = new LinkedBlockingQueue<>();
                submitLogThread = Lang.thread(new Runnable() {
                    /// Mutable batch copied before synchronous Swing publication.
                    private final ArrayList<Log> currentLogs = new ArrayList<>();

                    /// Publishes the current batch to the initialized Swing log window.
                    ///
                    /// Synchronous EDT completion makes thread join a complete flush boundary before crash analysis
                    /// reads the shared retained history. `SwingGameLogWindow` copies the batch before returning.
                    private void submitLogs() {
                        SwingGameLogWindow window = Objects.requireNonNull(
                                logWindow,
                                "log window");
                        publishSwingLogBatch(window, currentLogs);
                    }

                    /// Drains queued log lines in bounded batches until interruption, then flushes the remainder.
                    @Override
                    public void run() {
                        LinkedBlockingQueue<Log> queue = Objects.requireNonNull(logBuffer, "log buffer");
                        while (true) {
                            try {
                                currentLogs.add(queue.take());
                                //noinspection BusyWait
                                Thread.sleep(200); // Wait for more logs
                            } catch (InterruptedException e) {
                                break;
                            }

                            queue.drainTo(currentLogs);
                            submitLogs();
                        }

                        do {
                            submitLogs();
                        } while (queue.drainTo(currentLogs) > 0);
                    }
                }, "Game Log Submitter", true);
            }
        }

        /// Creates a native log window around this listener's exact process and retained history.
        ///
        /// Each crash-window request receives a fresh instance, so closing the automatically opened log
        /// window cannot permanently disable later access to the same retained diagnostics.
        ///
        /// @return fresh native game log window
        private SwingGameLogWindow createSwingLogWindow() {
            return new SwingGameLogWindow(
                    Objects.requireNonNull(process, "managed process"),
                    logs,
                    logLineLimit,
                    maxLines -> EdtDispatcher.execute(
                            () -> settings().logLinesProperty().set(maxLines)));
        }

        /// Opens a fresh native game log window for crash diagnostics.
        private void showCrashLogs() {
            createSwingLogWindow().show();
        }

        /// Captures and redacts one process log line.
        ///
        /// @param log raw process log line
        /// @param isErrorStream whether the line came from standard error
        @Override
        public void onLog(String log, boolean isErrorStream) {
            if (isErrorStream)
                System.err.println(log);
            else
                System.out.println(log);

            log = StringUtils.parseEscapeSequence(log);
            if (forbiddenAccessToken != null)
                log = log.replace(forbiddenAccessToken, "<access token>");

            @Nullable Log4jLevel level = isErrorStream && !log.startsWith("[authlib-injector]")
                    ? Log4jLevel.ERROR
                    : null;
            if (showLogs) {
                if (level == null)
                    level = Lang.requireNonNullElse(Log4jLevel.guessLevel(log), Log4jLevel.INFO);
                Objects.requireNonNull(logBuffer, "log buffer").add(new Log(log, level));
            } else {
                lock.lock();
                try {
                    logs.addLast(new Log(log, level));
                    if (logs.size() > logLineLimit)
                        logs.removeFirst();
                } finally {
                    lock.unlock();
                }
            }
        }

        /// Flushes log capture, records abnormal exit state, and opens crash diagnostics.
        ///
        /// @param exitCode raw process exit code
        /// @param exitType classified process exit type
        @Override
        public void onExit(int exitCode, ExitType exitType) {
            if (showLogs) {
                Objects.requireNonNull(logBuffer, "log buffer").add(new Log(String.format("[%s] [XYML ProcessListener] Minecraft exit with code %d(0x%x), type is %s.", TIME_FORMATTER.format(Instant.now()), exitCode, exitCode, exitType), Log4jLevel.INFO));
                Thread logThread = Objects.requireNonNull(submitLogThread, "log submitter");
                logThread.interrupt();
                try {
                    logThread.join();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }

            if (exitType == ExitType.INTERRUPTED)
                return;

            if (exitType != ExitType.NORMAL) {
                repository.markInstanceLaunchedAbnormally(manifest.id());
                SwingGameCrashWindow.open(
                        Objects.requireNonNull(process, "managed process"),
                        exitType,
                        repository,
                        manifest,
                        launchOptions,
                        logs,
                        this::showCrashLogs);
            }
        }

        /// Completes Swing visibility policy only after the entire exit monitor terminates.
        ///
        /// @param failure monitor failure, or null after listener and post-exit work succeed
        @Override
        public void onMonitorComplete(@Nullable Throwable failure) {
            if (failure == null) {
                processLifecycleCompletion.complete(null);
            } else {
                processLifecycleCompletion.completeExceptionally(failure);
            }
        }

    }

    /// Weak registrations for live managed processes owned by this launcher process.
    private static final Queue<WeakReference<ManagedProcess>> PROCESSES = new ConcurrentLinkedQueue<>();

    /// Removes stale or exited registrations and returns the number of live managed processes.
    ///
    /// @return number of currently live registered processes
    public static int countMangedProcesses() {
        PROCESSES.removeIf(it -> {
            @Nullable ManagedProcess process = it.get();
            return process == null || !process.isRunning();
        });
        return PROCESSES.size();
    }

    /// Stops every reachable registered process and empties the ownership queue.
    public static void stopManagedProcesses() {
        while (!PROCESSES.isEmpty())
            Optional.ofNullable(PROCESSES.poll()).map(WeakReference::get).ifPresent(ManagedProcess::stop);
    }
}
