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
import space.minecraftstl.xyml.game.launch.DefaultGameLaunchService;
import space.minecraftstl.xyml.game.launch.GameLaunchService;
import space.minecraftstl.xyml.game.launch.LaunchSession;
import space.minecraftstl.xyml.ui.launch.LaunchInteraction;
import space.minecraftstl.xyml.ui.swing.application.SwingApplicationCommands;
import space.minecraftstl.xyml.ui.swing.application.SwingApplicationPresentation;
import space.minecraftstl.xyml.ui.swing.page.accounts.AccountRefreshCommand;
import space.minecraftstl.xyml.ui.swing.page.accounts.AccountReauthentication;
import space.minecraftstl.xyml.ui.swing.page.home.HomeLaunchScriptExportCommand;

import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/// Owns the production launch service and exposes its workflows to the Swing composition.
///
/// The caller retains ownership of the preparation executor and account-creation command. Closing this
/// owner cancels launch preparation, local script export, and credential recovery, releases pending visibility
/// policies, and permanently rejects later launch commands through the owned service.
@NotNullByDefault
public final class SwingApplicationCommandOwner implements AutoCloseable {
    /// Launch preparation service exclusively owned by this command owner.
    private final GameLaunchService gameLaunchService;

    /// Factory lifecycle that owns pending process-visibility registrations.
    private final AutoCloseable visibilityPolicyOwner;

    /// Service lifecycle that owns an active local launch-script export, if any.
    private final AutoCloseable launchScriptExportOwner;

    /// Credential recovery service cancelled and released with the command runtime.
    private final AutoCloseable accountReauthenticationOwner;

    /// Stable command boundary retained by the Swing composition.
    private final SwingApplicationCommands commands;

    /// Ensures the owned service receives exactly one close request.
    private final AtomicBoolean closed = new AtomicBoolean();

    /// Creates the production command owner around the launcher stable-ID task adapter.
    ///
    /// @param presentation startup-selected localized presentation
    /// @param preparationExecutor caller-owned executor for asynchronous launch preparation
    /// @param addAccountCommand command that opens the supported account-creation workflow
    /// @param visibilityActions thread-safe runtime visibility actions passed to the launch adapter
    /// @param launchInteraction native production launch-decision boundary
    /// @param accountReauthentication stable-ID credential recovery service transferred to this owner
    public SwingApplicationCommandOwner(
            SwingApplicationPresentation presentation,
            Executor preparationExecutor,
            Runnable addAccountCommand,
            LaunchVisibilityActions visibilityActions,
            LaunchInteraction launchInteraction,
            AccountReauthentication accountReauthentication) {
        Objects.requireNonNull(addAccountCommand, "addAccountCommand");
        Objects.requireNonNull(accountReauthentication, "accountReauthentication");
        @Nullable ProductionLaunchBoundary boundary = null;
        @Nullable LaunchScriptExportService launchScriptExportService = null;
        try {
            boundary = createProductionBoundary(
                    presentation,
                    preparationExecutor,
                    addAccountCommand,
                    visibilityActions,
                    launchInteraction,
                    accountReauthentication);
            gameLaunchService = boundary.gameLaunchService();
            visibilityPolicyOwner = boundary.taskFactory();
            launchScriptExportService = new LaunchScriptExportService(
                    boundary.taskFactory(),
                    preparationExecutor);
            launchScriptExportOwner = launchScriptExportService;
            accountReauthenticationOwner = accountReauthentication;
            commands = createCommands(
                    gameLaunchService,
                    addAccountCommand,
                    AccountRefreshCommand.from(accountReauthentication),
                    boundary.taskFactory()::observeCompletion,
                    launchScriptExportService::export);
        } catch (RuntimeException | Error failure) {
            closeAfterConstructionFailure(launchScriptExportService, failure);
            closeBoundaryAfterConstructionFailure(boundary, failure);
            closeAfterConstructionFailure(accountReauthentication, failure);
            throw failure;
        }
    }

