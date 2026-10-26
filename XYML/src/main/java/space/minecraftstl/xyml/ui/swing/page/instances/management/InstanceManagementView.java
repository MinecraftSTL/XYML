// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.instances.management;

import org.jetbrains.annotations.NotNullByDefault;
import space.minecraftstl.xyml.game.GameInstanceID;

import javax.swing.JComponent;

/// One owned, dynamically hosted management view for a stable game-instance identifier.
///
/// Implementations are created, queried, hosted, and closed exclusively on the Swing EDT.
@NotNullByDefault
public interface InstanceManagementView extends AutoCloseable {
    /// Returns the stable repository identifier captured by this view.
    ///
    /// @return stable instance identifier
    GameInstanceID instanceId();

    /// Returns the root component hosted inside the instances page.
    ///
    /// @return root management component
    JComponent component();

    /// Releases every resource owned by this dynamic view on the EDT.
    @Override
    void close();
}
