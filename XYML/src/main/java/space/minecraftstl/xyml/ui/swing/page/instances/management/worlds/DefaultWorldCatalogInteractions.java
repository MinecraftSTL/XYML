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

import space.minecraftstl.xyml.ui.swing.dialog.EditablePathChooser;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;
import space.minecraftstl.xyml.util.platform.OperatingSystem;

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
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;

import static space.minecraftstl.xyml.util.i18n.I18n.i18n;

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
        JFileChooser chooser = new EditablePathChooser(
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

    /// Prompts for one sibling copy name on the EDT.
    ///
    /// @param owner dialog owner
    /// @param world selected world
    /// @return trimmed copy name, or null after cancellation or blank input
    @Override
    public @Nullable String chooseCopyName(Component owner, WorldCatalogItem world) {
        EdtDispatcher.requireEventDispatchThread();
        WorldCatalogItem selectedWorld = Objects.requireNonNull(world, "world");
        @Nullable Object input = JOptionPane.showInputDialog(
                Objects.requireNonNull(owner, "owner"),
                strings.copyNamePrompt(),
                strings.copyDialogTitle(),
                JOptionPane.QUESTION_MESSAGE,
                null,
                null,
                strings.copyName(selectedWorld.directoryName()));
        if (!(input instanceof String text)) {
            return null;
        }
        String normalized = text.trim();
        return normalized.isBlank() ? null : normalized;
    }

    /// Shows a ZIP save chooser and explicitly confirms replacement of an existing path.
    ///
    /// @param owner dialog owner
    /// @param world selected world
    /// @return normalized ZIP destination, or null after cancellation
    @Override
    public @Nullable Path chooseExportArchive(Component owner, WorldCatalogItem world) {
        EdtDispatcher.requireEventDispatchThread();
        Component checkedOwner = Objects.requireNonNull(owner, "owner");
        WorldCatalogItem selectedWorld = Objects.requireNonNull(world, "world");
        @Nullable Path initialDirectory = selectedWorld.path().getParent();
        JFileChooser chooser = initialDirectory == null
                ? new EditablePathChooser()
                : new EditablePathChooser(initialDirectory.toFile());
        chooser.setDialogTitle(strings.exportDialogTitle());
        chooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
        chooser.setMultiSelectionEnabled(false);
        chooser.setAcceptAllFileFilterUsed(false);
        chooser.setFileFilter(new FileNameExtensionFilter(strings.archiveDescription(), "zip"));
        chooser.setSelectedFile(new java.io.File(selectedWorld.directoryName() + ".zip"));
        if (chooser.showSaveDialog(checkedOwner) != JFileChooser.APPROVE_OPTION) {
            return null;
        }
        Path destination = chooser.getSelectedFile().toPath().toAbsolutePath().normalize();
        @Nullable Path fileName = destination.getFileName();
        if (fileName == null) {
            return null;
        }
        if (!fileName.toString().toLowerCase(Locale.ROOT).endsWith(".zip")) {
            destination = destination.resolveSibling(fileName + ".zip");
            fileName = destination.getFileName();
        }
        if (Files.exists(destination)
                && JOptionPane.showConfirmDialog(
                        checkedOwner,
                        strings.overwriteConfirmation(Objects.requireNonNull(fileName).toString()),
                        strings.exportDialogTitle(),
                        JOptionPane.YES_NO_OPTION,
                        JOptionPane.WARNING_MESSAGE) != JOptionPane.YES_OPTION) {
            return null;
        }
        return destination;
    }

    /// Shows a platform-aware standalone launch-script save chooser on the EDT.
    ///
    /// @param owner dialog owner
    /// @param world selected world used to retain the exact interaction boundary
    /// @return normalized supported script destination, or null after cancellation
    @Override
    public @Nullable Path chooseLaunchScriptDestination(Component owner, WorldCatalogItem world) {
        EdtDispatcher.requireEventDispatchThread();
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(world, "world");
        JFileChooser chooser = new EditablePathChooser();
        chooser.setDialogTitle(strings.launchScriptDialogTitle());
        chooser.setDialogType(JFileChooser.SAVE_DIALOG);
        chooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
        chooser.setMultiSelectionEnabled(false);
        chooser.setAcceptAllFileFilterUsed(false);
        configureScriptFilters(chooser);
        chooser.setSelectedFile(new File("launch." + defaultScriptExtension()));
        if (chooser.showSaveDialog(owner) != JFileChooser.APPROVE_OPTION) {
            return null;
        }
        @Nullable File selected = chooser.getSelectedFile();
        return selected == null ? null : ensureScriptExtension(selected.toPath());
    }

    /// Shows the exact successfully generated quick-play script path on the EDT.
    ///
    /// @param owner dialog owner
    /// @param scriptFile exact generated script path
    @Override
    public void launchScriptSucceeded(Component owner, Path scriptFile) {
        EdtDispatcher.requireEventDispatchThread();
        Path destination = Objects.requireNonNull(scriptFile, "scriptFile").toAbsolutePath().normalize();
        JOptionPane.showMessageDialog(
                Objects.requireNonNull(owner, "owner"),
                strings.launchScriptSuccess(destination),
                strings.launchScriptDialogTitle(),
                JOptionPane.INFORMATION_MESSAGE);
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
                throw new UnsupportedOperationException(i18n("swing.world_catalog.desktop_unavailable"));
            }
            Desktop desktop = Desktop.getDesktop();
            if (!desktop.isSupported(Desktop.Action.OPEN)) {
                throw new UnsupportedOperationException(i18n("swing.world_catalog.desktop_open_unsupported"));
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

    /// Adds only script formats supported by the current packaged launcher runtime.
    ///
    /// @param chooser chooser receiving extension filters
    private static void configureScriptFilters(JFileChooser chooser) {
        JFileChooser target = Objects.requireNonNull(chooser, "chooser");
        if (OperatingSystem.CURRENT_OS == OperatingSystem.MACOS) {
            target.addChoosableFileFilter(new FileNameExtensionFilter(i18n("extension.command"), "command"));
        }
        if (OperatingSystem.CURRENT_OS == OperatingSystem.WINDOWS) {
            target.addChoosableFileFilter(new FileNameExtensionFilter(i18n("extension.bat"), "bat"));
        } else {
            target.addChoosableFileFilter(new FileNameExtensionFilter(i18n("extension.sh"), "sh"));
        }
        target.addChoosableFileFilter(new FileNameExtensionFilter(i18n("extension.ps1"), "ps1"));
    }

    /// Appends the platform-default extension when the selected filename has no supported script suffix.
    ///
    /// @param selected selected local target
    /// @return normalized target using a supported extension
    private static Path ensureScriptExtension(Path selected) {
        Path destination = Objects.requireNonNull(selected, "selected").toAbsolutePath().normalize();
        Path fileName = Objects.requireNonNull(destination.getFileName(), "selected file name");
        String name = fileName.toString();
        int separator = name.lastIndexOf('.');
        String extension = separator >= 0 ? name.substring(separator + 1) : "";
        if (isSupportedScriptExtension(extension)) {
            return destination;
        }
        return destination.resolveSibling(name + "." + defaultScriptExtension());
    }

    /// Reports whether the packaged launcher can generate one filename extension on the current platform.
    ///
    /// @param extension extension without its leading dot
    /// @return whether the extension is supported
    private static boolean isSupportedScriptExtension(String extension) {
        String value = Objects.requireNonNull(extension, "extension");
        if (OperatingSystem.CURRENT_OS == OperatingSystem.WINDOWS) {
            return value.equalsIgnoreCase("bat") || value.equalsIgnoreCase("ps1");
        }
        return value.equalsIgnoreCase("sh")
                || value.equalsIgnoreCase("bash")
                || value.equalsIgnoreCase("command")
                || value.equalsIgnoreCase("ps1");
    }

    /// Returns the default script suffix for the active operating system.
    ///
    /// @return non-blank extension without its leading dot
    private static String defaultScriptExtension() {
        return switch (OperatingSystem.CURRENT_OS) {
            case WINDOWS -> "bat";
            case MACOS -> "command";
            default -> "sh";
        };
    }
}
