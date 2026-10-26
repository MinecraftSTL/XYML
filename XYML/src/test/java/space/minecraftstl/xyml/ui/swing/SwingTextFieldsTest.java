// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

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
