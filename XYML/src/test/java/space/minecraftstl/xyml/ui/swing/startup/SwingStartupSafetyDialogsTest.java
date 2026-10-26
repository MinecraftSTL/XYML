// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

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
