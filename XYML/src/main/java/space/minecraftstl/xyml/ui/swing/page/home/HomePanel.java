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

import net.miginfocom.swing.MigLayout;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import space.minecraftstl.xyml.game.launch.LaunchSession;
import space.minecraftstl.xyml.observable.Subscription;
import space.minecraftstl.xyml.observable.ValueChange;
import space.minecraftstl.xyml.observable.property.ReadOnlyProperty;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;
import space.minecraftstl.xyml.ui.swing.SwingAnimator;
import space.minecraftstl.xyml.ui.swing.SwingUiDispatcher;
import space.minecraftstl.xyml.ui.swing.task.TaskProgressHostPanel;
import space.minecraftstl.xyml.ui.swing.task.TaskProgressStrings;

import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSeparator;
import java.awt.CardLayout;
import java.awt.Cursor;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/// Presents launcher readiness, selected account and instance, and the primary launch command.
///
/// Selection rows are full-width commands rather than nested cards. The panel owns its model subscription and
/// must be closed when its cached shell page is permanently discarded.
@NotNullByDefault
public final class HomePanel extends JPanel implements AutoCloseable {
    /// Card identifier for the account and instance selectors.
    private static final String SELECTION_VIEW = "selection";

    /// Card identifier for the current launch task.
    private static final String TASK_VIEW = "task";

    /// Home model supplying state and commands.
    private final HomeModel model;

    /// Serializes close with EDT publications that mutate this component tree.
    private final Object publicationLock = new Object();

    /// Localized home-page text.
    private final HomeStrings strings;

    /// Selected-account command row.
    private final SelectionButton accountButton;

    /// Selected-instance command row.
    private final SelectionButton instanceButton;

    /// New-instance command.
    private final JButton addInstanceButton = new JButton();

    /// Command returning from a terminal launch task to the selectors.
    private final JButton backToSelectionsButton = new JButton();

    /// Primary launch command.
    private final JButton launchButton = new JButton();

    /// Current readiness or operation status.
    private final JLabel statusLabel = new JLabel();

    /// Stable card host for selectors and task progress.
    private final JPanel centerCards = new JPanel(new CardLayout());

    /// Stable card host for selection-specific and task-specific secondary commands.
    private final JPanel secondaryActionCards = new JPanel(new CardLayout());

    /// Owns the currently presented launch task panel.
    private final TaskProgressHostPanel taskProgressHost;

    /// Stable current-launch-session property used for reads and subscription ownership.
    private final ReadOnlyProperty<Optional<LaunchSession>> launchSessionProperty;

    /// Owned home-state listener registration.
    private final Subscription modelSubscription;

    /// Owned current-launch-session listener registration.
    private final Subscription launchSessionSubscription;

    /// Snapshot currently represented by the controls, or null before initialization.
    private @Nullable HomeSnapshot displayedSnapshot;

    /// Launch session currently represented by the task card, or null before the first launch.
    private @Nullable LaunchSession displayedLaunchSession;

    /// Whether the task card rather than the selection card is currently visible.
    private boolean taskViewVisible;

    /// Prevents repeated listener and child-resource cleanup from any caller thread.
    private final AtomicBoolean closed = new AtomicBoolean();

