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
package space.minecraftstl.xyml.ui.swing.page.instances.importing;

import net.miginfocom.swing.MigLayout;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import space.minecraftstl.xyml.game.XYMLGameRepository;
import space.minecraftstl.xyml.observable.Subscription;
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
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;

import static space.minecraftstl.xyml.util.logging.Logger.LOG;

/// Presents one reusable, cancellable Minecraft instance JSON import workflow.
///
/// Path replacement and visible validation are EDT-confined and never read the source file. The
/// injected service must return a deferred task so JSON parsing and child-task preparation happen
/// only after the executor starts on its worker scheduler.
@NotNullByDefault
public final class InstanceJsonImportPanel extends JPanel implements AutoCloseable {
    /// Deferred repository import boundary.
    private final InstanceJsonImportService service;

    /// Localized workflow text.
    private final InstanceJsonImportStrings strings;

    /// Read-only selected source path.
    private final JTextField sourceField = new JTextField();

    /// Editable destination instance ID.
    private final JTextField instanceIdField = new JTextField();

    /// Command beginning the deferred import task.
    private final JButton importButton = new JButton();

    /// Current validation or terminal feedback.
    private final JLabel statusLabel = new JLabel();

    /// Cancellable task presentation surface.
    private final TaskProgressHostPanel progressHost;

    /// Revalidates the pure destination-name constraint after edits.
    private final DocumentListener nameListener = new InstanceNameListener();

    /// Current source path, or null before the launcher opens a JSON file.
    private @Nullable Path source;

    /// Running executor, or null while idle or after termination.
    private @Nullable TaskExecutor activeExecutor;

    /// Presentation retained until replacement or panel closure.
    private @Nullable TaskExecutorPresentationModel activePresentation;

    /// Completion subscription for the active executor.
    private @Nullable Subscription activeCompletionSubscription;

    /// Whether this panel has permanently released its task resources.
    private boolean closed;

    /// Creates an empty reusable import panel on the Swing event-dispatch thread.
    ///
    /// @param service deferred import service
    /// @param strings localized workflow text
    /// @param taskProgressStrings localized task lifecycle text
    /// @param animator optional shared progress animator
    /// @param progressAnimationDuration non-negative progress animation duration
    public InstanceJsonImportPanel(
            InstanceJsonImportService service,
            InstanceJsonImportStrings strings,
            TaskProgressStrings taskProgressStrings,
            @Nullable SwingAnimator animator,
            Duration progressAnimationDuration) {
        super(new MigLayout(
                "insets 20 24 24 24, fill, wrap 2",
                "[][grow,fill]",
                "[40!]10[40!]10[]12[grow,fill]"));
        EdtDispatcher.requireEventDispatchThread();
        this.service = Objects.requireNonNull(service, "service");
        this.strings = Objects.requireNonNull(strings, "strings");
        progressHost = new TaskProgressHostPanel(
                Objects.requireNonNull(taskProgressStrings, "taskProgressStrings"),
                animator,
                Objects.requireNonNull(progressAnimationDuration, "progressAnimationDuration"));

        JLabel sourceLabel = new JLabel(strings.sourceLabel());
        sourceLabel.setLabelFor(sourceField);
        add(sourceLabel);
        sourceField.setName("instanceJsonSource");
        sourceField.setEditable(false);
        add(sourceField, "growx, h 40!");

        JLabel instanceIdLabel = new JLabel(strings.instanceIdLabel());
        instanceIdLabel.setLabelFor(instanceIdField);
        add(instanceIdLabel);
        instanceIdField.setName("instanceJsonInstanceId");
        instanceIdField.getDocument().addDocumentListener(nameListener);
        add(instanceIdField, "growx, h 40!");

        importButton.setName("instanceJsonImport");
        importButton.setText(strings.importAction());
        importButton.addActionListener(event -> beginImport());
        add(importButton, "skip 1, split 2, w 144!, h 40!");
        statusLabel.setName("instanceJsonStatus");
        add(statusLabel, "growx");

        progressHost.setName("instanceJsonProgress");
        add(progressHost, "span 2, grow");
        updateImportEligibility();
    }

