// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.dialog;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;

import javax.swing.JOptionPane;
import java.awt.Component;
import java.util.List;
import java.util.Objects;

import static space.minecraftstl.xyml.util.i18n.I18n.i18n;

/// Shows the three-way recovery choice after a target cannot be moved to the recycle bin.
@NotNullByDefault
public final class SwingDeletionFallbackDialog {
    /// Index returned by the recycle-bin retry option.
    private static final int RETRY_OPTION = 0;

    /// Index returned by the permanent-deletion option.
    private static final int PERMANENT_OPTION = 1;

    /// Prevents utility-class construction.
    private SwingDeletionFallbackDialog() {
    }

    /// Shows one fallback choice using the caller-owned title and message.
    ///
    /// @param owner dialog owner
    /// @param title existing deletion title
    /// @param message existing deletion confirmation
    /// @return selected action, never null
    public static DeletionFallbackDecision choose(
            Component owner,
            String title,
            String message) {
        return choose(owner, title, message, new JOptionPaneDialogActions());
    }

    /// Shows one fallback choice through an injectable option-dialog boundary.
    ///
    /// @param owner dialog owner
    /// @param title existing deletion title
    /// @param message existing deletion confirmation
    /// @param actions option-dialog boundary
    /// @return selected action, never null
    static DeletionFallbackDecision choose(
            Component owner,
            String title,
            String message,
            DialogActions actions) {
        EdtDispatcher.requireEventDispatchThread();
        Component checkedOwner = Objects.requireNonNull(owner, "owner");
        String checkedTitle = Objects.requireNonNull(title, "title");
        String checkedMessage = Objects.requireNonNull(message, "message");
        DialogActions checkedActions = Objects.requireNonNull(actions, "actions");
        int choice = checkedActions.showOptionDialog(
                checkedOwner,
                checkedMessage,
                checkedTitle,
                List.of(i18n("button.retry"), i18n("button.delete_permanently"), i18n("button.cancel")),
                i18n("button.cancel"));
        return switch (choice) {
            case RETRY_OPTION -> DeletionFallbackDecision.RETRY_RECYCLE_BIN;
            case PERMANENT_OPTION -> DeletionFallbackDecision.DELETE_PERMANENTLY;
            default -> DeletionFallbackDecision.CANCEL;
        };
    }

    /// Minimal option-dialog boundary used by focused tests.
    @NotNullByDefault
    @FunctionalInterface
    interface DialogActions {
        /// Shows Retry/Permanent/Cancel.
        ///
        /// @param owner dialog owner
        /// @param message displayed deletion confirmation
        /// @param title dialog title
        /// @param options immutable localized options in displayed order
        /// @param initialValue localized safe default
        /// @return selected option index, or a negative value after dialog closure
        int showOptionDialog(
                Component owner,
                Object message,
                String title,
                @Unmodifiable List<String> options,
                String initialValue);
    }

    /// Production implementation backed by `JOptionPane`.
    @NotNullByDefault
    private static final class JOptionPaneDialogActions implements DialogActions {
        /// Shows Retry/Permanent/Cancel with Cancel as the safe default.
        @Override
        public int showOptionDialog(
                Component owner,
                Object message,
                String title,
                @Unmodifiable List<String> options,
                String initialValue) {
            return JOptionPane.showOptionDialog(
                    owner,
                    message,
                    title,
                    JOptionPane.DEFAULT_OPTION,
                    JOptionPane.WARNING_MESSAGE,
                    null,
                    options.toArray(),
                    initialValue);
        }
    }
}
