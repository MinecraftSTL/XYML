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
package space.minecraftstl.xyml.ui.swing.page.home;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import space.minecraftstl.xyml.game.launch.LaunchRequest;
import space.minecraftstl.xyml.game.launch.LaunchSession;
import space.minecraftstl.xyml.game.launch.LaunchStatus;
import space.minecraftstl.xyml.observable.Subscription;
import space.minecraftstl.xyml.observable.ValueChange;
import space.minecraftstl.xyml.observable.ValueChangeListener;
import space.minecraftstl.xyml.observable.property.ReadOnlyProperty;
import space.minecraftstl.xyml.util.Lang;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;

/// Maps launcher selections and one launch session to toolkit-neutral home presentation state.
///
/// The three launch identifiers are captured together while holding [#stateLock], so a later selection-store update
/// cannot change the request already sent to [HomeLaunchCommand]. The model owns only its selection and session-status
/// subscriptions. The composition root retains ownership of the command's underlying launch service.
@NotNullByDefault
public final class LauncherHomeModel implements HomeModel, AutoCloseable {
    /// Serializes selections, launch invocation, session replacement, subscriptions, and closure.
    private final Object stateLock = new Object();

    /// Serializes observable publication with the close barrier without invoking listeners under [#stateLock].
    private final Object publicationLock = new Object();

    /// Selected account and instance state store.
    private final HomeSelectionStore selectionStore;

    /// Localized readiness status text.
    private final HomeStatusStrings statusStrings;

    /// Account-page navigation command.
    private final Runnable selectAccountCommand;

    /// Instance-page navigation command.
    private final Runnable selectInstanceCommand;

    /// New-instance workflow command.
    private final Runnable addInstanceCommand;

    /// Session-producing launch command invoked with a captured immutable request.
    private final HomeLaunchCommand launchCommand;

    /// Background launch-script export command invoked with a captured immutable request.
    private final HomeLaunchScriptExportCommand launchScriptExportCommand;

    /// Whether a script export has captured the current selection and has not reached a terminal outcome.
    private boolean launchScriptExportPending;

    /// Home-state listeners isolated per registration from runtime failures.
    private final CopyOnWriteArrayList<IsolatedListenerSlot<HomeSnapshot>> homeListeners =
            new CopyOnWriteArrayList<>();

    /// Latest session property retained through terminal presentation until the next launch.
    private final LaunchSessionProperty launchSessionProperty = new LaunchSessionProperty();

    /// Owned selection-store subscription.
    private final Subscription selectionSubscription;

    /// Latest complete selection identity and presentation.
    private HomeSelectionState currentSelection;

    /// Latest mapped home state.
    private volatile HomeSnapshot currentSnapshot;

    /// Monotonic commit revision used to discard state invalidations superseded before publication.
    private long snapshotRevision;

    /// Latest launch session, including its terminal state, or null before the first successful invocation.
    private @Nullable LaunchSession currentLaunchSession;

    /// Owned status subscription for the currently preparing session, or null outside preparation.
    private @Nullable Subscription launchStatusSubscription;

    /// Whether an external launch command is currently returning its session.
    private boolean launchInvocationPending;

    /// Whether the model has released its owned subscriptions.
    private volatile boolean closed;

