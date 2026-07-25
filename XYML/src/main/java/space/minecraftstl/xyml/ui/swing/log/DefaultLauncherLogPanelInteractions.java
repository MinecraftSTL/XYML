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
package space.minecraftstl.xyml.ui.swing.log;

import org.jetbrains.annotations.NotNullByDefault;
import space.minecraftstl.xyml.util.StringUtils;

import javax.swing.JOptionPane;
import java.awt.Component;
import java.awt.Desktop;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;

import static space.minecraftstl.xyml.util.i18n.I18n.i18n;
import static space.minecraftstl.xyml.util.logging.Logger.LOG;

/// Implements launcher-log interaction feedback with native Swing dialogs and desktop file-manager integration.
@NotNullByDefault
public final class DefaultLauncherLogPanelInteractions implements LauncherLogPanelInteractions {
    /// Reveals the live launcher log directory, reporting a platform integration failure without interrupting settings.
    ///
    /// @param owner native component owning any error dialog
    /// @param directory active launcher log directory
    @Override
    public void revealLogDirectory(Component owner, Path directory) {
        revealDirectory(owner, directory, "Failed to reveal launcher log directory");
    }

    /// Reveals the parent directory of one completed archive, reporting failures without changing export success.
    ///
    /// @param owner native component owning any error dialog
    /// @param exportFile completed export file
    @Override
    public void revealExport(Component owner, Path exportFile) {
        Path absoluteFile = Objects.requireNonNull(exportFile, "exportFile").toAbsolutePath().normalize();
        Path parent = Objects.requireNonNull(absoluteFile.getParent(), "exportFile must have a parent directory");
        revealDirectory(owner, parent, "Failed to reveal launcher log export");
    }

    /// Shows the localized successful-export confirmation.
    ///
    /// @param owner native component owning the confirmation dialog
    /// @param exportFile completed export file
    @Override
    public void showExportSuccess(Component owner, Path exportFile) {
        JOptionPane.showMessageDialog(
                Objects.requireNonNull(owner, "owner"),
                i18n("settings.launcher.launcher_log.export.success", exportFile),
                i18n("settings.launcher.launcher_log.export"),
                JOptionPane.INFORMATION_MESSAGE);
    }

    /// Logs the full failure and shows the established concise user-facing export error.
    ///
    /// @param owner native component owning the failure dialog
    /// @param failure export failure
    @Override
    public void showExportFailure(Component owner, Throwable failure) {
        Throwable nonNullFailure = Objects.requireNonNull(failure, "failure");
        LOG.warning("Failed to export launcher logs", nonNullFailure);
        JOptionPane.showMessageDialog(
                Objects.requireNonNull(owner, "owner"),
                i18n("settings.launcher.launcher_log.export.failed") + "\n" + StringUtils.getStackTrace(nonNullFailure),
                i18n("message.error"),
                JOptionPane.ERROR_MESSAGE);
    }

    /// Opens one directory in the platform file manager and presents any capability error locally.
    ///
    /// @param owner native component owning an error dialog
    /// @param directory directory to open
    /// @param failureMessage diagnostic logger message
    private static void revealDirectory(Component owner, Path directory, String failureMessage) {
        Path absoluteDirectory = Objects.requireNonNull(directory, "directory").toAbsolutePath().normalize();
        try {
            if (!Desktop.isDesktopSupported()) {
                throw new IOException("Desktop integration is unavailable");
            }
            Desktop desktop = Desktop.getDesktop();
            if (!desktop.isSupported(Desktop.Action.OPEN)) {
                throw new IOException("Directory opening is unavailable");
            }
            desktop.open(absoluteDirectory.toFile());
        } catch (IOException | SecurityException | UnsupportedOperationException exception) {
            LOG.warning(failureMessage + ": " + absoluteDirectory, exception);
            JOptionPane.showMessageDialog(
                    Objects.requireNonNull(owner, "owner"),
                    absoluteDirectory.toString(),
                    i18n("message.error"),
                    JOptionPane.ERROR_MESSAGE);
        }
    }
}
