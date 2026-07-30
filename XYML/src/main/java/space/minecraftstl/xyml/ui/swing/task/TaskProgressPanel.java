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
package space.minecraftstl.xyml.ui.swing.task;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import space.minecraftstl.xyml.observable.Subscription;
import space.minecraftstl.xyml.observable.ValueChange;
import space.minecraftstl.xyml.task.presentation.TaskPresentationModel;
import space.minecraftstl.xyml.task.presentation.TaskSnapshot;
import space.minecraftstl.xyml.task.presentation.TaskStatus;
import space.minecraftstl.xyml.ui.swing.AnimationHandle;
import space.minecraftstl.xyml.ui.swing.Easing;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;
import space.minecraftstl.xyml.ui.swing.MotionPurpose;
import space.minecraftstl.xyml.ui.swing.SwingAnimator;
import space.minecraftstl.xyml.ui.swing.SwingUiDispatcher;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/// Presents one toolkit-neutral task as a stable Swing progress surface.
///
/// The component must be created on the Swing event dispatch thread. Model notifications may arrive on any
/// thread; every resulting component mutation is routed through [SwingUiDispatcher]. The panel owns its model
/// subscription and must be closed when its host is disposed.
@NotNullByDefault
public final class TaskProgressPanel extends JPanel implements AutoCloseable {
    /// Preferred panel width in logical pixels.
    private static final int PREFERRED_WIDTH = 520;

    /// Preferred panel height in logical pixels, including the reserved details region.
    private static final int PREFERRED_HEIGHT = 284;

    /// Minimum width that keeps labels and commands usable.
    private static final int MINIMUM_WIDTH = 380;

    /// Fixed height of the expandable detail region to prevent surrounding layout jumps.
    private static final int DETAILS_HEIGHT = 96;

    /// Fixed height reserved for action controls even when an action is unavailable.
    private static final int ACTION_ROW_HEIGHT = 34;

    /// Integer progress-bar resolution corresponding to a percentage.
    private static final int PROGRESS_SCALE = 100;

    /// Model supplying the current snapshot and future transitions.
    private final TaskPresentationModel model;

    /// Serializes a close request with model publications that mutate this component tree.
    private final Object publicationLock = new Object();

    /// Synchronously prevents new component mutations before queued EDT cleanup runs.
    private final AtomicBoolean closeRequested = new AtomicBoolean();

    /// Localized labels used by controls and status text.
    private final TaskProgressStrings strings;

    /// Optional animator used to smooth transitions between two known progress values.
    private final @Nullable SwingAnimator animator;

    /// Requested duration for known progress transitions.
    private final Duration progressAnimationDuration;

    /// Main task title label.
    private final JLabel titleLabel = new JLabel();

    /// Current task phase label.
    private final JLabel phaseLabel = new JLabel();

    /// Determinate or indeterminate progress indicator.
    private final JProgressBar progressBar = new JProgressBar(0, PROGRESS_SCALE);

    /// Localized lifecycle status label.
    private final JLabel statusLabel = new JLabel();

    /// One-shot task cancellation command.
    private final JButton cancelButton = new JButton();

    /// Command that expands or collapses the reserved details region.
    private final JButton detailsButton = new JButton();

    /// Read-only multiline detail text.
    private final JTextArea detailsArea = new JTextArea();

    /// Scroll host for long task details.
    private final JScrollPane detailsScrollPane = new JScrollPane(detailsArea);

    /// Model change registration owned by this panel.
    private final Subscription modelSubscription;

    /// Snapshot currently represented by the components, or null before initial rendering completes.
    private @Nullable TaskSnapshot displayedSnapshot;

    /// Current determinate progress animation, or null when values are applied directly.
    private @Nullable AnimationHandle progressAnimation;

    /// Whether the user has already sent a cancellation request through this panel.
    private boolean cancellationRequested;

    /// Whether task details are currently shown.
    private boolean detailsExpanded;

    /// Whether the subscription and animation resources have been released.
    private boolean closed;

    /// Creates a task panel with English fallback text and direct progress updates.
    ///
    /// @param model the toolkit-neutral task presentation model
    public TaskProgressPanel(TaskPresentationModel model) {
        this(model, TaskProgressStrings.english(), null, Duration.ZERO);
    }

