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
package space.minecraftstl.xyml.ui.swing.page.resourcepacks;

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

/// Default Swing dialog, AWT desktop, and NIO implementation for resource-pack catalog actions.
///
/// The caller owns the injected executor. Dialog methods reject calls outside the event-dispatch
/// thread, while worker operations reject executors that attempt Desktop or Files I/O on that
/// thread. The implementation performs no network access and has no JavaFX dependency.
@NotNullByDefault
public final class DefaultResourcePackCatalogInteractions implements ResourcePackCatalogInteractions {
    /// Localized action presentation.
    private final ResourcePackCatalogActionStrings strings;

    /// Caller-owned executor used for all desktop and file-system integration.
    private final Executor executor;

    /// Injectable Swing dialog boundary.
    private final ResourcePackDialogActions dialogActions;

    /// Injectable AWT desktop boundary.
    private final ResourcePackDesktopActions desktopActions;

    /// Injectable NIO file-system boundary.
    private final ResourcePackFileActions fileActions;

    /// Creates production interactions with explicit localized text and background executor.
    ///
    /// @param strings localized action presentation
    /// @param executor caller-owned executor that must execute work outside the EDT
    public DefaultResourcePackCatalogInteractions(
            ResourcePackCatalogActionStrings strings,
            Executor executor) {
        this(
                strings,
                executor,
                new SwingResourcePackDialogActions(),
                new AwtResourcePackDesktopActions(),
                new NioResourcePackFileActions());
    }

    /// Creates interactions with deterministic dialog, desktop, and file-system boundaries.
    ///
    /// @param strings localized action presentation
    /// @param executor caller-owned executor that must execute work outside the EDT
    /// @param dialogActions dialog boundary
    /// @param desktopActions desktop boundary
    /// @param fileActions file-system boundary
    DefaultResourcePackCatalogInteractions(
            ResourcePackCatalogActionStrings strings,
            Executor executor,
            ResourcePackDialogActions dialogActions,
            ResourcePackDesktopActions desktopActions,
            ResourcePackFileActions fileActions) {
        this.strings = Objects.requireNonNull(strings, "strings");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.dialogActions = Objects.requireNonNull(dialogActions, "dialogActions");
        this.desktopActions = Objects.requireNonNull(desktopActions, "desktopActions");
        this.fileActions = Objects.requireNonNull(fileActions, "fileActions");
    }

    /// Opens the configured multi-selection ZIP chooser on the event-dispatch thread.
    @Override
    public @Unmodifiable List<Path> chooseImportFiles(Component owner, Path currentDirectory) {
        EdtDispatcher.requireEventDispatchThread();
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(currentDirectory, "currentDirectory");

        JFileChooser chooser = new EditablePathChooser(currentDirectory.toFile());
        chooser.setDialogTitle(strings.importDialogTitle());
        chooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
        chooser.setMultiSelectionEnabled(true);
        chooser.setAcceptAllFileFilterUsed(false);
        chooser.setFileFilter(new FileNameExtensionFilter(strings.zipFileDescription(), "zip"));

        if (dialogActions.showOpenDialog(chooser, owner) != JFileChooser.APPROVE_OPTION) {
            return List.of();
        }
        return Arrays.stream(chooser.getSelectedFiles())
                .map(File::toPath)
                .toList();
    }

    /// Confirms enabling one incompatible pack on the event-dispatch thread.
    @Override
    public boolean confirmEnableIncompatible(Component owner, ResourcePackCatalogItem target) {
        EdtDispatcher.requireEventDispatchThread();
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(target, "target");
        if (target.compatibility() == ResourcePackCompatibility.COMPATIBLE) {
            throw new IllegalArgumentException("target must be incompatible");
        }
        String message = strings.incompatibleEnableConfirmationFormat().formatted(target.fileName());
        return dialogActions.showConfirmDialog(
                owner,
                message,
                strings.incompatibleEnableTitle(),
                JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE) == JOptionPane.YES_OPTION;
    }

