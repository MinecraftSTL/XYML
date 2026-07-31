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
package space.minecraftstl.xyml.ui.swing.page.instances.management.backups;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;

import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
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

/// Production confirmation dialogs and file-manager actions for world backups.
///
/// Native prompts execute on the EDT, while directory creation and AWT desktop integration are
/// scheduled on the caller-owned background executor and reject accidental EDT execution.
@NotNullByDefault
public final class DefaultWorldBackupInteractions implements WorldBackupInteractions {
    /// Caller-owned worker used for platform file-manager integration.
    private final Executor executor;

    /// Creates native interactions backed by one caller-owned background executor.
    ///
    /// @param executor worker for filesystem and AWT desktop actions
    public DefaultWorldBackupInteractions(Executor executor) {
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    /// Schedules directory creation and platform file-manager opening outside the EDT.
    ///
    /// @param directory directory to create if needed and open
    /// @return terminal asynchronous completion
    @Override
    public CompletionStage<@Nullable Void> openDirectory(Path directory) {
        Path normalizedDirectory = Objects.requireNonNull(directory, "directory").toAbsolutePath().normalize();
        CompletableFuture<@Nullable Void> result = new CompletableFuture<>();
        try {
            executor.execute(() -> openDirectoryOnWorker(normalizedDirectory, result));
        } catch (RuntimeException exception) {
            result.completeExceptionally(exception);
        }
        return result;
    }

    /// Requests explicit confirmation before permanently removing an archive.
    ///
    /// @param owner Swing dialog owner
    /// @param archive selected archive
    /// @return whether deletion was accepted
    @Override
    public boolean confirmDelete(Component owner, WorldBackupArchive archive) {
        EdtDispatcher.requireEventDispatchThread();
        WorldBackupArchive selectedArchive = Objects.requireNonNull(archive, "archive");
        return JOptionPane.showConfirmDialog(
                Objects.requireNonNull(owner, "owner"),
                i18n("swing.world_backup.delete_confirmation", selectedArchive.fileName()),
                i18n("swing.world_backup.delete_title"),
                JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE) == JOptionPane.YES_OPTION;
    }

    /// Prompts for one new save directory name without inspecting archive contents on the EDT.
    ///
    /// @param owner Swing dialog owner
    /// @param archive selected archive
    /// @return trimmed new save name, or null after cancellation or blank input
    @Override
    public @Nullable String requestRestoreDestination(Component owner, WorldBackupArchive archive) {
        EdtDispatcher.requireEventDispatchThread();
        WorldBackupArchive selectedArchive = Objects.requireNonNull(archive, "archive");
        @Nullable Object input = JOptionPane.showInputDialog(
                Objects.requireNonNull(owner, "owner"),
                i18n("swing.world_backup.restore_prompt"),
                i18n("swing.world_backup.restore_title"),
                JOptionPane.QUESTION_MESSAGE,
                null,
                null,
                suggestedRestoreName(selectedArchive));
        if (!(input instanceof String text)) {
            return null;
        }
        String normalized = text.trim();
        return normalized.isBlank() ? null : normalized;
    }

    /// Requests final confirmation for a non-destructive new-save restoration.
    ///
    /// @param owner Swing dialog owner
    /// @param archive selected archive
    /// @param destinationName requested new save directory name
    /// @return whether restoration was accepted
    @Override
    public boolean confirmRestore(Component owner, WorldBackupArchive archive, String destinationName) {
        EdtDispatcher.requireEventDispatchThread();
        WorldBackupArchive selectedArchive = Objects.requireNonNull(archive, "archive");
        String selectedDestinationName = Objects.requireNonNull(destinationName, "destinationName");
        return JOptionPane.showConfirmDialog(
                Objects.requireNonNull(owner, "owner"),
                i18n(
                        "swing.world_backup.restore_confirmation",
                        selectedArchive.fileName(),
                        selectedDestinationName),
                i18n("swing.world_backup.restore_title"),
                JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE) == JOptionPane.YES_OPTION;
    }

    /// Displays one concise failure message on the EDT.
    ///
    /// @param owner Swing dialog owner
    /// @param title visible failure title
    /// @param detail concise operation failure detail
    @Override
    public void showFailure(Component owner, String title, String detail) {
        EdtDispatcher.requireEventDispatchThread();
        JOptionPane.showMessageDialog(
                Objects.requireNonNull(owner, "owner"),
                Objects.requireNonNull(detail, "detail"),
                Objects.requireNonNull(title, "title"),
                JOptionPane.ERROR_MESSAGE);
    }

    /// Runs platform desktop integration after asserting a non-EDT worker context.
    ///
    /// @param directory normalized directory to open
    /// @param result externally observed terminal result
    private static void openDirectoryOnWorker(Path directory, CompletableFuture<@Nullable Void> result) {
        try {
            requireBackgroundThread();
            Files.createDirectories(directory);
            if (!Desktop.isDesktopSupported()) {
                throw new UnsupportedOperationException(i18n("swing.world_backup.desktop_unavailable"));
            }
            Desktop desktop = Desktop.getDesktop();
            if (!desktop.isSupported(Desktop.Action.OPEN)) {
                throw new UnsupportedOperationException(i18n("swing.world_backup.desktop_open_unsupported"));
            }
            desktop.open(directory.toFile());
            result.complete(null);
        } catch (IOException | RuntimeException exception) {
            result.completeExceptionally(exception);
        }
    }

    /// Derives a safe-looking default directory name without parsing archive contents on the EDT.
    ///
    /// @param archive selected backup archive
    /// @return proposed new save directory name
    private static String suggestedRestoreName(WorldBackupArchive archive) {
        String fileName = Objects.requireNonNull(archive, "archive").fileName();
        String baseName = fileName.toLowerCase(java.util.Locale.ROOT).endsWith(".zip")
                ? fileName.substring(0, fileName.length() - 4)
                : fileName;
        return baseName + "-restored";
    }

    /// Rejects worker execution on the Swing event dispatch thread.
    private static void requireBackgroundThread() {
        if (SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException("World backup desktop work must not run on the Swing EDT");
        }
    }
}
