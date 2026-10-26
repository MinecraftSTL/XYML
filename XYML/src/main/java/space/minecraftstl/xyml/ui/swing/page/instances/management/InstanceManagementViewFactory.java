// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.instances.management;

import org.jetbrains.annotations.NotNullByDefault;
import space.minecraftstl.xyml.game.GameInstanceID;

/// Creates one internally hosted management view on the Swing EDT.
@FunctionalInterface
@NotNullByDefault
public interface InstanceManagementViewFactory {
    /// Creates a view for an exact stable instance identifier.
    ///
    /// The returned view transfers to the coordinator. The return command may be called from any
    /// thread and requests disposal of the dynamic view before showing the instances list again.
    ///
    /// @param instanceId stable repository instance identifier
    /// @param returnCommand command returning to the instances list
    /// @return newly owned management view
    InstanceManagementView create(GameInstanceID instanceId, Runnable returnCommand);
}
