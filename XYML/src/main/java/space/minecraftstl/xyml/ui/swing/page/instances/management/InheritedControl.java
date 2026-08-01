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
import javax.swing.JComponent;
import java.util.Objects;

/// One value editor paired with its independent instance-override checkbox.
///
/// @param overrideBox local-override checkbox
/// @param editor effective value editor
/// @param <T> editor component type
@NotNullByDefault
record InheritedControl<T extends JComponent>(JCheckBox overrideBox, T editor) {
    /// Rejects missing components.
    InheritedControl {
        Objects.requireNonNull(overrideBox, "overrideBox");
        Objects.requireNonNull(editor, "editor");
    }
}
