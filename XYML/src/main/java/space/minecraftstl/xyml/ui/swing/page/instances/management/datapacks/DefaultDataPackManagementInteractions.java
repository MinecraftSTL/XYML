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
package space.minecraftstl.xyml.ui.swing.page.instances.management.datapacks;

import space.minecraftstl.xyml.ui.swing.dialog.EditablePathChooser;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import space.minecraftstl.xyml.addon.datapack.DataPack;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;

import javax.swing.JFileChooser;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.Component;
import java.awt.Desktop;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;

import static space.minecraftstl.xyml.util.i18n.I18n.i18n;

/// Native Swing and AWT implementation for per-world data-pack page interactions.
///
/// File chooser and confirmation calls stay on the EDT. Directory creation and `Desktop` calls
/// run on the caller-provided background executor so they cannot freeze Swing event processing.
@NotNullByDefault
public final class DefaultDataPackManagementInteractions implements DataPackManagementInteractions {
    /// Stable visible text used by all native interactions.
    private final DataPackManagementStrings strings;

    /// Caller-owned executor for desktop and file-system operations.
    private final Executor executor;

    /// Creates production native interactions for one data-pack management page.
    ///
    /// @param strings stable visible text
    /// @param executor caller-owned background executor
    public DefaultDataPackManagementInteractions(DataPackManagementStrings strings, Executor executor) {
        this.strings = Objects.requireNonNull(strings, "strings");
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    /// Shows a single-file ZIP chooser on the EDT.
    ///
    /// @param owner dialog owner
    /// @param initialDirectory initial local directory for the chooser
    /// @return normalized archive path, or `null` after cancellation
    @Override
    public @Nullable Path chooseDataPackArchive(Component owner, Path initialDirectory) {
        EdtDispatcher.requireEventDispatchThread();
        JFileChooser chooser = new EditablePathChooser(
                Objects.requireNonNull(initialDirectory, "initialDirectory").toFile());
        chooser.setDialogTitle(strings.importDialogTitle());
        chooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
        chooser.setMultiSelectionEnabled(false);
        chooser.setAcceptAllFileFilterUsed(false);
        chooser.setFileFilter(new FileNameExtensionFilter(strings.archiveDescription(), "zip"));
        if (chooser.showOpenDialog(Objects.requireNonNull(owner, "owner")) != JFileChooser.APPROVE_OPTION) {
            return null;
        }
        return chooser.getSelectedFile().toPath().toAbsolutePath().normalize();
    }

    /// Shows the destructive confirmation dialog on the EDT.
    ///
    /// @param owner dialog owner
    /// @param dataPack selected data-pack entry
    /// @return whether the user explicitly accepted deletion
    @Override
    public boolean confirmDelete(Component owner, DataPack.Pack dataPack) {
        EdtDispatcher.requireEventDispatchThread();
        DataPack.Pack selected = Objects.requireNonNull(dataPack, "dataPack");
        return JOptionPane.showConfirmDialog(
                Objects.requireNonNull(owner, "owner"),
                strings.deleteConfirmationFormat().formatted(selected.getId()),
                strings.deleteDialogTitle(),
                JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE) == JOptionPane.YES_OPTION;
    }

    /// Schedules directory creation followed by platform reveal outside the EDT.
    ///
    /// @param directory directory to reveal
    /// @return nullable-void completion for the desktop operation
    @Override
    public CompletionStage<@Nullable Void> openDirectory(Path directory) {
        Path normalizedDirectory = Objects.requireNonNull(directory, "directory").toAbsolutePath().normalize();
        CompletableFuture<@Nullable Void> result = new CompletableFuture<>();
        try {
            executor.execute(() -> openDirectoryOnExecutor(normalizedDirectory, result));
        } catch (RuntimeException failure) {
            result.completeExceptionally(failure);
        }
        return result;
    }

    /// Displays one concise failure message on the EDT.
    ///
    /// @param owner dialog owner
    /// @param title visible dialog title
    /// @param detail concise failure detail
    @Override
    public void showFailure(Component owner, String title, String detail) {
        EdtDispatcher.requireEventDispatchThread();
        JOptionPane.showMessageDialog(
                Objects.requireNonNull(owner, "owner"),
                Objects.requireNonNull(detail, "detail"),
                Objects.requireNonNull(title, "title"),
                JOptionPane.ERROR_MESSAGE);
    }

    /// Opens one directory through the native desktop handler after ensuring it exists.
    ///
    /// @param directory normalized local directory
    /// @param result future completed from the background operation
    private static void openDirectoryOnExecutor(
            Path directory,
            CompletableFuture<@Nullable Void> result) {
        try {
            requireBackgroundThread();
            Files.createDirectories(directory);
            if (!Desktop.isDesktopSupported()) {
                throw new UnsupportedOperationException(i18n("swing.datapack_management.desktop_unavailable"));
            }
            Desktop desktop = Desktop.getDesktop();
            if (!desktop.isSupported(Desktop.Action.OPEN)) {
                throw new UnsupportedOperationException(i18n("swing.datapack_management.desktop_open_unsupported"));
            }
            desktop.open(directory.toFile());
            result.complete(null);
        } catch (IOException | RuntimeException failure) {
            result.completeExceptionally(failure);
        }
    }

    /// Rejects accidental desktop work on the Swing event-dispatch thread.
    private static void requireBackgroundThread() {
        if (SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException("Data-pack desktop work must not run on the EDT");
        }
    }
}
