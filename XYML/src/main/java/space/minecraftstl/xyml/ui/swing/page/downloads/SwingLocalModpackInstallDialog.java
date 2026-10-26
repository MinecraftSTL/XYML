// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.downloads;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;
import space.minecraftstl.xyml.ui.swing.SwingAnimator;
import space.minecraftstl.xyml.ui.swing.task.TaskProgressStrings;
import space.minecraftstl.xyml.ui.swing.task.TaskLaunchController;

import javax.swing.BorderFactory;
import javax.swing.JDialog;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;
import java.awt.Component;
import java.awt.Dialog;
import java.awt.Dimension;
import java.awt.Window;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;

import static space.minecraftstl.xyml.util.i18n.I18n.i18n;

/// Opens the existing local-modpack installation workflow in an owned modeless window.
@NotNullByDefault
public final class SwingLocalModpackInstallDialog {
    /// Prevents construction of the stateless dialog utility.
    private SwingLocalModpackInstallDialog() {
    }

    /// Shows a resizable installer prefilled with one dropped local archive.
    ///
    /// @param owner visible shell component owning the native window
    /// @param archive dropped local modpack archive
    /// @param taskProgressStrings localized task lifecycle controls
    /// @param animator optional shared determinate-progress animator
    /// @param progressAnimationDuration non-negative determinate-progress animation duration
    public static void show(
            Component owner,
            Path archive,
            TaskProgressStrings taskProgressStrings,
            TaskLaunchController taskLaunchController,
            @Nullable SwingAnimator animator,
            Duration progressAnimationDuration) {
        EdtDispatcher.requireEventDispatchThread();
        Component resolvedOwner = Objects.requireNonNull(owner, "owner");
        Path resolvedArchive = Objects.requireNonNull(archive, "archive")
                .toAbsolutePath()
                .normalize();
        LocalModpackImportPanel panel = new LocalModpackImportPanel(
                Objects.requireNonNull(taskProgressStrings, "taskProgressStrings"),
                animator,
                Objects.requireNonNull(progressAnimationDuration, "progressAnimationDuration"),
                Objects.requireNonNull(taskLaunchController, "taskLaunchController"));
        @Nullable Window ancestor = SwingUtilities.getWindowAncestor(resolvedOwner);
        JDialog dialog = new JDialog(
                ancestor,
                i18n("install.modpack"),
                Dialog.ModalityType.MODELESS);
        dialog.setName("localModpackInstallDialog");
        dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        dialog.setResizable(true);
        panel.setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));
        dialog.setContentPane(panel);
        dialog.setMinimumSize(new Dimension(680, 420));
        dialog.setPreferredSize(new Dimension(760, 520));
        panel.setSubmittedDismissAction(dialog::dispose);
        dialog.pack();
        dialog.setLocationRelativeTo(resolvedOwner);
        dialog.addWindowListener(new WindowAdapter() {
            /// Releases task resources after terminal native disposal.
            ///
            /// @param event native window event
            @Override
            public void windowClosed(WindowEvent event) {
                Objects.requireNonNull(event, "event");
                panel.close();
            }
        });
        panel.acceptDroppedArchive(resolvedArchive);
        dialog.setVisible(true);
        dialog.toFront();
        dialog.requestFocus();
    }
}
