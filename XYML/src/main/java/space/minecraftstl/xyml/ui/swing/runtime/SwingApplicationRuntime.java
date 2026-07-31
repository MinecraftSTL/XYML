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
import space.minecraftstl.xyml.auth.AuthInfo;
import space.minecraftstl.xyml.ui.launch.LaunchInteraction;
import space.minecraftstl.xyml.ui.swing.SystemThemeDetector;
import space.minecraftstl.xyml.ui.swing.application.SwingApplicationCommands;
import space.minecraftstl.xyml.ui.swing.application.SwingApplicationComposition;
import space.minecraftstl.xyml.ui.swing.application.SwingApplicationPresentation;
import space.minecraftstl.xyml.ui.swing.page.accounts.AccountReauthentication;

import java.awt.Component;
import java.util.Objects;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/// Owns the production Swing composition, its launch commands, and final toolkit exit.
///
/// The runtime is the single application-close boundary. Programmatic close, native-window disposal,
/// and launcher-requested close all converge on the same idempotent cleanup sequence. Construction uses
/// a delayed close relay so a native close observed before the runtime is attached is delivered afterward.
@NotNullByDefault
public final class SwingApplicationRuntime implements AutoCloseable {
    /// Swing composition opened by this runtime and closed before command services.
    private final ApplicationLifecycle composition;

    /// Startup command owner closed after the Swing composition.
    private final AutoCloseable commandOwner;

    /// Final toolkit shutdown command attempted after every owned resource.
    private final Runnable toolkitExitCommand;

    /// Prevents repeated application cleanup and toolkit exit.
    private final AtomicBoolean closed = new AtomicBoolean();

    /// Creates one fully attached runtime from already-created collaborators.
    ///
    /// @param composition Swing application lifecycle
    /// @param commandOwner startup command owner
    /// @param toolkitExitCommand final toolkit shutdown command
    private SwingApplicationRuntime(
            ApplicationLifecycle composition,
            AutoCloseable commandOwner,
            Runnable toolkitExitCommand) {
        this.composition = Objects.requireNonNull(composition, "composition");
        this.commandOwner = Objects.requireNonNull(commandOwner, "commandOwner");
        this.toolkitExitCommand = Objects.requireNonNull(toolkitExitCommand, "toolkitExitCommand");
    }

    /// Creates the production Swing runtime after launcher launcher state has been initialized.
    ///
    /// The supplied executor remains caller-owned. Invalid animation timing is rejected before any
    /// owned resource is created. The final toolkit exit command is reserved for a successfully
    /// constructed runtime and is not invoked by construction-failure cleanup.
    ///
    /// @param presentation explicit localized presentation and transition policy
    /// @param addAccountCommand supported account-creation workflow
    /// @param launchInteraction native production launch-decision boundary
    /// @param accountReauthentication credential recovery service transferred to the command owner
    /// @param systemThemeDetector fast operating-system appearance detector
    /// @param animationFrameDelayMillis positive Swing animation timer delay
    /// @param preparationExecutor caller-owned executor for launch preparation
    /// @param toolkitExitCommand final command that exits the remaining UI toolkit runtime
    /// @return fully attached Swing application runtime
    public static SwingApplicationRuntime create(
            SwingApplicationPresentation presentation,
            Runnable addAccountCommand,
            LaunchInteraction launchInteraction,
            AccountReauthentication accountReauthentication,
            SystemThemeDetector systemThemeDetector,
            int animationFrameDelayMillis,
            Executor preparationExecutor,
            Runnable toolkitExitCommand) {
        Objects.requireNonNull(presentation, "presentation");
        Objects.requireNonNull(addAccountCommand, "addAccountCommand");
        Objects.requireNonNull(launchInteraction, "launchInteraction");
        Objects.requireNonNull(accountReauthentication, "accountReauthentication");
        Objects.requireNonNull(systemThemeDetector, "systemThemeDetector");
        Objects.requireNonNull(preparationExecutor, "preparationExecutor");
        Objects.requireNonNull(toolkitExitCommand, "toolkitExitCommand");
        if (animationFrameDelayMillis <= 0) {
            throw new IllegalArgumentException("animationFrameDelayMillis must be positive");
        }

        AccountReauthentication ownedReauthentication =
                new CloseOnceAccountReauthentication(accountReauthentication);
        try {
            return createForCollaborators(
                    visibilityActions -> {
                        SwingApplicationCommandOwner owner = new SwingApplicationCommandOwner(
                                presentation,
                                preparationExecutor,
                                addAccountCommand,
                                visibilityActions,
                                launchInteraction,
                                ownedReauthentication);
                        return new CommandOwnerHandle(owner.commands(), owner);
                    },
                    (commands, closeCommand) -> new CompositionLifecycle(
                            SwingApplicationComposition.createAfterStateInitialization(
                                    presentation,
                                    commands,
                                    systemThemeDetector,
                                    animationFrameDelayMillis,
                                    closeCommand)),
                    toolkitExitCommand);
        } catch (RuntimeException | Error constructionFailure) {
            closeAfterFailure(ownedReauthentication, constructionFailure);
            throw constructionFailure;
        }
    }

