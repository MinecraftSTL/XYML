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
import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLightLaf;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import javax.swing.JButton;
import javax.swing.JTextField;
import java.awt.Component;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies the shared disposable-field clear action against FlatLaf's installed control.
@NotNullByDefault
public final class SwingTextFieldsTest {
    /// Under both production palettes, the trailing action clears the document and notifies ordinary field listeners.
    @Test
    public void clearsTextThroughFlatLafButton() {
        EdtDispatcher.executeAndWait(() -> {
            for (boolean dark : List.of(false, true)) {
                assertTrue(dark ? FlatDarkLaf.setup() : FlatLightLaf.setup());
                JTextField field = new JTextField();
                AtomicInteger documentChanges = new AtomicInteger();
                field.getDocument().addUndoableEditListener(ignored -> documentChanges.incrementAndGet());

                SwingTextFields.showClearButton(field);
                field.setText("temporary value");
                JButton clearButton = findClearButton(field);

                assertAll(
                        () -> assertEquals(
                                Boolean.TRUE,
                                field.getClientProperty(FlatClientProperties.TEXT_FIELD_SHOW_CLEAR_BUTTON)),
                        () -> assertTrue(clearButton.isVisible()),
                        () -> assertTrue(documentChanges.get() > 0));

                int changesBeforeClear = documentChanges.get();
                clearButton.doClick();

                assertAll(
                        () -> assertEquals("", field.getText()),
                        () -> assertFalse(clearButton.isVisible()),
                        () -> assertTrue(documentChanges.get() > changesBeforeClear));
            }
            FlatLightLaf.setup();
        });
    }

    /// Finds the FlatLaf control installed into one clearable text field.
    ///
    /// @param field configured text field
    /// @return installed clear button
    private static JButton findClearButton(JTextField field) {
        for (Component child : field.getComponents()) {
            if (child instanceof JButton button && "TextField.clearButton".equals(button.getName())) {
                return button;
            }
        }
        throw new AssertionError("FlatLaf did not install a text-field clear button");
    }
}
