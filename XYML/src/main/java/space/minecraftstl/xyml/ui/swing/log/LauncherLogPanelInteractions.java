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

import java.awt.Component;
import java.nio.file.Path;

/// Isolates native desktop and dialog behavior from the launcher-log settings controls.
@NotNullByDefault
public interface LauncherLogPanelInteractions {
    /// Reveals the active launcher log directory in the platform file manager.
    ///
    /// @param owner native component owning any error dialog
    /// @param directory active launcher log directory
    void revealLogDirectory(Component owner, Path directory);

    /// Reveals a completed launcher-log export in the platform file manager.
    ///
    /// @param owner native component owning any error dialog
    /// @param exportFile completed export file
    void revealExport(Component owner, Path exportFile);

    /// Shows a completed export location.
    ///
    /// @param owner native component owning the confirmation dialog
    /// @param exportFile completed export file
    void showExportSuccess(Component owner, Path exportFile);

    /// Shows an export failure.
    ///
    /// @param owner native component owning the failure dialog
    /// @param failure export failure
    void showExportFailure(Component owner, Throwable failure);
}