    /// Creates a runtime from explicit lifecycle factories without creating native windows.
    ///
    /// Construction first creates the command owner and then the composition. A failure closes every
    /// successfully returned resource in composition-before-command-owner order, suppresses cleanup
    /// failures onto the construction failure, and deliberately leaves the toolkit exit command unused.
    ///
    /// @param commandOwnerFactory command-owner factory receiving the shared close relay
    /// @param compositionFactory composition factory receiving commands and the shared close relay
    /// @param toolkitExitCommand final toolkit shutdown command for successful runtimes only
    /// @return fully attached application runtime
    static SwingApplicationRuntime createForCollaborators(
            CommandOwnerFactory commandOwnerFactory,
            CompositionFactory compositionFactory,
            Runnable toolkitExitCommand) {
        Objects.requireNonNull(commandOwnerFactory, "commandOwnerFactory");
        Objects.requireNonNull(compositionFactory, "compositionFactory");
        Objects.requireNonNull(toolkitExitCommand, "toolkitExitCommand");

        DeferredVisibilityActions visibilityRelay = new DeferredVisibilityActions();
        LaunchVisibilityActions visibilityActions = visibilityRelay.actions();
        @Nullable CommandOwnerHandle commandOwner = null;
        @Nullable ApplicationLifecycle composition = null;
        try {
            commandOwner = Objects.requireNonNull(
                    commandOwnerFactory.create(visibilityActions),
                    "commandOwnerFactory returned null");
            composition = Objects.requireNonNull(
                    compositionFactory.create(commandOwner.commands(), visibilityActions.close()),
                    "compositionFactory returned null");
        } catch (RuntimeException | Error constructionFailure) {
            closeNullableAfterFailure(composition, constructionFailure);
            if (commandOwner != null) {
                closeAfterFailure(commandOwner.resource(), constructionFailure);
            }
            throw constructionFailure;
        }

        SwingApplicationRuntime runtime = new SwingApplicationRuntime(
                composition,
                commandOwner.resource(),
                toolkitExitCommand);
        visibilityRelay.attach(new LaunchVisibilityActions(
                runtime::close,
                runtime::hideIfOpen,
                runtime::showIfOpen));
        return runtime;
    }

    /// Opens the owned Swing composition.
    public void open() {
        if (closed.get()) {
            throw new IllegalStateException("Launcher Swing application runtime is closed");
        }
        composition.open();
    }

    /// Hides the owned Swing composition without releasing it.
    public void hide() {
        if (closed.get()) {
            throw new IllegalStateException("Launcher Swing application runtime is closed");
        }
        composition.hide();
    }

    /// Returns the stable native Swing component used to own application dialogs.
    ///
    /// @return native application window component
    public Component dialogOwner() {
        if (closed.get()) {
            throw new IllegalStateException("Launcher Swing application runtime is closed");
        }
        return composition.dialogOwner();
    }

    /// Enables or disables application interaction without changing visibility.
    ///
    /// @param enabled whether application pages accept user input
    public void setInteractionEnabled(boolean enabled) {
        if (closed.get()) {
            throw new IllegalStateException("Launcher Swing application runtime is closed");
        }
        composition.setInteractionEnabled(enabled);
    }

    /// Returns whether application cleanup has started.
    ///
    /// @return `true` once the first close request wins
    public boolean isClosed() {
        return closed.get();
    }

