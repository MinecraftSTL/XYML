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
package space.minecraftstl.xyml.ui.swing.page.mods;

import space.minecraftstl.xyml.ui.swing.dialog.EditablePathChooser;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;

import javax.swing.JFileChooser;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.Component;
import java.awt.Desktop;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;

import static space.minecraftstl.xyml.util.i18n.I18n.i18n;

/// Production Swing dialog, AWT desktop, and NIO implementation for Mod commands.
///
/// Dialogs run on the EDT. Directory creation and every Desktop call run on the caller-owned
/// executor, which is rejected if it attempts to execute those operations on the EDT.
@NotNullByDefault
public final class DefaultModCatalogInteractions implements ModCatalogInteractions {
    /// Localized command presentation.
    private final ModCatalogActionStrings strings;

    /// Caller-owned executor for file-system and desktop work.
    private final Executor executor;

    /// Creates production interactions.
    ///
    /// @param strings localized action text
    /// @param executor caller-owned background executor
    public DefaultModCatalogInteractions(
            ModCatalogActionStrings strings,
            Executor executor) {
        this.strings = Objects.requireNonNull(strings, "strings");
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    /// Opens a JAR/LITEMOD multi-selection chooser on the EDT.
    @Override
    public @Unmodifiable List<Path> chooseImportFiles(
            Component owner,
            Path currentDirectory) {
        EdtDispatcher.requireEventDispatchThread();
        JFileChooser chooser = new EditablePathChooser(
                Objects.requireNonNull(currentDirectory, "currentDirectory").toFile());
        chooser.setDialogTitle(strings.importDialogTitle());
        chooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
        chooser.setMultiSelectionEnabled(true);
        chooser.setAcceptAllFileFilterUsed(false);
        chooser.setFileFilter(new FileNameExtensionFilter(
                strings.modFileDescription(), "jar", "litemod"));
        if (chooser.showOpenDialog(Objects.requireNonNull(owner, "owner"))
                != JFileChooser.APPROVE_OPTION) {
            return List.of();
        }
        return Arrays.stream(chooser.getSelectedFiles())
                .map(File::toPath)
                .toList();
    }

    /// Shows Replace, Skip, and Keep choices for one same-local-name import.
    @Override
    public @Nullable ModImportConflictAction resolveImportConflict(
            Component owner,
            Path source) {
        EdtDispatcher.requireEventDispatchThread();
        String fileName = Objects.requireNonNull(
                Objects.requireNonNull(source, "source").getFileName(),
                "Mod source must have a file name").toString();
        String[] options = {
                i18n("swing.mods.import_conflict.replace"),
                i18n("swing.mods.import_conflict.skip"),
                i18n("swing.mods.import_conflict.keep")
        };
        int selection = JOptionPane.showOptionDialog(
                Objects.requireNonNull(owner, "owner"),
                i18n("swing.mods.import_conflict", fileName),
                i18n("mods.add"),
                JOptionPane.DEFAULT_OPTION,
                JOptionPane.WARNING_MESSAGE,
                null,
                options,
                options[2]);
        return switch (selection) {
            case 0 -> ModImportConflictAction.REPLACE;
            case 1 -> ModImportConflictAction.SKIP;
            case 2 -> ModImportConflictAction.KEEP;
            default -> null;
        };
    }

    /// Shows one permanent-delete confirmation on the EDT.
    @Override
    public boolean confirmDelete(Component owner, ModCatalogItem target) {
        EdtDispatcher.requireEventDispatchThread();
        String message = strings.deleteConfirmationFormat().formatted(
                Objects.requireNonNull(target, "target").fileName());
        return JOptionPane.showConfirmDialog(
                Objects.requireNonNull(owner, "owner"),
                message,
                strings.deleteAction(),
                JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE) == JOptionPane.YES_OPTION;
    }

    /// Shows the legacy generic permanent-delete confirmation for a selected batch on the EDT.
    @Override
    public boolean confirmDeleteSelected(Component owner, int selectedCount) {
        EdtDispatcher.requireEventDispatchThread();
        Objects.requireNonNull(owner, "owner");
        if (selectedCount <= 0) {
            throw new IllegalArgumentException("selectedCount must be positive");
        }
        return JOptionPane.showConfirmDialog(
                owner,
                i18n("button.remove.confirm"),
                i18n("button.remove"),
                JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE) == JOptionPane.YES_OPTION;
    }

    /// Submits exact file reveal to the background executor.
    @Override
    public CompletionStage<@Nullable Void> reveal(Path target) {
        Path normalizedTarget = Objects.requireNonNull(target, "target")
                .toAbsolutePath().normalize();
        return submit(() -> revealOnBackground(normalizedTarget));
    }

    /// Submits directory creation and opening to the background executor.
    @Override
    public CompletionStage<@Nullable Void> openDirectory(Path directory) {
        Path normalizedDirectory = Objects.requireNonNull(directory, "directory")
                .toAbsolutePath().normalize();
        return submit(() -> openDirectoryOnBackground(normalizedDirectory));
    }

    /// Shows one JOptionPane failure on the EDT.
    @Override
    public void showFailure(Component owner, String title, String detail) {
        EdtDispatcher.requireEventDispatchThread();
        JOptionPane.showMessageDialog(
                Objects.requireNonNull(owner, "owner"),
                Objects.requireNonNull(detail, "detail"),
                Objects.requireNonNull(title, "title"),
                JOptionPane.ERROR_MESSAGE);
    }

    /// Submits one checked background action and preserves its original failure.
    ///
    /// @param action checked background action
    /// @return asynchronous nullable-void completion
    private CompletionStage<@Nullable Void> submit(BackgroundAction action) {
        CompletableFuture<@Nullable Void> result = new CompletableFuture<>();
        try {
            executor.execute(() -> execute(action, result));
        } catch (RuntimeException failure) {
            result.completeExceptionally(failure);
        }
        return result;
    }

    /// Runs one submitted action and completes its Future.
    ///
    /// @param action checked background action
    /// @param result externally visible completion
    private static void execute(
            BackgroundAction action,
            CompletableFuture<@Nullable Void> result) {
        try {
            requireBackgroundThread();
            action.run();
            result.complete(null);
        } catch (IOException | RuntimeException failure) {
            result.completeExceptionally(failure);
        }
    }

    /// Uses dedicated reveal support or falls back to opening the containing directory.
    ///
    /// @param target exact normalized target
    /// @throws IOException when desktop integration fails
    private static void revealOnBackground(Path target) throws IOException {
        Desktop desktop = desktop();
        if (desktop.isSupported(Desktop.Action.BROWSE_FILE_DIR)) {
            desktop.browseFileDirectory(target.toFile());
            return;
        }
        if (!desktop.isSupported(Desktop.Action.OPEN)) {
            throw new UnsupportedOperationException("Desktop cannot reveal files or open directories");
        }
        @Nullable Path parent = target.getParent();
        if (parent == null) {
            throw new IllegalArgumentException("Mod target must have a parent directory");
        }
        desktop.open(parent.toFile());
    }

    /// Ensures and opens one normalized directory.
    ///
    /// @param directory normalized directory
    /// @throws IOException when creation or desktop integration fails
    private static void openDirectoryOnBackground(Path directory) throws IOException {
        Files.createDirectories(directory);
        Desktop desktop = desktop();
        if (!desktop.isSupported(Desktop.Action.OPEN)) {
            throw new UnsupportedOperationException("Desktop cannot open directories");
        }
        desktop.open(directory.toFile());
    }

    /// Resolves the platform Desktop only on the worker thread.
    ///
    /// @return supported Desktop instance
    private static Desktop desktop() {
        if (!Desktop.isDesktopSupported()) {
            throw new UnsupportedOperationException("Desktop integration is unavailable");
        }
        return Desktop.getDesktop();
    }

    /// Rejects a caller-owned executor that runs blocking integration on the EDT.
    private static void requireBackgroundThread() {
        if (SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException("Mod desktop and file work must not run on the EDT");
        }
    }

    /// Checked background integration action.
    @FunctionalInterface
    @NotNullByDefault
    private interface BackgroundAction {
        /// Runs one blocking action.
        ///
        /// @throws IOException when platform integration fails
        void run() throws IOException;
    }
}
