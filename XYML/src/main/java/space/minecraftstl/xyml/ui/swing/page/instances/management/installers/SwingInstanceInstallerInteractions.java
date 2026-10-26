// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.instances.management.installers;

import space.minecraftstl.xyml.ui.swing.dialog.EditablePathChooser;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;

import javax.swing.JFileChooser;
import javax.swing.JOptionPane;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.Component;
import java.io.File;
import java.nio.file.Path;
import java.util.Objects;

import static space.minecraftstl.xyml.util.i18n.I18n.i18n;

/// Provides production native chooser, confirmation, and failure dialogs for [InstanceInstallerPanel].
///
/// This class deliberately owns no repository or task state. It performs only explicit user-interface
/// interaction after the panel has already selected a local workflow.
@NotNullByDefault
final class SwingInstanceInstallerInteractions implements InstanceInstallerInteractions {
    /// Opens a local Java or Windows installer-file chooser.
    ///
    /// @param owner native dialog owner
    /// @return selected local installer, or null when the dialog is cancelled
    @Override
    public @Nullable Path chooseOfflineInstaller(Component owner) {
        EdtDispatcher.requireEventDispatchThread();
        JFileChooser chooser = new EditablePathChooser();
        chooser.setDialogTitle(i18n("install.installer.install_offline"));
        chooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
        chooser.setAcceptAllFileFilterUsed(false);
        chooser.setFileFilter(new FileNameExtensionFilter(i18n("extension.modloader.installer"), "jar", "exe"));
        if (chooser.showOpenDialog(Objects.requireNonNull(owner, "owner")) != JFileChooser.APPROVE_OPTION) {
            return null;
        }
        @Nullable File selected = chooser.getSelectedFile();
        return selected == null ? null : selected.toPath();
    }

    /// Asks for confirmation before a structurally clear library is removed.
    ///
    /// @param owner native dialog owner
    /// @param libraryId exact Core library identifier proposed for removal
    /// @return whether the removal is approved
    @Override
    public boolean confirmRemoval(Component owner, String libraryId) {
        EdtDispatcher.requireEventDispatchThread();
        String target = Objects.requireNonNull(libraryId, "libraryId");
        return JOptionPane.showConfirmDialog(
                Objects.requireNonNull(owner, "owner"),
                new Object[]{target, i18n("button.remove.confirm")},
                i18n("button.remove"),
                JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.WARNING_MESSAGE) == JOptionPane.OK_OPTION;
    }

    /// Shows one concise terminal failure message.
    ///
    /// @param owner native dialog owner
    /// @param title visible failure title
    /// @param detail non-blank failure detail
    @Override
    public void showFailure(Component owner, String title, String detail) {
        EdtDispatcher.requireEventDispatchThread();
        JOptionPane.showMessageDialog(
                Objects.requireNonNull(owner, "owner"),
                Objects.requireNonNull(detail, "detail"),
                Objects.requireNonNull(title, "title"),
                JOptionPane.ERROR_MESSAGE);
    }
}
