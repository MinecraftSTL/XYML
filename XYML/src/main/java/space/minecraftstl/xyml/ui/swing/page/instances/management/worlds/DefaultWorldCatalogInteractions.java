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
package space.minecraftstl.xyml.ui.swing.page.instances.management.worlds;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
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

/// Production native-dialog and AWT desktop implementation for instance-world actions.
///
/// Swing chooser and confirmation calls remain on the EDT. Filesystem creation and `Desktop` calls
/// are scheduled through the caller-owned executor and reject accidental execution on the EDT.
@NotNullByDefault
public final class DefaultWorldCatalogInteractions implements WorldCatalogInteractions {
    /// Visible fallback text used by every native dialog and tooltip.
    private final WorldCatalogStrings strings;

    /// Caller-owned executor for filesystem and platform desktop work.
    private final Executor executor;

    /// Creates the production interaction implementation.
    ///
    /// @param strings stable visible text
    /// @param executor caller-owned background executor
    public DefaultWorldCatalogInteractions(WorldCatalogStrings strings, Executor executor) {
        this.strings = Objects.requireNonNull(strings, "strings");
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    /// Shows a ZIP-only single-file chooser on the EDT.
    ///
    /// @param owner dialog owner
    /// @param currentDirectory initial folder for the chooser
    /// @return selected archive, or null when cancelled
    @Override
    public @Nullable Path chooseWorldArchive(Component owner, Path currentDirectory) {
        EdtDispatcher.requireEventDispatchThread();
        JFileChooser chooser = new JFileChooser(
                Objects.requireNonNull(currentDirectory, "currentDirectory").toFile());
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

    /// Prompts for one final destination name on the EDT.
    ///
    /// @param owner dialog owner
    /// @param world validated archive candidate
    /// @return trimmed destination name, or null when cancelled or blank
    @Override
    public @Nullable String chooseWorldName(Component owner, WorldCatalogImport world) {
        EdtDispatcher.requireEventDispatchThread();
        WorldCatalogImport importWorld = Objects.requireNonNull(world, "world");
        @Nullable Object input = JOptionPane.showInputDialog(
                Objects.requireNonNull(owner, "owner"),
                strings.worldNamePrompt(),
                strings.importDialogTitle(),
                JOptionPane.QUESTION_MESSAGE,
                null,
                null,
                importWorld.suggestedName());
        if (!(input instanceof String text)) {
            return null;
        }
        String normalized = text.trim();
        return normalized.isBlank() ? null : normalized;
    }

    /// Confirms permanent removal on the EDT.
    ///
    /// @param owner dialog owner
    /// @param world current selected world
    /// @return whether the user explicitly accepted deletion
    @Override
    public boolean confirmDelete(Component owner, WorldCatalogItem world) {
        EdtDispatcher.requireEventDispatchThread();
        WorldCatalogItem selectedWorld = Objects.requireNonNull(world, "world");
        return JOptionPane.showConfirmDialog(
                Objects.requireNonNull(owner, "owner"),
                strings.deleteConfirmationFormat().formatted(selectedWorld.displayText()),
                strings.deleteDialogTitle(),
                JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE) == JOptionPane.YES_OPTION;
    }

    /// Schedules directory creation and a platform open call.
    ///
    /// @param directory target directory
    /// @return nullable-void asynchronous completion
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

    /// Displays one native failure dialog on the EDT.
    ///
    /// @param owner dialog owner
    /// @param title dialog title
    /// @param detail concise error detail
    @Override
    public void showFailure(Component owner, String title, String detail) {
        EdtDispatcher.requireEventDispatchThread();
        JOptionPane.showMessageDialog(
                Objects.requireNonNull(owner, "owner"),
                Objects.requireNonNull(detail, "detail"),
                Objects.requireNonNull(title, "title"),
                JOptionPane.ERROR_MESSAGE);
    }

    /// Opens one directory outside the EDT and completes the caller future.
    ///
    /// @param directory normalized directory
    /// @param result externally visible completion
    private static void openDirectoryOnExecutor(
            Path directory,
            CompletableFuture<@Nullable Void> result) {
        try {
            requireBackgroundThread();
            Files.createDirectories(directory);
            if (!Desktop.isDesktopSupported()) {
                throw new UnsupportedOperationException("Desktop integration is unavailable");
            }
            Desktop desktop = Desktop.getDesktop();
            if (!desktop.isSupported(Desktop.Action.OPEN)) {
                throw new UnsupportedOperationException("Desktop cannot open directories");
            }
            desktop.open(directory.toFile());
            result.complete(null);
        } catch (IOException | RuntimeException failure) {
            result.completeExceptionally(failure);
        }
    }

    /// Rejects a caller-owned executor that would execute blocking desktop work on the EDT.
    private static void requireBackgroundThread() {
        if (SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException("World desktop work must not run on the EDT");
        }
    }
}
