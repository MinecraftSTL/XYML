// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.resourcepacks;

import org.jetbrains.annotations.NotNullByDefault;

import javax.swing.JFileChooser;
import java.awt.Component;

/// Package-private Swing dialog boundary for deterministic resource-pack interaction tests.
@NotNullByDefault
interface ResourcePackDialogActions {
    /// Displays a configured open dialog.
    ///
    /// @param chooser configured chooser
    /// @param owner dialog owner
    /// @return one `JFileChooser` result constant
    int showOpenDialog(JFileChooser chooser, Component owner);

    /// Displays a confirmation dialog.
    ///
    /// @param owner dialog owner
    /// @param message confirmation content
    /// @param title dialog title
    /// @param optionType one `JOptionPane` option-type constant
    /// @param messageType one `JOptionPane` message-type constant
    /// @return one `JOptionPane` result constant
    int showConfirmDialog(
            Component owner,
            Object message,
            String title,
            int optionType,
            int messageType);

    /// Displays a message dialog.
    ///
    /// @param owner dialog owner
    /// @param message displayed content
    /// @param title dialog title
    /// @param messageType one `JOptionPane` message-type constant
    void showMessageDialog(Component owner, Object message, String title, int messageType);
}
