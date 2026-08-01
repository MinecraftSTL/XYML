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
package space.minecraftstl.xyml.ui.swing.page.instances.management;

import space.minecraftstl.xyml.game.GameInstanceID;
import space.minecraftstl.xyml.ui.swing.dialog.EditablePathChooser;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import space.minecraftstl.xyml.setting.GameInstanceIconType;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;

import javax.swing.JFileChooser;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.Component;
import java.awt.Desktop;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;

/// Production Swing and AWT integration for instance overview commands.
///
/// File chooser and confirmation calls stay on the EDT. Directory creation and `Desktop` calls run
/// on the supplied executor so opening a slow file browser never stalls the management view.
@NotNullByDefault
public final class DefaultInstanceOverviewInteractions implements InstanceOverviewInteractions {
    /// Text used for native dialog configuration.
    private final InstanceOverviewStrings strings;

    /// Caller-owned executor used for local desktop operations.
    private final Executor executor;

    /// Creates production interactions using the provided text and IO executor.
    ///
    /// @param strings stable overview text
    /// @param executor caller-owned executor that must not run work on the EDT
    DefaultInstanceOverviewInteractions(InstanceOverviewStrings strings, Executor executor) {
        this.strings = Objects.requireNonNull(strings, "strings");
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    /// Displays the supported-image chooser on the Swing event-dispatch thread.
    ///
    /// @param owner chooser parent component
    /// @param initialDirectory initial local directory
    /// @return selected image path, or `null` when cancelled
    private @Nullable Path chooseIcon(Component owner, Path initialDirectory) {
        EdtDispatcher.requireEventDispatchThread();
        Objects.requireNonNull(owner, "owner");
        Path directory = Objects.requireNonNull(initialDirectory, "initialDirectory")
                .toAbsolutePath()
                .normalize();

        JFileChooser chooser = new EditablePathChooser(directory.toFile());
        chooser.setDialogTitle(strings.iconChooserTitle());
        chooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
        chooser.setMultiSelectionEnabled(false);
        chooser.setAcceptAllFileFilterUsed(false);
        chooser.setFileFilter(new FileNameExtensionFilter(
                strings.imageFileDescription(),
                "png",
                "jpg",
                "jpeg",
                "gif",
                "webp"));
        if (chooser.showOpenDialog(owner) != JFileChooser.APPROVE_OPTION) {
            return null;
        }

        @Nullable File selected = chooser.getSelectedFile();
        return selected != null ? selected.toPath() : null;
    }

    /// Displays the complete single-select icon grid and reuses the supported-image chooser for its custom entry.
    ///
    /// @param owner parent component for both dialogs
    /// @param currentIconType persisted built-in fallback type
    /// @param hasCustomIcon whether a custom image currently overrides the fallback
    /// @param initialDirectory initial local directory for custom image acquisition
    /// @return completed icon choice, or `null` when either dialog is cancelled
    @Override
    public @Nullable InstanceIconChoice chooseInstanceIcon(
            Component owner,
            GameInstanceIconType currentIconType,
            boolean hasCustomIcon,
            Path initialDirectory) {
        EdtDispatcher.requireEventDispatchThread();
        return InstanceIconChooserDialog.show(
                owner,
                currentIconType,
                hasCustomIcon,
                strings,
                () -> chooseIcon(owner, initialDirectory));
    }

    /// Displays the destructive custom-icon confirmation on the EDT.
    ///
    /// @param owner confirmation parent component
    /// @param instanceId stable instance identifier
    /// @return whether deletion was confirmed
    @Override
    public boolean confirmDeleteIcon(Component owner, GameInstanceID instanceId) {
        EdtDispatcher.requireEventDispatchThread();
        Objects.requireNonNull(owner, "owner");
        GameInstanceID id = Objects.requireNonNull(instanceId, "instanceId");
        return JOptionPane.showConfirmDialog(
                owner,
                strings.deleteIconConfirmationFormat().formatted(id),
                strings.deleteIconTitle(),
                JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE) == JOptionPane.YES_OPTION;
    }

    /// Schedules directory creation and platform opening outside the EDT.
    ///
    /// @param directory directory to ensure and open
    /// @return completion stage for the platform request
    @Override
    public CompletionStage<@Nullable Void> openDirectory(Path directory) {
        Path normalizedDirectory = Objects.requireNonNull(directory, "directory")
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

    /// Displays one operational error on the EDT.
    ///
    /// @param owner dialog parent component
    /// @param title non-blank dialog title
    /// @param detail human-readable failure detail
    @Override
    public void showFailure(Component owner, String title, String detail) {
        EdtDispatcher.requireEventDispatchThread();
        JOptionPane.showMessageDialog(
                Objects.requireNonNull(owner, "owner"),
                Objects.requireNonNull(detail, "detail"),
                Objects.requireNonNull(title, "title"),
                JOptionPane.ERROR_MESSAGE);
    }

    /// Ensures and opens a directory from the caller-owned executor.
    ///
    /// @param directory normalized target directory
    /// @param completion completion to resolve after the platform request
    private static void openDirectoryOnExecutor(
            Path directory,
            CompletableFuture<@Nullable Void> completion) {
        try {
            requireBackgroundThread();
            Files.createDirectories(directory);
            if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                throw new UnsupportedOperationException("The platform desktop cannot open local directories");
            }
            Desktop.getDesktop().open(directory.toFile());
            completion.complete(null);
        } catch (Exception failure) {
            completion.completeExceptionally(failure);
        } catch (Error failure) {
            completion.completeExceptionally(failure);
            throw failure;
        }
    }

    /// Rejects Desktop and filesystem work that accidentally runs on the event-dispatch thread.
    private static void requireBackgroundThread() {
        if (SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException("Desktop and file-system work must not run on the EDT");
        }
    }

    /// Validates a required non-blank text value.
    ///
    /// @param value source text
    /// @param name parameter name
    /// @return validated text
    private static String requireNonBlank(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
