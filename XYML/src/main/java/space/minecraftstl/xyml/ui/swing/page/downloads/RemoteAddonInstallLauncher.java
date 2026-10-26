// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.downloads;

import org.jetbrains.annotations.NotNullByDefault;
import space.minecraftstl.xyml.task.Task;

import java.io.IOException;

/// Creates one stopped task graph that safely downloads a selected remote artifact to its resolved target.
@NotNullByDefault
public interface RemoteAddonInstallLauncher {
    /// Builds an acquisition task without starting network or filesystem mutation immediately.
    ///
    /// @param request selected project version and resolved destination
    /// @return unstarted task graph
    /// @throws IOException when local task setup cannot reserve a temporary destination
    Task<?> createInstallTask(RemoteAddonInstallRequest request) throws IOException;
}