    /// Creates a launcher home model.
    ///
    /// @param selectionStore account and instance state store
    /// @param statusStrings localized readiness text
    /// @param selectAccountCommand account navigation command
    /// @param selectInstanceCommand instance navigation command
    /// @param addInstanceCommand add-instance workflow command
    /// @param launchCommand command creating a session from captured stable identifiers
    /// @param launchScriptExportCommand command writing a script from captured stable identifiers
    public LauncherHomeModel(
            HomeSelectionStore selectionStore,
            HomeStatusStrings statusStrings,
            Runnable selectAccountCommand,
            Runnable selectInstanceCommand,
            Runnable addInstanceCommand,
            HomeLaunchCommand launchCommand,
            HomeLaunchScriptExportCommand launchScriptExportCommand) {
        this.selectionStore = Objects.requireNonNull(selectionStore, "selectionStore");
        this.statusStrings = Objects.requireNonNull(statusStrings, "statusStrings");
        this.selectAccountCommand = Objects.requireNonNull(selectAccountCommand, "selectAccountCommand");
        this.selectInstanceCommand = Objects.requireNonNull(selectInstanceCommand, "selectInstanceCommand");
        this.addInstanceCommand = Objects.requireNonNull(addInstanceCommand, "addInstanceCommand");
        this.launchCommand = Objects.requireNonNull(launchCommand, "launchCommand");
        this.launchScriptExportCommand = Objects.requireNonNull(
                launchScriptExportCommand,
                "launchScriptExportCommand");
        currentSelection = Objects.requireNonNull(selectionStore.snapshot(), "selectionStore snapshot");
        currentSnapshot = map(currentSelection);
        selectionSubscription = selectionStore.subscribe(this::selectionChanged);
        reconcileSelectionStore();
    }

    /// Returns the latest mapped home state.
    @Override
    public HomeSnapshot snapshot() {
        return currentSnapshot;
    }

    /// Registers a mapped home-state listener.
    @Override
    public Subscription subscribe(ValueChangeListener<HomeSnapshot> listener) {
        Objects.requireNonNull(listener, "listener");
        synchronized (stateLock) {
            requireOpen();
            IsolatedListenerSlot<HomeSnapshot> slot = new IsolatedListenerSlot<>(listener);
            homeListeners.add(slot);
            return Subscription.create(() -> homeListeners.remove(slot));
        }
    }

    /// Returns the latest optional launch session for task presentation.
    @Override
    public ReadOnlyProperty<Optional<LaunchSession>> launchSessionProperty() {
        return launchSessionProperty;
    }

    /// Opens account selection only while launch preparation permits selection changes.
    @Override
    public void selectAccount() {
        runSelectionCommand(selectAccountCommand);
    }

    /// Opens instance selection only while launch preparation permits selection changes.
    @Override
    public void selectInstance() {
        runSelectionCommand(selectInstanceCommand);
    }

    /// Opens new-instance creation only while launch preparation permits selection changes.
    @Override
    public void addInstance() {
        runSelectionCommand(addInstanceCommand);
    }

    /// Captures stable selection IDs and starts at most one preparing session.
    ///
    /// The external command is invoked without [#stateLock]. A synchronous terminal session is reconciled after its
    /// status listener is installed, so rejected scheduling and other immediate outcomes cannot leave the home page
    /// in a false launching state.
    @Override
    public void launch() {
        LaunchRequest request;
        @Nullable SnapshotTransition startingTransition;
        synchronized (stateLock) {
            requireOpen();
            if (!currentSnapshot.launchEnabled() || launchInvocationPending || launchScriptExportPending) {
                return;
            }
            launchInvocationPending = true;
            request = new LaunchRequest(
                    currentSelection.accountId(),
                    currentSelection.gameDirectoryId(),
                    Objects.requireNonNull(currentSelection.instanceId(), "selected instance"));
            startingTransition = replaceSnapshotLocked(map(currentSelection));
        }

        try {
            publishTransition(startingTransition);
        } catch (RuntimeException | Error publicationFailure) {
            @Nullable SnapshotTransition rollbackTransition = rollbackPendingInvocation();
            throw propagate(combineWithPublication(publicationFailure, rollbackTransition));
        }

        LaunchSession session;
        try {
            session = Objects.requireNonNull(
                    launchCommand.launch(request), "launch command returned null session");
        } catch (RuntimeException | Error launchFailure) {
            @Nullable SnapshotTransition rollbackTransition = rollbackPendingInvocation();
            throw propagate(combineWithPublication(launchFailure, rollbackTransition));
        }

        installLaunchSession(session);
    }