    /// Creates a launcher home panel on the EDT.
    ///
    /// @param model toolkit-neutral home model
    /// @param strings localized home-page text
    /// @param taskProgressStrings localized launch-task text
    /// @param animator optional shared progress animator
    /// @param progressAnimationDuration non-negative launch-progress animation duration
    public HomePanel(
            HomeModel model,
            HomeStrings strings,
            TaskProgressStrings taskProgressStrings,
            @Nullable SwingAnimator animator,
            Duration progressAnimationDuration) {
        super(new MigLayout(
                "insets 0, fill, wrap 1",
                "[grow,fill]",
                "[]28[grow,fill]20[]"));
        EdtDispatcher.requireEventDispatchThread();
        this.model = Objects.requireNonNull(model, "model");
        this.strings = Objects.requireNonNull(strings, "strings");
        taskProgressHost = new TaskProgressHostPanel(
                Objects.requireNonNull(taskProgressStrings, "taskProgressStrings"),
                animator,
                Objects.requireNonNull(progressAnimationDuration, "progressAnimationDuration"));
        accountButton = new SelectionButton("homeAccount", strings.accountLabel());
        instanceButton = new SelectionButton("homeInstance", strings.instanceLabel());

        configureComponents();
        launchSessionProperty = model.launchSessionProperty();
        @Nullable Subscription acquiredModelSubscription = null;
        @Nullable Subscription acquiredLaunchSessionSubscription = null;
        try {
            Optional<LaunchSession> initialLaunchSession = readLaunchSession(launchSessionProperty);
            acquiredModelSubscription = model.subscribe(this::modelChanged);
            acquiredLaunchSessionSubscription = launchSessionProperty.subscribe(this::launchSessionChanged);
            applySnapshot(model.snapshot());
            applyLaunchSession(initialLaunchSession);
            applyLaunchSession(readLaunchSession(launchSessionProperty));
        } catch (RuntimeException | Error initializationFailure) {
            synchronized (publicationLock) {
                closed.set(true);
            }
            final @Nullable Subscription modelRegistration = acquiredModelSubscription;
            final @Nullable Subscription launchRegistration = acquiredLaunchSessionSubscription;
            @Nullable Throwable combinedFailure = initializationFailure;
            combinedFailure = attemptCleanup(
                    combinedFailure,
                    () -> unsubscribeIfPresent(modelRegistration));
            combinedFailure = attemptCleanup(
                    combinedFailure,
                    () -> unsubscribeIfPresent(launchRegistration));
            combinedFailure = attemptCleanup(combinedFailure, taskProgressHost::close);
            rethrowCleanupFailure(combinedFailure);
            throw new AssertionError("home panel initialization failure was lost", initializationFailure);
        }
        modelSubscription = Objects.requireNonNull(
                acquiredModelSubscription,
                "home model subscription was not acquired");
        launchSessionSubscription = Objects.requireNonNull(
                acquiredLaunchSessionSubscription,
                "launch-session subscription was not acquired");
    }

    /// Returns the immutable snapshot currently represented by the home controls.
    ///
    /// @return displayed launcher-home state
    public HomeSnapshot displayedSnapshot() {
        EdtDispatcher.requireEventDispatchThread();
        return Objects.requireNonNull(displayedSnapshot, "initial home snapshot was not applied");
    }

    /// Returns whether launch progress rather than the selectors is currently visible.
    ///
    /// @return `true` while the task card is selected
    public boolean isTaskViewVisible() {
        EdtDispatcher.requireEventDispatchThread();
        return taskViewVisible;
    }

    /// Releases model subscriptions and the task host from any caller thread.
    @Override
    public void close() {
        synchronized (publicationLock) {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
        }
        @Nullable Throwable closingFailure = null;
        closingFailure = attemptCleanup(closingFailure, modelSubscription::unsubscribe);
        closingFailure = attemptCleanup(closingFailure, launchSessionSubscription::unsubscribe);
        closingFailure = attemptCleanup(closingFailure, taskProgressHost::close);
        rethrowCleanupFailure(closingFailure);
    }

    /// Builds the stable unframed home-page layout.
    private void configureComponents() {
        JLabel heading = new JLabel(strings.pageTitle());
        heading.setName("homePageTitle");
        heading.setFont(heading.getFont().deriveFont(Font.BOLD, 28.0F));
        add(heading);

        JPanel selectionPanel = new JPanel(new MigLayout(
                "insets 0, fill, wrap 1",
                "[grow,fill]",
                "[76!]0[]0[76!]"));
        selectionPanel.setOpaque(false);
        selectionPanel.setName("homeSelections");
        accountButton.addActionListener(event -> model.selectAccount());
        instanceButton.addActionListener(event -> model.selectInstance());
        selectionPanel.add(accountButton, "h 76!");
        selectionPanel.add(new JSeparator(), "growx");
        selectionPanel.add(instanceButton, "h 76!");

        centerCards.setOpaque(false);
        centerCards.setName("homeCenterCards");
        taskProgressHost.setName("homeTaskProgressHost");
        centerCards.add(selectionPanel, SELECTION_VIEW);
        centerCards.add(taskProgressHost, TASK_VIEW);
        add(centerCards, "grow, push");

        JPanel actionBand = new JPanel(new MigLayout(
                "insets 0, fill",
                "[grow,fill]16[180!]16[260!]",
                "[64!]"));
        actionBand.setOpaque(false);
        actionBand.setName("homeActionBand");

        statusLabel.setName("homeStatus");
        addInstanceButton.setName("homeAddInstance");
        addInstanceButton.setText(strings.addInstanceAction());
        addInstanceButton.addActionListener(event -> model.addInstance());
        backToSelectionsButton.setName("homeBackToSelections");
        backToSelectionsButton.setText(strings.backToSelectionsAction());
        backToSelectionsButton.addActionListener(event -> showSelectionView());
        secondaryActionCards.setOpaque(false);
        secondaryActionCards.add(addInstanceButton, SELECTION_VIEW);
        secondaryActionCards.add(backToSelectionsButton, TASK_VIEW);
        launchButton.setName("homeLaunch");
        launchButton.putClientProperty("JButton.buttonType", "roundRect");
        launchButton.setFont(launchButton.getFont().deriveFont(Font.BOLD, 17.0F));
        launchButton.addActionListener(event -> model.launch());

        actionBand.add(statusLabel, "growx");
        actionBand.add(secondaryActionCards, "grow, h 40!");
        actionBand.add(launchButton, "grow");
        add(actionBand, "growx");
    }

