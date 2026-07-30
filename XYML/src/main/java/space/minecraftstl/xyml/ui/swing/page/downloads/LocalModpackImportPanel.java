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
package space.minecraftstl.xyml.ui.swing.page.downloads;

import space.minecraftstl.xyml.ui.swing.dialog.EditablePathChooser;

import net.miginfocom.swing.MigLayout;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import space.minecraftstl.xyml.game.ManuallyCreatedModpackException;
import space.minecraftstl.xyml.game.ModpackHelper;
import space.minecraftstl.xyml.game.XYMLGameRepository;
import space.minecraftstl.xyml.modpack.Modpack;
import space.minecraftstl.xyml.modpack.UnsupportedModpackException;
import space.minecraftstl.xyml.observable.Subscription;
import space.minecraftstl.xyml.setting.GameDirectoryManager;
import space.minecraftstl.xyml.task.Schedulers;
import space.minecraftstl.xyml.task.Task;
import space.minecraftstl.xyml.task.TaskExecutor;
import space.minecraftstl.xyml.task.TaskListener;
import space.minecraftstl.xyml.task.presentation.TaskExecutorPresentationModel;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;
import space.minecraftstl.xyml.ui.swing.SwingAnimator;
import space.minecraftstl.xyml.ui.swing.SwingUiDispatcher;
import space.minecraftstl.xyml.ui.swing.task.TaskProgressHostPanel;
import space.minecraftstl.xyml.ui.swing.task.TaskProgressStrings;
import space.minecraftstl.xyml.util.io.FileUtils;

import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

import static space.minecraftstl.xyml.util.i18n.I18n.i18n;
import static space.minecraftstl.xyml.util.logging.Logger.LOG;

/// Imports a local modpack archive through the established {@link ModpackHelper} installation tasks.
///
/// Archive inspection happens off the Swing event dispatch thread. The user-selected repository and
/// destination are captured before inspection, then task creation, progress binding, and all controls
/// return to the EDT. A monotonic revision rejects an archive inspection result after replacement or
/// shutdown, preventing a late worker from beginning an unwanted import.
@NotNullByDefault
public final class LocalModpackImportPanel extends JPanel implements AutoCloseable {
    /// Serializes archive-inspection results with new selections and panel closure.
    private final AtomicLong importRevision = new AtomicLong();

    /// Read-only path display for the currently selected archive.
    private final JTextField archiveField = new JTextField();

    /// Editable destination instance name.
    private final JTextField instanceNameField = new JTextField();

    /// Command opening the native archive chooser.
    private final JButton chooseArchiveButton = new JButton();

    /// Command beginning archive inspection and subsequent import.
    private final JButton importButton = new JButton();

    /// Current import feedback and validation result.
    private final JLabel statusLabel = new JLabel();

    /// Progress surface that owns one task-presentation panel at a time.
    private final TaskProgressHostPanel progressHost;

    /// Listener that reevaluates whether the selected archive and name form a valid request.
    private final DocumentListener inputListener = new ImportInputListener();

    /// Archive selected by the user, or null before selection.
    private @Nullable Path selectedArchive;

    /// Executor currently importing an archive, or null while user input is available.
    private @Nullable TaskExecutor activeExecutor;

    /// Presentation model currently retained by the progress host, or null before the first import.
    private @Nullable TaskExecutorPresentationModel activePresentation;

    /// Completion registration owned by the current executor, or null while idle.
    private @Nullable Subscription activeCompletionSubscription;

    /// Whether the panel no longer accepts input, preparation results, or completion feedback.
    private volatile boolean closed;

