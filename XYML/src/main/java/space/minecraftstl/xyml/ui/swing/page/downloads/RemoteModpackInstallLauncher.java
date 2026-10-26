// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.downloads;

import org.jetbrains.annotations.NotNullByDefault;
import space.minecraftstl.xyml.task.Task;

import java.io.IOException;

/// Creates the task chain that downloads and installs a selected remote modpack version.
///
/// The Swing panel owns task presentation and executor lifecycle; implementations only assemble the
/// real launcher task graph. This separation makes it possible to verify selected-version handoff
/// without downloading an archive in a headless component test.
@NotNullByDefault
public interface RemoteModpackInstallLauncher {
    /// Creates one unstarted installation task for a user-confirmed request.
    ///
    /// @param request selected remote project, version, and target instance identifier
    /// @return non-null task graph ready for presentation and execution
    /// @throws IOException when temporary archive preparation cannot start
    Task<?> createInstallTask(RemoteModpackInstallRequest request) throws IOException;
}