    /// Creates a task panel with explicit localization and optional progress animation.
    ///
    /// A null animator or zero duration applies known progress immediately. The caller selects animation timing
    /// explicitly so this component does not invent a motion budget.
    ///
    /// @param model the toolkit-neutral task presentation model
    /// @param strings localized control and status text
    /// @param animator optional shared Swing animator
    /// @param progressAnimationDuration non-negative duration for known progress transitions
    public TaskProgressPanel(
            TaskPresentationModel model,
            TaskProgressStrings strings,
            @Nullable SwingAnimator animator,
            Duration progressAnimationDuration) {
        super(new BorderLayout(0, 12));
        this.model = Objects.requireNonNull(model, "model");
        this.strings = Objects.requireNonNull(strings, "strings");
        this.animator = animator;
        this.progressAnimationDuration = Objects.requireNonNull(
                progressAnimationDuration, "progressAnimationDuration");
        if (progressAnimationDuration.isNegative()) {
            throw new IllegalArgumentException("progressAnimationDuration must not be negative");
        }

        EdtDispatcher.requireEventDispatchThread();
        configureComponents();
        modelSubscription = model.subscribe(this::modelSnapshotChanged);
        try {
            SwingUiDispatcher.INSTANCE.dispatchOrRun(() -> applySnapshot(model.snapshot()));
        } catch (RuntimeException | Error initializationFailure) {
            synchronized (publicationLock) {
                closeRequested.set(true);
                closed = true;
            }
            Throwable combinedFailure = initializationFailure;
            try {
                modelSubscription.unsubscribe();
            } catch (RuntimeException | Error unsubscribeFailure) {
                combinedFailure = combineUncheckedFailures(combinedFailure, unsubscribeFailure);
            }
            try {
                cancelProgressAnimation();
            } catch (RuntimeException | Error animationFailure) {
                combinedFailure = combineUncheckedFailures(combinedFailure, animationFailure);
            }
            throw propagate(combinedFailure);
        }
    }

    /// Returns the snapshot currently represented by this panel.
    ///
    /// This method must be called on the Swing event dispatch thread.
    ///
    /// @return the displayed immutable snapshot
    public TaskSnapshot getDisplayedSnapshot() {
        EdtDispatcher.requireEventDispatchThread();
        return Objects.requireNonNull(displayedSnapshot, "initial task snapshot was not rendered");
    }

    /// Returns whether the progress bar is currently in its indeterminate state.
    ///
    /// This method must be called on the Swing event dispatch thread.
    ///
    /// @return `true` when an active task has no known normalized progress
    public boolean isProgressIndeterminate() {
        EdtDispatcher.requireEventDispatchThread();
        return progressBar.isIndeterminate();
    }

    /// Returns the progress value currently painted by the determinate bar.
    ///
    /// This method must be called on the Swing event dispatch thread.
    ///
    /// @return normalized displayed progress from zero through one
    public double getDisplayedProgress() {
        EdtDispatcher.requireEventDispatchThread();
        return (double) progressBar.getValue() / progressBar.getMaximum();
    }

    /// Returns whether the cancellation command is currently visible.
    ///
    /// This method must be called on the Swing event dispatch thread.
    ///
    /// @return `true` when the current snapshot accepts cancellation and no request has been sent
    public boolean isCancellationActionVisible() {
        EdtDispatcher.requireEventDispatchThread();
        return cancelButton.isVisible();
    }

    /// Returns whether the cancellation command is currently enabled.
    ///
    /// This method must be called on the Swing event dispatch thread.
    ///
    /// @return `true` when cancellation can be requested from this panel
    public boolean isCancellationActionEnabled() {
        EdtDispatcher.requireEventDispatchThread();
        return cancelButton.isEnabled();
    }

    /// Returns whether task details are currently expanded.
    ///
    /// This method must be called on the Swing event dispatch thread.
    ///
    /// @return `true` when detail text is visible
    public boolean isDetailsExpanded() {
        EdtDispatcher.requireEventDispatchThread();
        return detailsExpanded;
    }

    /// Returns the diagnostic text currently held by the detail area.
    ///
    /// This method must be called on the Swing event dispatch thread.
    ///
    /// @return current detail text, or an empty string when absent
    public String getDisplayedDetails() {
        EdtDispatcher.requireEventDispatchThread();
        return detailsArea.getText();
    }

    /// Changes whether available task details are shown.
    ///
    /// Calls from worker threads are queued through [SwingUiDispatcher]. A request to expand empty details is
    /// treated as a collapsed state.
    ///
    /// @param expanded whether available details should be visible
    public void setDetailsExpanded(boolean expanded) {
        SwingUiDispatcher.INSTANCE.dispatchOrRun(() -> {
            synchronized (publicationLock) {
                if (!closeRequested.get()) {
                    setDetailsExpandedOnEventDispatchThread(expanded);
                }
            }
        });
    }

