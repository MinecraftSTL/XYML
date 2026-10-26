// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.instances.importing;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;
import space.minecraftstl.xyml.ui.swing.SwingAnimator;
import space.minecraftstl.xyml.ui.swing.task.TaskProgressStrings;
import space.minecraftstl.xyml.ui.swing.task.TaskLaunchController;

import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.WindowConstants;
import java.awt.Dimension;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import java.util.function.Consumer;

/// Modeless native window hosting one cancellable instance JSON import panel.
@NotNullByDefault
final class SwingInstanceJsonImportDialog extends JDialog
        implements SwingInstanceJsonImportLauncher.ImportWindow {
    /// Stable content panel owning task lifecycle and cancellation.
    private final InstanceJsonImportPanel panel;

    /// Terminal disposal observer used to release launcher window identity.
    private final Consumer<SwingInstanceJsonImportLauncher.ImportWindow> closedObserver;

    /// Whether terminal disposal has already released the panel and observer.
    private boolean closed;

    /// Creates a modeless import dialog on the Swing event-dispatch thread.
    ///
    /// @param owner launcher frame
    /// @param service deferred repository import service
    /// @param strings localized workflow text
    /// @param taskProgressStrings localized task lifecycle text
    /// @param animator optional shared progress animator
    /// @param progressAnimationDuration non-negative progress animation duration
    /// @param closedObserver terminal disposal observer
    SwingInstanceJsonImportDialog(
            JFrame owner,
            InstanceJsonImportService service,
            InstanceJsonImportStrings strings,
            TaskProgressStrings taskProgressStrings,
            TaskLaunchController taskLaunchController,
            @Nullable SwingAnimator animator,
            Duration progressAnimationDuration,
            Consumer<SwingInstanceJsonImportLauncher.ImportWindow> closedObserver) {
        super(Objects.requireNonNull(owner, "owner"), strings.dialogTitle(), false);
        EdtDispatcher.requireEventDispatchThread();
        this.closedObserver = Objects.requireNonNull(closedObserver, "closedObserver");
        panel = new InstanceJsonImportPanel(
                Objects.requireNonNull(service, "service"),
                Objects.requireNonNull(strings, "strings"),
                Objects.requireNonNull(taskProgressStrings, "taskProgressStrings"),
                animator,
                Objects.requireNonNull(progressAnimationDuration, "progressAnimationDuration"),
                Objects.requireNonNull(taskLaunchController, "taskLaunchController"),
                this::dismissSubmittedWindow);
        setName("instanceJsonImportDialog");
        setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        setContentPane(panel);
        setMinimumSize(new Dimension(620, 380));
        setPreferredSize(new Dimension(700, 440));
        pack();
        setLocationRelativeTo(owner);
        addWindowListener(new WindowAdapter() {
            /// Releases task resources after native terminal disposal.
            ///
            /// @param event native window event
            @Override
            public void windowClosed(WindowEvent event) {
                Objects.requireNonNull(event, "event");
                finishClose();
            }
        });
    }

    /// Disposes the confirmation window after the task has been handed to global task management.
    private void dismissSubmittedWindow() {
        EdtDispatcher.requireEventDispatchThread();
        if (!closed) {
            dispose();
        }
    }

    /// Replaces the selected source while the hosted workflow is idle.
    ///
    /// @param source normalized JSON source
    @Override
    public void open(Path source) {
        EdtDispatcher.requireEventDispatchThread();
        if (!closed) {
            panel.open(Objects.requireNonNull(source, "source"));
        }
    }

    /// Reveals this modeless window or focuses its existing native peer.
    @Override
    public void showOrFocus() {
        EdtDispatcher.requireEventDispatchThread();
        if (closed) {
            return;
        }
        if (!isVisible()) {
            setVisible(true);
        }
        toFront();
        requestFocus();
    }

    /// Forces terminal disposal and cancellation of a running import.
    @Override
    public void close() {
        EdtDispatcher.requireEventDispatchThread();
        if (!closed) {
            dispose();
            finishClose();
        }
    }

    /// Releases the panel and reports terminal disposal exactly once.
    private void finishClose() {
        EdtDispatcher.requireEventDispatchThread();
        if (closed) {
            return;
        }
        closed = true;
        panel.close();
        closedObserver.accept(this);
    }
}
