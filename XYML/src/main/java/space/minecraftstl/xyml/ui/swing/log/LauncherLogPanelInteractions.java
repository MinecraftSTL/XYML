// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

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
