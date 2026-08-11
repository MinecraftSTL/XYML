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
package space.minecraftstl.xyml.ui.swing.runtime;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import space.minecraftstl.xyml.auth.Account;
import space.minecraftstl.xyml.auth.AccountID;
import space.minecraftstl.xyml.game.GameInstanceID;
import space.minecraftstl.xyml.game.LauncherHelper;
import space.minecraftstl.xyml.game.QuickPlayOption;
import space.minecraftstl.xyml.game.World;
import space.minecraftstl.xyml.game.XYMLGameRepository;
import space.minecraftstl.xyml.game.launch.LaunchRequest;
import space.minecraftstl.xyml.game.launch.LaunchSession;
import space.minecraftstl.xyml.game.launch.LaunchTaskFactory;
import space.minecraftstl.xyml.setting.Accounts;
import space.minecraftstl.xyml.setting.GameDirectoryID;
import space.minecraftstl.xyml.setting.GameDirectoryManager;
import space.minecraftstl.xyml.setting.LauncherVisibility;
import space.minecraftstl.xyml.task.Schedulers;
import space.minecraftstl.xyml.task.Task;
import space.minecraftstl.xyml.ui.launch.LaunchInteraction;
import space.minecraftstl.xyml.ui.swing.page.accounts.AccountReauthentication;
import space.minecraftstl.xyml.util.platform.ManagedProcess;
import space.minecraftstl.xyml.util.versioning.GameVersionNumber;

import javax.swing.SwingUtilities;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import static space.minecraftstl.xyml.util.logging.Logger.LOG;

/// Resolves one immutable Swing launch request against Swing-confined launcher stores.
///
/// The factory itself is invoked by [space.minecraftstl.xyml.game.launch.DefaultGameLaunchService]
/// on its caller-owned preparation executor. It synchronously crosses to the Swing EDT only while
/// resolving stable identifiers and constructing an unstarted task. Construction retains the launcher
/// metadata-maintenance and game-state analysis work, so callers must keep it away from the Swing EDT;
/// the returned process-producing task itself remains unstarted for launch-session execution.
@NotNullByDefault
public final class LauncherLaunchTaskFactory implements LaunchTaskFactory, AutoCloseable {
    /// Synchronous launcher-state bridge used only from a non-EDT preparation worker.
    private final Consumer<Runnable> stateDispatcher;

    /// Swing-confined builder that resolves the exact request and returns an unstarted task.
    private final Function<LaunchRequest, Task<ManagedProcess>> launchTaskBuilder;

    /// Swing-confined builder that resolves an exact request and returns an unstarted script-export task.
    private final BiFunction<LaunchRequest, Path, Task<Path>> launchScriptTaskBuilder;

    /// Runtime visibility actions applied only after a launch session commits process creation.
    private final LaunchVisibilityActions visibilityActions;

    /// Original visibility policies awaiting their successful session completion callbacks.
    private final ConcurrentHashMap<ManagedProcess, RegisteredVisibility> pendingVisibilities =
            new ConcurrentHashMap<>();

    /// Prevents late task completion from retaining policies after owner shutdown.
    private final AtomicBoolean closed = new AtomicBoolean();

    /// Creates the production stable-ID adapter.
    ///
    /// @param visibilityActions thread-safe Swing runtime visibility actions
    /// @param launchInteraction native production launch-decision boundary
    /// @param accountReauthentication stable-ID credential recovery boundary
    public LauncherLaunchTaskFactory(
            LaunchVisibilityActions visibilityActions,
            LaunchInteraction launchInteraction,
            AccountReauthentication accountReauthentication) {
        this.stateDispatcher = LauncherStateDispatcher::executeAndWait;
        this.visibilityActions = Objects.requireNonNull(visibilityActions, "visibilityActions");
        Objects.requireNonNull(launchInteraction, "launchInteraction");
        Objects.requireNonNull(accountReauthentication, "accountReauthentication");
        this.launchTaskBuilder = request -> createProductionTask(
                request,
                this::registerVisibility,
                launchInteraction,
                accountReauthentication);
        this.launchScriptTaskBuilder = (request, scriptFile) -> createProductionLaunchScriptTask(
                request,
                scriptFile,
                launchInteraction,
                accountReauthentication);
    }