    /// Captures stable selection IDs and delegates standalone script generation without changing launch-session state.
    ///
    /// The pending marker owns the same readiness gate as ordinary launch preparation. It prevents both another
    /// script export and a game launch from racing account authentication or dependency resolution for this selection.
    ///
    /// @param scriptFile local destination selected by the native Swing interaction
    /// @return completion stage yielding the exact written script path
    @Override
    public CompletionStage<Path> exportLaunchScript(Path scriptFile) {
        Path destination = Objects.requireNonNull(scriptFile, "scriptFile");
        LaunchRequest request;
        @Nullable SnapshotTransition startingTransition;
        synchronized (stateLock) {
            requireOpen();
            if (!currentSnapshot.launchEnabled() || launchInvocationPending || launchScriptExportPending) {
                return CompletableFuture.failedFuture(
                        new IllegalStateException("A launch-ready account and instance are required"));
            }
            launchScriptExportPending = true;
            request = new LaunchRequest(
                    currentSelection.accountId(),
                    currentSelection.gameDirectoryId(),
                    Objects.requireNonNull(currentSelection.instanceId(), "selected instance"));
            startingTransition = replaceSnapshotLocked(map(currentSelection));
        }

        try {
            publishTransition(startingTransition);
        } catch (RuntimeException | Error publicationFailure) {
            @Nullable SnapshotTransition rollbackTransition = rollbackLaunchScriptExport();
            throw propagate(combineWithPublication(publicationFailure, rollbackTransition));
        }

        final CompletionStage<Path> completion;
        try {
            completion = Objects.requireNonNull(
                    launchScriptExportCommand.export(request, destination),
                    "launch-script export command returned null stage");
        } catch (RuntimeException | Error commandFailure) {
            @Nullable SnapshotTransition rollbackTransition = rollbackLaunchScriptExport();
            return CompletableFuture.failedFuture(combineWithPublication(commandFailure, rollbackTransition));
        }
        completion.whenComplete((@Nullable Path ignored, @Nullable Throwable failure) -> completeLaunchScriptExport());
        return completion;
    }

    /// Releases selection and session-status subscriptions, then cancels preparation owned by the current session.
    @Override
    public void close() {
        @Nullable Subscription statusSubscription;
        @Nullable LaunchSession session;
        synchronized (stateLock) {
            if (closed) {
                return;
            }
            closed = true;
            launchInvocationPending = false;
            launchScriptExportPending = false;
            statusSubscription = launchStatusSubscription;
            launchStatusSubscription = null;
            session = currentLaunchSession;
            currentLaunchSession = null;
        }

        awaitPublicationBarrier();
        homeListeners.clear();
        launchSessionProperty.clearSubscribers();

        @Nullable Throwable failure = null;
        failure = attempt(failure, selectionSubscription::unsubscribe);
        failure = attempt(failure, () -> unsubscribe(statusSubscription));
        if (session != null && session.status() == LaunchStatus.PREPARING) {
            failure = attempt(failure, session::cancel);
        }
        rethrowFailure(failure);
    }

    /// Runs one navigation or creation command only when selection controls are enabled.
    ///
    /// @param command command to run without holding the model lock
    private void runSelectionCommand(Runnable command) {
        synchronized (stateLock) {
            requireOpen();
            if (!currentSnapshot.selectionCommandsEnabled() || launchInvocationPending || launchScriptExportPending) {
                return;
            }
        }
        command.run();
    }

