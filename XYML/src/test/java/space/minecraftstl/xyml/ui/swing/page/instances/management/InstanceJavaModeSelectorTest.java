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

import net.miginfocom.swing.MigLayout;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import space.minecraftstl.xyml.setting.JavaVersionType;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;

import javax.swing.JComboBox;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JTextField;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies native Java-strategy radio rows, inheritance, ordering, and interaction availability.
@NotNullByDefault
class InstanceJavaModeSelectorTest {
    /// Places inheritance first and each local radio directly beside its corresponding payload editor.
    @Test
    void createsOrderedInstanceRadioRows() {
        EdtDispatcher.executeAndWait(() -> {
            InstanceJavaModeSelector selector = new InstanceJavaModeSelector(true);
            JPanel section = section();
            JTextField version = new JTextField();
            JTextField custom = new JTextField();
            JComboBox<String> detected = new JComboBox<>();

            selector.addRows(section, version, custom, detected);

            assertEquals(List.of(
                    JavaVersionType.AUTO,
                    JavaVersionType.VERSION,
                    JavaVersionType.CUSTOM,
                    JavaVersionType.DETECTED), InstanceJavaModeSelector.displayOrder());
            assertSame(selector.inheritanceButton(), section.getComponent(0));
            assertSame(selector.button(JavaVersionType.AUTO), section.getComponent(2));
            assertSame(selector.button(JavaVersionType.VERSION), section.getComponent(4));
            assertSame(version, section.getComponent(5));
            assertSame(selector.button(JavaVersionType.CUSTOM), section.getComponent(6));
            assertSame(custom, section.getComponent(7));
            assertSame(selector.button(JavaVersionType.DETECTED), section.getComponent(8));
            assertSame(detected, section.getComponent(9));
            assertTrue(selector.isInherited());
            assertThrows(IllegalStateException.class, selector::selectedMode);

            for (JavaVersionType mode : InstanceJavaModeSelector.displayOrder()) {
                JRadioButton button = selector.button(mode);
                assertEquals("instanceGameSettingsJavaMode" + mode.name(), button.getName());
                assertNull(button.getClientProperty("JButton.buttonType"));
                button.doClick();
                assertEquals(mode, selector.selectedMode());
                assertFalse(selector.isInherited());
                assertEquals(1L, selectedCount(selector));
            }
        });
    }

    /// Omits inheritance for global presets and keeps programmatic state changes callback-free.
    @Test
    void distinguishesGlobalPresetAndInstanceInheritance() {
        EdtDispatcher.executeAndWait(() -> {
            InstanceJavaModeSelector globalSelector = new InstanceJavaModeSelector(false);
            AtomicInteger changes = new AtomicInteger();
            globalSelector.addSelectionListener(changes::incrementAndGet);

            assertFalse(globalSelector.isInherited());
            assertEquals(JavaVersionType.AUTO, globalSelector.selectedMode());
            globalSelector.apply(true, JavaVersionType.DETECTED);
            assertEquals(JavaVersionType.DETECTED, globalSelector.selectedMode());
            assertEquals(0, changes.get());

            globalSelector.setEnabled(false);
            for (JavaVersionType mode : InstanceJavaModeSelector.displayOrder()) {
                assertFalse(globalSelector.button(mode).isEnabled());
            }

            InstanceJavaModeSelector instanceSelector = new InstanceJavaModeSelector(true);
            instanceSelector.apply(true, JavaVersionType.VERSION);
            assertEquals(JavaVersionType.VERSION, instanceSelector.selectedMode());
            instanceSelector.apply(false, JavaVersionType.CUSTOM);
            assertTrue(instanceSelector.isInherited());
        });
    }

    /// Counts the selected inheritance or local strategy choices.
    ///
    /// @param selector selector under test
    /// @return selected radio count
    private static long selectedCount(InstanceJavaModeSelector selector) {
        long localSelections = InstanceJavaModeSelector.displayOrder().stream()
                .map(selector::button)
                .filter(JRadioButton::isSelected)
                .count();
        return localSelections + (selector.inheritanceButton().isSelected() ? 1L : 0L);
    }

    /// Creates the production three-column row layout.
    ///
    /// @return empty settings section
    private static JPanel section() {
        return new JPanel(new MigLayout(
                "insets 0, fillx, wrap 3",
                "[26!,center][280!,fill][grow,fill]",
                "[]10[]"));
    }
}