    /// Creates an adapter around explicit deterministic collaborators for focused tests.
    ///
    /// @param stateDispatcher synchronous launcher-state dispatcher
    /// @param launchTaskBuilder Swing-EDT task builder
    LauncherLaunchTaskFactory(
            Consumer<Runnable> stateDispatcher,
            Function<LaunchRequest, Task<ManagedProcess>> launchTaskBuilder) {
        this(
                stateDispatcher,
                launchTaskBuilder,
                new LaunchVisibilityActions(() -> { }, () -> { }, () -> { }));
    }

    /// Creates an adapter around deterministic request and visibility collaborators for focused tests.
    ///
    /// @param stateDispatcher synchronous launcher-state dispatcher
    /// @param launchTaskBuilder Swing-EDT task builder
    /// @param visibilityActions runtime actions consumed after successful session completion
    LauncherLaunchTaskFactory(
            Consumer<Runnable> stateDispatcher,
            Function<LaunchRequest, Task<ManagedProcess>> launchTaskBuilder,
            LaunchVisibilityActions visibilityActions) {
        this(
                stateDispatcher,
                launchTaskBuilder,
                (request, scriptFile) -> Task.<Path>fromCompletableFuture(CompletableFuture.failedFuture(
                        new UnsupportedOperationException("Launch-script export is unavailable"))),
                visibilityActions);
    }

    /// Creates an adapter around deterministic launch and script task builders for focused tests.
    ///
    /// @param stateDispatcher synchronous launcher-state dispatcher
    /// @param launchTaskBuilder Swing-EDT game-launch task builder
    /// @param launchScriptTaskBuilder Swing-EDT script-export task builder
    /// @param visibilityActions runtime actions consumed after successful process creation
    LauncherLaunchTaskFactory(
            Consumer<Runnable> stateDispatcher,
            Function<LaunchRequest, Task<ManagedProcess>> launchTaskBuilder,
            BiFunction<LaunchRequest, Path, Task<Path>> launchScriptTaskBuilder,
            LaunchVisibilityActions visibilityActions) {
        this.stateDispatcher = Objects.requireNonNull(stateDispatcher, "stateDispatcher");
        this.launchTaskBuilder = Objects.requireNonNull(launchTaskBuilder, "launchTaskBuilder");
        this.launchScriptTaskBuilder = Objects.requireNonNull(launchScriptTaskBuilder, "launchScriptTaskBuilder");
        this.visibilityActions = Objects.requireNonNull(visibilityActions, "visibilityActions");
    }

    /// Resolves and builds one exact unstarted launch task away from the Swing EDT.
    ///
    /// Launcher metadata maintenance may execute synchronously before this method returns.
    ///
    /// @param request immutable stable launch identifiers
    /// @return unstarted task for the same captured request
    @Override
    public Task<ManagedProcess> create(LaunchRequest request) {
        Objects.requireNonNull(request, "request");
        if (SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException("Launcher launch task creation must not block the Swing EDT");
        }

        AtomicReference<@Nullable Task<ManagedProcess>> result = new AtomicReference<>();
        stateDispatcher.accept(() -> result.set(Objects.requireNonNull(
                launchTaskBuilder.apply(request),
                "launchTaskBuilder returned null")));
        return Objects.requireNonNull(result.get(), "launcher dispatcher did not run launch task builder");
    }

