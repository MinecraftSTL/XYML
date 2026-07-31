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
package space.minecraftstl.xyml.ui.swing.shell;

import com.formdev.flatlaf.extras.FlatSVGIcon;
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
import space.minecraftstl.xyml.ui.swing.page.home.HomeModel;
import space.minecraftstl.xyml.ui.swing.page.home.HomeSnapshot;
import space.minecraftstl.xyml.ui.swing.page.home.HomeStrings;
import space.minecraftstl.xyml.ui.swing.task.TaskProgressHostPanel;
import space.minecraftstl.xyml.ui.swing.task.TaskProgressStrings;

import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.Font;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/// Presents the current launch task above the persistent instance-management workspace.
///
/// The terminal task remains available until explicitly dismissed, preserving diagnostics without restoring the
/// removed home page. A later launch-session identity always replaces a previously dismissed terminal task.
@NotNullByDefault
final class LaunchTaskOverlayPanel extends JPanel implements AutoCloseable {
    /// Launcher selection and launch-session model.
    private final HomeModel model;

    /// Stable current-launch-session property.
    private final ReadOnlyProperty<Optional<LaunchSession>> launchSessionProperty;

    /// Host owning the currently presented task progress panel.
    private final TaskProgressHostPanel taskProgressHost;

    /// Command dismissing a terminal launch task and revealing the underlying workspace.
    private final JButton dismissButton = new JButton();

    /// Model listener used to refresh terminal-dismiss availability.
    private final Subscription modelSubscription;

    /// Launch-session listener used to replace the overlay binding by identity.
    private final Subscription launchSessionSubscription;

    /// Latest home snapshot represented by this overlay.
    private HomeSnapshot displayedSnapshot;

    /// Launch session currently bound to the task host, or null before the first launch.
    private @Nullable LaunchSession displayedSession;

    /// Terminal session dismissed by the user, or null before dismissal or after a new launch.
    private @Nullable LaunchSession dismissedSession;

    /// Whether listener delivery and later bindings have been permanently rejected.
    private boolean closed;

    /// Creates an initially hidden launch-task overlay.
    ///
    /// @param model launcher selection and launch-session model
    /// @param strings localized launch controls
    /// @param taskProgressStrings localized task-progress controls
    /// @param animator optional shared progress animator
    /// @param progressAnimationDuration explicit non-negative progress animation duration
    LaunchTaskOverlayPanel(
            HomeModel model,
            HomeStrings strings,
            TaskProgressStrings taskProgressStrings,
            @Nullable SwingAnimator animator,
            Duration progressAnimationDuration) {
        super(new MigLayout(
                "insets 24, fill, wrap 1",
                "[grow,fill]",
                "[]16[grow,fill]"));
        EdtDispatcher.requireEventDispatchThread();
        this.model = Objects.requireNonNull(model, "model");
        Objects.requireNonNull(strings, "strings");
        taskProgressHost = new TaskProgressHostPanel(
                Objects.requireNonNull(taskProgressStrings, "taskProgressStrings"),
                animator,
                Objects.requireNonNull(progressAnimationDuration, "progressAnimationDuration"));
        launchSessionProperty = model.launchSessionProperty();
        displayedSnapshot = model.snapshot();
        configureComponents(strings);
        modelSubscription = model.subscribe(this::modelChanged);
        launchSessionSubscription = launchSessionProperty.subscribe(this::launchSessionChanged);
        applyLaunchSession(readLaunchSession());
    }

    /// Returns whether a non-dismissed launch session currently covers the workspace.
    ///
    /// @return whether the overlay is visible
    boolean isPresentingTask() {
        EdtDispatcher.requireEventDispatchThread();
        return isVisible();
    }

    /// Returns the dismiss command for focused lifecycle and accessibility tests.
    ///
    /// @return stable dismiss button
    JButton dismissButton() {
        return dismissButton;
    }

    /// Releases both subscriptions and the task host on the EDT.
    @Override
    public void close() {
        SwingUiDispatcher.INSTANCE.dispatchOrRun(() -> {
            if (closed) {
                return;
            }
            closed = true;
            modelSubscription.unsubscribe();
            launchSessionSubscription.unsubscribe();
            taskProgressHost.close();
            displayedSession = null;
            dismissedSession = null;
            dismissButton.setEnabled(false);
            setVisible(false);
        });
    }

