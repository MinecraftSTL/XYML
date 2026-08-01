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
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;

import javax.swing.JPanel;
import javax.swing.JTextField;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies instance and global memory-mode radio rows, inheritance, and mutual exclusion.
@NotNullByDefault
final class InstanceMemoryModeSelectorTest {
    /// Renders inheritance, automatic, and manual choices in the required instance order.
    @Test
    void createsThreeInstanceChoices() {
        EdtDispatcher.executeAndWait(() -> {
            InstanceMemoryModeSelector selector = new InstanceMemoryModeSelector(true);
            JPanel section = section();
            JTextField maximumMemory = new JTextField();

            selector.addRows(section, maximumMemory);

            assertSame(selector.inheritanceButton(), section.getComponent(0));
            assertSame(selector.automaticButton(), section.getComponent(2));
            assertSame(selector.manualButton(), section.getComponent(4));
            assertSame(maximumMemory, section.getComponent(5));
            assertTrue(selector.isInherited());
            assertThrows(IllegalStateException.class, selector::isAutomatic);

            selector.automaticButton().doClick();
            assertFalse(selector.isInherited());
            assertTrue(selector.isAutomatic());
            assertEquals(1, selectedCount(selector));

            selector.manualButton().doClick();
            assertFalse(selector.isAutomatic());
            assertEquals(1, selectedCount(selector));
        });
    }

    /// Renders only automatic and manual choices for global presets and preserves callback semantics.
    @Test
    void createsTwoGlobalChoices() {
        EdtDispatcher.executeAndWait(() -> {
            InstanceMemoryModeSelector selector = new InstanceMemoryModeSelector(false);
            JPanel section = section();
            JTextField maximumMemory = new JTextField();
            AtomicInteger changes = new AtomicInteger();
            selector.addSelectionListener(changes::incrementAndGet);

            selector.addRows(section, maximumMemory);

            assertEquals(4, section.getComponentCount());
            assertSame(selector.automaticButton(), section.getComponent(0));
            assertSame(selector.manualButton(), section.getComponent(2));
            assertSame(maximumMemory, section.getComponent(3));
            assertTrue(selector.isAutomatic());

            selector.apply(true, false);
            assertFalse(selector.isAutomatic());
            assertEquals(0, changes.get());
            selector.automaticButton().doClick();
            assertEquals(1, changes.get());

            selector.setEnabled(false);
            assertFalse(selector.automaticButton().isEnabled());
            assertFalse(selector.manualButton().isEnabled());
        });
    }

    /// Counts selected instance choices.
    ///
    /// @param selector selector under test
    /// @return selected choice count
    private static int selectedCount(InstanceMemoryModeSelector selector) {
        int selected = selector.inheritanceButton().isSelected() ? 1 : 0;
        selected += selector.automaticButton().isSelected() ? 1 : 0;
        selected += selector.manualButton().isSelected() ? 1 : 0;
        return selected;
    }

    /// Creates the production three-column section layout.
    ///
    /// @return empty section
    private static JPanel section() {
        return new JPanel(new MigLayout(
                "insets 0, fillx, wrap 3",
                "[26!,center][280!,fill][grow,fill]",
                "[]10[]"));
    }
}
