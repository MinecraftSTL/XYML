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
package space.minecraftstl.xyml.ui.swing.crash;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import space.minecraftstl.xyml.game.ExportedCrashBundle;
import space.minecraftstl.xyml.game.ExportedCrashBundleText;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;

import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Desktop;
import java.awt.Dialog;
import java.awt.Dimension;
import java.awt.Frame;
import java.awt.Window;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static space.minecraftstl.xyml.util.i18n.I18n.i18n;

/// Read-only Swing actions for an imported crash-report archive.
@NotNullByDefault
final class ImportedGameCrashWindowActions implements GameCrashWindowActions, AutoCloseable {
    /// Component used to resolve the dialog owner.
    private final Component owner;

    /// Validated archive whose individual text entries are displayed lazily.
    private final ExportedCrashBundle bundle;

    /// Currently open lazy log viewer, accessed on the EDT.
    private @Nullable JDialog logDialog;

    /// Creates read-only imported-report actions without concatenating archive contents.
    ///
    /// @param owner component or window owning the log viewer
    /// @param bundle validated crash-export contents
    ImportedGameCrashWindowActions(Component owner, ExportedCrashBundle bundle) {
        this.owner = Objects.requireNonNull(owner, "owner");
        this.bundle = Objects.requireNonNull(bundle, "bundle");
    }

    /// Rejects export because imported reports have no mutable instance context.
    ///
    /// @return a failed asynchronous operation
    @Override
    public CompletionStage<Path> exportCrashLogs() {
        return CompletableFuture.failedFuture(new UnsupportedOperationException(
                "Imported crash reports cannot be exported"));
    }

    /// Rejects file-manager reveal because imported reports are already external input.
    ///
    /// @param file ignored file path
    /// @throws UnsupportedOperationException always
    @Override
    public void revealFile(Path file) {
        throw new UnsupportedOperationException("Imported crash reports cannot reveal files");
    }

    /// Opens a lazy, selectable source viewer for the imported report texts.
    @Override
    public void showGameLogs() {
        EdtDispatcher.requireEventDispatchThread();
        if (java.awt.GraphicsEnvironment.isHeadless()) {
            return;
        }
        @Nullable JDialog currentDialog = logDialog;
        if (currentDialog != null && currentDialog.isDisplayable()) {
            currentDialog.setVisible(true);
            currentDialog.toFront();
            return;
        }

        Window owningWindow = owner instanceof Window window
                ? window
                : SwingUtilities.getWindowAncestor(owner);
        JDialog dialog = owningWindow == null
                ? new JDialog((Frame) null, i18n("game.crash.import.logs.title"), false)
                : new JDialog(owningWindow, i18n("game.crash.import.logs.title"), Dialog.ModalityType.MODELESS);
        JComboBox<SourceItem> sources = new JComboBox<>();
        JTextArea content = new JTextArea();
        content.setEditable(false);
        content.setLineWrap(false);
        content.setWrapStyleWord(false);
        content.setCaretPosition(0);
        for (ExportedCrashBundleText text : bundle.texts()) {
            sources.addItem(new SourceItem(String.join(", ", text.sources()), text));
        }
        sources.addActionListener(event -> {
            @Nullable SourceItem selected = (SourceItem) sources.getSelectedItem();
            if (selected != null) {
                content.setText(selected.text().content());
                content.setCaretPosition(0);
            }
        });
        if (sources.getItemCount() > 0) {
            sources.setSelectedIndex(0);
        }

        dialog.setLayout(new BorderLayout(8, 8));
        dialog.add(new JLabel(i18n("game.crash.import.logs.source")), BorderLayout.NORTH);
        dialog.add(sources, BorderLayout.CENTER);
        dialog.add(new JScrollPane(content), BorderLayout.SOUTH);
        dialog.setMinimumSize(new Dimension(720, 480));
        dialog.setSize(900, 620);
        dialog.setLocationByPlatform(true);
        dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
        dialog.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosed(WindowEvent event) {
                if (logDialog == dialog) {
                    logDialog = null;
                }
            }
        });
        logDialog = dialog;
        dialog.setVisible(true);
    }

    /// Opens one trusted help link through the native desktop browser.
    ///
    /// @param destination trusted destination
    /// @throws IOException when desktop browsing is unavailable
    @Override
    public void openLink(URI destination) throws IOException {
        Objects.requireNonNull(destination, "destination");
        if (!Desktop.isDesktopSupported()) {
            throw new IOException("Desktop integration is unavailable");
        }
        Desktop desktop = Desktop.getDesktop();
        if (!desktop.isSupported(Desktop.Action.BROWSE)) {
            throw new IOException("Desktop browsing is unavailable");
        }
        desktop.browse(destination);
    }

    /// Returns the number of lazily selectable archive texts for package tests.
    ///
    /// @return archive text count
    int sourceCount() {
        return bundle.texts().size();
    }

    /// Disposes the source viewer on the EDT.
    @Override
    public void close() {
        EdtDispatcher.executeAndWait(() -> {
            @Nullable JDialog currentDialog = logDialog;
            logDialog = null;
            if (currentDialog != null) {
                currentDialog.dispose();
            }
        });
    }

    /// Compact display item that keeps one source text available for selection.
    @NotNullByDefault
    private record SourceItem(String label, ExportedCrashBundleText text) {
        /// Returns the source names shown in the selector.
        @Override
        public String toString() {
            return label;
        }
    }
}
