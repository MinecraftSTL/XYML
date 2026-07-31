/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2026 huangyuhui <huanghongxun2008@126.com> and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package space.minecraftstl.xyml.ui.swing.page.schematics;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import javax.swing.JFileChooser;
import java.awt.Component;

/// Package-private Swing dialog boundary used to test configured dialogs without displaying them.
@NotNullByDefault
interface DialogActions {
    /// Displays a configured open dialog.
    ///
    /// @param chooser configured chooser
    /// @param owner dialog owner
    /// @return one `JFileChooser` result constant
    int showOpenDialog(JFileChooser chooser, Component owner);

    /// Displays a text-input dialog.
    ///
    /// @param owner dialog owner
    /// @param message prompt content
    /// @param title dialog title
    /// @param messageType one `JOptionPane` message-type constant
    /// @return entered value, or null after cancellation
    @Nullable String showInputDialog(Component owner, Object message, String title, int messageType);

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
