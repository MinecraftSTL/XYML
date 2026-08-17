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
package space.minecraftstl.xyml.ui.swing.page.accounts;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import space.minecraftstl.xyml.auth.offline.OfflineAccountFactory;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;

import javax.swing.Action;
import javax.swing.JButton;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.KeyStroke;
import javax.swing.text.DefaultEditorKit;
import java.awt.Cursor;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies headless-safe account-dialog control styling and interaction contracts.
@NotNullByDefault
public final class SwingAccountCreationDialogStyleTest {
    /// Browser and device-code modes form one two-segment highlight control with a browser default.
    @Test
    public void createsExclusiveSegmentedMicrosoftModes() {
        EdtDispatcher.executeAndWait(() -> {
            JToggleButton browser = new JToggleButton("Browser", true);
            JToggleButton device = new JToggleButton("Device code");
            JPanel choices = SwingAccountCreationDialog.createMicrosoftModeChoices(browser, device);

            assertAll(
                    () -> assertFalse(choices.isOpaque()),
                    () -> assertEquals(2, choices.getComponentCount()),
                    () -> assertEquals("accountMicrosoftBrowserMode", browser.getName()),
                    () -> assertEquals("accountMicrosoftDeviceMode", device.getName()),
                    () -> assertEquals("segmented", browser.getClientProperty("JButton.buttonType")),
                    () -> assertEquals("segmented", device.getClientProperty("JButton.buttonType")),
                    () -> assertEquals("first", browser.getClientProperty("JButton.segmentPosition")),
                    () -> assertEquals("last", device.getClientProperty("JButton.segmentPosition")),
                    () -> assertTrue(browser.isSelected()),
                    () -> assertFalse(device.isSelected()));

            device.doClick();
            assertAll(
                    () -> assertFalse(browser.isSelected()),
                    () -> assertTrue(device.isSelected()));
        });
    }

    /// The shrinkable footer keeps both original commands inside a narrow dialog content width.
    @Test
    public void keepsLoginAndCancelInsideDialogFooter() {
        EdtDispatcher.executeAndWait(() -> {
            JButton login = new JButton("Log in");
            JButton cancel = new JButton("Cancel");
            JPanel actions = SwingAccountCreationDialog.createDialogActions(login, cancel);
            actions.setSize(420, actions.getPreferredSize().height);
            actions.doLayout();

            assertAll(
                    () -> assertTrue(login.getX() >= 0),
                    () -> assertTrue(cancel.getX() >= 0),
                    () -> assertTrue(login.getX() + login.getWidth() <= actions.getWidth()),
                    () -> assertTrue(cancel.getX() + cancel.getWidth() <= actions.getWidth()));
        });
    }

    /// The empty UUID field presents and updates the same derived value used by the offline factory.
    @Test
    public void updatesDerivedOfflineUuidPlaceholderWithoutSettingAnOverride() {
        EdtDispatcher.executeAndWait(() -> {
            JTextField username = new JTextField();
            JTextField uuid = new JTextField();
            SwingAccountCreationDialog.bindOfflineUuidPlaceholder(username, uuid);

            assertAll(
                    () -> assertEquals("", uuid.getText()),
                    () -> assertEquals(
                            OfflineAccountFactory.getUUIDFromUserName("").toString(),
                            uuid.getClientProperty("JTextField.placeholderText")));

            username.setText("Steve");
            assertAll(
                    () -> assertEquals("", uuid.getText()),
                    () -> assertEquals(
                            OfflineAccountFactory.getUUIDFromUserName("Steve").toString(),
                            uuid.getClientProperty("JTextField.placeholderText")));

            String explicitUuid = "12345678-1234-1234-1234-123456789abc";
            uuid.setText(explicitUuid);
            username.setText("Alex");
            assertAll(
                    () -> assertEquals(explicitUuid, uuid.getText()),
                    () -> assertEquals(
                            OfflineAccountFactory.getUUIDFromUserName("Alex").toString(),
                            uuid.getClientProperty("JTextField.placeholderText")));
        });
    }

    /// Invalid-name guidance permits fragment selection and copying without a text cursor or popup menu.
    @Test
    public void createsPartiallySelectableInvalidUsernameGuidance() {
        EdtDispatcher.executeAndWait(() -> {
            String guidance = "Warning body\n\nEnter required phrase below";
            JTextArea promptText = SwingAccountCreationDialog.createSelectablePromptText(guidance);
            int selectionStart = guidance.indexOf("required");
            int selectionEnd = selectionStart + "required phrase".length();
            promptText.select(selectionStart, selectionEnd);

            KeyStroke copyShortcut = KeyStroke.getKeyStroke(
                    KeyEvent.VK_C,
                    InputEvent.CTRL_DOWN_MASK);
            KeyStroke selectAllShortcut = KeyStroke.getKeyStroke(
                    KeyEvent.VK_A,
                    InputEvent.CTRL_DOWN_MASK);
            Object copyBinding = promptText.getInputMap().get(copyShortcut);
            Object selectAllBinding = promptText.getInputMap().get(selectAllShortcut);
            Action copyAction = promptText.getActionMap().get(DefaultEditorKit.copyAction);

            assertAll(
                    () -> assertEquals("required phrase", promptText.getSelectedText()),
                    () -> assertFalse(promptText.isEditable()),
                    () -> assertTrue(promptText.isFocusable()),
                    () -> assertEquals(Cursor.DEFAULT_CURSOR, promptText.getCursor().getType()),
                    () -> assertNull(promptText.getComponentPopupMenu()),
                    () -> assertFalse(promptText.getInheritsPopupMenu()),
                    () -> assertEquals(DefaultEditorKit.copyAction, copyBinding),
                    () -> assertEquals(DefaultEditorKit.selectAllAction, selectAllBinding),
                    () -> assertNotNull(copyAction));
        });
    }

    /// The invalid-name confirm option stays disabled until the acknowledgement satisfies normalization.
    @Test
    public void enablesInvalidUsernameConfirmationOnlyForMatchingText() {
        EdtDispatcher.executeAndWait(() -> {
            JTextField confirmation = new JTextField();
            JButton confirmButton = new JButton("Confirm");
            JButton cancelButton = new JButton("Cancel");
            JOptionPane pane = new JOptionPane();
            Object @Unmodifiable [] options = {confirmButton, cancelButton};
            SwingAccountCreationDialog.bindConfirmationButton(
                    confirmation,
                    confirmButton,
                    "I accept");
            SwingAccountCreationDialog.wireButtonOptions(pane, options);

            assertFalse(confirmButton.isEnabled());
            confirmButton.doClick();
            assertSame(JOptionPane.UNINITIALIZED_VALUE, pane.getValue());

            confirmation.setText("wrong");
            assertFalse(confirmButton.isEnabled());
            confirmation.setText("I   accept");
            assertTrue(confirmButton.isEnabled());
            confirmation.setText("wrong again");
            assertFalse(confirmButton.isEnabled());

            confirmation.setText("I accept");
            confirmButton.doClick();
            assertSame(confirmButton, pane.getValue());
        });
    }
}
