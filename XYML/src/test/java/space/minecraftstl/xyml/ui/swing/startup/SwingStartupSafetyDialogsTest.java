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
package space.minecraftstl.xyml.ui.swing.startup;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import javax.swing.JButton;
import javax.swing.JOptionPane;
import javax.swing.JPanel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Verifies headless policy helpers used by bootstrap safety dialogs.
@NotNullByDefault
class SwingStartupSafetyDialogsTest {
    /// Finds an exact button recursively without accepting a different label.
    @Test
    void findsExactNestedButton() {
        JButton expected = new JButton("Continue");
        JPanel nested = new JPanel();
        nested.add(expected);
        JPanel root = new JPanel();
        root.add(nested);

        assertSame(expected, SwingStartupSafetyDialogs.findButton(root, "Continue"));
        assertNull(SwingStartupSafetyDialogs.findButton(root, "Cancel"));
    }

    /// Maps severities to stable native Swing message types.
    @Test
    void mapsSeverityToSwingMessageTypes() {
        assertEquals(
                JOptionPane.INFORMATION_MESSAGE,
                SwingStartupSafetyDialogs.messageType(
                        SwingStartupSafetyDialogs.Severity.INFO));
        assertEquals(
                JOptionPane.WARNING_MESSAGE,
                SwingStartupSafetyDialogs.messageType(
                        SwingStartupSafetyDialogs.Severity.WARNING));
        assertEquals(
                JOptionPane.ERROR_MESSAGE,
                SwingStartupSafetyDialogs.messageType(
                        SwingStartupSafetyDialogs.Severity.ERROR));
    }

    /// Resolves a localized non-empty title for every severity.
    @Test
    void resolvesLocalizedSeverityTitles() {
        for (SwingStartupSafetyDialogs.Severity severity
                : SwingStartupSafetyDialogs.Severity.values()) {
            assertFalse(SwingStartupSafetyDialogs.title(severity).isBlank());
        }
    }

    /// Rejects an invalid countdown before creating any native dialog.
    @Test
    void rejectsNonPositiveCountdownBeforeUiDispatch() {
        assertThrows(
                IllegalArgumentException.class,
                () -> SwingStartupSafetyDialogs.confirmWithCountdown(
                        SwingStartupSafetyDialogs.Severity.WARNING,
                        "Message",
                        0));
    }
}
