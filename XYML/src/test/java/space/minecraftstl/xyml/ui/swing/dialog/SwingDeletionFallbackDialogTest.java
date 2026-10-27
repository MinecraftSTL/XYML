// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.dialog;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static space.minecraftstl.xyml.util.i18n.I18n.i18n;

/// Verifies three-way fallback order, safe default, and closure mapping without native windows.
@NotNullByDefault
public final class SwingDeletionFallbackDialogTest {
    /// Verifies every supported choice and treats closure or unknown values as cancellation.
    @Test
    public void mapsOptionsAndDefaultsToCancel() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            List<DeletionFallbackDecision> expected = List.of(
                    DeletionFallbackDecision.RETRY_RECYCLE_BIN,
                    DeletionFallbackDecision.DELETE_PERMANENTLY,
                    DeletionFallbackDecision.CANCEL);
            for (int choice : new int[]{0, 1, 2, -1, -2, 99}) {
                DeletionFallbackDecision result = SwingDeletionFallbackDialog.choose(
                        new JPanel(), "Existing title", "Existing message",
                        (owner, message, title, options, initialValue) -> {
                            assertEquals("Existing title", title);
                            assertEquals("Existing message", message);
                            assertEquals(List.of(i18n("button.retry"), i18n("button.delete_permanently"),
                                    i18n("button.cancel")), options);
                            assertEquals(options.get(2), initialValue);
                            assertThrows(UnsupportedOperationException.class, () -> options.add("Unexpected"));
                            return choice;
                        });
                assertEquals(choice >= 0 && choice < 3 ? expected.get(choice) : DeletionFallbackDecision.CANCEL,
                        result);
            }
        });
    }

    /// Rejects accidental use outside the event-dispatch thread.
    @Test
    public void requiresEventDispatchThread() {
        assertThrows(IllegalStateException.class, () -> SwingDeletionFallbackDialog.choose(
                new JPanel(), "Title", "Message", (owner, message, title, options, initialValue) -> 0));
    }
}
