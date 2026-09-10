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
package space.minecraftstl.xyml.ui.swing;

import com.formdev.flatlaf.FlatClientProperties;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import javax.swing.JComboBox;
import javax.swing.JTextField;
import java.util.Objects;

/// Configures shared behavior for editable Swing text fields.
@NotNullByDefault
public final class SwingTextFields {
    /// Prevents construction of the stateless behavior holder.
    private SwingTextFields() {
    }

    /// Adds FlatLaf's trailing clear action to an editable text field.
    ///
    /// The action is visible only while the field is enabled, editable, and non-empty. FlatLaf clears the document
    /// directly, so existing document listeners receive the same update as they do for keyboard editing.
    ///
    /// @param field disposable search, filter, or one-shot input field
    public static void showClearButton(JTextField field) {
        JTextField target = Objects.requireNonNull(field, "field");
        target.putClientProperty(FlatClientProperties.TEXT_FIELD_SHOW_CLEAR_BUTTON, true);
    }

    /// Adds the same clear action to an editable combo-box and its text editor.
    ///
    /// The combo-box property keeps the control discoverable by shared Swing layout tests, while
    /// the editor property is the one consumed by FlatLaf when the user types a custom value.
    ///
    /// @param combo editable search or version combo-box
    public static void showClearButton(JComboBox<?> combo) {
        JComboBox<?> target = Objects.requireNonNull(combo, "combo");
        target.putClientProperty(FlatClientProperties.TEXT_FIELD_SHOW_CLEAR_BUTTON, true);
        if (target.isEditable() && target.getEditor().getEditorComponent() instanceof JTextField editor) {
            showClearButton(editor);
        }
    }

    /// Returns the text editor owned by a combo-box.
    ///
    /// @param combo combo-box whose editor is required
    /// @return text editor component
    /// @throws IllegalStateException when the installed editor is not a text field
    public static JTextField textEditor(JComboBox<?> combo) {
        JComboBox<?> target = Objects.requireNonNull(combo, "combo");
        if (target.getEditor().getEditorComponent() instanceof JTextField editor) {
            return editor;
        }
        throw new IllegalStateException("Combo-box must use a text editor");
    }

    /// Returns normalized text from an editable combo-box editor or its selected item.
    ///
    /// @param combo combo-box supplying the current text
    /// @return trimmed text, or an empty string when no item is selected
    public static String comboText(JComboBox<?> combo) {
        JComboBox<?> target = Objects.requireNonNull(combo, "combo");
        @Nullable Object value = target.isEditable()
                ? target.getEditor().getItem()
                : target.getSelectedItem();
        return value == null ? "" : value.toString().trim();
    }
}
