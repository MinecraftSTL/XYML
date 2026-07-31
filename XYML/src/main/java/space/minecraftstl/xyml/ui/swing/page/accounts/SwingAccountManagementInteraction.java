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
package space.minecraftstl.xyml.ui.swing.page.accounts;

import org.jetbrains.annotations.NotNullByDefault;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;

import javax.swing.JOptionPane;
import java.awt.Component;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.util.Objects;

import static space.minecraftstl.xyml.util.i18n.I18n.i18n;

/// Production Swing confirmation, AWT clipboard, and error-dialog implementation for account actions.
@NotNullByDefault
final class SwingAccountManagementInteraction implements AccountManagementInteraction {
    /// Shows a native destructive-action confirmation on the EDT.
    @Override
    public boolean confirmRemoval(Component owner, String title, String message) {
        EdtDispatcher.requireEventDispatchThread();
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(message, "message");
        return JOptionPane.showConfirmDialog(
                owner,
                message,
                title,
                JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.WARNING_MESSAGE) == JOptionPane.OK_OPTION;
    }

    /// Shows a native backup-and-overwrite confirmation with an explicit destructive action.
    @Override
    public boolean confirmReadOnlyOverwrite(Component owner) {
        EdtDispatcher.requireEventDispatchThread();
        Objects.requireNonNull(owner, "owner");
        Object[] options = {
                i18n("settings.file.force_write"),
                i18n("button.cancel")
        };
        return JOptionPane.showOptionDialog(
                owner,
                i18n("account.storage.read_only")
                        + "\n\n"
                        + i18n("settings.file.force_write.confirm"),
                i18n("message.warning"),
                JOptionPane.DEFAULT_OPTION,
                JOptionPane.WARNING_MESSAGE,
                null,
                options,
                options[1]) == 0;
    }

    /// Replaces the system clipboard contents with one exact string.
    @Override
    public void copyText(String text) {
        EdtDispatcher.requireEventDispatchThread();
        String value = Objects.requireNonNull(text, "text");
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(value), null);
    }

    /// Shows one native terminal error dialog on the EDT.
    @Override
    public void showFailure(Component owner, String title, String message) {
        EdtDispatcher.requireEventDispatchThread();
        JOptionPane.showMessageDialog(
                Objects.requireNonNull(owner, "owner"),
                Objects.requireNonNull(message, "message"),
                Objects.requireNonNull(title, "title"),
                JOptionPane.ERROR_MESSAGE);
    }
}