    /// Replaces the source and derives its default instance name while no task is running.
    ///
    /// This method performs lexical path work only and never stats or opens the file.
    ///
    /// @param selectedSource JSON source selected by shell drag-and-drop
    public void open(Path selectedSource) {
        EdtDispatcher.requireEventDispatchThread();
        if (closed || activeExecutor != null) {
            return;
        }
        releaseCompletedPresentation();
        source = Objects.requireNonNull(selectedSource, "selectedSource")
                .toAbsolutePath()
                .normalize();
        sourceField.setText(source.toString());
        instanceIdField.setText(FileUtils.getNameWithoutExtension(source));
        setStatus(strings.readyStatus());
        updateImportEligibility();
    }

    /// Cancels a running import and releases all task presentation resources.
    @Override
    public void close() {
        SwingUiDispatcher.INSTANCE.dispatchOrRun(this::closeOnEventDispatchThread);
    }

    /// Validates visible input and starts the deferred task without parsing on the EDT.
    private void beginImport() {
        EdtDispatcher.requireEventDispatchThread();
        @Nullable Path selectedSource = source;
        String instanceId = instanceIdField.getText().strip();
        if (closed || activeExecutor != null || selectedSource == null) {
            return;
        }
        if (!XYMLGameRepository.isValidVersionId(instanceId)) {
            setStatus(strings.invalidInstanceIdStatus());
            updateImportEligibility();
            return;
        }

        releaseCompletedPresentation();
        final Task<@Nullable Void> task;
        try {
            task = Objects.requireNonNull(
                    service.createImportTask(selectedSource, instanceId),
                    "service returned null task");
        } catch (RuntimeException preparationFailure) {
            LOG.warning("Failed to create deferred instance JSON import task", preparationFailure);
            setStatus(strings.failedStatus());
            return;
        }

        TaskExecutor executor = task.executor();
        TaskExecutorPresentationModel presentation = new TaskExecutorPresentationModel(
                executor,
                strings.importingStatus(),
                selectedSource.getFileName().toString());
        Subscription completionSubscription = executor.subscribeTaskListener(
                new ImportCompletionListener(executor));
        activeExecutor = executor;
        activePresentation = presentation;
        activeCompletionSubscription = completionSubscription;
        setInputsEnabled(false);
        setStatus(strings.importingStatus());
        try {
            progressHost.bind(presentation);
            executor.start();
        } catch (RuntimeException | Error startFailure) {
            LOG.warning("Failed to start instance JSON import", startFailure);
            cleanupFailedStart(presentation, completionSubscription);
            setInputsEnabled(true);
            setStatus(strings.failedStatus());
        }
    }

