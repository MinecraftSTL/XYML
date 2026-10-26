// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.log;

import org.jetbrains.annotations.NotNullByDefault;
import space.minecraftstl.xyml.util.StringUtils;

import javax.swing.JOptionPane;
import java.awt.Component;
import java.awt.GraphicsEnvironment;
import java.nio.file.Path;

import static space.minecraftstl.xyml.util.i18n.I18n.i18n;

/// Presents game-log export results with native Swing dialogs.
@NotNullByDefault
final class SwingGameLogWindowNotifier implements GameLogWindowNotifier {
    /// Presents a localized success message unless the runtime is headless.
    ///
    /// @param owner component owning the notification
    /// @param file exported file
    @Override
    public void exportSucceeded(Component owner, Path file) {
        if (GraphicsEnvironment.isHeadless()) {
            return;
        }
        JOptionPane.showMessageDialog(
                owner,
                i18n("settings.launcher.launcher_log.export.success", file),
                i18n("message.success"),
                JOptionPane.INFORMATION_MESSAGE);
    }

    /// Presents the operation name and stack trace unless the runtime is headless.
    ///
    /// @param owner component owning the notification
    /// @param operation localized operation name
    /// @param failure export failure
    @Override
    public void exportFailed(Component owner, String operation, Throwable failure) {
        if (GraphicsEnvironment.isHeadless()) {
            return;
        }
        JOptionPane.showMessageDialog(
                owner,
                operation + "\n" + StringUtils.getStackTrace(failure),
                i18n("message.error"),
                JOptionPane.ERROR_MESSAGE);
    }
}