    /// Installs one returned session, its status listener, and both observable model transitions.
    ///
    /// @param session session returned by the launch command
    private void installLaunchSession(LaunchSession session) {
        @Nullable Subscription previousStatusSubscription;
        boolean rejectedByClose;
        synchronized (stateLock) {
            launchInvocationPending = false;
            rejectedByClose = closed;
            if (rejectedByClose) {
                previousStatusSubscription = null;
            } else {
                previousStatusSubscription = launchStatusSubscription;
                launchStatusSubscription = null;
                currentLaunchSession = session;
            }
        }

        unsubscribe(previousStatusSubscription);
        if (rejectedByClose) {
            session.cancel();
            return;
        }

        Subscription statusSubscription;
        try {
            statusSubscription = session.statusProperty().subscribe(
                    change -> launchStatusChanged(session, change));
        } catch (RuntimeException | Error bindingFailure) {
            throw propagate(abandonSessionAfterBindingFailure(session, bindingFailure));
        }

        boolean sessionStillOwned;
        boolean retainStatusSubscription;
        synchronized (stateLock) {
            sessionStillOwned = !closed && currentLaunchSession == session;
            retainStatusSubscription = sessionStillOwned
                    && session.status() == LaunchStatus.PREPARING;
            if (retainStatusSubscription) {
                launchStatusSubscription = statusSubscription;
            }
        }
        if (!retainStatusSubscription) {
            statusSubscription.unsubscribe();
        }
        if (!sessionStillOwned) {
            return;
        }

        @Nullable Throwable publicationFailure = null;
        publicationFailure = attempt(
                publicationFailure,
                () -> publishLaunchSession(session));
        publicationFailure = attempt(publicationFailure, () -> reconcileLaunchSession(session));
        rethrowFailure(publicationFailure);
    }

    /// Removes a session whose status listener could not be installed and restores launch readiness.
    ///
    /// @param session session that failed binding
    /// @param bindingFailure primary binding failure
    /// @return combined unchecked failure with cleanup diagnostics retained
    private Throwable abandonSessionAfterBindingFailure(LaunchSession session, Throwable bindingFailure) {
        @Nullable SnapshotTransition rollbackTransition;
        synchronized (stateLock) {
            if (currentLaunchSession == session) {
                currentLaunchSession = null;
            }
            rollbackTransition = closed ? null : replaceSnapshotLocked(map(currentSelection));
        }

        @Nullable Throwable combinedFailure = bindingFailure;
        combinedFailure = attempt(combinedFailure, session::cancel);
        combinedFailure = attempt(combinedFailure, () -> publishTransition(rollbackTransition));
        return Objects.requireNonNull(combinedFailure, "binding failure was lost");
    }

    /// Applies a status invalidation only when it belongs to the currently installed session.
    ///
    /// @param session session whose status changed
    /// @param change status transition that invalidated the home state
    private void launchStatusChanged(LaunchSession session, ValueChange<LaunchStatus> change) {
        Objects.requireNonNull(change, "change");
        reconcileLaunchSession(session);
    }

    /// Re-reads one session after subscription to close the snapshot-before-subscribe race.
    ///
    /// @param session session to reconcile by identity
    private void reconcileLaunchSession(LaunchSession session) {
        @Nullable Subscription terminalSubscription = null;
        @Nullable SnapshotTransition transition;
        synchronized (stateLock) {
            if (closed || currentLaunchSession != session) {
                return;
            }
            transition = replaceSnapshotLocked(map(currentSelection));
            if (session.status() != LaunchStatus.PREPARING) {
                terminalSubscription = launchStatusSubscription;
                launchStatusSubscription = null;
            }
        }
        final @Nullable Subscription subscriptionToRelease = terminalSubscription;
        @Nullable Throwable reconciliationFailure = null;
        reconciliationFailure = attempt(
                reconciliationFailure,
                () -> unsubscribe(subscriptionToRelease));
        reconciliationFailure = attempt(
                reconciliationFailure,
                () -> publishTransition(transition));
        rethrowFailure(reconciliationFailure);
    }

    /// Clears the in-flight command marker and restores state when no session was installed.
    ///
    /// @return restored snapshot transition, or null after closure or when no invocation was pending
    private @Nullable SnapshotTransition rollbackPendingInvocation() {
        synchronized (stateLock) {
            if (!launchInvocationPending) {
                return null;
            }
            launchInvocationPending = false;
            return closed ? null : replaceSnapshotLocked(map(currentSelection));
        }
    }