    /// Confirms enabling one selected path batch without loading off-screen metadata.
    @Override
    public boolean confirmEnableSelected(Component owner, int selectedCount) {
        EdtDispatcher.requireEventDispatchThread();
        Objects.requireNonNull(owner, "owner");
        requirePositiveSelectionCount(selectedCount);
        return dialogActions.showConfirmDialog(
                owner,
                i18n("resourcepack.warning.manipulate"),
                i18n("message.warning"),
                JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE) == JOptionPane.YES_OPTION;
    }

    /// Confirms permanent deletion of one pack on the event-dispatch thread.
    @Override
    public boolean confirmDelete(Component owner, ResourcePackCatalogItem target) {
        EdtDispatcher.requireEventDispatchThread();
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(target, "target");
        String message = strings.deleteConfirmationFormat().formatted(target.fileName());
        return dialogActions.showConfirmDialog(
                owner,
                message,
                strings.deleteAction(),
                JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE) == JOptionPane.YES_OPTION;
    }

    /// Confirms permanently deleting one selected path batch.
    @Override
    public boolean confirmDeleteSelected(Component owner, int selectedCount) {
        EdtDispatcher.requireEventDispatchThread();
        Objects.requireNonNull(owner, "owner");
        requirePositiveSelectionCount(selectedCount);
        return dialogActions.showConfirmDialog(
                owner,
                i18n("button.remove.confirm"),
                i18n("button.remove"),
                JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE) == JOptionPane.YES_OPTION;
    }

    /// Submits a platform reveal without blocking the caller.
    @Override
    public CompletionStage<@Nullable Void> reveal(ResourcePackCatalogItem target) {
        Objects.requireNonNull(target, "target");
        CompletableFuture<@Nullable Void> completion = new CompletableFuture<>();
        try {
            executor.execute(() -> revealOnExecutor(target, completion));
        } catch (RuntimeException failure) {
            completion.completeExceptionally(failure);
        } catch (Error failure) {
            completion.completeExceptionally(failure);
            throw failure;
        }
        return completion;
    }

    /// Submits directory creation and opening without blocking the caller.
    @Override
    public CompletionStage<@Nullable Void> openResourcePackDirectory(Path resourcePackDirectory) {
        Path normalizedDirectory = Objects.requireNonNull(resourcePackDirectory, "resourcePackDirectory")
                .toAbsolutePath()
                .normalize();
        CompletableFuture<@Nullable Void> completion = new CompletableFuture<>();
        try {
            executor.execute(() -> openDirectoryOnExecutor(normalizedDirectory, completion));
        } catch (RuntimeException failure) {
            completion.completeExceptionally(failure);
        } catch (Error failure) {
            completion.completeExceptionally(failure);
            throw failure;
        }
        return completion;
    }

    /// Shows one error message on the event-dispatch thread.
    @Override
    public void showFailure(Component owner, String title, String detail) {
        EdtDispatcher.requireEventDispatchThread();
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(detail, "detail");
        dialogActions.showMessageDialog(owner, detail, title, JOptionPane.ERROR_MESSAGE);
    }

    /// Rejects empty or negative batch confirmations.
    ///
    /// @param selectedCount selected path count
    private static void requirePositiveSelectionCount(int selectedCount) {
        if (selectedCount <= 0) {
            throw new IllegalArgumentException("selectedCount must be positive");
        }
    }

    /// Performs one reveal attempt on the injected executor and preserves its terminal error.
    ///
    /// @param target exact installed pack
    /// @param completion reveal completion
    private void revealOnExecutor(
            ResourcePackCatalogItem target,
            CompletableFuture<@Nullable Void> completion) {
        try {
            requireBackgroundThread();
            Path targetPath = target.path();
            if (desktopActions.isSupported(Desktop.Action.BROWSE_FILE_DIR)) {
                desktopActions.browseFileDirectory(targetPath);
            } else {
                if (!desktopActions.isSupported(Desktop.Action.OPEN)) {
                    throw new UnsupportedOperationException(
                            "The platform desktop cannot reveal files or open directories");
                }
                desktopActions.open(parentDirectory(targetPath));
            }
            completion.complete(null);
        } catch (Exception failure) {
            completion.completeExceptionally(failure);
        } catch (Error failure) {
            completion.completeExceptionally(failure);
            throw failure;
        }
    }

