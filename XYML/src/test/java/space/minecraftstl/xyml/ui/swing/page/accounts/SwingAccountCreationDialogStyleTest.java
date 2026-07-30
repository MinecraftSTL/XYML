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
import org.junit.jupiter.api.Test;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;

import javax.swing.JPanel;
import javax.swing.JToggleButton;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies headless-safe Microsoft login mode selection styling and exclusivity.
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
}