    /// Clears the script-export marker after synchronous command failure and restores normal readiness.
    ///
    /// @return restored snapshot transition, or null after closure or when no script export was pending
    private @Nullable SnapshotTransition rollbackLaunchScriptExport() {
        synchronized (stateLock) {
            if (!launchScriptExportPending) {
                return null;
            }
            launchScriptExportPending = false;
            return closed ? null : replaceSnapshotLocked(map(currentSelection));
        }
    }

    /// Clears the script-export marker after either successful or failed asynchronous completion.
    private void completeLaunchScriptExport() {
        @Nullable SnapshotTransition transition = rollbackLaunchScriptExport();
        publishTransition(transition);
    }

    /// Publishes a rollback while retaining it as a secondary failure diagnostic.
    ///
    /// @param primaryFailure launch-command failure
    /// @param rollbackTransition restored home transition, or null
    /// @return combined unchecked failure with [Error] precedence
    private Throwable combineWithPublication(
            Throwable primaryFailure,
            @Nullable SnapshotTransition rollbackTransition) {
        @Nullable Throwable combinedFailure = attempt(
                primaryFailure,
                () -> publishTransition(rollbackTransition));
        return Objects.requireNonNull(combinedFailure, "primary launch failure was lost");
    }

    /// Maps and publishes one selection transition.
    ///
    /// @param change raw selection transition
    private void selectionChanged(ValueChange<HomeSelectionState> change) {
        HomeSelectionState selection = Objects.requireNonNull(
                change.currentValue(), "home selection store emitted null");
        applySelection(selection);
    }

    /// Reads and applies the latest store state after listener registration.
    private void reconcileSelectionStore() {
        @Nullable SnapshotTransition transition;
        synchronized (stateLock) {
            if (closed) {
                return;
            }
            currentSelection = Objects.requireNonNull(selectionStore.snapshot(), "selectionStore snapshot");
            transition = replaceSnapshotLocked(map(currentSelection));
        }
        publishTransition(transition);
    }

    /// Applies one complete selection under the model lock and publishes after releasing it.
    ///
    /// @param selection selected account and instance identity plus presentation
    private void applySelection(HomeSelectionState selection) {
        @Nullable SnapshotTransition transition;
        synchronized (stateLock) {
            if (closed) {
                return;
            }
            currentSelection = Objects.requireNonNull(selection, "selection");
            transition = replaceSnapshotLocked(map(currentSelection));
        }
        publishTransition(transition);
    }

    /// Maps selected values and launch lifecycle to readiness and localized status.
    ///
    /// @param selection selected account and instance identity plus presentation
    /// @return mapped home state
    private HomeSnapshot map(HomeSelectionState selection) {
        boolean hasAccount = !selection.accountId().isBlank();
        boolean hasInstance = !selection.gameDirectoryId().isBlank() && selection.instanceId() != null;
        boolean preparingLaunch = launchInvocationPending
                || currentLaunchSession != null && currentLaunchSession.status() == LaunchStatus.PREPARING;
        boolean preparing = preparingLaunch || launchScriptExportPending;
        String status = !hasAccount
                ? statusStrings.missingAccountStatus()
                : !hasInstance
                        ? statusStrings.missingInstanceStatus()
                        : launchScriptExportPending
                                ? statusStrings.exportingLaunchScriptStatus()
                                : statusStrings.readyStatus();
        return new HomeSnapshot(
                selection.accountName(),
                selection.accountDetail(),
                selection.instanceName(),
                selection.instanceDetail(),
                status,
                hasAccount && hasInstance && !preparing,
                preparingLaunch,
                !preparing);
    }