    /// Builds the compact overlay heading and full task host.
    ///
    /// @param strings localized launch copy
    private void configureComponents(HomeStrings strings) {
        setName("shellLaunchTaskOverlay");
        setOpaque(true);
        setVisible(false);

        JPanel header = new JPanel(new MigLayout("insets 0, fillx", "[grow,fill][]", "[40!]"));
        header.setOpaque(false);
        JLabel title = new JLabel(strings.launchingAction());
        title.setName("shellLaunchTaskTitle");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 22.0F));
        header.add(title, "growx");

        dismissButton.setName("shellLaunchTaskDismiss");
        dismissButton.setText(null);
        dismissButton.setIcon(new FlatSVGIcon("assets/swing/icons/arrow-back.svg", 18, 18));
        dismissButton.setToolTipText(strings.backToSelectionsAction());
        dismissButton.getAccessibleContext().setAccessibleName(strings.backToSelectionsAction());
        dismissButton.addActionListener(event -> dismissTerminalTask());
        header.add(dismissButton, "h 40!, w 40!");

        taskProgressHost.setName("shellLaunchTaskProgress");
        add(header, "growx");
        add(taskProgressHost, "grow");
    }

    /// Coalesces selection and terminal-state changes onto the EDT.
    ///
    /// @param change transition invalidating dismiss availability
    private void modelChanged(ValueChange<HomeSnapshot> change) {
        Objects.requireNonNull(change, "change");
        SwingUiDispatcher.INSTANCE.dispatchOrRun(() -> {
            if (!closed) {
                displayedSnapshot = model.snapshot();
                updateDismissAvailability();
            }
        });
    }

    /// Coalesces launch-session identity changes onto the EDT.
    ///
    /// @param change transition invalidating the current task binding
    private void launchSessionChanged(ValueChange<Optional<LaunchSession>> change) {
        Objects.requireNonNull(change, "change");
        SwingUiDispatcher.INSTANCE.dispatchOrRun(() -> {
            if (!closed) {
                applyLaunchSession(readLaunchSession());
            }
        });
    }

    /// Binds a new session by identity or hides after the optional session clears.
    ///
    /// @param launchSession latest optional launch session
    private void applyLaunchSession(Optional<LaunchSession> launchSession) {
        EdtDispatcher.requireEventDispatchThread();
        @Nullable LaunchSession replacement = launchSession.orElse(null);
        if (replacement == displayedSession) {
            updateDismissAvailability();
            return;
        }
        displayedSession = replacement;
        dismissedSession = null;
        if (replacement == null) {
            taskProgressHost.clear();
            setVisible(false);
        } else {
            taskProgressHost.bind(replacement);
            setVisible(true);
        }
        updateDismissAvailability();
        revalidate();
        repaint();
    }

    /// Hides one terminal task while retaining its session identity until another launch starts.
    private void dismissTerminalTask() {
        EdtDispatcher.requireEventDispatchThread();
        @Nullable LaunchSession session = displayedSession;
        if (closed || session == null || !session.status().isTerminal()
                || !displayedSnapshot.selectionCommandsEnabled()) {
            return;
        }
        dismissedSession = session;
        taskProgressHost.clear();
        setVisible(false);
        updateDismissAvailability();
    }

    /// Synchronizes terminal-dismiss availability with launch and selection lifecycle state.
    private void updateDismissAvailability() {
        @Nullable LaunchSession session = displayedSession;
        dismissButton.setEnabled(
                !closed
                        && session != null
                        && session != dismissedSession
                        && session.status().isTerminal()
                        && displayedSnapshot.selectionCommandsEnabled());
    }

    /// Reads and validates the optional launch-session property value.
    ///
    /// @return non-null optional session
    private Optional<LaunchSession> readLaunchSession() {
        @Nullable Optional<LaunchSession> value = launchSessionProperty.getValue();
        return Objects.requireNonNull(value, "launch-session property value");
    }
}