    /// Coalesces a worker-published transition to the model's latest snapshot on the EDT.
    ///
    /// @param change transition that invalidated the displayed page
    private void modelChanged(ValueChange<HomeSnapshot> change) {
        Objects.requireNonNull(change, "change");
        SwingUiDispatcher.INSTANCE.dispatchOrRun(() -> {
            synchronized (publicationLock) {
                if (!closed.get()) {
                    applySnapshot(model.snapshot());
                    applyLaunchSession(readLaunchSession(launchSessionProperty));
                }
            }
        });
    }

    /// Coalesces a worker-published launch-session transition to the property's latest identity on the EDT.
    ///
    /// @param change transition that invalidated the current task card
    private void launchSessionChanged(ValueChange<Optional<LaunchSession>> change) {
        Objects.requireNonNull(change, "change");
        SwingUiDispatcher.INSTANCE.dispatchOrRun(() -> {
            synchronized (publicationLock) {
                if (!closed.get()) {
                    applyLaunchSession(readLaunchSession(launchSessionProperty));
                }
            }
        });
    }

    /// Applies one immutable home state to every control.
    ///
    /// @param snapshot latest home state
    private void applySnapshot(HomeSnapshot snapshot) {
        EdtDispatcher.requireEventDispatchThread();
        Objects.requireNonNull(snapshot, "snapshot");
        displayedSnapshot = snapshot;

        accountButton.setValues(
                snapshot.accountName().isBlank() ? strings.missingAccountLabel() : snapshot.accountName(),
                snapshot.accountDetail());
        instanceButton.setValues(
                snapshot.instanceName().isBlank() ? strings.missingInstanceLabel() : snapshot.instanceName(),
                snapshot.instanceDetail());
        accountButton.setEnabled(snapshot.selectionCommandsEnabled());
        instanceButton.setEnabled(snapshot.selectionCommandsEnabled());
        addInstanceButton.setEnabled(snapshot.selectionCommandsEnabled());
        statusLabel.setText(snapshot.statusText());
        statusLabel.setToolTipText(snapshot.statusText());
        launchButton.setText(snapshot.launching() ? strings.launchingAction() : strings.launchAction());
        launchButton.setEnabled(snapshot.launchEnabled());
        updateTaskActions();
    }

    /// Replaces the task host only when the launch-session identity changes.
    ///
    /// A terminal task remains bound until another session replaces it or the page is closed. This preserves
    /// diagnostic details while the user decides whether to retry or return to the selectors.
    ///
    /// @param launchSession optional latest launch session
    private void applyLaunchSession(Optional<LaunchSession> launchSession) {
        EdtDispatcher.requireEventDispatchThread();
        Objects.requireNonNull(launchSession, "launchSession");
        @Nullable LaunchSession replacement = launchSession.orElse(null);
        if (displayedLaunchSession == replacement) {
            updateTaskActions();
            return;
        }

        if (replacement == null) {
            displayedLaunchSession = null;
            @Nullable Throwable clearingFailure = null;
            clearingFailure = attemptCleanup(clearingFailure, taskProgressHost::clear);
            clearingFailure = attemptCleanup(clearingFailure, this::showSelectionView);
            rethrowCleanupFailure(clearingFailure);
            return;
        }

        try {
            taskProgressHost.bind(replacement);
        } catch (RuntimeException | Error bindingFailure) {
            @Nullable Object boundModel = taskProgressHost.boundModel().orElse(null);
            if (boundModel == replacement) {
                displayedLaunchSession = replacement;
                showTaskView();
            }
            throw bindingFailure;
        }
        displayedLaunchSession = replacement;
        showTaskView();
    }