    /// Resolves and builds one exact unstarted script-export task away from the Swing EDT.
    ///
    /// Launcher metadata maintenance may execute synchronously before this method returns. The returned task preserves
    /// ordinary launcher authentication and dependency preparation, but ends by writing a local script instead of
    /// creating a game process.
    ///
    /// @param request immutable stable launch identifiers
    /// @param scriptFile local script target selected by the user
    /// @return unstarted task that completes with the exact generated script path
    Task<Path> createLaunchScriptTask(LaunchRequest request, Path scriptFile) {
        LaunchRequest capturedRequest = Objects.requireNonNull(request, "request");
        Path destination = Objects.requireNonNull(scriptFile, "scriptFile").toAbsolutePath().normalize();
        if (SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException("Launcher script task creation must not block the Swing EDT");
        }

        AtomicReference<@Nullable Task<Path>> result = new AtomicReference<>();
        stateDispatcher.accept(() -> result.set(Objects.requireNonNull(
                launchScriptTaskBuilder.apply(capturedRequest, destination),
                "launchScriptTaskBuilder returned null")));
        return Objects.requireNonNull(result.get(), "launcher dispatcher did not run launch-script task builder");
    }

    /// Observes a session's successful process completion and then consumes its registered policy.
    ///
    /// `DefaultLaunchSession` commits `PROCESS_CREATED`, exposes the process, and releases the service's
    /// single-flight slot before completing this stage. Runtime closure therefore cannot cancel the
    /// successful session whose process triggered it.
    ///
    /// @param session exact session returned by the launch service
    void observeCompletion(LaunchSession session) {
        Objects.requireNonNull(session, "session");
        session.completion().thenAccept(this::applyRegisteredVisibility);
    }

