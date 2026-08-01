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

import javax.swing.JCheckBox;
import javax.swing.JTextField;
import java.util.Objects;

/// Restores the legacy mutual exclusion between automatic and manual instance-memory allocation.
@NotNullByDefault
final class InstanceMemorySelectionController {
    /// Prevents construction of the interaction-only controller.
    private InstanceMemorySelectionController() {
    }

    /// Connects selecting a local manual heap value to disabling effective automatic allocation.
    ///
    /// @param automaticMemory automatic-allocation override and value
    /// @param maximumMemory manual heap override and value
    static void install(
            InheritedControl<JCheckBox> automaticMemory,
            InheritedControl<JTextField> maximumMemory) {
        InheritedControl<JCheckBox> validatedAutomatic = Objects.requireNonNull(
                automaticMemory,
                "automaticMemory");
        InheritedControl<JTextField> validatedMaximum = Objects.requireNonNull(maximumMemory, "maximumMemory");
        validatedMaximum.overrideBox().addActionListener(event -> selectManual(
                validatedAutomatic,
                validatedMaximum));
    }

    /// Selects a local manual mode only when the effective mode is currently automatic.
    ///
    /// @param automaticMemory automatic-allocation override and value
    /// @param maximumMemory manual heap override and value
    private static void selectManual(
            InheritedControl<JCheckBox> automaticMemory,
            InheritedControl<JTextField> maximumMemory) {
        if (!maximumMemory.overrideBox().isSelected() || !automaticMemory.editor().isSelected()) {
            return;
        }
        if (!automaticMemory.overrideBox().isSelected()) {
            automaticMemory.overrideBox().doClick(0);
        }
        if (automaticMemory.editor().isSelected()) {
            automaticMemory.editor().doClick(0);
        }
    }
}
