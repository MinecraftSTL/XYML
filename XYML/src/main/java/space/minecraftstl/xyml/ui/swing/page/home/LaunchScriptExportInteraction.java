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

import java.awt.Component;
import java.nio.file.Path;
import java.util.Optional;

/// Native Swing boundary for choosing and reporting one local launch-script export.
///
/// The interaction never opens a web browser or performs network work. Tests provide a deterministic implementation
/// so the home page can be exercised without constructing a native chooser or dialog.
@NotNullByDefault
public interface LaunchScriptExportInteraction {
    /// Opens a local save-file selection for the given instance.
    ///
    /// @param owner native dialog owner
    /// @param instanceLabel selected instance label used as a local save-dialog suggestion
    /// @return selected destination, or an empty value when the user cancels
    Optional<Path> chooseDestination(Component owner, String instanceLabel);

    /// Reports that a script was successfully created.
    ///
    /// @param owner native dialog owner
    /// @param scriptFile exact generated local script
    void exportSucceeded(Component owner, Path scriptFile);

    /// Reports that script generation did not complete.
    ///
    /// @param owner native dialog owner
    /// @param failure terminal export failure
    void exportFailed(Component owner, Throwable failure);
}