    /// Shows the selector card and its secondary command.
    private void showSelectionView() {
        EdtDispatcher.requireEventDispatchThread();
        @Nullable LaunchSession launchSession = displayedLaunchSession;
        @Nullable HomeSnapshot snapshot = displayedSnapshot;
        if (launchSession != null
                && (!launchSession.status().isTerminal()
                || snapshot == null
                || !snapshot.selectionCommandsEnabled())) {
            return;
        }
        taskViewVisible = false;
        ((CardLayout) centerCards.getLayout()).show(centerCards, SELECTION_VIEW);
        ((CardLayout) secondaryActionCards.getLayout()).show(secondaryActionCards, SELECTION_VIEW);
        updateTaskActions();
    }

    /// Shows the launch-task card and its secondary command.
    private void showTaskView() {
        EdtDispatcher.requireEventDispatchThread();
        taskViewVisible = true;
        ((CardLayout) centerCards.getLayout()).show(centerCards, TASK_VIEW);
        ((CardLayout) secondaryActionCards.getLayout()).show(secondaryActionCards, TASK_VIEW);
        updateTaskActions();
    }

    /// Synchronizes secondary-command availability with the current card and launch lifecycle.
    private void updateTaskActions() {
        EdtDispatcher.requireEventDispatchThread();
        @Nullable LaunchSession launchSession = displayedLaunchSession;
        @Nullable HomeSnapshot snapshot = displayedSnapshot;
        backToSelectionsButton.setEnabled(
                taskViewVisible
                        && launchSession != null
                        && launchSession.status().isTerminal()
                        && snapshot != null
                        && snapshot.selectionCommandsEnabled());
    }

    /// Reads and validates a launch-session property value.
    ///
    /// @param property property to read
    /// @return non-null optional launch session
    private static Optional<LaunchSession> readLaunchSession(
            ReadOnlyProperty<Optional<LaunchSession>> property) {
        @Nullable Optional<LaunchSession> launchSession = Objects.requireNonNull(property, "property").getValue();
        return Objects.requireNonNull(launchSession, "launchSession property value");
    }

    /// Attempts one cleanup operation and retains every unchecked failure.
    ///
    /// @param previousFailure first earlier failure, or null
    /// @param cleanup cleanup operation to attempt
    /// @return accumulated failure, or null when every operation succeeded
    private static @Nullable Throwable attemptCleanup(
            @Nullable Throwable previousFailure,
            Runnable cleanup) {
        try {
            cleanup.run();
            return previousFailure;
        } catch (RuntimeException | Error cleanupFailure) {
            if (previousFailure == null) {
                return cleanupFailure;
            }
            if (previousFailure == cleanupFailure) {
                return previousFailure;
            }
            if (!(previousFailure instanceof Error) && cleanupFailure instanceof Error) {
                cleanupFailure.addSuppressed(previousFailure);
                return cleanupFailure;
            }
            previousFailure.addSuppressed(cleanupFailure);
            return previousFailure;
        }
    }

    /// Unsubscribes an optional constructor-acquired registration.
    ///
    /// @param subscription registration to remove, or null when acquisition failed first
    private static void unsubscribeIfPresent(@Nullable Subscription subscription) {
        if (subscription != null) {
            subscription.unsubscribe();
        }
    }