    /// Commits one mapped snapshot and captures its exact transition while holding [#stateLock].
    ///
    /// @param replacement mapped replacement snapshot
    /// @return transition, or null when the state is unchanged
    private @Nullable SnapshotTransition replaceSnapshotLocked(HomeSnapshot replacement) {
        HomeSnapshot previous = currentSnapshot;
        currentSnapshot = Objects.requireNonNull(replacement, "replacement");
        if (previous.equals(replacement)) {
            return null;
        }
        snapshotRevision++;
        return new SnapshotTransition(previous, replacement, snapshotRevision);
    }

    /// Publishes one committed transition without holding [#stateLock].
    ///
    /// @param transition committed transition, or null when no value changed
    private void publishTransition(@Nullable SnapshotTransition transition) {
        if (transition == null) {
            return;
        }
        synchronized (publicationLock) {
            synchronized (stateLock) {
                if (closed || transition.revision() != snapshotRevision) {
                    return;
                }
            }
            ValueChange<HomeSnapshot> change = new ValueChange<>(
                    this,
                    transition.previous(),
                    transition.current());
            homeListeners.forEach(listener -> listener.notifySafely(change));
        }
    }

    /// Publishes a new session only while it remains current and the model remains open.
    ///
    /// @param session session to publish through the stable optional property
    private void publishLaunchSession(LaunchSession session) {
        synchronized (publicationLock) {
            synchronized (stateLock) {
                if (closed || currentLaunchSession != session) {
                    return;
                }
            }
            launchSessionProperty.publish(Optional.of(session));
        }
    }

    /// Waits for an already-started observable publication before close releases owned subscriptions.
    private void awaitPublicationBarrier() {
        synchronized (publicationLock) {
            // Acquiring this monitor is the barrier; new publishers observe closed state and return.
        }
    }

