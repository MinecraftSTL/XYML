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
package space.minecraftstl.xyml.ui.swing.page.instances.management.maintenance;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;
import space.minecraftstl.xyml.util.platform.OperatingSystem;

import javax.swing.JFileChooser;
import javax.swing.JOptionPane;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.Component;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

import static space.minecraftstl.xyml.util.i18n.I18n.i18n;

/// Implements native local-file, confirmation, and outcome dialogs for instance maintenance.
@NotNullByDefault
public final class SwingInstanceMaintenanceInteractions implements InstanceMaintenanceInteractions {
    /// Immutable visible text used in destructive confirmations.
    private final InstanceMaintenanceStrings strings;

    /// Creates native interactions with production localized text.
    public SwingInstanceMaintenanceInteractions() {
        this(InstanceMaintenanceStrings.localized());
    }

    /// Creates native interactions with explicit visible text.
    ///
    /// @param strings immutable dialog text
    public SwingInstanceMaintenanceInteractions(InstanceMaintenanceStrings strings) {
        this.strings = Objects.requireNonNull(strings, "strings");
    }

    /// Opens a local chooser restricted to supported archive suffixes.
    ///
    /// @param owner native dialog owner
    /// @return selected archive, or null after cancellation
    @Override
    public @Nullable Path chooseModpackArchive(Component owner) {
        EdtDispatcher.requireEventDispatchThread();
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle(strings.updateModpackAction());
        chooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
        chooser.setMultiSelectionEnabled(false);
        chooser.setAcceptAllFileFilterUsed(false);
        chooser.setFileFilter(new FileNameExtensionFilter(strings.updateModpackAction(), "zip", "mrpack"));
        if (chooser.showOpenDialog(Objects.requireNonNull(owner, "owner")) != JFileChooser.APPROVE_OPTION) {
            return null;
        }
        @Nullable File selected = chooser.getSelectedFile();
        return selected == null ? null : selected.toPath().toAbsolutePath().normalize();
    }

    /// Opens a platform-aware local script save dialog.
    ///
    /// @param owner native dialog owner
    /// @param initialDirectory instance run directory used when available
    /// @return normalized supported script target, or null after cancellation
    @Override
    public @Nullable Path chooseLaunchScript(Component owner, Path initialDirectory) {
        EdtDispatcher.requireEventDispatchThread();
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle(strings.exportScriptAction());
        chooser.setDialogType(JFileChooser.SAVE_DIALOG);
        chooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
        chooser.setMultiSelectionEnabled(false);
        chooser.setAcceptAllFileFilterUsed(false);
        Path directory = Objects.requireNonNull(initialDirectory, "initialDirectory");
        if (Files.isDirectory(directory)) {
            chooser.setCurrentDirectory(directory.toFile());
        }
        configureScriptFilters(chooser);
        chooser.setSelectedFile(new File("launch." + defaultScriptExtension()));
        if (chooser.showSaveDialog(Objects.requireNonNull(owner, "owner")) != JFileChooser.APPROVE_OPTION) {
            return null;
        }
        @Nullable File selected = chooser.getSelectedFile();
        return selected == null ? null : ensureScriptExtension(selected.toPath());
    }

    /// Shows an explicit warning before shared or local data deletion.
    ///
    /// @param owner native dialog owner
    /// @param action visible operation name
    /// @param sharedScope whether deletion affects every repository instance
    /// @return whether the user approved deletion
    @Override
    public boolean confirmDestructive(Component owner, String action, boolean sharedScope) {
        EdtDispatcher.requireEventDispatchThread();
        Object[] message = sharedScope
                ? new Object[]{strings.sharedDataWarning(), strings.permanentRemovalWarning()}
                : new Object[]{strings.permanentRemovalWarning()};
        return JOptionPane.showConfirmDialog(
                Objects.requireNonNull(owner, "owner"),
                message,
                requireNonBlank(action, "action"),
                JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.WARNING_MESSAGE) == JOptionPane.OK_OPTION;
    }

    /// Shows one concise terminal failure.
    ///
    /// @param owner native dialog owner
    /// @param title visible operation title
    /// @param detail non-blank failure detail
    @Override
    public void showFailure(Component owner, String title, String detail) {
        showMessage(owner, title, detail, JOptionPane.ERROR_MESSAGE);
    }

    /// Shows one successful result with useful detail.
    ///
    /// @param owner native dialog owner
    /// @param title visible operation title
    /// @param detail non-blank completion detail
    @Override
    public void showSuccess(Component owner, String title, String detail) {
        showMessage(owner, title, detail, JOptionPane.INFORMATION_MESSAGE);
    }

    /// Shows a validated native message on the EDT.
    ///
    /// @param owner native dialog owner
    /// @param title visible title
    /// @param detail visible detail
    /// @param messageType Swing message severity
    private static void showMessage(Component owner, String title, String detail, int messageType) {
        EdtDispatcher.requireEventDispatchThread();
        JOptionPane.showMessageDialog(
                Objects.requireNonNull(owner, "owner"),
                requireNonBlank(detail, "detail"),
                requireNonBlank(title, "title"),
                messageType);
    }

    /// Adds every script format supported by the current operating system.
    ///
    /// @param chooser target local chooser
    private static void configureScriptFilters(JFileChooser chooser) {
        JFileChooser target = Objects.requireNonNull(chooser, "chooser");
        if (OperatingSystem.CURRENT_OS == OperatingSystem.MACOS) {
            target.addChoosableFileFilter(new FileNameExtensionFilter(i18n("extension.command"), "command"));
        }
        if (OperatingSystem.CURRENT_OS == OperatingSystem.WINDOWS) {
            target.addChoosableFileFilter(new FileNameExtensionFilter(i18n("extension.bat"), "bat"));
        } else {
            target.addChoosableFileFilter(new FileNameExtensionFilter(i18n("extension.sh"), "sh", "bash"));
        }
        target.addChoosableFileFilter(new FileNameExtensionFilter(i18n("extension.ps1"), "ps1"));
    }

    /// Appends the platform-default suffix when the selected name has no supported suffix.
    ///
    /// @param selected selected local target
    /// @return normalized supported target
    private static Path ensureScriptExtension(Path selected) {
        Path destination = Objects.requireNonNull(selected, "selected").toAbsolutePath().normalize();
        String name = Objects.requireNonNull(destination.getFileName(), "selected file name").toString();
        int separator = name.lastIndexOf('.');
        String extension = separator >= 0 ? name.substring(separator + 1) : "";
        if (isSupportedScriptExtension(extension)) {
            return destination;
        }
        return destination.resolveSibling(name + "." + defaultScriptExtension());
    }

    /// Reports whether one suffix is supported on the current platform.
    ///
    /// @param extension suffix without the leading dot
    /// @return whether launch script generation supports it
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

    /// Returns the native script suffix for the active platform.
    ///
    /// @return default suffix without the leading dot
    private static String defaultScriptExtension() {
        return switch (OperatingSystem.CURRENT_OS) {
            case WINDOWS -> "bat";
            case MACOS -> "command";
            default -> "sh";
        };
    }

    /// Rejects missing visible text.
    ///
    /// @param value candidate text
    /// @param name diagnostic field name
    /// @return exact non-blank text
    private static String requireNonBlank(String value, String name) {
        String candidate = Objects.requireNonNull(value, name);
        if (candidate.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return candidate;
    }
}
