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
