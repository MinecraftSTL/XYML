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
package space.minecraftstl.xyml.ui.swing.page.instances.management.maintenance;

import com.formdev.flatlaf.extras.FlatSVGIcon;
import net.miginfocom.swing.MigLayout;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import space.minecraftstl.xyml.game.XYMLGameRepository;
import space.minecraftstl.xyml.game.launch.LaunchSession;
import space.minecraftstl.xyml.observable.Subscription;
import space.minecraftstl.xyml.task.Task;
import space.minecraftstl.xyml.task.TaskExecutor;
import space.minecraftstl.xyml.task.TaskListener;
import space.minecraftstl.xyml.task.presentation.TaskExecutorPresentationModel;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;
import space.minecraftstl.xyml.ui.swing.SwingAnimator;
import space.minecraftstl.xyml.ui.swing.SwingUiDispatcher;
import space.minecraftstl.xyml.ui.swing.page.home.HomeLaunchCommand;
import space.minecraftstl.xyml.ui.swing.page.home.HomeLaunchScriptExportCommand;
import space.minecraftstl.xyml.ui.swing.task.TaskProgressHostPanel;
import space.minecraftstl.xyml.ui.swing.task.TaskProgressStrings;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.Font;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

/// Presents test launch, script export, dependency repair, and destructive cleanup for one instance.
///
/// Construction performs no repository or network work. [#activate()] loads the initial snapshot lazily, Core
/// mutations own the shared task-progress surface, and every delayed completion is revision-gated after close.
@NotNullByDefault
public final class InstanceMaintenancePanel extends JPanel implements AutoCloseable {
    /// Stable fixed instance identifier.
    private final String instanceId;

    /// Instance run directory used only as the native script chooser's initial location.
    private final Path runDirectory;

    /// Background Core task boundary.
    private final InstanceMaintenanceService service;

    /// Application-owned launch and script command boundary.
    private final InstanceMaintenanceLaunchActions launchActions;

    /// Native chooser, confirmation, and result-dialog boundary.
    private final InstanceMaintenanceInteractions interactions;

    /// Immutable visible text.
    private final InstanceMaintenanceStrings strings;

    /// Task-progress host shared by Core repair tasks and test-launch sessions.
    private final TaskProgressHostPanel progressHost;

    /// Starts one diagnostic game launch.
    private final JButton testLaunchButton = new JButton();

    /// Exports one standalone local launch script.
    private final JButton exportScriptButton = new JButton();

    /// Applies a local archive to an installed modpack.
    private final JButton updateModpackButton = new JButton();

    /// Forcibly refreshes the selected instance's assets.
    private final JButton redownloadAssetsButton = new JButton();

    /// Removes repository-wide asset data after confirmation.
    private final JButton removeAssetsButton = new JButton();

    /// Removes repository-wide libraries after confirmation.
    private final JButton removeLibrariesButton = new JButton();

    /// Removes generated logs and crash reports after confirmation.
    private final JButton cleanGeneratedFilesButton = new JButton();

    /// Retains the latest concise lifecycle result.
    private final JLabel statusLabel = new JLabel();

    /// Rejects commands and delayed publications after disposal begins.
    private final AtomicBoolean closed = new AtomicBoolean();

    /// Latest successful snapshot, or null before activation completes.
    private @Nullable InstanceMaintenanceSnapshot displayedSnapshot;

    /// Whether an explicit asynchronous snapshot read is in progress.
    private boolean snapshotLoading;

    /// Monotonic identity for snapshot requests.
    private long snapshotRevision;

    /// Current Core task executor, or null while idle.
    private volatile @Nullable TaskExecutor activeExecutor;

    /// Presentation model owned by the current or latest completed Core task.
    private @Nullable TaskExecutorPresentationModel activePresentation;

    /// Terminal listener registration owned by the active Core task.
    private @Nullable Subscription activeCompletionSubscription;

    /// Test-launch session currently preparing or retained for terminal diagnostics.
    private @Nullable LaunchSession activeLaunchSession;

    /// Whether a script export completion is pending.
    private boolean scriptExportPending;

    /// Monotonic identity for script export requests and close invalidation.
    private long scriptExportRevision;

