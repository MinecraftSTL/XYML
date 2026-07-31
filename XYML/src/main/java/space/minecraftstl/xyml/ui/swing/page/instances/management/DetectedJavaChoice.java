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
import space.minecraftstl.xyml.setting.GameSettings;

import java.util.Objects;

/// Display value for one detected Java reference.
///
/// @param value persisted Java runtime reference
/// @param label visible runtime description
@NotNullByDefault
record DetectedJavaChoice(GameSettings.DetectedJava value, String label) {
    /// Rejects missing detected Java choice values.
    DetectedJavaChoice {
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(label, "label");
    }

    /// Returns the visible combo-box label.
    @Override
    public String toString() {
        return label;
    }
}
