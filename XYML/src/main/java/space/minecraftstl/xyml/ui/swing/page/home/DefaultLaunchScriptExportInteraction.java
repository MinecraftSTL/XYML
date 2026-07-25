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
package space.minecraftstl.xyml.ui.swing.page.home;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;
import space.minecraftstl.xyml.util.platform.OperatingSystem;

import javax.swing.JFileChooser;
import javax.swing.JOptionPane;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.Component;
import java.io.File;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

import static space.minecraftstl.xyml.util.i18n.I18n.i18n;

/// Implements the local native file chooser and completion dialogs for launch-script export.
///
/// The interaction is deliberately limited to local path selection and user-visible feedback. Authentication,
/// dependency resolution, and actual writing remain in the legacy launch-command boundary.
@NotNullByDefault
final class DefaultLaunchScriptExportInteraction implements LaunchScriptExportInteraction {
    /// Opens a save dialog with every script suffix supported by the current operating system.
    ///
    /// @param owner native dialog owner
    /// @param instanceLabel selected instance label used only to validate the selection boundary
    /// @return selected normalized target, or an empty value when the user cancels
    @Override
    public Optional<Path> chooseDestination(Component owner, String instanceLabel) {
        EdtDispatcher.requireEventDispatchThread();
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(instanceLabel, "instanceLabel");

        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle(i18n("version.launch_script.save"));
        chooser.setDialogType(JFileChooser.SAVE_DIALOG);
        chooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
        chooser.setMultiSelectionEnabled(false);
        chooser.setAcceptAllFileFilterUsed(false);
        configureScriptFilters(chooser);
        chooser.setSelectedFile(new File("launch." + defaultScriptExtension()));
        if (chooser.showSaveDialog(owner) != JFileChooser.APPROVE_OPTION) {
            return Optional.empty();
        }
        @Nullable File selected = chooser.getSelectedFile();
        return selected == null
                ? Optional.empty()
                : Optional.of(ensureScriptExtension(selected.toPath()));
    }

    /// Shows the successfully written script location on the EDT.
    ///
    /// @param owner native dialog owner
    /// @param scriptFile exact generated local script
    @Override
    public void exportSucceeded(Component owner, Path scriptFile) {
        EdtDispatcher.requireEventDispatchThread();
        Path destination = Objects.requireNonNull(scriptFile, "scriptFile").toAbsolutePath().normalize();
        JOptionPane.showMessageDialog(
                Objects.requireNonNull(owner, "owner"),
                i18n("version.launch_script.success", destination),
                i18n("version.launch_script"),
                JOptionPane.INFORMATION_MESSAGE);
    }

    /// Shows a concise terminal export error on the EDT.
    ///
    /// @param owner native dialog owner
    /// @param failure terminal export failure
    @Override
    public void exportFailed(Component owner, Throwable failure) {
        EdtDispatcher.requireEventDispatchThread();
        Throwable source = unwrap(Objects.requireNonNull(failure, "failure"));
        JOptionPane.showMessageDialog(
                Objects.requireNonNull(owner, "owner"),
                failureDetail(source),
                i18n("version.launch_script.failed"),
                JOptionPane.ERROR_MESSAGE);
    }

    /// Adds platform-supported script suffix filters without exposing unsupported defaults.
    ///
    /// @param chooser local native chooser to configure
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

    /// Appends the platform-default suffix when the user supplied no supported script extension.
    ///
    /// @param selected selected local target
    /// @return normalized target with a supported extension
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

    /// Reports whether one filename extension can be generated by the current launcher runtime.
    ///
    /// @param extension candidate extension without its leading dot
    /// @return whether the extension is supported by the active operating system
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

    /// Returns the native shell suffix chosen when the user omits a supported extension.
    ///
    /// @return non-blank platform-default suffix without the leading dot
    private static String defaultScriptExtension() {
        return switch (OperatingSystem.CURRENT_OS) {
            case WINDOWS -> "bat";
            case MACOS -> "command";
            default -> "sh";
        };
    }

    /// Removes completion wrappers so the native dialog presents the underlying operational failure.
    ///
    /// @param failure terminal failure or wrapper
    /// @return innermost available failure
    private static Throwable unwrap(Throwable failure) {
        Throwable current = Objects.requireNonNull(failure, "failure");
        while ((current instanceof CompletionException || current instanceof ExecutionException)
                && current.getCause() != null) {
            current = Objects.requireNonNull(current.getCause(), "completion wrapper cause");
        }
        return current;
    }

    /// Returns a stable human-readable error detail without exposing an empty dialog body.
    ///
    /// @param failure unwrapped terminal failure
    /// @return non-blank visible detail
    private static String failureDetail(Throwable failure) {
        @Nullable String message = Objects.requireNonNull(failure, "failure").getMessage();
        return message == null || message.isBlank() ? failure.getClass().getSimpleName() : message;
    }
}