    /// Drops policies that can no longer be consumed after command-owner shutdown.
    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            pendingVisibilities.clear();
        }
    }

    /// Resolves stable IDs on the Swing EDT and builds a real process-producing launch task.
    ///
    /// The helper retains its process and I/O policy without applying any presentation visibility action.
    /// The effective policy and listener-completion signal are registered with the created process, then
    /// applied by Swing after the launch session commits successful process creation.
    ///
    /// @param request exact captured launch request
    /// @param visibilityRegistrar factory-owned policy registrar
    /// @param launchInteraction native production launch-decision boundary
    /// @param accountReauthentication stable-ID credential recovery boundary
    /// @return unstarted real game-launch task
    private static Task<ManagedProcess> createProductionTask(
            LaunchRequest request,
            VisibilityRegistrar visibilityRegistrar,
            LaunchInteraction launchInteraction,
            AccountReauthentication accountReauthentication) {
        LauncherStateDispatcher.requireEventThread();
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(visibilityRegistrar, "visibilityRegistrar");
        Objects.requireNonNull(launchInteraction, "launchInteraction");
        Objects.requireNonNull(accountReauthentication, "accountReauthentication");

        GameDirectoryID gameDirectoryId = GameDirectoryID.parse(request.gameDirectoryId());
        XYMLGameRepository repository = GameDirectoryManager.getRepository(gameDirectoryId);
        return afterRepositoryReady(
                repository.isLoaded(),
                repository::refreshAsync,
                () -> createLoadedProductionTask(
                        request,
                        visibilityRegistrar,
                        launchInteraction,
                        accountReauthentication),
                Schedulers.io());
    }

    /// Resolves stable IDs on the Swing EDT and builds a real local script-export task.
    ///
    /// The resulting task keeps normal account login, native preparation, and dependency-completion behavior. It
    /// changes only the final action from game process creation to local script generation.
    ///
    /// @param request exact captured launch request
    /// @param scriptFile local script target selected by the user
    /// @param launchInteraction native production launch-decision boundary
    /// @param accountReauthentication stable-ID credential recovery boundary
    /// @return unstarted real script-export task
    private static Task<Path> createProductionLaunchScriptTask(
            LaunchRequest request,
            Path scriptFile,
            LaunchInteraction launchInteraction,
            AccountReauthentication accountReauthentication) {
        LauncherStateDispatcher.requireEventThread();
        LaunchRequest capturedRequest = Objects.requireNonNull(request, "request");
        Path destination = Objects.requireNonNull(scriptFile, "scriptFile").toAbsolutePath().normalize();
        Objects.requireNonNull(launchInteraction, "launchInteraction");
        Objects.requireNonNull(accountReauthentication, "accountReauthentication");

        GameDirectoryID gameDirectoryId = GameDirectoryID.parse(capturedRequest.gameDirectoryId());
        XYMLGameRepository repository = GameDirectoryManager.getRepository(gameDirectoryId);
        return afterRepositoryReady(
                repository.isLoaded(),
                repository::refreshAsync,
                () -> createLoadedProductionLaunchScriptTask(
                        capturedRequest,
                        destination,
                        launchInteraction,
                        accountReauthentication),
                Schedulers.io());
    }

    /// Builds the exact launcher launch task after the requested repository is known to be loaded.
    ///
    /// Deferred calls synchronously cross back to the Swing EDT because account and settings objects remain
    /// thread-confined during this migration stage. The directory and instance are resolved again only
    /// from the immutable request; no current UI selection is consulted.
    ///
    /// @param request exact captured launch request
    /// @param visibilityRegistrar factory-owned policy registrar
    /// @param launchInteraction native production launch-decision boundary
    /// @param accountReauthentication stable-ID credential recovery boundary
    /// @return unstarted real game-launch task
    private static Task<ManagedProcess> createLoadedProductionTask(
            LaunchRequest request,
            VisibilityRegistrar visibilityRegistrar,
            LaunchInteraction launchInteraction,
            AccountReauthentication accountReauthentication) {
        AtomicReference<@Nullable Task<ManagedProcess>> result = new AtomicReference<>();
        LauncherStateDispatcher.executeAndWait(() -> {
            AccountID accountId = AccountID.parse(request.accountId());
            Account account = Accounts.getAccounts().stream()
                    .filter(candidate -> candidate.getAccountID().equals(accountId))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Unknown account: " + accountId));
            GameDirectoryID gameDirectoryId = GameDirectoryID.parse(request.gameDirectoryId());
            XYMLGameRepository repository = GameDirectoryManager.getRepository(gameDirectoryId);
            GameInstanceID instanceId = new GameInstanceID(request.instanceId());
            if (!repository.isLoaded() || !repository.hasInstance(instanceId)) {
                throw new IllegalArgumentException(
                        "Unknown instance " + request.instanceId() + " in " + gameDirectoryId);
            }
            requireSupportedQuickPlay(repository, request);

            LauncherHelper helper = new LauncherHelper(
                    repository,
                    account,
                    instanceId,
                    launchInteraction,
                    accountReauthentication);
            configureLaunchModes(request, helper::setQuickPlayOption, helper::setTestMode);
            LauncherVisibility originalVisibility = helper.getLauncherVisibility();
            Task<ManagedProcess> processTask = helper.createLaunchTask();
            result.set(processTask.thenApplyAsync(Runnable::run, (@Nullable ManagedProcess value) -> {
                ManagedProcess process = Objects.requireNonNull(value, "launch task returned no managed process");
                visibilityRegistrar.accept(
                        process,
                        originalVisibility,
                        helper.processLifecycleCompletion());
                return process;
            }));
        });
        return Objects.requireNonNull(result.get(), "launcher dispatcher did not build launch task");
    }

    /// Resolves the exact account and instance again after repository readiness, then creates the export task.
    ///
    /// Re-resolution deliberately uses only the immutable request. It never reads an active Swing selection, so a
    /// later account or instance change cannot alter the script that the user explicitly requested.
    ///
    /// @param request exact captured launch request
    /// @param scriptFile normalized local script target
    /// @param launchInteraction native production launch-decision boundary
    /// @param accountReauthentication stable-ID credential recovery boundary
    /// @return unstarted launcher task writing the selected script
    private static Task<Path> createLoadedProductionLaunchScriptTask(
            LaunchRequest request,
            Path scriptFile,
            LaunchInteraction launchInteraction,
            AccountReauthentication accountReauthentication) {
        LaunchRequest capturedRequest = Objects.requireNonNull(request, "request");
        Path destination = Objects.requireNonNull(scriptFile, "scriptFile").toAbsolutePath().normalize();
        AtomicReference<@Nullable Task<Path>> result = new AtomicReference<>();
        LauncherStateDispatcher.executeAndWait(() -> {
            AccountID accountId = AccountID.parse(capturedRequest.accountId());
            Account account = Accounts.getAccounts().stream()
                    .filter(candidate -> candidate.getAccountID().equals(accountId))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Unknown account: " + accountId));
            GameDirectoryID gameDirectoryId = GameDirectoryID.parse(capturedRequest.gameDirectoryId());
            XYMLGameRepository repository = GameDirectoryManager.getRepository(gameDirectoryId);
            GameInstanceID instanceId = new GameInstanceID(capturedRequest.instanceId());
            if (!repository.isLoaded() || !repository.hasInstance(instanceId)) {
                throw new IllegalArgumentException(
                        "Unknown instance " + capturedRequest.instanceId() + " in " + gameDirectoryId);
            }
            requireSupportedQuickPlay(repository, capturedRequest);

            LauncherHelper helper = new LauncherHelper(
                    repository,
                    account,
                    instanceId,
                    launchInteraction,
                    accountReauthentication);
            configureLaunchModes(capturedRequest, helper::setQuickPlayOption, helper::setTestMode);
            result.set(helper.createLaunchScriptTask(destination));
        });
        return Objects.requireNonNull(result.get(), "launcher dispatcher did not build launch-script task");
    }

    /// Applies the immutable quick-play or test-game mode to a newly created helper.
    ///
    /// Keeping this operation shared by process launch and script export guarantees both paths receive request-local
    /// mode state instead of consulting later UI selections or mutable game settings.
    ///
    /// @param request captured launch request
    /// @param quickPlaySetter helper target setter invoked only when the request contains a world folder
    /// @param testModeSetter helper test-mode setter invoked only for an explicit test request
    static void configureLaunchModes(
            LaunchRequest request,
            Consumer<QuickPlayOption> quickPlaySetter,
            Runnable testModeSetter) {
        LaunchRequest capturedRequest = Objects.requireNonNull(request, "request");
        Consumer<QuickPlayOption> setter = Objects.requireNonNull(quickPlaySetter, "quickPlaySetter");
        Runnable checkedTestModeSetter = Objects.requireNonNull(testModeSetter, "testModeSetter");
        @Nullable String worldFolder = capturedRequest.quickPlaySingleplayer();
        if (worldFolder != null) {
            setter.accept(new QuickPlayOption.SinglePlayer(worldFolder));
        }
        if (capturedRequest.testMode()) {
            checkedTestModeSetter.run();
        }
    }

    /// Rejects single-player quick play when the target instance would silently discard its launch argument.
    ///
    /// @param repository loaded repository containing the target instance
    /// @param request captured launch request
    private static void requireSupportedQuickPlay(
            XYMLGameRepository repository,
            LaunchRequest request) {
        XYMLGameRepository targetRepository = Objects.requireNonNull(repository, "repository");
        LaunchRequest capturedRequest = Objects.requireNonNull(request, "request");
        if (capturedRequest.quickPlaySingleplayer() != null
                && !World.supportQuickPlay(GameVersionNumber.asGameVersion(
                        targetRepository.getGameVersion(new GameInstanceID(capturedRequest.instanceId()))))) {
            throw new IllegalArgumentException("Single-player quick play requires Minecraft 1.20 or newer");
        }
    }

    /// Registers one original policy without running runtime actions inside the root launch task.
    ///
    /// @param process created managed process
    /// @param visibility original effective launcher visibility
    void registerVisibility(
            ManagedProcess process,
            LauncherVisibility visibility) {
        registerVisibility(process, visibility, CompletableFuture.completedFuture(null));
    }

    /// Registers one original policy and its full process-listener completion signal.
    ///
    /// @param process created managed process
    /// @param visibility original effective launcher visibility
    /// @param processLifecycleCompletion completion after log and crash bookkeeping
    private void registerVisibility(
            ManagedProcess process,
            LauncherVisibility visibility,
            CompletionStage<@Nullable Void> processLifecycleCompletion) {
        Objects.requireNonNull(process, "process");
        Objects.requireNonNull(visibility, "visibility");
        Objects.requireNonNull(processLifecycleCompletion, "processLifecycleCompletion");
        if (closed.get()) {
            return;
        }
        RegisteredVisibility registration = new RegisteredVisibility(
                visibility,
                processLifecycleCompletion);
        pendingVisibilities.put(process, registration);
        if (closed.get()) {
            pendingVisibilities.remove(process, registration);
        }
    }

    /// Consumes one policy only after its launch session has committed the same process.
    ///
    /// @param process exact process supplied by successful session completion
    private void applyRegisteredVisibility(ManagedProcess process) {
        Objects.requireNonNull(process, "process");
        @Nullable RegisteredVisibility registration = pendingVisibilities.remove(process);
        if (registration != null) {
            try {
                applyVisibilityPolicy(
                        process,
                        registration.visibility(),
                        visibilityActions,
                        registration.processLifecycleCompletion());
            } catch (RuntimeException | Error failure) {
                reportVisibilityFailure(
                        "apply " + registration.visibility() + " policy",
                        failure);
            }
        }
    }

    /// Applies one captured policy using the full process-listener completion boundary.
    ///
    /// Hiding is attempted before the completion callback is registered. A stage that completed before
    /// policy application therefore still runs close or reopen after the hide attempt, never before it.
    /// The supplied stage fires only after log draining, crash bookkeeping, and post-exit commands.
    ///
    /// @param process exact managed process committed by the launch session
    /// @param visibility original effective launcher visibility
    /// @param visibilityActions runtime-owned close, hide, and show commands
    /// @param processLifecycleCompletion completion after log and crash bookkeeping
    static void applyVisibilityPolicy(
            ManagedProcess process,
            LauncherVisibility visibility,
            LaunchVisibilityActions visibilityActions,
            CompletionStage<@Nullable Void> processLifecycleCompletion) {
        Objects.requireNonNull(process, "process");
        Objects.requireNonNull(visibility, "visibility");
        Objects.requireNonNull(visibilityActions, "visibilityActions");
        Objects.requireNonNull(processLifecycleCompletion, "processLifecycleCompletion");
        switch (visibility) {
            case CLOSE -> runVisibilityAction("close launcher", visibilityActions.close());
            case KEEP -> {
                // Keeping the current window state requires no lifecycle callback.
            }
            case HIDE -> {
                runVisibilityAction("hide launcher", visibilityActions.hide());
                runAfterProcessLifecycle(
                        processLifecycleCompletion,
                        "close hidden launcher after process exit",
                        visibilityActions.close());
            }
            case HIDE_AND_REOPEN -> {
                runVisibilityAction("hide launcher", visibilityActions.hide());
                runAfterProcessLifecycle(
                        processLifecycleCompletion,
                        "reopen launcher after process exit",
                        visibilityActions.show());
            }
        }
    }

    /// Runs one lifecycle action after listener completion, including exceptional completion.
    ///
    /// @param processLifecycleCompletion full listener-completion stage
    /// @param actionName diagnostic action name
    /// @param action runtime lifecycle action
    private static void runAfterProcessLifecycle(
            CompletionStage<@Nullable Void> processLifecycleCompletion,
            String actionName,
            Runnable action) {
        processLifecycleCompletion.whenComplete((
                @Nullable Void ignored,
                @Nullable Throwable failure) -> {
            if (failure != null) {
                reportVisibilityFailure(
                        "observe process lifecycle before " + actionName,
                        failure);
            }
            runVisibilityAction(actionName, action);
        });
    }

    /// Runs one post-session visibility action and reports failures without changing the successful session.
    ///
    /// @param actionName diagnostic action name
    /// @param action runtime visibility action
    private static void runVisibilityAction(String actionName, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException | Error failure) {
            reportVisibilityFailure(actionName, failure);
        }
    }

    /// Records a post-session visibility failure that can no longer belong to launch preparation.
    ///
    /// @param actionName diagnostic action name
    /// @param failure runtime or error failure raised by the callback
    private static void reportVisibilityFailure(String actionName, Throwable failure) {
        if (failure instanceof Error) {
            LOG.error("Failed to " + actionName + " after game process creation", failure);
        } else {
            LOG.warning("Failed to " + actionName + " after game process creation", failure);
        }
    }

    /// Defers exact task construction until an initially unloaded repository has completed a fresh scan.
    ///
    /// The refresh task itself is created eagerly but remains unstarted as part of the returned task graph.
    /// A loaded repository bypasses refresh and preserves the exact loaded-task identity.
    ///
    /// @param repositoryLoaded whether the exact repository already has a completed version snapshot
    /// @param refreshTaskSupplier supplier for the exact repository refresh task
    /// @param loadedTaskSupplier task builder that validates the exact instance after readiness
    /// @param continuationExecutor executor used only to obtain the post-refresh task
    /// @param <T> result type preserved by the loaded task
    /// @return unstarted exact task, preceded by a refresh when required
    static <T> Task<T> afterRepositoryReady(
            boolean repositoryLoaded,
            Supplier<Task<?>> refreshTaskSupplier,
            Supplier<Task<T>> loadedTaskSupplier,
            Executor continuationExecutor) {
        Objects.requireNonNull(refreshTaskSupplier, "refreshTaskSupplier");
        Objects.requireNonNull(loadedTaskSupplier, "loadedTaskSupplier");
        Objects.requireNonNull(continuationExecutor, "continuationExecutor");
        if (repositoryLoaded) {
            return Objects.requireNonNull(loadedTaskSupplier.get(), "loadedTaskSupplier returned null");
        }

        Task<?> refreshTask = Objects.requireNonNull(
                refreshTaskSupplier.get(),
                "refreshTaskSupplier returned null");
        return refreshTask.thenComposeAsync(
                continuationExecutor,
                () -> Objects.requireNonNull(
                        loadedTaskSupplier.get(),
                        "loadedTaskSupplier returned null"));
    }

    /// Registers a process policy together with the helper's full listener-completion signal.
    @FunctionalInterface
    @NotNullByDefault
    private interface VisibilityRegistrar {
        /// Registers one created process.
        ///
        /// @param process created managed process
        /// @param visibility captured launcher visibility
        /// @param processLifecycleCompletion completion after listener bookkeeping
        void accept(
                ManagedProcess process,
                LauncherVisibility visibility,
                CompletionStage<@Nullable Void> processLifecycleCompletion);
    }

    /// Pending policy and listener-completion boundary for one exact managed process.
    ///
    /// @param visibility captured launcher visibility
    /// @param processLifecycleCompletion completion after listener bookkeeping
    @NotNullByDefault
    private record RegisteredVisibility(
            LauncherVisibility visibility,
            CompletionStage<@Nullable Void> processLifecycleCompletion) {
        /// Rejects incomplete registrations before they enter the concurrent map.
        private RegisteredVisibility {
            Objects.requireNonNull(visibility, "visibility");
            Objects.requireNonNull(processLifecycleCompletion, "processLifecycleCompletion");
        }
    }
}