    /// Runs one cleanup or publication action and combines unchecked failures.
    ///
    /// @param previousFailure earlier failure, or null
    /// @param action action to attempt
    /// @return combined failure, or null when every attempted action succeeded
    private static @Nullable Throwable attempt(@Nullable Throwable previousFailure, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException | Error actionFailure) {
            return combineFailures(previousFailure, actionFailure);
        }
        return previousFailure;
    }

    /// Combines failures while preventing an earlier runtime exception from hiding a later [Error].
    ///
    /// @param previousFailure earlier failure, or null
    /// @param laterFailure later failure
    /// @return primary failure with secondary diagnostics suppressed
    private static Throwable combineFailures(@Nullable Throwable previousFailure, Throwable laterFailure) {
        Objects.requireNonNull(laterFailure, "laterFailure");
        if (previousFailure == null) {
            return laterFailure;
        }
        if (previousFailure == laterFailure) {
            return previousFailure;
        }
        if (laterFailure instanceof Error && !(previousFailure instanceof Error)) {
            laterFailure.addSuppressed(previousFailure);
            return laterFailure;
        }
        previousFailure.addSuppressed(laterFailure);
        return previousFailure;
    }

    /// Cancels one optional subscription.
    ///
    /// @param subscription subscription to cancel, or null
    private static void unsubscribe(@Nullable Subscription subscription) {
        if (subscription != null) {
            subscription.unsubscribe();
        }
    }

    /// Rethrows an optional accumulated failure after every cleanup action has run.
    ///
    /// @param failure accumulated failure, or null
    private static void rethrowFailure(@Nullable Throwable failure) {
        if (failure != null) {
            throw propagate(failure);
        }
    }

    /// Converts or rethrows one unchecked failure without losing [Error] identity.
    ///
    /// @param failure failure to propagate
    /// @return the same runtime exception, or a wrapper for an unexpected checked throwable
    private static RuntimeException propagate(Throwable failure) {
        Objects.requireNonNull(failure, "failure");
        if (failure instanceof RuntimeException runtimeException) {
            return runtimeException;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        return new IllegalStateException("Unexpected checked home-model failure", failure);
    }

    /// Reports one isolated listener runtime failure without letting a failing reporting hook stop later listeners.
    ///
    /// @param listenerFailure listener failure to report
    private static void reportListenerFailure(RuntimeException listenerFailure) {
        try {
            Lang.handleUncaughtException(listenerFailure);
        } catch (RuntimeException ignored) {
            // Listener reporting is diagnostic and cannot replace the already committed home state.
        }
    }

    /// Rejects commands and subscriptions after closure.
    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("Launcher home model is closed");
        }
    }

    /// Keeps one listener registration independently removable and isolates its runtime failures.
    ///
    /// @param <T> observed immutable value type
    @NotNullByDefault
    private static final class IsolatedListenerSlot<T> {
        /// Listener owned by this exact registration.
        private final ValueChangeListener<T> listener;

        /// Creates one isolated listener registration.
        ///
        /// @param listener listener to own
        private IsolatedListenerSlot(ValueChangeListener<T> listener) {
            this.listener = Objects.requireNonNull(listener, "listener");
        }

        /// Delivers one change while allowing later listeners to survive a runtime failure.
        ///
        /// @param change immutable value transition
        private void notifySafely(ValueChange<T> change) {
            try {
                listener.onChange(change);
            } catch (RuntimeException listenerFailure) {
                reportListenerFailure(listenerFailure);
            }
        }
    }

    /// Stable read-only optional session property with per-registration runtime isolation.
    @NotNullByDefault
    private final class LaunchSessionProperty implements ReadOnlyProperty<Optional<LaunchSession>> {
        /// Session-property listeners in subscription order.
        private final CopyOnWriteArrayList<IsolatedListenerSlot<Optional<LaunchSession>>> listeners =
                new CopyOnWriteArrayList<>();

        /// Latest optional session value.
        private volatile Optional<LaunchSession> value = Optional.empty();

        /// Returns the latest optional session.
        @Override
        public Optional<LaunchSession> getValue() {
            return value;
        }

        /// Registers one session-property listener while the home model remains open.
        @Override
        public Subscription subscribe(ValueChangeListener<Optional<LaunchSession>> listener) {
            Objects.requireNonNull(listener, "listener");
            synchronized (stateLock) {
                requireOpen();
                IsolatedListenerSlot<Optional<LaunchSession>> slot = new IsolatedListenerSlot<>(listener);
                listeners.add(slot);
                return Subscription.create(() -> listeners.remove(slot));
            }
        }

        /// Returns the owning home model.
        @Override
        public Object getBean() {
            return LauncherHomeModel.this;
        }

        /// Returns the stable property name.
        @Override
        public String getName() {
            return "launchSession";
        }

        /// Commits and publishes one distinct optional session value.
        ///
        /// @param replacement new optional session value
        private void publish(Optional<LaunchSession> replacement) {
            Objects.requireNonNull(replacement, "replacement");
            Optional<LaunchSession> previous = value;
            @Nullable LaunchSession previousSession = previous.orElse(null);
            @Nullable LaunchSession replacementSession = replacement.orElse(null);
            if (previousSession == replacementSession) {
                return;
            }
            value = replacement;
            ValueChange<Optional<LaunchSession>> change = new ValueChange<>(this, previous, replacement);
            listeners.forEach(listener -> listener.notifySafely(change));
        }

        /// Removes every remaining listener after the model's close publication barrier.
        private void clearSubscribers() {
            listeners.clear();
        }
    }

    /// Exact immutable home-state transition committed under [#stateLock].
    ///
    /// @param previous previous home state
    /// @param current replacement home state
    /// @param revision monotonic commit revision
    @NotNullByDefault
    private record SnapshotTransition(HomeSnapshot previous, HomeSnapshot current, long revision) {
        /// Validates one transition.
        private SnapshotTransition {
            Objects.requireNonNull(previous, "previous");
            Objects.requireNonNull(current, "current");
            if (revision <= 0L) {
                throw new IllegalArgumentException("revision must be positive");
            }
        }
    }
}
