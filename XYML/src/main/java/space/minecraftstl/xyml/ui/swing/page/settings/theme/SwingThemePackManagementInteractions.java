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
package space.minecraftstl.xyml.ui.swing.page.settings.theme;

import space.minecraftstl.xyml.ui.swing.dialog.EditablePathChooser;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;

import javax.swing.JFileChooser;
import javax.swing.JOptionPane;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.Component;
import java.awt.Desktop;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;

/// Production Swing interactions for local theme-pack management.
@NotNullByDefault
public final class SwingThemePackManagementInteractions implements ThemePackManagementInteractions {
    /// Localized chooser and confirmation text.
    private final ThemePackManagementStrings strings;

    /// Caller-owned worker used for potentially blocking desktop integration.
    private final Executor executor;

    /// Creates production interactions.
    ///
    /// @param strings localized interaction text
    /// @param executor caller-owned non-EDT worker executor
    public SwingThemePackManagementInteractions(ThemePackManagementStrings strings, Executor executor) {
        this.strings = Objects.requireNonNull(strings, "strings");
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    /// Shows a native file chooser constrained to `.xyml-theme` archives.
    ///
    /// @param owner dialog owner
    /// @return selected archive, or `null`
    @Override
    public @Nullable Path chooseImportArchive(Component owner) {
        EdtDispatcher.requireEventDispatchThread();
        JFileChooser chooser = new EditablePathChooser();
        chooser.setDialogTitle(strings.chooserTitle());
        chooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
        chooser.setAcceptAllFileFilterUsed(false);
        chooser.setFileFilter(new FileNameExtensionFilter(strings.chooserFilter(), "xyml-theme"));
        return chooser.showOpenDialog(Objects.requireNonNull(owner, "owner")) == JFileChooser.APPROVE_OPTION
                ? chooser.getSelectedFile().toPath().toAbsolutePath().normalize()
                : null;
    }

    /// Shows one destructive confirmation for the complete containing package.
    ///
    /// @param owner dialog owner
    /// @param item selected installed item
    /// @return whether deletion was confirmed
    @Override
    public boolean confirmDelete(Component owner, ThemePackItem item) {
        EdtDispatcher.requireEventDispatchThread();
        ThemePackItem checked = Objects.requireNonNull(item, "item");
        return JOptionPane.showConfirmDialog(
                Objects.requireNonNull(owner, "owner"),
                strings.confirmDeleteFormat().formatted(checked.packageName()),
                strings.confirmDeleteTitle(),
                JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.WARNING_MESSAGE) == JOptionPane.OK_OPTION;
    }

    /// Opens one revalidated directory on the caller-owned worker.
    ///
    /// @param directory exact validated directory
    /// @return completion stage resolved after the native desktop call
    @Override
    public CompletionStage<@Nullable Void> revealInstalledDirectory(Path directory) {
        Path target = Objects.requireNonNull(directory, "directory").toAbsolutePath().normalize();
        CompletableFuture<@Nullable Void> completion = new CompletableFuture<>();
        try {
            executor.execute(() -> {
                try {
                    if (javax.swing.SwingUtilities.isEventDispatchThread()) {
                        throw new IllegalStateException("Desktop integration must not run on the EDT");
                    }
                    if (!Desktop.isDesktopSupported()) {
                        throw new UnsupportedOperationException("Desktop integration is unavailable");
                    }
                    Desktop desktop = Desktop.getDesktop();
                    if (!desktop.isSupported(Desktop.Action.OPEN)) {
                        throw new UnsupportedOperationException("Desktop cannot open directories");
                    }
                    desktop.open(target.toFile());
                    completion.complete(null);
                } catch (IOException | RuntimeException failure) {
                    completion.completeExceptionally(failure);
                }
            });
        } catch (RuntimeException failure) {
            completion.completeExceptionally(failure);
        }
        return completion.minimalCompletionStage();
    }
}