    /// Sends at most one unresolved cancellation request from this panel.
    ///
    /// Calls from worker threads are queued through [SwingUiDispatcher]. The command has no effect when the
    /// current snapshot is terminal or does not permit cancellation. If a request throws and a later authoritative
    /// snapshot permits cancellation again, the user may retry.
    public void requestCancellation() {
        SwingUiDispatcher.INSTANCE.dispatchOrRun(() -> {
            synchronized (publicationLock) {
                if (!closeRequested.get()) {
                    requestCancellationOnEventDispatchThread();
                }
            }
        });
    }

    /// Releases the model subscription and any running progress animation.
    ///
    /// Calls from worker threads are queued through [SwingUiDispatcher]. Closing an already closed panel has no
    /// effect.
    @Override
    public void close() {
        synchronized (publicationLock) {
            if (!closeRequested.compareAndSet(false, true)) {
                return;
            }
        }
        SwingUiDispatcher.INSTANCE.dispatchOrRun(this::closeOnEventDispatchThread);
    }

    /// Creates and lays out all stable component regions.
    private void configureComponents() {
        EdtDispatcher.requireEventDispatchThread();

        setOpaque(false);
        setBorder(BorderFactory.createEmptyBorder(20, 24, 20, 24));
        setPreferredSize(new Dimension(PREFERRED_WIDTH, PREFERRED_HEIGHT));
        setMinimumSize(new Dimension(MINIMUM_WIDTH, PREFERRED_HEIGHT));

        titleLabel.setName("taskTitle");
        titleLabel.setFont(titleLabel.getFont().deriveFont(
                Font.BOLD, titleLabel.getFont().getSize2D() + 2.0F));
        phaseLabel.setName("taskPhase");
        statusLabel.setName("taskStatus");

        JPanel headingPanel = transparentPanel();
        headingPanel.setLayout(new BoxLayout(headingPanel, BoxLayout.Y_AXIS));
        titleLabel.setAlignmentX(LEFT_ALIGNMENT);
        phaseLabel.setAlignmentX(LEFT_ALIGNMENT);
        headingPanel.add(titleLabel);
        headingPanel.add(phaseLabel);

        progressBar.setName("taskProgress");
        progressBar.setStringPainted(false);
        progressBar.getAccessibleContext().setAccessibleName(strings.progressAccessibleName());

        JPanel progressPanel = transparentPanel();
        progressPanel.setLayout(new BorderLayout(0, 8));
        progressPanel.add(progressBar, BorderLayout.CENTER);
        progressPanel.add(statusLabel, BorderLayout.SOUTH);

        cancelButton.setName("taskCancel");
        cancelButton.addActionListener(event -> requestCancellation());
        detailsButton.setName("taskDetailsToggle");
        detailsButton.addActionListener(event -> setDetailsExpanded(!detailsExpanded));

        JPanel actionPanel = transparentPanel();
        actionPanel.setLayout(new FlowLayout(FlowLayout.TRAILING, 8, 0));
        actionPanel.setPreferredSize(new Dimension(1, ACTION_ROW_HEIGHT));
        actionPanel.setMinimumSize(new Dimension(1, ACTION_ROW_HEIGHT));
        actionPanel.add(detailsButton);
        actionPanel.add(cancelButton);

        detailsArea.setName("taskDetails");
        detailsArea.setEditable(false);
        detailsArea.setLineWrap(true);
        detailsArea.setWrapStyleWord(true);
        detailsArea.setRows(4);
        detailsScrollPane.setName("taskDetailsScrollPane");
        detailsScrollPane.setPreferredSize(new Dimension(1, DETAILS_HEIGHT));
        detailsScrollPane.setMinimumSize(new Dimension(1, DETAILS_HEIGHT));
        detailsScrollPane.setVisible(false);

        JPanel footerPanel = transparentPanel();
        footerPanel.setLayout(new BorderLayout(0, 8));
        footerPanel.add(actionPanel, BorderLayout.NORTH);
        footerPanel.add(detailsScrollPane, BorderLayout.CENTER);

        add(headingPanel, BorderLayout.NORTH);
        add(progressPanel, BorderLayout.CENTER);
        add(footerPanel, BorderLayout.SOUTH);
    }

    /// Creates a non-opaque grouping panel that does not introduce another visual card.
    ///
    /// @return a transparent layout-only panel
    private static JPanel transparentPanel() {
        JPanel panel = new JPanel();
        panel.setOpaque(false);
        return panel;
    }