    /// Creates an owner around an explicitly supplied launch service for focused tests.
    ///
    /// @param gameLaunchService service exclusively owned by this owner
    /// @param addAccountCommand externally implemented account-creation command
    SwingApplicationCommandOwner(
            GameLaunchService gameLaunchService,
            Runnable addAccountCommand) {
        this(gameLaunchService, addAccountCommand, ignored -> { }, () -> { }, () -> { });
    }

    /// Creates an owner around explicit service, completion, and policy-lifecycle collaborators.
    ///
    /// @param gameLaunchService service exclusively owned by this owner
    /// @param addAccountCommand externally implemented account-creation command
    /// @param completionObserver observer registered on every exact returned launch session
    /// @param visibilityPolicyOwner owner of pending process-visibility registrations
    /// @param accountReauthenticationOwner credential recovery lifecycle owned by this command boundary
    SwingApplicationCommandOwner(
            GameLaunchService gameLaunchService,
            Runnable addAccountCommand,
            Consumer<LaunchSession> completionObserver,
            AutoCloseable visibilityPolicyOwner,
            AutoCloseable accountReauthenticationOwner) {
        this.gameLaunchService = Objects.requireNonNull(gameLaunchService, "gameLaunchService");
        this.visibilityPolicyOwner = Objects.requireNonNull(visibilityPolicyOwner, "visibilityPolicyOwner");
        this.launchScriptExportOwner = () -> { };
        this.accountReauthenticationOwner = Objects.requireNonNull(
                accountReauthenticationOwner,
                "accountReauthenticationOwner");
        this.commands = createCommands(
                this.gameLaunchService,
                addAccountCommand,
                AccountRefreshCommand.unavailable(),
                completionObserver,
                HomeLaunchScriptExportCommand.unavailable());
    }

    /// Returns the stable commands supplied to the Swing composition.
    ///
    /// @return commands backed by the owned launch service and supplied account workflow
    public SwingApplicationCommands commands() {
        return commands;
    }