    /// Rethrows an accumulated unchecked cleanup failure when present.
    ///
    /// @param failure accumulated cleanup failure, or null
    private static void rethrowCleanupFailure(@Nullable Throwable failure) {
        if (failure == null) {
            return;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        throw (RuntimeException) failure;
    }

    /// Full-width account or instance selection command with stable title and detail regions.
    @NotNullByDefault
    private static final class SelectionButton extends JButton {
        /// Left padding used by painted field and value text.
        private static final int TEXT_INSET = 14;

        /// Gap between the field label and selected value baselines.
        private static final int VALUE_BASELINE_GAP = 25;

        /// Localized field label painted above the current selection.
        private final String fieldLabel;

        /// Current selected display value.
        private String value = "";

        /// Current short provider or version detail.
        private String detail = "";

        /// Creates one selection command row.
        ///
        /// @param componentName stable automation name
        /// @param fieldLabel localized field label
        private SelectionButton(String componentName, String fieldLabel) {
            this.fieldLabel = Objects.requireNonNull(fieldLabel, "fieldLabel");
            setName(componentName);
            setHorizontalAlignment(LEFT);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            putClientProperty("JButton.buttonType", "toolBarButton");
            setText("");
        }

        /// Updates the selected value and short detail without changing row geometry.
        ///
        /// @param value selected display value
        /// @param detail short provider or version detail
        private void setValues(String value, String detail) {
            this.value = Objects.requireNonNull(value, "value");
            this.detail = Objects.requireNonNull(detail, "detail");
            setToolTipText(detail.isBlank() ? value : value + " - " + detail);
            getAccessibleContext().setAccessibleDescription(value + (detail.isBlank() ? "" : ", " + detail));
            repaint();
        }

        /// Paints the field, selected value, and trailing detail while retaining the current look-and-feel button.
        ///
        /// @param graphics target button graphics
        @Override
        protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            Graphics2D textGraphics = (Graphics2D) graphics.create();
            try {
                textGraphics.setRenderingHint(
                        RenderingHints.KEY_TEXT_ANTIALIASING,
                        RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                textGraphics.setColor(getForeground());

                Font fieldFont = getFont().deriveFont(Math.max(10.0F, getFont().getSize2D() - 1.0F));
                Font valueFont = getFont().deriveFont(Font.BOLD, getFont().getSize2D() + 2.0F);
                FontMetrics fieldMetrics = textGraphics.getFontMetrics(fieldFont);
                FontMetrics valueMetrics = textGraphics.getFontMetrics(valueFont);
                int fieldBaseline = Math.max(fieldMetrics.getAscent() + 9, getHeight() / 2 - 7);
                int valueBaseline = Math.min(
                        getHeight() - valueMetrics.getDescent() - 8,
                        fieldBaseline + VALUE_BASELINE_GAP);

                textGraphics.setFont(fieldFont);
                textGraphics.drawString(
                        fitText(fieldMetrics, fieldLabel, getWidth() - TEXT_INSET * 2),
                        TEXT_INSET,
                        fieldBaseline);

                textGraphics.setFont(valueFont);
                int detailWidth = detail.isBlank()
                        ? 0
                        : Math.min(valueMetrics.stringWidth(detail), Math.max(0, getWidth() / 3));
                int valueWidth = Math.max(0, getWidth() - TEXT_INSET * 3 - detailWidth);
                textGraphics.drawString(
                        fitText(valueMetrics, value, valueWidth),
                        TEXT_INSET,
                        valueBaseline);
                if (!detail.isBlank()) {
                    String fittedDetail = fitText(valueMetrics, detail, detailWidth);
                    int detailX = getWidth() - TEXT_INSET - valueMetrics.stringWidth(fittedDetail);
                    textGraphics.drawString(fittedDetail, detailX, valueBaseline);
                }
            } finally {
                textGraphics.dispose();
            }
        }

        /// Fits text to a pixel width using an ASCII ellipsis when truncation is required.
        ///
        /// @param metrics active font metrics
        /// @param text source text
        /// @param maximumWidth available width in pixels
        /// @return original or width-constrained text
        private static String fitText(FontMetrics metrics, String text, int maximumWidth) {
            if (maximumWidth <= 0) {
                return "";
            }
            if (metrics.stringWidth(text) <= maximumWidth) {
                return text;
            }
            String ellipsis = "...";
            int ellipsisWidth = metrics.stringWidth(ellipsis);
            if (ellipsisWidth >= maximumWidth) {
                return "";
            }

            int low = 0;
            int high = text.length();
            while (low < high) {
                int middle = (low + high + 1) >>> 1;
                if (metrics.stringWidth(text.substring(0, middle)) + ellipsisWidth <= maximumWidth) {
                    low = middle;
                } else {
                    high = middle - 1;
                }
            }
            return text.substring(0, low) + ellipsis;
        }
    }
}