    /// Ensures and opens the resource-pack directory on the injected executor.
    ///
    /// @param directory normalized absolute resource-pack directory
    /// @param completion directory-open completion
    private void openDirectoryOnExecutor(
            Path directory,
            CompletableFuture<@Nullable Void> completion) {
        try {
            requireBackgroundThread();
            fileActions.createDirectories(directory);
            if (!desktopActions.isSupported(Desktop.Action.OPEN)) {
                throw new UnsupportedOperationException(
                        "The platform desktop cannot open the resource-pack directory");
            }
            desktopActions.open(directory);
            completion.complete(null);
        } catch (Exception failure) {
            completion.completeExceptionally(failure);
        } catch (Error failure) {
            completion.completeExceptionally(failure);
            throw failure;
        }
    }

    /// Resolves the containing directory required by the reveal fallback.
    ///
    /// @param target exact resource-pack path
    /// @return containing directory
    private static Path parentDirectory(Path target) {
        @Nullable Path parent = target.getParent();
        if (parent == null) {
            throw new IllegalArgumentException("A resource-pack target must have a parent directory");
        }
        return parent;
    }

    /// Prevents a misconfigured executor from performing Desktop or Files I/O on the EDT.
    private static void requireBackgroundThread() {
        if (SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException("Desktop and file-system work must not run on the EDT");
        }
    }

    /// Production `JFileChooser` and `JOptionPane` calls.
    @NotNullByDefault
    private static final class SwingResourcePackDialogActions implements ResourcePackDialogActions {
        /// Creates a stateless production dialog boundary.
        private SwingResourcePackDialogActions() {
        }

        /// Displays the configured chooser.
        @Override
        public int showOpenDialog(JFileChooser chooser, Component owner) {
            return chooser.showOpenDialog(owner);
        }

        /// Displays one confirmation.
        @Override
        public int showConfirmDialog(
                Component owner,
                Object message,
                String title,
                int optionType,
                int messageType) {
            return JOptionPane.showConfirmDialog(owner, message, title, optionType, messageType);
        }

        /// Displays one failure message.
        @Override
        public void showMessageDialog(
                Component owner,
                Object message,
                String title,
                int messageType) {
            JOptionPane.showMessageDialog(owner, message, title, messageType);
        }
    }

    /// Production adapter around `java.awt.Desktop`.
    @NotNullByDefault
    private static final class AwtResourcePackDesktopActions implements ResourcePackDesktopActions {
        /// Creates a stateless desktop adapter without resolving the desktop eagerly.
        private AwtResourcePackDesktopActions() {
        }

        /// Reports desktop action support without failing construction in headless environments.
        @Override
        public boolean isSupported(Desktop.Action action) {
            return Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(action);
        }

        /// Uses the dedicated platform reveal operation.
        @Override
        public void browseFileDirectory(Path target) throws IOException {
            desktop().browseFileDirectory(target.toFile());
        }

        /// Opens one directory with the platform handler.
        @Override
        public void open(Path directory) throws IOException {
            desktop().open(directory.toFile());
        }

        /// Resolves the platform desktop only on the executor thread.
        ///
        /// @return available platform desktop
        private static Desktop desktop() {
            if (!Desktop.isDesktopSupported()) {
                throw new UnsupportedOperationException("Desktop integration is unavailable");
            }
            return Desktop.getDesktop();
        }
    }

    /// Production adapter around `Files.createDirectories`.
    @NotNullByDefault
    private static final class NioResourcePackFileActions implements ResourcePackFileActions {
        /// Creates a stateless file-system adapter.
        private NioResourcePackFileActions() {
        }

        /// Ensures one directory and any missing parents.
        @Override
        public void createDirectories(Path directory) throws IOException {
            Files.createDirectories(directory);
        }
    }
}