    /// Closes the owned launch service once without closing the caller-owned executor.
    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            @Nullable Throwable failure = null;
            try {
                launchScriptExportOwner.close();
            } catch (Throwable exportFailure) {
                failure = exportFailure;
            }
            try {
                gameLaunchService.close();
            } catch (Throwable serviceFailure) {
                if (failure == null) {
                    failure = serviceFailure;
                } else if (failure != serviceFailure) {
                    failure.addSuppressed(serviceFailure);
                }
            }
            try {
                visibilityPolicyOwner.close();
            } catch (Throwable policyFailure) {
                if (failure == null) {
                    failure = policyFailure;
                } else if (failure != policyFailure) {
                    failure.addSuppressed(policyFailure);
                }
            }
            try {
                accountReauthenticationOwner.close();
            } catch (Throwable reauthenticationFailure) {
                if (failure == null) {
                    failure = reauthenticationFailure;
                } else if (failure != reauthenticationFailure) {
                    failure.addSuppressed(reauthenticationFailure);
                }
            }
            rethrowFailure(failure);
        }
    }

    /// Creates the production launch service with presentation text selected by startup.
    ///
    /// @param presentation startup-selected localized presentation
    /// @param preparationExecutor caller-owned asynchronous preparation executor
    /// @param addAccountCommand account command validated before allocating owned launch resources
    /// @param visibilityActions thread-safe runtime visibility actions used by the task adapter
    /// @param launchInteraction native production launch-decision boundary
    /// @param accountReauthentication credential recovery service transferred to the returned owner
    /// @return complete production service and task-factory boundary
    private static ProductionLaunchBoundary createProductionBoundary(
            SwingApplicationPresentation presentation,
            Executor preparationExecutor,
            Runnable addAccountCommand,
            LaunchVisibilityActions visibilityActions,
            LaunchInteraction launchInteraction,
            AccountReauthentication accountReauthentication) {
        Objects.requireNonNull(presentation, "presentation");
        Objects.requireNonNull(preparationExecutor, "preparationExecutor");
        Objects.requireNonNull(addAccountCommand, "addAccountCommand");
        Objects.requireNonNull(visibilityActions, "visibilityActions");
        Objects.requireNonNull(launchInteraction, "launchInteraction");
        Objects.requireNonNull(accountReauthentication, "accountReauthentication");
        @Nullable LauncherLaunchTaskFactory taskFactory = null;
        try {
            taskFactory = new LauncherLaunchTaskFactory(
                    visibilityActions,
                    launchInteraction,
                    accountReauthentication);
            GameLaunchService gameLaunchService = new DefaultGameLaunchService(
                    taskFactory,
                    preparationExecutor,
                    presentation.home().launchAction(),
                    presentation.taskProgress().waitingStatus());
            return new ProductionLaunchBoundary(gameLaunchService, taskFactory);
        } catch (RuntimeException | Error failure) {
            closeAfterConstructionFailure(taskFactory, failure);
            throw failure;
        }
    }

    /// Creates the stable application commands around one launch service and completion observer.
    ///
    /// @param gameLaunchService service receiving exact launch requests
    /// @param addAccountCommand externally owned account-creation command
    /// @param refreshAccountCommand caller-owned account refresh command
    /// @param completionObserver observer attached to every exact returned launch session
    /// @param launchScriptExportCommand local script-export command sharing the launcher launch preparation chain
    /// @return stable Swing command boundary
    private static SwingApplicationCommands createCommands(
            GameLaunchService gameLaunchService,
            Runnable addAccountCommand,
            AccountRefreshCommand refreshAccountCommand,
            Consumer<LaunchSession> completionObserver,
            HomeLaunchScriptExportCommand launchScriptExportCommand) {
        Objects.requireNonNull(gameLaunchService, "gameLaunchService");
        Objects.requireNonNull(refreshAccountCommand, "refreshAccountCommand");
        Objects.requireNonNull(completionObserver, "completionObserver");
        Objects.requireNonNull(launchScriptExportCommand, "launchScriptExportCommand");
        return new SwingApplicationCommands(
                Objects.requireNonNull(addAccountCommand, "addAccountCommand"),
                refreshAccountCommand,
                request -> {
                    LaunchSession session = gameLaunchService.launch(request);
                    completionObserver.accept(session);
                    return session;
                },
                launchScriptExportCommand);
    }

    /// Closes a fully created production boundary after later owner construction fails.
    ///
    /// @param boundary created service and factory, or null when creation itself failed
    /// @param constructionFailure original owner construction failure
    private static void closeBoundaryAfterConstructionFailure(
            @Nullable ProductionLaunchBoundary boundary,
            Throwable constructionFailure) {
        if (boundary == null) {
            return;
        }
        closeAfterConstructionFailure(boundary.gameLaunchService(), constructionFailure);
        closeAfterConstructionFailure(boundary.taskFactory(), constructionFailure);
    }

    /// Closes one partially owned resource and suppresses cleanup failure onto construction failure.
    ///
    /// @param resource partially owned resource, or null before allocation
    /// @param constructionFailure original construction failure
    private static void closeAfterConstructionFailure(
            @Nullable AutoCloseable resource,
            Throwable constructionFailure) {
        if (resource == null) {
            return;
        }
        try {
            resource.close();
        } catch (Throwable cleanupFailure) {
            if (cleanupFailure != constructionFailure) {
                constructionFailure.addSuppressed(cleanupFailure);
            }
        }
    }

    /// Rethrows one aggregated close failure without erasing unchecked types.
    ///
    /// @param failure first close failure with later failures suppressed, or null
    private static void rethrowFailure(@Nullable Throwable failure) {
        if (failure == null) {
            return;
        }
        if (failure instanceof RuntimeException runtimeException) {
            throw runtimeException;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        throw new IllegalStateException("Failed to close launcher Swing launch commands", failure);
    }

    /// Pairs the launch service with the exact factory that owns its deferred visibility policies.
    ///
    /// @param gameLaunchService launch service owned by the command owner
    /// @param taskFactory task factory and deferred policy owner
    @NotNullByDefault
    private record ProductionLaunchBoundary(
            GameLaunchService gameLaunchService,
            LauncherLaunchTaskFactory taskFactory) {
        /// Validates both halves of the production launch boundary.
        private ProductionLaunchBoundary {
            Objects.requireNonNull(gameLaunchService, "gameLaunchService");
            Objects.requireNonNull(taskFactory, "taskFactory");
        }
    }
}
