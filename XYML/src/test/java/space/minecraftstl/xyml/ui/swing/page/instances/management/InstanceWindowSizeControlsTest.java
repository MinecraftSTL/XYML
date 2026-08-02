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
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;

import javax.swing.JComboBox;
import javax.swing.JTextField;
import java.awt.Component;
import java.awt.Container;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies common game-window resolutions and paired width-height editing.
@NotNullByDefault
final class InstanceWindowSizeControlsTest {
    /// Keeps baseline sizes and includes larger presets only at matching physical display thresholds.
    @Test
    void filtersPresetsByDisplayBounds() {
        assertEquals(
                List.of("854x480", "1280x720", "1600x900"),
                InstanceWindowSizeControls.supportedResolutions(1_919, 1_079));
        assertEquals(
                List.of("854x480", "1280x720", "1600x900", "1920x1080", "2560x1440"),
                InstanceWindowSizeControls.supportedResolutions(2_560, 1_440));
        assertEquals(
                List.of("854x480", "1280x720", "1600x900", "1920x1080", "2560x1440", "3840x2160"),
                InstanceWindowSizeControls.supportedResolutions(3_840, 2_160));
    }

    /// Shows effective dimensions first and applies a chosen preset after the parent enables paired editing.
    @Test
    void appliesSelectedPresetWithoutInventingADefault() {
        EdtDispatcher.executeAndWait(() -> {
            JTextField width = new JTextField("854.5");
            JTextField height = new JTextField("480");
            InstanceWindowSizeControls controls = new InstanceWindowSizeControls(
                    width,
                    height);
            JComboBox<?> selector = findNamed(
                    controls.component(),
                    "instanceGameSettingsWindowSizePreset",
                    JComboBox.class);

            assertEquals("854.5x480", selector.getEditor().getItem());
            assertFalse(selector.isEnabled());
            controls.setEditingAvailable(true);
            selector.setSelectedItem("1280x720");
            assertEquals("1280", width.getText());
            assertEquals("720", height.getText());
        });
    }

    /// Preserves editable custom dimensions and follows parent-resolved editing availability.
    @Test
    void acceptsCustomResolutionAndFollowsEditingAvailability() {
        EdtDispatcher.executeAndWait(() -> {
            JTextField width = new JTextField("854");
            JTextField height = new JTextField("480");
            InstanceWindowSizeControls controls = new InstanceWindowSizeControls(
                    width,
                    height);
            JComboBox<?> selector = findNamed(
                    controls.component(),
                    "instanceGameSettingsWindowSizePreset",
                    JComboBox.class);

            assertFalse(selector.isEnabled());
            controls.setEditingAvailable(true);
            assertTrue(selector.isEnabled());
            selector.setSelectedItem("1000.5x700");
            assertEquals("1000.5", width.getText());
            assertEquals("700", height.getText());

            controls.setEditingAvailable(false);
            assertFalse(selector.isEnabled());
            controls.setEditingAvailable(true);
            assertTrue(selector.isEnabled());
        });
    }

    /// Locates a named descendant with the expected Swing type.
    ///
    /// @param root search root
    /// @param name stable component name
    /// @param type expected component type
    /// @param <T> component type
    /// @return matching component
    private static <T extends Component> T findNamed(Component root, String name, Class<T> type) {
        @Nullable T match = findNamedOrNull(root, name, type);
        if (match == null) {
            throw new AssertionError("Missing component: " + name);
        }
        return match;
    }

    /// Recursively searches without raising when one branch has no match.
    ///
    /// @param root search root
    /// @param name stable component name
    /// @param type expected component type
    /// @param <T> component type
    /// @return matching component, or null
    private static <T extends Component> @Nullable T findNamedOrNull(
            Component root,
            String name,
            Class<T> type) {
        if (Objects.equals(root.getName(), name) && type.isInstance(root)) {
            return type.cast(root);
        }
        if (root instanceof Container container) {
            for (Component child : container.getComponents()) {
                @Nullable T match = findNamedOrNull(child, name, type);
                if (match != null) {
                    return match;
                }
            }
        }
        return null;
    }
}
