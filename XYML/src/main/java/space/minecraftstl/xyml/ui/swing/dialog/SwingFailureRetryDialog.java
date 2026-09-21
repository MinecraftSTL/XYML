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
package space.minecraftstl.xyml.ui.swing.dialog;

import org.jetbrains.annotations.NotNullByDefault;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;

import javax.swing.JOptionPane;
import java.awt.Component;
import java.util.Objects;

import static space.minecraftstl.xyml.util.i18n.I18n.i18n;

/// Shows one localized Retry/Cancel failure dialog without automatic retries.
@NotNullByDefault
public final class SwingFailureRetryDialog {
    /// Index returned by the Retry option.
    private static final int RETRY_OPTION = 0;

    /// Prevents utility-class construction.
    private SwingFailureRetryDialog() {
    }

    /// Shows one retryable failure and runs the captured action only after an explicit retry choice.
    ///
    /// @param owner dialog owner
    /// @param title concise title
    /// @param detail actionable detail
    /// @param retryAction captured operation to replay
    public static void show(
            Component owner,
            String title,
            String detail,
            Runnable retryAction) {
        show(owner, title, detail, retryAction, new JOptionPaneDialogActions());
    }

    /// Shows one retryable failure through an injectable option-dialog boundary.
    ///
    /// @param owner dialog owner
    /// @param title concise title
    /// @param detail actionable detail
    /// @param retryAction captured operation to replay
    /// @param actions option-dialog boundary
    static void show(
            Component owner,
            String title,
            String detail,
            Runnable retryAction,
            DialogActions actions) {
        EdtDispatcher.requireEventDispatchThread();
        Component checkedOwner = Objects.requireNonNull(owner, "owner");
        String checkedTitle = Objects.requireNonNull(title, "title");
        String checkedDetail = Objects.requireNonNull(detail, "detail");
        Runnable checkedRetry = Objects.requireNonNull(retryAction, "retryAction");
        DialogActions checkedActions = Objects.requireNonNull(actions, "actions");
        int choice = checkedActions.showOptionDialog(
                checkedOwner,
                checkedDetail,
                checkedTitle,
                i18n("button.retry"),
                i18n("button.cancel"));
        if (choice == RETRY_OPTION) {
            checkedRetry.run();
        }
    }

    /// Minimal option-dialog boundary used by focused tests.
    @FunctionalInterface
    @NotNullByDefault
    interface DialogActions {
        /// Shows a Retry/Cancel choice.
        ///
        /// @param owner dialog owner
        /// @param message displayed detail
        /// @param title dialog title
        /// @param retryLabel localized retry label
        /// @param cancelLabel localized cancel label
        /// @return selected option index, or a negative value after dialog closure
        int showOptionDialog(
                Component owner,
                Object message,
                String title,
                String retryLabel,
                String cancelLabel);
    }

    /// Production implementation backed by `JOptionPane`.
    @NotNullByDefault
    private static final class JOptionPaneDialogActions implements DialogActions {
        /// Shows Retry/Cancel with Cancel as the safe default.
        @Override
        public int showOptionDialog(
                Component owner,
                Object message,
                String title,
                String retryLabel,
                String cancelLabel) {
            return JOptionPane.showOptionDialog(
                    owner,
                    message,
                    title,
                    JOptionPane.DEFAULT_OPTION,
                    JOptionPane.ERROR_MESSAGE,
                    null,
                    new Object[]{retryLabel, cancelLabel},
                    cancelLabel);
        }
    }
}