    /// Closes composition and commands, then exits the remaining toolkit exactly once.
    ///
    /// Every step is attempted even when an earlier step fails. The first failure is rethrown with
    /// later failures suppressed in cleanup order.
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }

        @Nullable Throwable failure = null;
        failure = closeCollecting(composition, failure);
        failure = closeCollecting(commandOwner, failure);
        failure = runCollecting(toolkitExitCommand, failure);
        rethrowFailure(failure);
    }

    /// Hides the application only while its close transition has not started.
    ///
    /// No runtime monitor is held across the composition call because it may synchronously wait for
    /// the Swing EDT, whose native-close callback can concurrently enter [#close()]. A close race is
    /// resolved by the composition's own closed state and EDT serialization.
    private void hideIfOpen() {
        if (!closed.get()) {
            try {
                composition.hide();
            } catch (IllegalStateException failure) {
                if (!closed.get()) {
                    throw failure;
                }
            }
        }
    }

    /// Shows the application only while its close transition has not started.
    ///
    /// A close that wins after the initial check either closes the newly shown window or makes the
    /// composition reject this request; the latter race is ignored only after runtime closure is visible.
    private void showIfOpen() {
        if (!closed.get()) {
            try {
                composition.open();
            } catch (IllegalStateException failure) {
                if (!closed.get()) {
                    throw failure;
                }
            }
        }
    }

    /// Closes one nullable partially constructed resource after factory failure.
    ///
    /// @param resource resource returned before construction failed, or null
    /// @param constructionFailure original construction failure
    private static void closeNullableAfterFailure(
            @Nullable AutoCloseable resource,
            Throwable constructionFailure) {
        if (resource != null) {
            closeAfterFailure(resource, constructionFailure);
        }
    }

    /// Closes one partially constructed resource and suppresses any failure onto the original failure.
    ///
    /// @param resource resource returned before construction failed
    /// @param constructionFailure original construction failure
    private static void closeAfterFailure(AutoCloseable resource, Throwable constructionFailure) {
        try {
            resource.close();
        } catch (Throwable closingFailure) {
            if (constructionFailure != closingFailure) {
                constructionFailure.addSuppressed(closingFailure);
            }
        }
    }

    /// Closes one runtime resource while retaining the first failure.
    ///
    /// @param resource resource to close
    /// @param previous first earlier failure, or null
    /// @return first failure with any later failure suppressed
    private static @Nullable Throwable closeCollecting(
            AutoCloseable resource,
            @Nullable Throwable previous) {
        try {
            resource.close();
            return previous;
        } catch (Throwable current) {
            return accumulateFailure(previous, current);
        }
    }

    /// Runs one final cleanup command while retaining the first failure.
    ///
    /// @param command cleanup command
    /// @param previous first earlier failure, or null
    /// @return first failure with any later failure suppressed
    private static @Nullable Throwable runCollecting(
            Runnable command,
            @Nullable Throwable previous) {
        try {
            command.run();
            return previous;
        } catch (Throwable current) {
            return accumulateFailure(previous, current);
        }
    }

    /// Appends a later cleanup failure to the first failure.
    ///
    /// @param previous first earlier failure, or null
    /// @param current next cleanup failure
    /// @return first cleanup failure with later failures suppressed
    private static Throwable accumulateFailure(@Nullable Throwable previous, Throwable current) {
        Objects.requireNonNull(current, "current");
        if (previous == null) {
            return current;
        }
        if (previous != current) {
            previous.addSuppressed(current);
        }
        return previous;
    }

    /// Rethrows one accumulated cleanup failure without erasing unchecked types.
    ///
    /// @param failure accumulated cleanup failure, or null for successful cleanup
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
        throw new IllegalStateException("Failed to close launcher Swing application runtime", failure);
    }

    /// Minimal visibility and close lifecycle used to isolate runtime tests from native windows.
    @NotNullByDefault
    interface ApplicationLifecycle extends AutoCloseable {
        /// Opens the application surface.
        void open();

        /// Hides the application surface without releasing it.
        void hide();

        /// Returns the stable native dialog owner.
        ///
        /// @return application window component
        Component dialogOwner();

        /// Enables or disables application interaction.
        ///
        /// @param enabled whether application pages accept user input
        void setInteractionEnabled(boolean enabled);

        /// Closes the application surface.
        @Override
        void close();
    }

    /// Creates one owned command boundary around shared runtime visibility actions.
    @FunctionalInterface
    @NotNullByDefault
    interface CommandOwnerFactory {
        /// Creates commands and their owning resource.
        ///
        /// @param visibilityActions shared close, hide, and show relays
        /// @return owned application commands
        CommandOwnerHandle create(LaunchVisibilityActions visibilityActions);
    }

    /// Creates the Swing composition around startup-owned commands and the shared close relay.
    @FunctionalInterface
    @NotNullByDefault
    interface CompositionFactory {
        /// Creates one application lifecycle.
        ///
        /// @param commands startup-owned application commands
        /// @param closeCommand shared idempotent application-close relay
        /// @return owned composition lifecycle
        ApplicationLifecycle create(SwingApplicationCommands commands, Runnable closeCommand);
    }

    /// Pairs commands with the resource that owns their active services.
    ///
    /// @param commands commands supplied to the Swing composition
    /// @param resource owner closed after the Swing composition
    @NotNullByDefault
    record CommandOwnerHandle(SwingApplicationCommands commands, AutoCloseable resource) {
        /// Validates both halves of the owned command boundary.
        CommandOwnerHandle {
            Objects.requireNonNull(commands, "commands");
            Objects.requireNonNull(resource, "resource");
        }
    }

    /// Adapts the concrete production composition to the focused runtime lifecycle contract.
    @NotNullByDefault
    private static final class CompositionLifecycle implements ApplicationLifecycle {
        /// Concrete production Swing composition.
        private final SwingApplicationComposition composition;

        /// Creates a production lifecycle adapter.
        ///
        /// @param composition concrete Swing composition
        private CompositionLifecycle(SwingApplicationComposition composition) {
            this.composition = Objects.requireNonNull(composition, "composition");
        }

        /// Opens the concrete Swing composition.
        @Override
        public void open() {
            composition.open();
        }

        /// Hides the concrete Swing composition without releasing it.
        @Override
        public void hide() {
            composition.hide();
        }

        /// Returns the production composition's stable dialog owner.
        ///
        /// @return native application window component
        @Override
        public Component dialogOwner() {
            return composition.dialogOwner();
        }

        /// Enables or disables production composition interaction.
        ///
        /// @param enabled whether application pages accept user input
        @Override
        public void setInteractionEnabled(boolean enabled) {
            composition.setInteractionEnabled(enabled);
        }

        /// Closes the concrete Swing composition.
        @Override
        public void close() {
            composition.close();
        }
    }

    /// Transfers one reauthentication service through nested construction boundaries with exactly-once close.
    @NotNullByDefault
    private static final class CloseOnceAccountReauthentication implements AccountReauthentication {
        /// Caller-supplied credential recovery service.
        private final AccountReauthentication delegate;

        /// Prevents construction cleanup and the completed owner from closing the delegate twice.
        private final AtomicBoolean closed = new AtomicBoolean();

        /// Creates an ownership wrapper around one caller-supplied service.
        ///
        /// @param delegate credential recovery service whose ownership is transferred
        private CloseOnceAccountReauthentication(AccountReauthentication delegate) {
            this.delegate = Objects.requireNonNull(delegate, "delegate");
        }

        /// Delegates one stable-ID credential recovery while this ownership boundary remains open.
        ///
        /// @param accountId stable persisted account identifier
        /// @return delegated authentication completion
        @Override
        public CompletionStage<AuthInfo> reauthenticate(String accountId) {
            if (closed.get()) {
                throw new IllegalStateException("Account reauthentication owner is closed");
            }
            return delegate.reauthenticate(accountId);
        }

        /// Closes the transferred service exactly once.
        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                delegate.close();
            }
        }
    }

    /// Queues visibility actions until their fully constructed runtime target is attached.
    @NotNullByDefault
    private static final class DeferredVisibilityActions {
        /// Actions requested before runtime attachment, retained in request order.
        private final List<Consumer<LaunchVisibilityActions>> pendingActions = new ArrayList<>();

        /// Runtime actions attached after composition construction, or null beforehand.
        private @Nullable LaunchVisibilityActions target;

        /// Whether attachment is draining actions that must precede newly submitted work.
        private boolean draining;

        /// Creates an unattached visibility relay.
        private DeferredVisibilityActions() {
        }

        /// Returns stable relay actions safe to pass into command construction.
        ///
        /// @return close, hide, and show relays
        private LaunchVisibilityActions actions() {
            return new LaunchVisibilityActions(
                    () -> request(actions -> actions.close().run()),
                    () -> request(actions -> actions.hide().run()),
                    () -> request(actions -> actions.show().run()));
        }

        /// Attaches the sole runtime target and drains earlier actions in their original order.
        ///
        /// @param visibilityActions completed runtime actions
        private void attach(LaunchVisibilityActions visibilityActions) {
            Objects.requireNonNull(visibilityActions, "visibilityActions");
            synchronized (this) {
                if (target != null) {
                    throw new IllegalStateException("Visibility relay target is already attached");
                }
                target = visibilityActions;
                draining = true;
            }
            drainPendingActions(visibilityActions);
        }

        /// Runs one action immediately after attachment or queues it beforehand.
        ///
        /// @param action action selecting one command from the attached runtime boundary
        private void request(Consumer<LaunchVisibilityActions> action) {
            Objects.requireNonNull(action, "action");
            final LaunchVisibilityActions current;
            synchronized (this) {
                current = target;
                if (current == null || draining) {
                    pendingActions.add(action);
                    return;
                }
            }
            action.accept(current);
        }

        /// Drains pre-attachment and reentrant actions without holding the relay monitor.
        ///
        /// @param visibilityActions attached runtime target
        private void drainPendingActions(LaunchVisibilityActions visibilityActions) {
            try {
                while (true) {
                    final List<Consumer<LaunchVisibilityActions>> batch;
                    synchronized (this) {
                        if (pendingActions.isEmpty()) {
                            draining = false;
                            return;
                        }
                        batch = new ArrayList<>(pendingActions);
                        pendingActions.clear();
                    }
                    for (Consumer<LaunchVisibilityActions> pendingAction : batch) {
                        pendingAction.accept(visibilityActions);
                    }
                }
            } catch (RuntimeException | Error failure) {
                synchronized (this) {
                    draining = false;
                }
                throw failure;
            }
        }
    }
}