    /// Routes a model transition to a fresh snapshot read on the Swing event dispatch thread.
    ///
    /// Reading the model again on the EDT coalesces queued worker updates and prevents an older event payload from
    /// overwriting a newer snapshot.
    ///
    /// @param change the model transition that invalidated the displayed state
    private void modelSnapshotChanged(ValueChange<TaskSnapshot> change) {
        Objects.requireNonNull(change, "change");
        SwingUiDispatcher.INSTANCE.dispatchOrRun(() -> {
            synchronized (publicationLock) {
                if (!closeRequested.get()) {
                    applySnapshot(model.snapshot());
                }
            }
        });
    }

    /// Applies one immutable snapshot to every component on the Swing event dispatch thread.
    ///
    /// @param snapshot the current model snapshot
    private void applySnapshot(TaskSnapshot snapshot) {
        EdtDispatcher.requireEventDispatchThread();
        Objects.requireNonNull(snapshot, "snapshot");

        @Nullable TaskSnapshot previousSnapshot = displayedSnapshot;
        displayedSnapshot = snapshot;

        titleLabel.setText(snapshot.title());
        titleLabel.setToolTipText(snapshot.title());
        phaseLabel.setText(snapshot.phase());
        phaseLabel.setToolTipText(snapshot.phase());
        statusLabel.setText(strings.statusText(snapshot.status()));
        detailsArea.setText(snapshot.details());
        detailsArea.setCaretPosition(0);

        boolean hasDetails = !snapshot.details().isBlank();
        detailsButton.setVisible(hasDetails);
        detailsButton.setEnabled(hasDetails);
        if (!hasDetails) {
            setDetailsExpandedOnEventDispatchThread(false);
        } else {
            updateDetailsPresentation();
        }

        boolean canCancel = snapshot.cancelable()
                && !snapshot.status().isTerminal()
                && !cancellationRequested;
        cancelButton.setText(strings.cancelAction());
        cancelButton.setVisible(canCancel);
        cancelButton.setEnabled(canCancel);

        updateProgress(previousSnapshot, snapshot);
        revalidate();
        repaint();
    }

    /// Updates determinate, indeterminate, and terminal progress presentation.
    ///
    /// @param previousSnapshot the previously displayed state, or null during initial rendering
    /// @param snapshot the newly displayed state
    private void updateProgress(@Nullable TaskSnapshot previousSnapshot, TaskSnapshot snapshot) {
        EdtDispatcher.requireEventDispatchThread();
        cancelProgressAnimation();

        if (snapshot.progress().isEmpty()) {
            boolean active = !snapshot.status().isTerminal();
            progressBar.setIndeterminate(active);
            progressBar.setValue(snapshot.status() == TaskStatus.SUCCEEDED ? PROGRESS_SCALE : 0);
            return;
        }

        progressBar.setIndeterminate(false);
        int targetValue = normalizedProgressToBarValue(snapshot.progress().getAsDouble());
        boolean canAnimate = animator != null
                && !progressAnimationDuration.isZero()
                && previousSnapshot != null
                && previousSnapshot.progress().isPresent()
                && progressBar.getValue() != targetValue;
        if (!canAnimate) {
            progressBar.setValue(targetValue);
            return;
        }

        int initialValue = progressBar.getValue();
        progressAnimation = animator.animate(
                progressAnimationDuration,
                MotionPurpose.ESSENTIAL,
                Easing.DECELERATE,
                progress -> {
                    synchronized (publicationLock) {
                        if (!closeRequested.get()) {
                            progressBar.setValue(interpolateProgress(initialValue, targetValue, progress));
                        }
                    }
                },
                () -> { });
    }

    /// Converts normalized progress to the integer progress-bar range.
    ///
    /// @param progress normalized progress from zero through one
    /// @return the corresponding integer progress value
    private static int normalizedProgressToBarValue(double progress) {
        return (int) Math.round(progress * PROGRESS_SCALE);
    }

    /// Interpolates between two progress-bar values for one animation frame.
    ///
    /// @param initialValue the progress value at animation start
    /// @param targetValue the final progress value
    /// @param progress eased animation progress from zero through one
    /// @return the integer value for this frame
    private static int interpolateProgress(int initialValue, int targetValue, double progress) {
        return (int) Math.round(initialValue + (targetValue - initialValue) * progress);
    }

