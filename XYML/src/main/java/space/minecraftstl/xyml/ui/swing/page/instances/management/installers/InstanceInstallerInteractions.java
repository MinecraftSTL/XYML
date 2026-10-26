// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.instances.management.installers;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.awt.Component;
import java.nio.file.Path;

/// Separates native local-file and destructive-action dialogs from installer management behavior.
///
/// All operations are called on the Swing event-dispatch thread. A null installer result represents
/// user cancellation and must not cause the panel to construct or start a Core task.
@NotNullByDefault
public interface InstanceInstallerInteractions {
    /// Opens a native chooser restricted to supported local installer-file extensions.
    ///
    /// @param owner native dialog owner
    /// @return chosen local `.jar` or `.exe` file, or null when the chooser is cancelled
    @Nullable Path chooseOfflineInstaller(Component owner);

    /// Requests explicit confirmation before removing one structurally clear library.
    ///
    /// The panel validates its current recognized or third-party row is structurally clear before invoking this
    /// callback, so no external or uncertain library identifier reaches this destructive boundary.
    ///
    /// @param owner native dialog owner
    /// @param libraryId exact clear Core library identifier proposed for removal
    /// @return whether the mutation is approved
    boolean confirmRemoval(Component owner, String libraryId);

    /// Shows a concise terminal operation failure.
    ///
    /// @param owner native dialog owner
    /// @param title visible failure title
    /// @param detail non-blank failure detail
    void showFailure(Component owner, String title, String detail);
}
