// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

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

    /// Provides an asynchronous validated input boundary while preserving deterministic injected dialogs.
    default java.util.concurrent.CompletionStage<@Nullable String> showValidatedInputDialog(
            Component owner, String message, String title,
            java.util.function.Function<String, java.util.concurrent.CompletionStage<@Nullable String>> validator) {
        @Nullable String name = showInputDialog(owner, message, title, javax.swing.JOptionPane.QUESTION_MESSAGE);
        if (name == null) return java.util.concurrent.CompletableFuture.completedFuture(null);
        return validator.apply(name).thenApply(error -> {
            if (error != null) throw new IllegalArgumentException(error);
            return name;
        });
    }

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
