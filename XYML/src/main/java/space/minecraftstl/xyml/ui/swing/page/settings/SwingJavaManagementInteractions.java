// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.settings;

import space.minecraftstl.xyml.ui.swing.dialog.EditablePathChooser;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import javax.swing.JFileChooser;
import javax.swing.JOptionPane;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.Component;
import java.awt.Desktop;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.Objects;

import static space.minecraftstl.xyml.util.i18n.I18n.i18n;

/// Implements Java-management chooser, confirmation, and directory reveal interactions with Swing and AWT.
@NotNullByDefault
final class SwingJavaManagementInteractions implements JavaManagementInteractions {
    /// Opens a chooser that accepts a Java executable or Java home directory.
    ///
    /// @param parent dialog parent component
    /// @return selected path, or null when the chooser is cancelled
    @Override
    public @Nullable Path chooseLocalRuntime(Component parent) {
        JFileChooser chooser = new EditablePathChooser();
        chooser.setDialogTitle(i18n("java.add"));
        chooser.setFileSelectionMode(JFileChooser.FILES_AND_DIRECTORIES);
        if (chooser.showOpenDialog(Objects.requireNonNull(parent, "parent")) != JFileChooser.APPROVE_OPTION) {
            return null;
        }
        @Nullable File selection = chooser.getSelectedFile();
        return selection == null ? null : selection.toPath();
    }

    /// Opens a chooser restricted to supported local Java installation archives.
    ///
    /// @param parent dialog parent component
    /// @return selected archive path, or null when the chooser is cancelled
    @Override
    public @Nullable Path chooseLocalJavaArchive(Component parent) {
        JFileChooser chooser = new EditablePathChooser();
        chooser.setDialogTitle(i18n("java.install"));
        chooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
        chooser.setAcceptAllFileFilterUsed(false);
        chooser.setFileFilter(new FileNameExtensionFilter(
                i18n("java.install.archive") + " (*.zip, *.tar.gz)",
                "zip",
                "tar.gz"));
        if (chooser.showOpenDialog(Objects.requireNonNull(parent, "parent")) != JFileChooser.APPROVE_OPTION) {
            return null;
        }
        @Nullable File selection = chooser.getSelectedFile();
        return selection == null ? null : selection.toPath();
    }

    /// Shows one destructive-action confirmation dialog.
    ///
    /// @param parent dialog parent component
    /// @param message localized confirmation message
    /// @param title localized dialog title
    /// @return true only for an explicit affirmative choice
    @Override
    public boolean confirm(Component parent, String message, String title) {
        return JOptionPane.showConfirmDialog(
                        Objects.requireNonNull(parent, "parent"),
                        Objects.requireNonNull(message, "message"),
                        Objects.requireNonNull(title, "title"),
                        JOptionPane.YES_NO_OPTION,
                        JOptionPane.WARNING_MESSAGE)
                == JOptionPane.YES_OPTION;
    }

    /// Opens one directory using supported desktop integration.
    ///
    /// @param directory existing directory to open
    /// @throws IOException when desktop integration is unavailable or rejects the directory
    @Override
    public void revealDirectory(Path directory) throws IOException {
        Path target = Objects.requireNonNull(directory, "directory");
        if (!Desktop.isDesktopSupported()) {
            throw new IOException("Desktop integration is unavailable");
        }
        Desktop desktop = Desktop.getDesktop();
        if (!desktop.isSupported(Desktop.Action.OPEN)) {
            throw new IOException("Directory opening is unavailable");
        }
        desktop.open(target.toFile());
    }

    /// Opens one validated Java download page through supported desktop browser integration.
    ///
    /// @param parent owning component used for interaction context
    /// @param uri validated HTTP or HTTPS destination
    /// @throws IOException when desktop browser integration is unavailable or rejects the URI
    @Override
    public void openExternalJavaDownload(Component parent, URI uri) throws IOException {
        Objects.requireNonNull(parent, "parent");
        URI target = Objects.requireNonNull(uri, "uri");
        if (!("https".equalsIgnoreCase(target.getScheme()) || "http".equalsIgnoreCase(target.getScheme()))
                || target.getHost() == null) {
            throw new IOException("External Java download URI is invalid");
        }
        if (!Desktop.isDesktopSupported()) {
            throw new IOException("Desktop integration is unavailable");
        }
        Desktop desktop = Desktop.getDesktop();
        if (!desktop.isSupported(Desktop.Action.BROWSE)) {
            throw new IOException("Browser integration is unavailable");
        }
        desktop.browse(target);
    }
}
