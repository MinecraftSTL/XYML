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
}