    /// Handles one terminal executor state on the Swing event-dispatch thread.
    ///
    /// @param executor exact executor that terminated
    /// @param succeeded whether the complete task chain succeeded
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
                setStatus(strings.succeededStatus());
            } else if (executor.isCancelled()) {
                setStatus(strings.cancelledStatus());
            } else {
                setStatus(resolveFailureStatus(executor.getFailure()));
            }
            updateImportEligibility();
        });
    }

    /// Maps a categorized worker failure to localized feedback.
    ///
    /// @param failure terminal task failure, or null for an uncategorized failure
    /// @return localized visible status
    private String resolveFailureStatus(@Nullable Throwable failure) {
        @Nullable Throwable current = failure;
        while (current != null) {
            if (current instanceof InstanceJsonImportException importFailure) {
                return switch (importFailure.reason()) {
                    case INVALID_INSTANCE_ID -> strings.invalidInstanceIdStatus();
                    case INSTANCE_ALREADY_EXISTS -> strings.instanceAlreadyExistsStatus();
                    case MALFORMED_JSON -> strings.malformedJsonStatus();
                };
            }
            current = current.getCause();
        }
        return strings.failedStatus();
    }

    /// Releases a completed presentation before replacing the source or beginning another import.
    private void releaseCompletedPresentation() {
        EdtDispatcher.requireEventDispatchThread();
        if (activeExecutor != null) {
            return;
        }
        @Nullable TaskExecutorPresentationModel previous = activePresentation;
        activePresentation = null;
        progressHost.clear();
        if (previous != null) {
            previous.close();
        }
    }

    /// Releases resources created before task startup failed.
    ///
    /// @param presentation failed presentation
    /// @param completionSubscription failed executor subscription
    private void cleanupFailedStart(
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

    /// Enables or disables editable workflow controls as one state transition.
    ///
    /// @param enabled whether user input is accepted
    private void setInputsEnabled(boolean enabled) {
        sourceField.setEnabled(enabled);
        instanceIdField.setEnabled(enabled);
        importButton.setEnabled(enabled && canImport());
    }

    /// Reconciles import eligibility after source or name changes.
    private void updateImportEligibility() {
        EdtDispatcher.requireEventDispatchThread();
        importButton.setEnabled(canImport());
    }

    /// Returns whether visible pure input validation permits task creation.
    ///
    /// @return whether the panel is open, idle, and has a valid source/name pair
    private boolean canImport() {
        return !closed
                && activeExecutor == null
                && source != null
                && XYMLGameRepository.isValidVersionId(instanceIdField.getText().strip());
    }

    /// Updates visible and assistive status feedback.
    ///
    /// @param status localized status text
    private void setStatus(String status) {
        EdtDispatcher.requireEventDispatchThread();
        String resolvedStatus = Objects.requireNonNull(status, "status");
        statusLabel.setText(resolvedStatus);
        statusLabel.setToolTipText(resolvedStatus.isBlank() ? null : resolvedStatus);
    }

    /// Cancels live work and closes every retained presentation on the EDT.
    private void closeOnEventDispatchThread() {
        EdtDispatcher.requireEventDispatchThread();
        if (closed) {
            return;
        }
        closed = true;
        @Nullable TaskExecutor executor = activeExecutor;
        activeExecutor = null;
        if (executor != null) {
            try {
                executor.cancel();
            } catch (RuntimeException cancellationFailure) {
                LOG.warning("Failed to cancel instance JSON import during panel close", cancellationFailure);
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
        sourceField.setEnabled(false);
        instanceIdField.setEnabled(false);
        importButton.setEnabled(false);
    }

    /// Removes one optional subscription without exposing cleanup races to callers.
    ///
    /// @param subscription subscription to remove, or null
    private static void unsubscribe(@Nullable Subscription subscription) {
        if (subscription != null) {
            subscription.unsubscribe();
        }
    }

    /// Revalidates the destination ID after each document mutation.
    @NotNullByDefault
    private final class InstanceNameListener implements DocumentListener {
        /// Handles text insertion.
        ///
        /// @param event document event
        @Override
        public void insertUpdate(DocumentEvent event) {
            Objects.requireNonNull(event, "event");
            updateImportEligibility();
        }

        /// Handles text removal.
        ///
        /// @param event document event
        @Override
        public void removeUpdate(DocumentEvent event) {
            Objects.requireNonNull(event, "event");
            updateImportEligibility();
        }

        /// Handles styled-document attribute changes.
        ///
        /// @param event document event
        @Override
        public void changedUpdate(DocumentEvent event) {
            Objects.requireNonNull(event, "event");
            updateImportEligibility();
        }
    }

    /// Routes one exact executor's terminal callback to the panel.
    @NotNullByDefault
    private final class ImportCompletionListener extends TaskListener {
        /// Executor represented by this listener registration.
        private final TaskExecutor sourceExecutor;

        /// Creates a listener for one executor identity.
        ///
        /// @param sourceExecutor represented executor
        private ImportCompletionListener(TaskExecutor sourceExecutor) {
            this.sourceExecutor = Objects.requireNonNull(sourceExecutor, "sourceExecutor");
        }

        /// Publishes the terminal result when the represented executor stops.
        ///
        /// @param succeeded whether the complete task chain succeeded
        /// @param executor executor reporting the terminal state
        @Override
        public void onStop(boolean succeeded, TaskExecutor executor) {
            if (executor == sourceExecutor) {
                importCompleted(sourceExecutor, succeeded);
            }
        }
    }
}
