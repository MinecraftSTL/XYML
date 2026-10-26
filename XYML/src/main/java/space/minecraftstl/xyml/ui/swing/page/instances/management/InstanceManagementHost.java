// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.instances.management;

import org.jetbrains.annotations.NotNullByDefault;

import javax.swing.JComponent;

/// Mutates the internal instances-page card host exclusively on the Swing EDT.
@NotNullByDefault
public interface InstanceManagementHost {
    /// Replaces the list card with one coordinator-owned management component.
    ///
    /// @param component dynamic management root
    void showManagementView(JComponent component);

    /// Removes any dynamic component and restores the instances list card.
    void showInstanceList();
}
