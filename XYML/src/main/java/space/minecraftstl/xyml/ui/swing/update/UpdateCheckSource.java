// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.update;

import org.jetbrains.annotations.NotNullByDefault;
import space.minecraftstl.xyml.upgrade.RemoteVersion;

/// Loads one remote launcher version without imposing a UI toolkit or scheduling policy.
@FunctionalInterface
@NotNullByDefault
public interface UpdateCheckSource {
    /// Performs the blocking source request.
    ///
    /// [SwingUpdateCheckService] guarantees that this method runs on its configured worker executor and serializes
    /// distinct requests. Implementations may therefore perform blocking integrity and network operations.
    ///
    /// @param request exact update request
    /// @return fetched remote version
    /// @throws Exception when integrity verification, request construction, or network loading fails
    RemoteVersion fetch(UpdateCheckRequest request) throws Exception;
}
