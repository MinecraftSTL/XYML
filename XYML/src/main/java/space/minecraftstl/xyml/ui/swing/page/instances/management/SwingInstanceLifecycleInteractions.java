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
package space.minecraftstl.xyml.ui.swing.page.instances.management;

import net.miginfocom.swing.MigLayout;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;
import space.minecraftstl.xyml.ui.swing.SwingTextFields;

import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTextField;
import java.awt.Component;
import java.util.Objects;

/// Supplies production native dialogs for instance rename, duplication, deletion, and failures.
///
/// The confirmation dialogs run on the EDT and never perform repository work. A duplicate request
/// deliberately places the copy-worlds toggle in the same native confirmation surface as its target ID.
@NotNullByDefault
final class SwingInstanceLifecycleInteractions implements InstanceLifecycleInteractions {
    /// Localized text shared with the lifecycle panel.
    private final InstanceLifecycleStrings strings;

    /// Creates native interactions using one immutable visible text bundle.
    ///
    /// @param strings visible current-locale strings
    SwingInstanceLifecycleInteractions(InstanceLifecycleStrings strings) {
        this.strings = Objects.requireNonNull(strings, "strings");
    }

    /// Shows a native single-field prompt for a rename destination.
    ///
    /// @param owner native dialog owner
    /// @param sourceId current instance identifier
    /// @return raw entered destination, or `null` after cancellation
    @Override
    public @Nullable String requestRename(Component owner, String sourceId) {
        EdtDispatcher.requireEventDispatchThread();
        String source = Objects.requireNonNull(sourceId, "sourceId");
        @Nullable Object value = JOptionPane.showInputDialog(
                Objects.requireNonNull(owner, "owner"),
                strings.renamePrompt(),
                strings.renameAction(),
                JOptionPane.QUESTION_MESSAGE,
                null,
                null,
                source);
        return value instanceof String entered ? entered : null;
    }

    /// Shows a native duplicate confirmation with destination and copy-worlds fields.
    ///
    /// @param owner native dialog owner
    /// @param sourceId current instance identifier
    /// @return confirmed duplicate request, or `null` after cancellation
    @Override
    public @Nullable InstanceLifecycleDuplicateRequest requestDuplicate(Component owner, String sourceId) {
        EdtDispatcher.requireEventDispatchThread();
        String source = Objects.requireNonNull(sourceId, "sourceId");
        JTextField destinationField = new JTextField(source + "-copy", 24);
        destinationField.setName("instanceLifecycleDuplicateDestination");
        SwingTextFields.showClearButton(destinationField);
        JCheckBox copySaves = new JCheckBox(strings.duplicateSavesLabel());
        copySaves.setName("instanceLifecycleDuplicateSaves");

        JPanel content = new JPanel(new MigLayout(
                "insets 0, fillx, wrap 1",
                "[grow,fill]",
                "[][]10[]"));
        content.add(new JLabel(strings.duplicatePrompt()), "growx");
        content.add(destinationField, "growx");
        content.add(copySaves, "growx");

        int result = JOptionPane.showConfirmDialog(
                Objects.requireNonNull(owner, "owner"),
                new Object[]{strings.duplicateConfirmation(), content},
                strings.duplicateAction(),
                JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.QUESTION_MESSAGE);
        if (result != JOptionPane.OK_OPTION) {
            return null;
        }
        return new InstanceLifecycleDuplicateRequest(destinationField.getText(), copySaves.isSelected());
    }

    /// Shows a native warning confirmation before an irreversible instance deletion.
    ///
    /// @param owner native dialog owner
    /// @param sourceId current instance identifier
    /// @return whether deletion was approved
    @Override
    public boolean confirmDelete(Component owner, String sourceId) {
        EdtDispatcher.requireEventDispatchThread();
        String source = Objects.requireNonNull(sourceId, "sourceId");
        return JOptionPane.showConfirmDialog(
                Objects.requireNonNull(owner, "owner"),
                strings.deleteConfirmation().formatted(source),
                strings.deleteAction(),
                JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.WARNING_MESSAGE) == JOptionPane.OK_OPTION;
    }

    /// Shows a native error message for one terminal mutation failure.
    ///
    /// @param owner native dialog owner
    /// @param title visible failure title
    /// @param detail non-blank failure detail
    @Override
    public void showFailure(Component owner, String title, String detail) {
        EdtDispatcher.requireEventDispatchThread();
        JOptionPane.showMessageDialog(
                Objects.requireNonNull(owner, "owner"),
                Objects.requireNonNull(detail, "detail"),
                Objects.requireNonNull(title, "title"),
                JOptionPane.ERROR_MESSAGE);
    }
}
