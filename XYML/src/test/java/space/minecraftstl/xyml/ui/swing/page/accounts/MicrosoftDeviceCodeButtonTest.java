// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.accounts;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;

import java.awt.Font;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies Microsoft device-code presentation and clipboard activation without a native dialog.
@NotNullByDefault
final class MicrosoftDeviceCodeButtonTest {
    /// The device code uses the log family with fixed old-UI weight and size, then clears without retaining text.
    @Test
    void presentsAndCopiesCodeWithLogFontFamily() {
        AtomicReference<@Nullable String> copied = new AtomicReference<>();

        EdtDispatcher.executeAndWait(() -> {
            MicrosoftDeviceCodeButton button = new MicrosoftDeviceCodeButton(
                    new Font(Font.SERIF, Font.PLAIN, 13),
                    copied::set);
            assertFalse(button.isVisible());
            assertNull(button.code());

            button.showCode("ABCD-EFGH");
            assertTrue(button.isVisible());
            assertEquals("ABCD-EFGH", button.getText());
            assertEquals(Font.SERIF, button.getFont().getFamily());
            assertEquals(Font.BOLD, button.getFont().getStyle());
            assertEquals(22.0F, button.getFont().getSize2D());
            button.doClick();
            assertEquals("ABCD-EFGH", copied.get());

            button.clearCode();
            assertFalse(button.isVisible());
            assertEquals("", button.getText());
            assertNull(button.code());
        });
    }
}