    /// Creates a local modpack importer on the Swing event dispatch thread.
    ///
    /// @param taskProgressStrings localized task lifecycle controls
    /// @param animator optional shared determinate-progress animator
    /// @param progressAnimationDuration non-negative determinate-progress animation duration
    public LocalModpackImportPanel(
            TaskProgressStrings taskProgressStrings,
            @Nullable SwingAnimator animator,
            Duration progressAnimationDuration) {
        super(new MigLayout(
                "insets 0, fill, wrap 3",
                "[][grow,fill][180!]",
                "[40!]8[40!]8[]8[grow,fill]"));
        EdtDispatcher.requireEventDispatchThread();
        setOpaque(false);

        progressHost = new TaskProgressHostPanel(
                Objects.requireNonNull(taskProgressStrings, "taskProgressStrings"),
                animator,
                Objects.requireNonNull(progressAnimationDuration, "progressAnimationDuration"));

        JLabel archiveLabel = new JLabel(i18n("modpack.choose"));
        archiveLabel.setLabelFor(archiveField);
        add(archiveLabel);
        archiveField.setName("localModpackArchive");
        archiveField.setEditable(false);
        add(archiveField, "growx, h 40!");
        chooseArchiveButton.setName("localModpackChooseArchive");
        chooseArchiveButton.setText(i18n("modpack.choose.local"));
        chooseArchiveButton.addActionListener(event -> chooseArchive());
        add(chooseArchiveButton, "grow, h 40!");

        JLabel instanceNameLabel = new JLabel(i18n("modpack.enter_name"));
        instanceNameLabel.setLabelFor(instanceNameField);
        add(instanceNameLabel);
        instanceNameField.setName("localModpackInstanceName");
        instanceNameField.getDocument().addDocumentListener(inputListener);
        add(instanceNameField, "growx, h 40!");
        importButton.setName("localModpackImport");
        importButton.setText(i18n("install.modpack"));
        importButton.addActionListener(event -> beginImport());
        add(importButton, "grow, h 40!");

        statusLabel.setName("localModpackImportStatus");
        add(statusLabel, "skip 1, span 2, growx, h 24!");
        progressHost.setName("localModpackImportProgress");
        add(progressHost, "span 3, grow");
        updateImportButton();
    }

    /// Releases task presentation resources and cancels a still-running import without blocking the EDT.
    @Override
    public void close() {
        closed = true;
        importRevision.incrementAndGet();
        SwingUiDispatcher.INSTANCE.dispatchOrRun(this::closeOnEventDispatchThread);
    }

    /// Opens the native file chooser and stores a supported local archive selection.
    private void chooseArchive() {
        EdtDispatcher.requireEventDispatchThread();
        if (closed || activeExecutor != null) {
            return;
        }

        JFileChooser chooser = new EditablePathChooser();
        chooser.setDialogTitle(i18n("modpack.choose"));
        chooser.setFileFilter(new FileNameExtensionFilter(i18n("modpack"), "zip", "mrpack"));
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }

        Path archive = chooser.getSelectedFile().toPath().toAbsolutePath().normalize();
        if (!Files.isRegularFile(archive) || !ModpackHelper.isFileModpackByExtension(archive)) {
            setStatus(i18n("modpack.unsupported"));
            return;
        }

