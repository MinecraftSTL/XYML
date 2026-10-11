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
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;

import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dialog;
import java.awt.FlowLayout;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;

import static space.minecraftstl.xyml.util.i18n.I18n.i18n;

/// Presents a non-blocking directory-name editor with asynchronous inline validation.
@NotNullByDefault
final class SchematicNameDialog {
    /// Prevents construction of the stateless dialog factory.
    private SchematicNameDialog() {
    }

    /// Returns entered text only after the current revision validates, or null after cancellation/closure.
    static CompletionStage<@Nullable String> show(Component owner, String prompt, String title,
            Function<String, CompletionStage<@Nullable String>> validator) {
        EdtDispatcher.requireEventDispatchThread();
        CompletableFuture<@Nullable String> result = new CompletableFuture<>();
        JDialog dialog = new JDialog(SwingUtilities.getWindowAncestor(owner), title, Dialog.ModalityType.MODELESS);
        JTextField input = new JTextField(30);
        input.setName("schematicsDirectoryName");
        JLabel error = new JLabel(" ");
        error.setName("schematicsDirectoryNameError");
        JButton accept = new JButton(i18n("button.ok"));
        accept.setName("schematicsDirectoryNameConfirm");
        JButton cancel = new JButton(i18n("button.cancel"));
        cancel.setName("schematicsDirectoryNameCancel");
        SchematicNameValidationController validation = new SchematicNameValidationController(validator, (valid, detail) -> {
            accept.setEnabled(valid);
            error.setText(detail == null ? " " : detail);
        });
        result.whenComplete((name, failure) -> EdtDispatcher.execute(() -> {
            validation.close();
            dialog.dispose();
        }));
        JPanel content = new JPanel(new BorderLayout(8, 8));
        content.setBorder(javax.swing.BorderFactory.createEmptyBorder(12, 12, 12, 12));
        content.add(new JLabel(prompt), BorderLayout.NORTH);
        content.add(input, BorderLayout.CENTER);
        content.add(error, BorderLayout.SOUTH);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttons.add(accept);
        buttons.add(cancel);
        dialog.add(content, BorderLayout.CENTER);
        dialog.add(buttons, BorderLayout.SOUTH);
        input.getDocument().addDocumentListener(new InputListener(input, validation));
        accept.addActionListener(event -> {
            if (accept.isEnabled()) result.complete(input.getText());
            dialog.dispose();
        });
        cancel.addActionListener(event -> dialog.dispose());
        dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
        dialog.addWindowListener(new CloseListener(result, validation));
        validation.request(input.getText());
        dialog.pack();
        dialog.setLocationRelativeTo(owner);
        dialog.setVisible(true);
        input.requestFocusInWindow();
        return result;
    }

    /// Forwards each text edit to the bounded coalescing controller.
    @NotNullByDefault
    private static final class InputListener implements DocumentListener {
        /// Dialog-owned text input.
        private final JTextField input;
        /// Dialog-owned validation lifetime.
        private final SchematicNameValidationController validation;

        /// Binds input and controller without capturing filesystem objects.
        private InputListener(JTextField input, SchematicNameValidationController validation) {
            this.input = input;
            this.validation = validation;
        }

        /// Invalidates validation when text is inserted.
        @Override
        public void insertUpdate(DocumentEvent event) {
            validation.request(input.getText());
        }

        /// Invalidates validation when text is removed.
        @Override
        public void removeUpdate(DocumentEvent event) {
            validation.request(input.getText());
        }

        /// Handles attributed-document updates with the same revision semantics.
        @Override
        public void changedUpdate(DocumentEvent event) {
            validation.request(input.getText());
        }
    }

    /// Completes cancellation once and releases the validation listener lifetime.
    @NotNullByDefault
    private static final class CloseListener extends WindowAdapter {
        /// Single terminal input result.
        private final CompletableFuture<@Nullable String> result;
        /// Controller whose late completions must be ignored.
        private final SchematicNameValidationController validation;

        /// Binds the owned completion and validation state.
        private CloseListener(CompletableFuture<@Nullable String> result, SchematicNameValidationController validation) {
            this.result = result;
            this.validation = validation;
        }

        /// Owner disposal and user cancellation both yield null unless submission already completed.
        @Override
        public void windowClosed(WindowEvent event) {
            validation.close();
            result.complete(null);
        }
    }
}
