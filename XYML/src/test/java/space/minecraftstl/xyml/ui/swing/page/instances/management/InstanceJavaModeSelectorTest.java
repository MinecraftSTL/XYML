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
package space.minecraftstl.xyml.ui.swing.page.instances.management;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import space.minecraftstl.xyml.setting.JavaVersionType;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;

import javax.swing.JRadioButton;
import javax.swing.JToggleButton;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies the Java strategy selector's stable order, highlight styling, and interaction contract.
@NotNullByDefault
class InstanceJavaModeSelectorTest {
    /// Keeps mode ordering aligned with the payload settings shown beneath the selector.
    @Test
    void followsPayloadRowOrderWithHighlightedExclusiveButtons() {
        EdtDispatcher.executeAndWait(() -> {
            InstanceJavaModeSelector selector = new InstanceJavaModeSelector();

            assertEquals(List.of(
                    JavaVersionType.AUTO,
                    JavaVersionType.VERSION,
                    JavaVersionType.CUSTOM,
                    JavaVersionType.DETECTED), InstanceJavaModeSelector.displayOrder());
            assertEquals(JavaVersionType.AUTO, selector.selectedMode());

            List<JavaVersionType> displayOrder = InstanceJavaModeSelector.displayOrder();
            for (int index = 0; index < displayOrder.size(); index++) {
                JavaVersionType mode = displayOrder.get(index);
                JToggleButton button = selector.button(mode);
                assertSame(button, selector.getComponent(index));
                assertInstanceOf(JToggleButton.class, button);
                assertFalse(button instanceof JRadioButton);
                assertEquals("instanceGameSettingsJavaMode" + mode.name(), button.getName());
                assertEquals("tab", button.getClientProperty("JButton.buttonType"));
                button.doClick();
                assertEquals(mode, selector.selectedMode());
                assertEquals(1L, displayOrder.stream()
                        .map(selector::button)
                        .filter(JToggleButton::isSelected)
                        .count());
            }
        });
    }

    /// Keeps callbacks user-driven and propagates interaction availability to every option.
    @Test
    void preservesProgrammaticSelectionAndInteractionAvailability() {
        EdtDispatcher.executeAndWait(() -> {
            InstanceJavaModeSelector selector = new InstanceJavaModeSelector();
            AtomicInteger changes = new AtomicInteger();
            selector.addSelectionListener(changes::incrementAndGet);

            selector.setSelectedMode(JavaVersionType.DETECTED);
            assertEquals(JavaVersionType.DETECTED, selector.selectedMode());
            assertEquals(0, changes.get());

            selector.setEnabled(false);
            for (JavaVersionType mode : InstanceJavaModeSelector.displayOrder()) {
                assertFalse(selector.button(mode).isEnabled());
            }

            selector.setEnabled(true);
            selector.button(JavaVersionType.VERSION).doClick();
            assertEquals(JavaVersionType.VERSION, selector.selectedMode());
            assertEquals(1, changes.get());
            assertTrue(selector.isEnabled());
        });
    }
}