    /// Cancels a currently running progress transition without changing the displayed value.
    private void cancelProgressAnimation() {
        EdtDispatcher.requireEventDispatchThread();

        if (progressAnimation != null) {
            progressAnimation.cancel();
            progressAnimation = null;
        }
    }

    /// Applies expanded-detail state on the Swing event dispatch thread.
    ///
    /// @param expanded whether available details should be visible
    private void setDetailsExpandedOnEventDispatchThread(boolean expanded) {
        EdtDispatcher.requireEventDispatchThread();

        if (closeRequested.get()) {
            return;
        }

        boolean hasDetails = !detailsArea.getText().isBlank();
        detailsExpanded = expanded && hasDetails;
        updateDetailsPresentation();
        revalidate();
        repaint();
    }

    /// Synchronizes detail visibility, command text, and accessibility state.
    private void updateDetailsPresentation() {
        EdtDispatcher.requireEventDispatchThread();

        detailsScrollPane.setVisible(detailsExpanded);
        detailsButton.setText(detailsExpanded
                ? strings.hideDetailsAction()
                : strings.showDetailsAction());
        detailsButton.getAccessibleContext().setAccessibleName(detailsButton.getText());
    }

    /// Sends cancellation after rechecking the current display state on the event dispatch thread.
    private void requestCancellationOnEventDispatchThread() {
        EdtDispatcher.requireEventDispatchThread();

        @Nullable TaskSnapshot snapshot = displayedSnapshot;
        if (closeRequested.get()
                || closed
                || cancellationRequested
                || snapshot == null
                || snapshot.status().isTerminal()
                || !snapshot.cancelable()) {
            return;
        }

        cancellationRequested = true;
        cancelButton.setEnabled(false);
        cancelButton.setVisible(false);
        try {
            model.requestCancellation();
        } catch (RuntimeException | Error cancellationFailure) {
            cancellationRequested = false;
            @Nullable TaskSnapshot recoverySnapshot = null;
            try {
                recoverySnapshot = model.snapshot();
                if (!closed) {
                    applySnapshot(recoverySnapshot);
                }
            } catch (RuntimeException | Error recoveryFailure) {
                TaskSnapshot fallbackSnapshot = recoverySnapshot == null ? snapshot : recoverySnapshot;
                if (!closed) {
                    restoreCancellationAction(fallbackSnapshot);
                }
                throw propagate(combineUncheckedFailures(cancellationFailure, recoveryFailure));
            }
            throw cancellationFailure;
        }
    }

    /// Restores only the cancellation command after best-effort snapshot recovery itself fails.
    ///
    /// @param snapshot last reliable task state
    private void restoreCancellationAction(TaskSnapshot snapshot) {
        EdtDispatcher.requireEventDispatchThread();
        boolean canCancel = snapshot.cancelable() && !snapshot.status().isTerminal();
        cancelButton.setVisible(canCancel);
        cancelButton.setEnabled(canCancel);
    }

    /// Combines two unchecked failures while preventing a runtime exception from hiding an [Error].
    ///
    /// @param primary original cancellation failure
    /// @param secondary recovery failure
    /// @return failure that retains the other as suppressed context
    private static Throwable combineUncheckedFailures(Throwable primary, Throwable secondary) {
        if (primary == secondary) {
            return primary;
        }
        if (!(primary instanceof Error) && secondary instanceof Error) {
            secondary.addSuppressed(primary);
            return secondary;
        }
        primary.addSuppressed(secondary);
        return primary;
    }

    /// Returns or throws an unchecked failure without erasing an [Error].
    ///
    /// @param failure unchecked failure to propagate
    /// @return runtime exception when the failure is not an error
    private static RuntimeException propagate(Throwable failure) {
        if (failure instanceof Error error) {
            throw error;
        }
        return (RuntimeException) failure;
    }

    /// Releases resources on the Swing event dispatch thread.
    private void closeOnEventDispatchThread() {
        EdtDispatcher.requireEventDispatchThread();

        if (!closed) {
            closed = true;
            @Nullable Throwable closingFailure = null;
            try {
                modelSubscription.unsubscribe();
            } catch (RuntimeException | Error unsubscribeFailure) {
                closingFailure = unsubscribeFailure;
            }
            try {
                cancelProgressAnimation();
            } catch (RuntimeException | Error animationFailure) {
                closingFailure = closingFailure == null
                        ? animationFailure
                        : combineUncheckedFailures(closingFailure, animationFailure);
            }
            if (closingFailure != null) {
                throw propagate(closingFailure);
            }
        }
    }
}