        selectedArchive = archive;
        archiveField.setText(archive.toString());
        if (instanceNameField.getText().isBlank()) {
            instanceNameField.setText(FileUtils.getNameWithoutExtension(archive));
        }
        setStatus("");
        updateImportButton();
    }

    /// Validates the visible request and begins archive inspection on the shared I/O scheduler.
    private void beginImport() {
        EdtDispatcher.requireEventDispatchThread();
        if (closed || !importButton.isEnabled()) {
            return;
        }

        @Nullable Path archive = selectedArchive;
        String instanceName = instanceNameField.getText().trim();
        if (archive == null || !Files.isRegularFile(archive) || !ModpackHelper.isFileModpackByExtension(archive)) {
            setStatus(i18n("modpack.unsupported"));
            updateImportButton();
            return;
        }
        if (!XYMLGameRepository.isValidInstanceId(instanceName)) {
            setStatus(i18n("install.new_game.malformed"));
            updateImportButton();
            return;
        }

        final XYMLGameRepository repository;
        try {
            repository = GameDirectoryManager.getSelectedRepository();
        } catch (RuntimeException selectionFailure) {
            LOG.warning("Unable to resolve the selected repository for local modpack import", selectionFailure);
            setStatus(i18n("instance.empty"));
            return;
        }
        if (repository.hasVersion(instanceName)) {
            setStatus(i18n("install.new_game.already_exists"));
            return;
        }

        releaseCompletedPresentation();
        long requestedRevision = importRevision.incrementAndGet();
        setInputsEnabled(false);
        setStatus(i18n("modpack.scan"));
        Path selectedFile = archive;
        try {
            Schedulers.io().execute(() -> inspectArchive(
                    repository,
                    selectedFile,
                    instanceName,
                    requestedRevision));
        } catch (RuntimeException schedulingFailure) {
            LOG.warning("Failed to schedule local modpack archive inspection", schedulingFailure);
            restoreInputAfterPreparationFailure(requestedRevision, i18n("install.failed"));
        }
    }

    /// Parses one archive away from the EDT and publishes a task-ready description back to the panel.
    ///
    /// @param repository destination repository captured from the current selection
    /// @param archive selected archive to inspect
    /// @param instanceName validated destination instance name
    /// @param requestedRevision request identity captured before scheduling
    private void inspectArchive(
            XYMLGameRepository repository,
            Path archive,
            String instanceName,
        long requestedRevision) {
        try {
            PreparedImport prepared = parseArchive(archive);
            SwingUiDispatcher.INSTANCE.dispatchOrRun(
                    () -> startPreparedImport(repository, archive, instanceName, prepared, requestedRevision));
        } catch (UnsupportedModpackException unsupported) {
            restoreInputAfterPreparationFailure(requestedRevision, i18n("modpack.unsupported"));
        } catch (RuntimeException unexpectedFailure) {
            LOG.warning("Failed to inspect local modpack archive", unexpectedFailure);
            restoreInputAfterPreparationFailure(requestedRevision, i18n("install.failed"));
        }
    }

    /// Parses an archive into the exact ModpackHelper installation route without mutating repository state.
    ///
    /// @param archive local archive selected by the user
    /// @return recognized provider metadata or the manually assembled archive route
    /// @throws UnsupportedModpackException when the archive has no supported modpack manifest
    private static PreparedImport parseArchive(Path archive) throws UnsupportedModpackException {
        try {
            return PreparedImport.recognized(
                    ModpackHelper.readModpackManifest(archive, StandardCharsets.UTF_8));
        } catch (ManuallyCreatedModpackException manuallyCreated) {
            return PreparedImport.manuallyCreated();
        }
    }

    /// Creates and starts an existing ModpackHelper task after a successful background archive inspection.
    ///
    /// @param repository destination repository captured from the current selection
    /// @param archive selected local archive
    /// @param instanceName validated destination instance name
    /// @param prepared recognized or manually assembled archive metadata
    /// @param requestedRevision request identity captured before scheduling
    private void startPreparedImport(
            XYMLGameRepository repository,
            Path archive,
            String instanceName,
            PreparedImport prepared,
            long requestedRevision) {
        EdtDispatcher.requireEventDispatchThread();
        if (closed || importRevision.get() != requestedRevision) {
            return;
        }

        final Task<?> task;
        try {
            task = prepared.isManuallyCreated()
                    ? ModpackHelper.getInstallManuallyCreatedModpackTask(archive, instanceName, StandardCharsets.UTF_8)
                    : ModpackHelper.getInstallTask(repository, archive, instanceName, prepared.modpack(), "");
        } catch (RuntimeException preparationFailure) {
            LOG.warning("Failed to create local modpack installation task", preparationFailure);
            restoreInputAfterPreparationFailure(requestedRevision, i18n("install.failed"));
            return;
        }

        TaskExecutor executor = task.executor();
        TaskExecutorPresentationModel presentation = new TaskExecutorPresentationModel(
                executor,
                i18n("modpack.installing"),
                i18n("modpack.scan"));
        @Nullable Runnable terminalCleanup = prepared.isManuallyCreated()
                ? null
                : () -> repository.undoMark(instanceName);
        Subscription completionSubscription = executor.subscribeTaskListener(
                new ImportCompletionListener(executor, terminalCleanup));
        activeExecutor = executor;
        activePresentation = presentation;
        activeCompletionSubscription = completionSubscription;
        try {
            progressHost.bind(presentation);
            executor.start();
        } catch (RuntimeException | Error startFailure) {
            LOG.warning("Failed to start local modpack installation", startFailure);
            cleanupFailedTaskStart(presentation, completionSubscription);
            if (terminalCleanup != null) {
                terminalCleanup.run();
            }
            restoreInputAfterPreparationFailure(requestedRevision, i18n("install.failed"));
        }
    }

    /// Restores editable controls after unsupported archive parsing or task preparation failure.
    ///
    /// @param requestedRevision request identity captured before scheduling
    /// @param status localized failure feedback
    private void restoreInputAfterPreparationFailure(long requestedRevision, String status) {
        Objects.requireNonNull(status, "status");
        SwingUiDispatcher.INSTANCE.dispatchOrRun(() -> {
            if (closed || importRevision.get() != requestedRevision) {
                return;
            }
            setInputsEnabled(true);
            setStatus(status);
            updateImportButton();
        });
    }

    /// Completes one import presentation and makes the next archive request available.
    ///
    /// @param executor executor that reached a terminal state
    /// @param succeeded whether every task in the import chain succeeded
    private void importCompleted(TaskExecutor executor, boolean succeeded) {
        SwingUiDispatcher.INSTANCE.dispatchOrRun(() -> {
            if (closed || activeExecutor != executor) {
                return;
            }
            unsubscribe(activeCompletionSubscription);
            activeCompletionSubscription = null;
            activeExecutor = null;
            setInputsEnabled(true);
            if (succeeded) {
                setStatus(i18n("install.success"));
            } else if (executor.isCancelled()) {
                setStatus(i18n("button.cancel"));
            } else {
                setStatus(i18n("install.failed"));
            }
            updateImportButton();
        });
    }

    /// Releases a terminal presentation before a new import begins without cancelling a completed task.
    private void releaseCompletedPresentation() {
        EdtDispatcher.requireEventDispatchThread();
        if (activeExecutor != null) {
            return;
        }
        @Nullable TaskExecutorPresentationModel previousPresentation = activePresentation;
        activePresentation = null;
        progressHost.clear();
        if (previousPresentation != null) {
            previousPresentation.close();
        }
    }

    /// Cleans up a task presentation that failed before executor startup completed.
    ///
    /// @param presentation presentation created for the failed start
    /// @param completionSubscription completion registration created for the failed start
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

    /// Enables or disables editable import controls as one consistent user-input state.
    ///
    /// @param enabled whether the chooser and destination editor accept interaction
    private void setInputsEnabled(boolean enabled) {
        chooseArchiveButton.setEnabled(enabled);
        archiveField.setEnabled(enabled);
        instanceNameField.setEnabled(enabled);
        importButton.setEnabled(enabled && canStartImport());
    }

    /// Reconciles import eligibility from the visible fields and current task lifecycle.
    private void updateImportButton() {
        EdtDispatcher.requireEventDispatchThread();
        importButton.setEnabled(canStartImport());
    }

    /// Returns whether the current visible request may create an import task.
    ///
    /// @return true when the panel is open, idle, and has a valid archive/name pair
    private boolean canStartImport() {
        @Nullable Path archive = selectedArchive;
        return !closed
                && activeExecutor == null
                && archive != null
                && Files.isRegularFile(archive)
                && ModpackHelper.isFileModpackByExtension(archive)
                && XYMLGameRepository.isValidInstanceId(instanceNameField.getText().trim());
    }

    /// Updates the visible feedback label and its assistive tooltip on the EDT.
    ///
    /// @param status localized feedback, or empty text to clear it
    private void setStatus(String status) {
        EdtDispatcher.requireEventDispatchThread();
        Objects.requireNonNull(status, "status");
        statusLabel.setText(status);
        statusLabel.setToolTipText(status.isBlank() ? null : status);
    }

    /// Cancels a live executor and releases all task-presentation subscriptions on the EDT.
    private void closeOnEventDispatchThread() {
        EdtDispatcher.requireEventDispatchThread();
        @Nullable TaskExecutor executor = activeExecutor;
        activeExecutor = null;
        if (executor != null) {
            try {
                executor.cancel();
            } catch (RuntimeException cancellationFailure) {
                LOG.warning("Failed to cancel a local modpack installation during panel close", cancellationFailure);
            }
        }

        unsubscribe(activeCompletionSubscription);
        activeCompletionSubscription = null;
        @Nullable TaskExecutorPresentationModel presentation = activePresentation;
        activePresentation = null;
        if (presentation != null) {
            presentation.close();
        }
        progressHost.close();
        chooseArchiveButton.setEnabled(false);
        archiveField.setEnabled(false);
        instanceNameField.setEnabled(false);
        importButton.setEnabled(false);
    }

    /// Removes one optional task listener registration.
    ///
    /// @param subscription listener registration, or null when no listener is owned
    private static void unsubscribe(@Nullable Subscription subscription) {
        if (subscription != null) {
            subscription.unsubscribe();
        }
    }

    /// Reconciles import eligibility whenever the user edits the destination instance name.
    @NotNullByDefault
    private final class ImportInputListener implements DocumentListener {
        /// Reconciles a text insertion.
        ///
        /// @param event document mutation
        @Override
        public void insertUpdate(DocumentEvent event) {
            updateImportButton();
        }

        /// Reconciles a text removal.
        ///
        /// @param event document mutation
        @Override
        public void removeUpdate(DocumentEvent event) {
            updateImportButton();
        }

        /// Reconciles a document attribute mutation.
        ///
        /// @param event document mutation
        @Override
        public void changedUpdate(DocumentEvent event) {
            updateImportButton();
        }
    }

    /// Receives one executor terminal transition and routes it to the Swing event dispatch thread.
    @NotNullByDefault
    private final class ImportCompletionListener extends TaskListener {
        /// Exact executor whose terminal state this listener represents.
        private final TaskExecutor sourceExecutor;

        /// Optional cleanup required after a provider-backed import reaches its terminal state.
        private final @Nullable Runnable terminalCleanup;

        /// Creates a listener for one exact import executor.
        ///
        /// @param sourceExecutor executor whose terminal result should update this panel
        /// @param terminalCleanup cleanup required after executor completion, or null for manual imports
        private ImportCompletionListener(
                TaskExecutor sourceExecutor,
                @Nullable Runnable terminalCleanup) {
            this.sourceExecutor = Objects.requireNonNull(sourceExecutor, "sourceExecutor");
            this.terminalCleanup = terminalCleanup;
        }

        /// Publishes the terminal import result when this registration's executor stops.
        ///
        /// @param succeeded whether the complete task chain succeeded
        /// @param executor executor reporting terminal state
        @Override
        public void onStop(boolean succeeded, TaskExecutor executor) {
            if (executor == sourceExecutor) {
                runTerminalCleanup();
                importCompleted(sourceExecutor, succeeded);
            }
        }

        /// Clears the provisional repository mark after the executor has actually stopped.
        private void runTerminalCleanup() {
            @Nullable Runnable cleanup = terminalCleanup;
            if (cleanup == null) {
                return;
            }
            try {
                cleanup.run();
            } catch (RuntimeException cleanupFailure) {
                LOG.warning("Failed to clear a terminal local-modpack import marker", cleanupFailure);
            }
        }
    }

    /// Represents a successfully parsed provider archive or a manually assembled archive fallback.
    @NotNullByDefault
    private static final class PreparedImport {
        /// Parsed provider metadata, or null when the archive is manually assembled.
        private final @Nullable Modpack modpack;

        /// Whether ModpackHelper must use the manually-created archive task.
        private final boolean manuallyCreated;

        /// Creates one parsed-archive description.
        ///
        /// @param modpack parsed metadata, or null for a manual archive
        /// @param manuallyCreated whether the manual archive path should be used
        private PreparedImport(@Nullable Modpack modpack, boolean manuallyCreated) {
            this.modpack = modpack;
            this.manuallyCreated = manuallyCreated;
        }

        /// Creates a description for a recognized provider archive.
        ///
        /// @param modpack parsed provider metadata
        /// @return recognized archive description
        static PreparedImport recognized(Modpack modpack) {
            return new PreparedImport(Objects.requireNonNull(modpack, "modpack"), false);
        }

        /// Creates a description for an archive containing a manually assembled Minecraft directory.
        ///
        /// @return manually assembled archive description
        static PreparedImport manuallyCreated() {
            return new PreparedImport(null, true);
        }

        /// Returns whether the archive must use the manually-created installation path.
        ///
        /// @return true when no provider manifest was recognized
        boolean isManuallyCreated() {
            return manuallyCreated;
        }

        /// Returns parsed provider metadata for a recognized archive.
        ///
        /// @return non-null parsed modpack metadata
        Modpack modpack() {
            return Objects.requireNonNull(modpack, "manual archive has no parsed modpack metadata");
        }
    }
}