    /// Creates a production maintenance page for one existing XYML instance.
    ///
    /// @param repository repository containing the fixed instance
    /// @param instanceId stable fixed instance identifier
    /// @param launchCommand application-owned test-aware process launch command
    /// @param exportCommand application-owned script export command
    /// @param taskProgressStrings localized task progress text
    /// @param animator optional shared motion-aware animator
    /// @param progressAnimationDuration non-negative determinate progress animation duration
    public InstanceMaintenancePanel(
            XYMLGameRepository repository,
            String instanceId,
            HomeLaunchCommand launchCommand,
            HomeLaunchScriptExportCommand exportCommand,
            TaskProgressStrings taskProgressStrings,
            @Nullable SwingAnimator animator,
            Duration progressAnimationDuration) {
        this(
                instanceId,
                Objects.requireNonNull(repository, "repository").getRunDirectory(instanceId),
                new RepositoryInstanceMaintenanceService(repository, instanceId),
                new CommandInstanceMaintenanceLaunchActions(
                        repository,
                        instanceId,
                        launchCommand,
                        exportCommand),
                InstanceMaintenanceStrings.localized(),
                new SwingInstanceMaintenanceInteractions(),
                taskProgressStrings,
                animator,
                progressAnimationDuration);
    }

    /// Creates a production maintenance page around an already bound application launch command adapter.
    ///
    /// @param repository repository containing the fixed instance
    /// @param instanceId stable fixed instance identifier
    /// @param launchActions test-launch and script commands bound to this exact instance
    /// @param taskProgressStrings localized task progress text
    /// @param animator optional shared motion-aware animator
    /// @param progressAnimationDuration non-negative determinate progress animation duration
    public InstanceMaintenancePanel(
            XYMLGameRepository repository,
            String instanceId,
            InstanceMaintenanceLaunchActions launchActions,
            TaskProgressStrings taskProgressStrings,
            @Nullable SwingAnimator animator,
            Duration progressAnimationDuration) {
        this(
                instanceId,
                Objects.requireNonNull(repository, "repository").getRunDirectory(instanceId),
                new RepositoryInstanceMaintenanceService(repository, instanceId),
                launchActions,
                InstanceMaintenanceStrings.localized(),
                new SwingInstanceMaintenanceInteractions(),
                taskProgressStrings,
                animator,
                progressAnimationDuration);
    }

    /// Creates a maintenance page around explicit deterministic collaborators.
    ///
    /// @param instanceId stable fixed instance identifier
    /// @param runDirectory fixed instance run directory
    /// @param service background Core operation boundary
    /// @param launchActions application launch command boundary
    /// @param strings immutable visible text
    /// @param interactions native interaction boundary
    /// @param taskProgressStrings localized task progress text
    /// @param animator optional shared animator
    /// @param progressAnimationDuration non-negative progress animation duration
    InstanceMaintenancePanel(
            String instanceId,
            Path runDirectory,
            InstanceMaintenanceService service,
            InstanceMaintenanceLaunchActions launchActions,
            InstanceMaintenanceStrings strings,
            InstanceMaintenanceInteractions interactions,
            TaskProgressStrings taskProgressStrings,
            @Nullable SwingAnimator animator,
            Duration progressAnimationDuration) {
        super(new MigLayout(
                "insets 20, fillx, wrap 1",
                "[grow,fill]",
                "[]16[]12[]16[]12[]16[]12[]"));
        EdtDispatcher.requireEventDispatchThread();
        this.instanceId = requireNonBlank(instanceId, "instanceId");
        this.runDirectory = Objects.requireNonNull(runDirectory, "runDirectory").toAbsolutePath().normalize();
        this.service = Objects.requireNonNull(service, "service");
        this.launchActions = Objects.requireNonNull(launchActions, "launchActions");
        this.strings = Objects.requireNonNull(strings, "strings");
        this.interactions = Objects.requireNonNull(interactions, "interactions");
        Duration animationDuration = Objects.requireNonNull(
                progressAnimationDuration,
                "progressAnimationDuration");
        if (animationDuration.isNegative()) {
            throw new IllegalArgumentException("progressAnimationDuration must not be negative");
        }
        progressHost = new TaskProgressHostPanel(
                Objects.requireNonNull(taskProgressStrings, "taskProgressStrings"),
                animator,
                animationDuration);
        configureComponents();
    }

