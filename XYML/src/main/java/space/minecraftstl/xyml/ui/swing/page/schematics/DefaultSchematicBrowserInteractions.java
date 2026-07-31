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
package space.minecraftstl.xyml.ui.swing.page.schematics;

import space.minecraftstl.xyml.ui.swing.dialog.EditablePathChooser;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;

import javax.swing.JFileChooser;
import javax.swing.JOptionPane;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.Component;
import java.awt.Desktop;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;

/// Default Swing dialog and AWT desktop implementation for schematic browser actions.
///
/// The caller owns the injected executor; this class neither shuts it down nor substitutes a
/// default. Dialog methods reject calls outside the event-dispatch thread. Reveal work is submitted
/// to the executor and never performs network access or references JavaFX.
@NotNullByDefault
public final class DefaultSchematicBrowserInteractions implements SchematicBrowserInteractions {
    /// Localized action presentation.
    private final SchematicBrowserActionStrings strings;

    /// Caller-owned executor used for desktop integration.
    private final Executor executor;

    /// Injectable Swing dialog boundary.
    private final DialogActions dialogActions;

    /// Injectable AWT desktop boundary.
    private final DesktopActions desktopActions;

    /// Creates production interactions with explicit localized text and background executor.
    ///
    /// @param strings localized action presentation
    /// @param executor caller-owned executor suitable for desktop calls
    public DefaultSchematicBrowserInteractions(
            SchematicBrowserActionStrings strings,
            Executor executor) {
        this(strings, executor, new SwingDialogActions(), new AwtDesktopActions());
    }

    /// Creates interactions with deterministic dialog and desktop boundaries.
    ///
    /// @param strings localized action presentation
    /// @param executor caller-owned executor suitable for desktop calls
    /// @param dialogActions dialog boundary
    /// @param desktopActions desktop boundary
    DefaultSchematicBrowserInteractions(
            SchematicBrowserActionStrings strings,
            Executor executor,
            DialogActions dialogActions,
            DesktopActions desktopActions) {
        this.strings = Objects.requireNonNull(strings, "strings");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.dialogActions = Objects.requireNonNull(dialogActions, "dialogActions");
        this.desktopActions = Objects.requireNonNull(desktopActions, "desktopActions");
    }

    /// Opens the configured multi-selection Litematic chooser on the event-dispatch thread.
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
        chooser.setFileFilter(new FileNameExtensionFilter(
                strings.litematicFileDescription(), "litematic"));

        if (dialogActions.showOpenDialog(chooser, owner) != JFileChooser.APPROVE_OPTION) {
            return List.of();
        }
        return Arrays.stream(chooser.getSelectedFiles())
                .map(File::toPath)
                .toList();
    }

    /// Prompts for one direct child-directory name on the event-dispatch thread.
    @Override
    public @Nullable String promptDirectoryName(Component owner) {
        EdtDispatcher.requireEventDispatchThread();
        Objects.requireNonNull(owner, "owner");
        return dialogActions.showInputDialog(
                owner,
                strings.createDirectoryPrompt(),
                strings.createDirectoryAction(),
                JOptionPane.QUESTION_MESSAGE);
    }

    /// Confirms deletion of one exact row on the event-dispatch thread.
    @Override
    public boolean confirmDelete(Component owner, SchematicBrowserItem target) {
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

    /// Submits a platform reveal without blocking the caller.
    @Override
    public CompletionStage<@Nullable Void> reveal(SchematicBrowserItem target) {
        Objects.requireNonNull(target, "target");
        CompletableFuture<@Nullable Void> completion = new CompletableFuture<>();
        try {
            executor.execute(() -> revealOnExecutor(target, completion));
        } catch (RuntimeException failure) {
            completion.completeExceptionally(failure);
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

    /// Performs one reveal attempt on the injected executor and preserves its terminal error.
    ///
    /// @param target exact browser row
    /// @param completion reveal completion
    private void revealOnExecutor(
            SchematicBrowserItem target,
            CompletableFuture<@Nullable Void> completion) {
        try {
            Path targetPath = target.path();
            if (desktopActions.isSupported(Desktop.Action.BROWSE_FILE_DIR)) {
                desktopActions.browseFileDirectory(targetPath);
            } else {
                if (!desktopActions.isSupported(Desktop.Action.OPEN)) {
                    throw new UnsupportedOperationException(
                            "The platform desktop cannot reveal files or open directories");
                }
                desktopActions.open(fallbackDirectory(target));
            }
            completion.complete(null);
        } catch (Exception failure) {
            completion.completeExceptionally(failure);
        }
    }

    /// Resolves the documented open fallback for directory and file rows.
    ///
    /// @param target exact browser row
    /// @return directory row itself or file row parent
    private static Path fallbackDirectory(SchematicBrowserItem target) {
        if (target instanceof SchematicDirectoryItem) {
            return target.path();
        }
        @Nullable Path parent = target.path().getParent();
        if (parent == null) {
            throw new IllegalArgumentException("A schematic file target must have a parent directory");
        }
        return parent;
    }

    /// Production `JFileChooser` and `JOptionPane` calls.
    @NotNullByDefault
    private static final class SwingDialogActions implements DialogActions {
        /// Creates a stateless production dialog boundary.
        private SwingDialogActions() {
        }

        /// Displays the configured chooser.
        @Override
        public int showOpenDialog(JFileChooser chooser, Component owner) {
            return chooser.showOpenDialog(owner);
        }

        /// Displays the directory-name prompt.
        @Override
        public @Nullable String showInputDialog(
                Component owner,
                Object message,
                String title,
                int messageType) {
            return JOptionPane.showInputDialog(owner, message, title, messageType);
        }

        /// Displays the deletion confirmation.
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
    private static final class AwtDesktopActions implements DesktopActions {
        /// Creates a stateless desktop adapter without resolving the desktop eagerly.
        private AwtDesktopActions() {
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

        /// Opens one fallback directory with the platform handler.
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
}
