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
import space.minecraftstl.xyml.game.XYMLGameRepository;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;
import space.minecraftstl.xyml.util.platform.hardware.PhysicalMemoryStatus;

import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JSlider;
import javax.swing.JTextField;
import java.awt.Component;
import java.awt.Container;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static space.minecraftstl.xyml.util.i18n.I18n.i18n;

/// Verifies the restored manual memory slider and physical-memory summary.
@NotNullByDefault
final class InstanceMemoryAllocationControlsTest {
    /// Keeps the text field and slider synchronized and renders the detected memory status.
    @Test
    void synchronizesManualAllocationAndStatus() {
        long gibibyte = 1_024L * 1_024L * 1_024L;
        AtomicReference<@Nullable JComponent> componentReference = new AtomicReference<>();
        AtomicReference<@Nullable JTextField> maximumReference = new AtomicReference<>();
        EdtDispatcher.executeAndWait(() -> {
            JCheckBox automatic = new JCheckBox();
            JTextField maximum = new JTextField("4096");
            maximum.setEnabled(true);
            InstanceMemoryAllocationControls controls = new InstanceMemoryAllocationControls(
                    automatic,
                    maximum,
                    () -> new PhysicalMemoryStatus(8L * gibibyte, 4L * gibibyte),
                    Runnable::run);
            componentReference.set(controls.component());
            maximumReference.set(maximum);
            controls.requestMemoryStatus();
        });

        EdtDispatcher.executeAndWait(() -> {
            JComponent component = Objects.requireNonNull(componentReference.get(), "component");
            JSlider slider = findNamed(
                    component,
                    "instanceGameSettingsMaximumMemorySlider",
                    JSlider.class);
            JLabel physical = findNamed(
                    component,
                    "instanceGameSettingsPhysicalMemory",
                    JLabel.class);
            JLabel allocated = findNamed(
                    component,
                    "instanceGameSettingsAllocatedMemory",
                    JLabel.class);
            JTextField maximum = Objects.requireNonNull(maximumReference.get(), "maximum");

            assertEquals(8_192, slider.getMaximum());
            assertEquals(4_096, slider.getValue());
            assertEquals(i18n("settings.memory.used_per_total", 4.0D, 8.0D), physical.getText());
            assertEquals(i18n("settings.memory.allocate", 4.0D), allocated.getText());

            maximum.setText("2048");
            assertEquals(2_048, slider.getValue());
            slider.setValue(3_072);
            assertEquals("3072", maximum.getText());
        });
    }

    /// Uses automatic allocation when selected and mirrors text-field availability onto the slider.
    @Test
    void followsAutomaticModeAndEditorAvailability() {
        long gibibyte = 1_024L * 1_024L * 1_024L;
        AtomicReference<@Nullable JCheckBox> automaticReference = new AtomicReference<>();
        AtomicReference<@Nullable JTextField> maximumReference = new AtomicReference<>();
        AtomicReference<@Nullable JComponent> componentReference = new AtomicReference<>();
        EdtDispatcher.executeAndWait(() -> {
            JCheckBox automatic = new JCheckBox();
            JTextField maximum = new JTextField("4096");
            InstanceMemoryAllocationControls controls = new InstanceMemoryAllocationControls(
                    automatic,
                    maximum,
                    () -> new PhysicalMemoryStatus(8L * gibibyte, 4L * gibibyte),
                    Runnable::run);
            automaticReference.set(automatic);
            maximumReference.set(maximum);
            componentReference.set(controls.component());
            controls.requestMemoryStatus();
        });

        EdtDispatcher.executeAndWait(() -> {
            JCheckBox automatic = Objects.requireNonNull(automaticReference.get(), "automatic");
            JTextField maximum = Objects.requireNonNull(maximumReference.get(), "maximum");
            JComponent component = Objects.requireNonNull(componentReference.get(), "component");
            JSlider slider = findNamed(
                    component,
                    "instanceGameSettingsMaximumMemorySlider",
                    JSlider.class);
            JLabel allocated = findNamed(
                    component,
                    "instanceGameSettingsAllocatedMemory",
                    JLabel.class);

            automatic.doClick();
            long automaticBytes = XYMLGameRepository.getAutoAllocatedMemory(4L * gibibyte);
            assertEquals(i18n(
                    "settings.memory.allocate",
                    automaticBytes / (double) gibibyte), allocated.getText());

            maximum.setEnabled(false);
            assertFalse(slider.isEnabled());
            maximum.setEnabled(true);
            assertTrue(slider.isEnabled());
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
        throw new AssertionError("Missing component: " + name);
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