    /// Returns the localized containing-tab title.
    ///
    /// @return non-blank page title
    public String title() {
        return strings.title();
    }

    /// Returns the latest successful local snapshot, or null before activation succeeds.
    ///
    /// @return current maintenance snapshot or null
    public @Nullable InstanceMaintenanceSnapshot displayedSnapshot() {
        EdtDispatcher.requireEventDispatchThread();
        return displayedSnapshot;
    }

    /// Starts the initial local-state read once the surrounding tab becomes visible.
    public void activate() {
        EdtDispatcher.requireEventDispatchThread();
        if (closed.get() || displayedSnapshot != null || snapshotLoading || isOperationPending()) {
            return;
        }
        requestSnapshot();
    }

    /// Cancels active preparation or Core work and rejects every delayed completion exactly once.
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        ++snapshotRevision;
        ++scriptExportRevision;
        @Nullable Throwable failure = null;
        @Nullable TaskExecutor executor = activeExecutor;
        if (executor != null) {
            failure = attemptCleanup(failure, executor::cancel);
        }
        @Nullable LaunchSession launchSession = activeLaunchSession;
        if (launchSession != null) {
            failure = attemptCleanup(failure, () -> {
                if (!launchSession.status().isTerminal()) {
                    launchSession.cancel();
                }
            });
        }
        failure = attemptCleanup(
                failure,
                () -> SwingUiDispatcher.INSTANCE.dispatchOrRun(this::closeOnEventDispatchThread));
        rethrowCleanupFailure(failure);
    }

    /// Builds stable action groups, status, and task presentation without starting I/O.
    private void configureComponents() {
        setName("instanceMaintenancePage");
        setOpaque(false);
        setBorder(BorderFactory.createEmptyBorder());

        JLabel title = new JLabel(strings.title());
        title.setName("instanceMaintenanceTitle");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 20.0F));
        add(title, "growx");

        add(createSection(
                strings.launchSection(),
                configureAction(
                        testLaunchButton,
                        "instanceMaintenanceTestLaunch",
                        "assets/swing/icons/rocket-launch.svg",
                        strings.testLaunchAction(),
                        this::startTestLaunch),
                configureAction(
                        exportScriptButton,
                        "instanceMaintenanceExportScript",
                        "assets/swing/icons/script.svg",
                        strings.exportScriptAction(),
                        this::exportLaunchScript)),
                "growx");

        add(createSection(
                strings.repairSection(),
                configureAction(
                        updateModpackButton,
                        "instanceMaintenanceUpdateModpack",
                        "assets/swing/icons/file-import.svg",
                        strings.updateModpackAction(),
                        this::updateModpack),
                configureAction(
                        redownloadAssetsButton,
                        "instanceMaintenanceRedownloadAssets",
                        "assets/swing/icons/refresh.svg",
                        strings.redownloadAssetsAction(),
                        () -> startTask(service::redownloadAssets, strings.redownloadAssetsAction()))),
                "growx");

        add(createSection(
                strings.cleanupSection(),
                configureAction(
                        removeAssetsButton,
                        "instanceMaintenanceRemoveAssets",
                        "assets/swing/icons/delete-forever.svg",
                        strings.removeAssetsAction(),
                        this::removeAssets),
                configureAction(
                        removeLibrariesButton,
                        "instanceMaintenanceRemoveLibraries",
                        "assets/swing/icons/delete-forever.svg",
                        strings.removeLibrariesAction(),
                        this::removeLibraries),
                configureAction(
                        cleanGeneratedFilesButton,
                        "instanceMaintenanceCleanGenerated",
                        "assets/swing/icons/delete.svg",
                        strings.cleanGeneratedFilesAction(),
                        this::cleanGeneratedFiles)),
                "growx");

        statusLabel.setName("instanceMaintenanceStatus");
        statusLabel.setText(strings.loadingStatus());
        add(statusLabel, "growx");

        progressHost.setName("instanceMaintenanceProgress");
        add(progressHost, "growx, hmin 116");
        updateControls();
    }

    /// Creates one unframed responsive action band.
    ///
    /// @param title visible group title
    /// @param buttons one or more configured actions
    /// @return unframed action band
    private static JPanel createSection(String title, JButton @Unmodifiable ... buttons) {
        JPanel section = new JPanel(new MigLayout(
                "insets 0, fillx, wrap 1",
                "[grow,fill]",
                "[]8[]"));
        section.setOpaque(false);
        JLabel heading = new JLabel(requireNonBlank(title, "title"));
        heading.setFont(heading.getFont().deriveFont(Font.BOLD));
        section.add(heading, "growx");
        JPanel actions = new JPanel(new MigLayout(
                "insets 0, fillx, gap 8, wrap 2",
                "[grow,fill][grow,fill]",
                "[]"));
        actions.setOpaque(false);
        for (JButton button : Objects.requireNonNull(buttons, "buttons")) {
            actions.add(Objects.requireNonNull(button, "buttons contains null"), "h 40!");
        }
        section.add(actions, "growx");
        return section;
    }

    /// Configures one clear icon-and-text maintenance command.
    ///
    /// @param button target button
    /// @param name deterministic component name
    /// @param iconResource bundled icon path
    /// @param text visible and accessible action text
    /// @param action EDT command implementation
    /// @return configured target button
    private static JButton configureAction(
            JButton button,
            String name,
            String iconResource,
            String text,
            Runnable action) {
        JButton target = Objects.requireNonNull(button, "button");
        target.setName(requireNonBlank(name, "name"));
        String actionText = requireNonBlank(text, "text");
        target.setText(actionText);
        target.setIcon(new FlatSVGIcon(requireNonBlank(iconResource, "iconResource"), 18, 18));
        target.setToolTipText(actionText);
        target.getAccessibleContext().setAccessibleName(actionText);
        target.addActionListener(event -> Objects.requireNonNull(action, "action").run());
        return target;
    }

    /// Starts a revision-gated asynchronous local snapshot read.
    private void requestSnapshot() {
        EdtDispatcher.requireEventDispatchThread();
        if (closed.get() || snapshotLoading || isOperationPending()) {
            return;
        }
        snapshotLoading = true;
        displayedSnapshot = null;
        long revision = ++snapshotRevision;
        statusLabel.setText(strings.loadingStatus());
        updateControls();
        final CompletionStage<InstanceMaintenanceSnapshot> completion;
        try {
            completion = Objects.requireNonNull(service.loadSnapshot(), "service returned null snapshot stage");
        } catch (RuntimeException failure) {
            snapshotCompleted(revision, null, failure);
            return;
        } catch (Error failure) {
            snapshotLoading = false;
            updateControls();
            throw failure;
        }
        completion.whenComplete((@Nullable InstanceMaintenanceSnapshot snapshot, @Nullable Throwable failure) ->
                SwingUiDispatcher.INSTANCE.dispatchOrRun(
                        () -> snapshotCompleted(revision, snapshot, failure)));
    }

    /// Applies only the latest open-page snapshot completion.
    ///
    /// @param revision snapshot request identity
    /// @param snapshot completed snapshot, or null after failure
    /// @param failure terminal failure, or null after success
    private void snapshotCompleted(
            long revision,
            @Nullable InstanceMaintenanceSnapshot snapshot,
            @Nullable Throwable failure) {
        EdtDispatcher.requireEventDispatchThread();
        if (closed.get() || revision != snapshotRevision) {
            return;
        }
        snapshotLoading = false;
        if (failure == null && snapshot != null && instanceId.equals(snapshot.instanceId())) {
            applySnapshot(snapshot);
            statusLabel.setText(strings.readyStatus());
        } else {
            Throwable terminalFailure = failure == null
                    ? new IllegalStateException("Snapshot completed without the target instance")
                    : failure;
            presentFailure(strings.title(), terminalFailure);
        }
        updateControls();
    }

    /// Applies one authoritative local snapshot.
    ///
    /// @param snapshot current fixed-instance state
    private void applySnapshot(InstanceMaintenanceSnapshot snapshot) {
        EdtDispatcher.requireEventDispatchThread();
        displayedSnapshot = Objects.requireNonNull(snapshot, "snapshot");
        updateModpackButton.setToolTipText(snapshot.modpack()
                ? strings.updateModpackAction()
                : strings.updateUnavailableStatus());
    }

    /// Opens a local archive chooser and starts a provider-validated update task.
    private void updateModpack() {
        EdtDispatcher.requireEventDispatchThread();
        @Nullable InstanceMaintenanceSnapshot snapshot = displayedSnapshot;
        if (!isReady() || snapshot == null || !snapshot.modpack()) {
            return;
        }
        @Nullable Path archive = interactions.chooseModpackArchive(this);
        if (archive != null) {
            startTask(
                    () -> service.updateModpack(archive, StandardCharsets.UTF_8),
                    strings.updateModpackAction());
        }
    }

    /// Confirms and removes shared repository assets.
    private void removeAssets() {
        EdtDispatcher.requireEventDispatchThread();
        if (isReady() && interactions.confirmDestructive(this, strings.removeAssetsAction(), true)) {
            startTask(service::removeAssets, strings.removeAssetsAction());
        }
    }

    /// Confirms and removes shared repository libraries.
    private void removeLibraries() {
        EdtDispatcher.requireEventDispatchThread();
        if (isReady() && interactions.confirmDestructive(this, strings.removeLibrariesAction(), true)) {
            startTask(service::removeLibraries, strings.removeLibrariesAction());
        }
    }

    /// Confirms and removes generated diagnostic files.
    private void cleanGeneratedFiles() {
        EdtDispatcher.requireEventDispatchThread();
        if (isReady() && interactions.confirmDestructive(
                this,
                strings.cleanGeneratedFilesAction(),
                false)) {
            startTask(service::cleanGeneratedFiles, strings.cleanGeneratedFilesAction());
        }
    }

    /// Starts one test-mode launch and binds its observable preparation directly to the progress host.
    private void startTestLaunch() {
        EdtDispatcher.requireEventDispatchThread();
        if (!isReady()) {
            return;
        }
        releaseCompletedPresentation();
        final LaunchSession launchSession;
        try {
            launchSession = Objects.requireNonNull(launchActions.testLaunch(), "launch actions returned null session");
            activeLaunchSession = launchSession;
            progressHost.bind(launchSession);
        } catch (RuntimeException failure) {
            @Nullable LaunchSession failedSession = activeLaunchSession;
            activeLaunchSession = null;
            if (failedSession != null) {
                failedSession.cancel();
            }
            presentFailure(strings.testLaunchAction(), failure);
            updateControls();
            return;
        } catch (Error failure) {
            @Nullable LaunchSession failedSession = activeLaunchSession;
            activeLaunchSession = null;
            if (failedSession != null) {
                try {
                    failedSession.cancel();
                } catch (RuntimeException | Error cleanupFailure) {
                    if (cleanupFailure != failure) {
                        failure.addSuppressed(cleanupFailure);
                    }
                }
            }
            throw failure;
        }
        statusLabel.setText(strings.testLaunchStartedStatus());
        updateControls();
        launchSession.completion().whenComplete((process, failure) ->
                SwingUiDispatcher.INSTANCE.dispatchOrRun(
                        () -> testLaunchCompleted(launchSession, failure)));
    }

    /// Applies the exact current test-launch terminal result.
    ///
    /// @param launchSession completed session identity
    /// @param failure terminal failure, or null once a process was created
    private void testLaunchCompleted(LaunchSession launchSession, @Nullable Throwable failure) {
        EdtDispatcher.requireEventDispatchThread();
        if (closed.get() || activeLaunchSession != launchSession) {
            return;
        }
        if (failure == null) {
            statusLabel.setText(strings.successStatus());
        } else if (launchSession.status().isTerminal() && launchSession.failure().isEmpty()) {
            statusLabel.setText(strings.cancelledStatus());
        } else {
            presentFailure(strings.testLaunchAction(), failure);
        }
        updateControls();
    }

    /// Opens the native destination chooser and starts one application-owned script export.
    private void exportLaunchScript() {
        EdtDispatcher.requireEventDispatchThread();
        if (!isReady()) {
            return;
        }
        @Nullable Path destination = interactions.chooseLaunchScript(this, runDirectory);
        if (destination == null) {
            return;
        }
        releaseCompletedPresentation();
        scriptExportPending = true;
        long revision = ++scriptExportRevision;
        statusLabel.setText(strings.workingStatus());
        updateControls();
        final CompletionStage<Path> completion;
        try {
            completion = Objects.requireNonNull(
                    launchActions.exportLaunchScript(destination),
                    "launch actions returned null export stage");
        } catch (RuntimeException failure) {
            scriptExportCompleted(revision, null, failure);
            return;
        } catch (Error failure) {
            scriptExportPending = false;
            updateControls();
            throw failure;
        }
        completion.whenComplete((@Nullable Path scriptFile, @Nullable Throwable failure) ->
                SwingUiDispatcher.INSTANCE.dispatchOrRun(
                        () -> scriptExportCompleted(revision, scriptFile, failure)));
    }

    /// Applies one exact script export completion.
    ///
    /// @param revision export request identity
    /// @param scriptFile generated script path, or null after failure
    /// @param failure terminal failure, or null after success
    private void scriptExportCompleted(
            long revision,
            @Nullable Path scriptFile,
            @Nullable Throwable failure) {
        EdtDispatcher.requireEventDispatchThread();
        if (closed.get() || revision != scriptExportRevision) {
            return;
        }
        scriptExportPending = false;
        if (failure == null && scriptFile != null) {
            String success = strings.scriptSuccess(scriptFile);
            statusLabel.setText(success);
            interactions.showSuccess(this, strings.exportScriptAction(), success);
        } else {
            Throwable terminalFailure = failure == null
                    ? new IllegalStateException("Script export completed without a path")
                    : failure;
            presentFailure(strings.exportScriptAction(), terminalFailure);
        }
        updateControls();
    }

    /// Creates and starts one stopped Core maintenance task.
    ///
    /// @param taskSupplier deferred task construction boundary
    /// @param title visible operation title
    private void startTask(MaintenanceTaskSupplier taskSupplier, String title) {
        EdtDispatcher.requireEventDispatchThread();
        if (!isReady()) {
            return;
        }
        releaseCompletedPresentation();
        final Task<InstanceMaintenanceSnapshot> task;
        try {
            task = Objects.requireNonNull(taskSupplier.create(), "task supplier returned null");
        } catch (RuntimeException failure) {
            presentFailure(title, failure);
            updateControls();
            return;
        }

        TaskExecutor executor = task.executor();
        TaskExecutorPresentationModel presentation = new TaskExecutorPresentationModel(
                executor,
                requireNonBlank(title, "title"),
                strings.workingStatus());
        Subscription completionSubscription = executor.subscribeTaskListener(
                new MaintenanceCompletionListener(executor, task, title));
        activeExecutor = executor;
        activePresentation = presentation;
        activeCompletionSubscription = completionSubscription;
        statusLabel.setText(strings.workingStatus());
        updateControls();
        try {
            progressHost.bind(presentation);
            executor.start();
        } catch (RuntimeException startFailure) {
            cleanupFailedTaskStart(presentation, completionSubscription);
            presentFailure(title, startFailure);
            updateControls();
        } catch (Error startFailure) {
            try {
                cleanupFailedTaskStart(presentation, completionSubscription);
            } catch (RuntimeException | Error cleanupFailure) {
                if (cleanupFailure != startFailure) {
                    startFailure.addSuppressed(cleanupFailure);
                }
            }
            throw startFailure;
        }
    }

    /// Applies one exact Core task terminal result on the EDT.
    ///
    /// @param executor exact active executor
    /// @param task exact task yielding the new snapshot
    /// @param title visible operation title
    /// @param succeeded whether the full Core graph succeeded
    private void taskCompleted(
            TaskExecutor executor,
            Task<InstanceMaintenanceSnapshot> task,
            String title,
            boolean succeeded) {
        SwingUiDispatcher.INSTANCE.dispatchOrRun(() -> {
            if (closed.get() || activeExecutor != executor) {
                return;
            }
            unsubscribe(activeCompletionSubscription);
            activeCompletionSubscription = null;
            activeExecutor = null;
            if (succeeded) {
                @Nullable InstanceMaintenanceSnapshot result = task.getResult();
                if (result == null || !instanceId.equals(result.instanceId())) {
                    presentFailure(title, new IllegalStateException(
                            "Maintenance task completed without the target instance snapshot"));
                } else {
                    applySnapshot(result);
                    statusLabel.setText(strings.successStatus());
                }
            } else if (executor.isCancelled()) {
                statusLabel.setText(strings.cancelledStatus());
            } else {
                @Nullable Throwable failure = executor.getFailure();
                presentFailure(
                        title,
                        failure == null
                                ? new IllegalStateException("Maintenance task failed without a cause")
                                : failure);
            }
            updateControls();
        });
    }

    /// Clears a previous completed Core presentation before binding a different operation.
    private void releaseCompletedPresentation() {
        EdtDispatcher.requireEventDispatchThread();
        if (activeExecutor != null) {
            return;
        }
        @Nullable TaskExecutorPresentationModel presentation = activePresentation;
        activePresentation = null;
        progressHost.clear();
        if (presentation != null) {
            presentation.close();
        }
        @Nullable LaunchSession launchSession = activeLaunchSession;
        if (launchSession != null && launchSession.status().isTerminal()) {
            activeLaunchSession = null;
        }
    }

    /// Releases a task whose executor could not start.
    ///
    /// @param presentation created task presentation
    /// @param completionSubscription terminal listener registration
    private void cleanupFailedTaskStart(
            TaskExecutorPresentationModel presentation,
            Subscription completionSubscription) {
        unsubscribe(completionSubscription);
        activeCompletionSubscription = null;
        activeExecutor = null;
        if (activePresentation == presentation) {
            activePresentation = null;
        }
        progressHost.clear();
        presentation.close();
    }

    /// Returns whether a new command may begin.
    ///
    /// @return whether local state is loaded and every operation is idle
    private boolean isReady() {
        return !closed.get()
                && displayedSnapshot != null
                && !snapshotLoading
                && !isOperationPending();
    }

    /// Returns whether any Core, launch, or script operation still owns interaction.
    ///
    /// @return whether another command must remain disabled
    private boolean isOperationPending() {
        @Nullable LaunchSession launchSession = activeLaunchSession;
        return activeExecutor != null
                || scriptExportPending
                || launchSession != null && !launchSession.status().isTerminal();
    }

    /// Synchronizes all command availability with local state and operation ownership.
    private void updateControls() {
        EdtDispatcher.requireEventDispatchThread();
        boolean ready = isReady();
        @Nullable InstanceMaintenanceSnapshot snapshot = displayedSnapshot;
        testLaunchButton.setEnabled(ready);
        exportScriptButton.setEnabled(ready);
        updateModpackButton.setEnabled(ready && snapshot != null && snapshot.modpack());
        redownloadAssetsButton.setEnabled(ready);
        removeAssetsButton.setEnabled(ready && snapshot != null && snapshot.assetsPresent());
        removeLibrariesButton.setEnabled(ready && snapshot != null && snapshot.librariesPresent());
        cleanGeneratedFilesButton.setEnabled(ready && snapshot != null && snapshot.generatedFilesPresent());
    }

    /// Presents a concise native and inline failure.
    ///
    /// @param title visible operation title
    /// @param failure terminal failure or wrapper
    private void presentFailure(String title, Throwable failure) {
        EdtDispatcher.requireEventDispatchThread();
        String detail = failureDetail(failure);
        statusLabel.setText(strings.failedStatus() + ": " + detail);
        interactions.showFailure(this, requireNonBlank(title, "title"), detail);
    }

    /// Unwraps completion wrappers and returns non-blank diagnostic text.
    ///
    /// @param failure terminal failure or wrapper
    /// @return concise non-blank detail
    private static String failureDetail(Throwable failure) {
        Throwable current = Objects.requireNonNull(failure, "failure");
        while ((current instanceof CompletionException || current instanceof ExecutionException)
                && current.getCause() != null) {
            current = Objects.requireNonNull(current.getCause(), "completion cause");
        }
        @Nullable String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }

    /// Releases every EDT-owned child and disables all controls.
    private void closeOnEventDispatchThread() {
        EdtDispatcher.requireEventDispatchThread();
        snapshotLoading = false;
        scriptExportPending = false;
        displayedSnapshot = null;
        unsubscribe(activeCompletionSubscription);
        activeCompletionSubscription = null;
        activeExecutor = null;
        @Nullable TaskExecutorPresentationModel presentation = activePresentation;
        activePresentation = null;
        activeLaunchSession = null;
        progressHost.close();
        if (presentation != null) {
            presentation.close();
        }
        updateControls();
        removeAll();
    }

    /// Unsubscribes an optional owned registration.
    ///
    /// @param subscription owned registration, or null while absent
    private static void unsubscribe(@Nullable Subscription subscription) {
        if (subscription != null) {
            subscription.unsubscribe();
        }
    }

    /// Attempts one cleanup action and combines unchecked failures without skipping later cleanup.
    ///
    /// @param previousFailure earlier failure, or null
    /// @param action cleanup action to attempt
    /// @return accumulated failure, or null when every attempted action succeeded
    private static @Nullable Throwable attemptCleanup(
            @Nullable Throwable previousFailure,
            Runnable action) {
        try {
            Objects.requireNonNull(action, "action").run();
        } catch (RuntimeException | Error cleanupFailure) {
            return combineFailures(previousFailure, cleanupFailure);
        }
        return previousFailure;
    }

    /// Combines cleanup failures while retaining an [Error] as the primary throwable.
    ///
    /// @param previousFailure earlier failure, or null
    /// @param laterFailure later cleanup failure
    /// @return primary accumulated failure
    private static Throwable combineFailures(
            @Nullable Throwable previousFailure,
            Throwable laterFailure) {
        Throwable currentFailure = Objects.requireNonNull(laterFailure, "laterFailure");
        if (previousFailure == null) {
            return currentFailure;
        }
        if (previousFailure == currentFailure) {
            return previousFailure;
        }
        if (currentFailure instanceof Error && !(previousFailure instanceof Error)) {
            currentFailure.addSuppressed(previousFailure);
            return currentFailure;
        }
        previousFailure.addSuppressed(currentFailure);
        return previousFailure;
    }

    /// Rethrows one accumulated cleanup failure after every resource was attempted.
    ///
    /// @param failure accumulated failure, or null
    private static void rethrowCleanupFailure(@Nullable Throwable failure) {
        if (failure instanceof Error error) {
            throw error;
        }
        if (failure instanceof RuntimeException exception) {
            throw exception;
        }
    }

    /// Rejects missing labels and identities without rewriting them.
    ///
    /// @param value candidate text
    /// @param name diagnostic field name
    /// @return exact non-blank text
    private static String requireNonBlank(String value, String name) {
        String candidate = Objects.requireNonNull(value, name);
        if (candidate.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return candidate;
    }

    /// Constructs one stopped maintenance task after all EDT validation and confirmation.
    @FunctionalInterface
    @NotNullByDefault
    private interface MaintenanceTaskSupplier {
        /// Creates one stopped task yielding the authoritative resulting snapshot.
        ///
        /// @return stopped Core maintenance task
        Task<InstanceMaintenanceSnapshot> create();
    }

    /// Routes one exact Core task executor's terminal event back to the page state machine.
    @NotNullByDefault
    private final class MaintenanceCompletionListener extends TaskListener {
        /// Exact task executor represented by this listener.
        private final TaskExecutor executor;

        /// Exact task yielding the resulting snapshot.
        private final Task<InstanceMaintenanceSnapshot> task;

        /// Visible operation title retained for terminal failure presentation.
        private final String title;

        /// Creates a listener bound to one exact task execution.
        ///
        /// @param executor exact task executor
        /// @param task exact task yielding the resulting snapshot
        /// @param title visible operation title
        private MaintenanceCompletionListener(
                TaskExecutor executor,
                Task<InstanceMaintenanceSnapshot> task,
                String title) {
            this.executor = Objects.requireNonNull(executor, "executor");
            this.task = Objects.requireNonNull(task, "task");
            this.title = requireNonBlank(title, "title");
        }

        /// Accepts only the exact represented executor's terminal notification.
        ///
        /// @param succeeded whether the full task graph succeeded
        /// @param sourceExecutor executor publishing the event
        @Override
        public void onStop(boolean succeeded, TaskExecutor sourceExecutor) {
            if (sourceExecutor == executor) {
                taskCompleted(executor, task, title, succeeded);
            }
        }
    }
}
