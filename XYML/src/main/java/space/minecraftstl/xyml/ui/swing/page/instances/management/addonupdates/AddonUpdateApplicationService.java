// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.instances.management.addonupdates;

import org.jetbrains.annotations.NotNullByDefault;
import space.minecraftstl.xyml.task.Task;

import java.util.Collection;

/// Builds deferred tasks that apply exact add-on updates selected from a completed scan.
@NotNullByDefault
public interface AddonUpdateApplicationService {
    /// Creates a stopped task for the exact selected update objects in caller order.
    ///
    /// Per-item preparation, download, and restoration failures are captured in the successful
    /// aggregate task result so callers can report partial completion and rescan authoritative state.
    /// Cancellation or an unrecoverable task-framework error can still terminate the aggregate task.
    ///
    /// @param updates exact selected update items
    /// @return stopped task that produces ordered successes and failures after explicit execution
    Task<AddonUpdateApplicationResult> applyUpdates(Collection<AddonUpdateItem> updates);
}
