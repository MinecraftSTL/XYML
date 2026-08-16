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
package space.minecraftstl.xyml.ui.swing.page.nbt;

import space.minecraftstl.xyml.library.nbt.NBTElement;
import space.minecraftstl.xyml.library.nbt.tag.ValueTag;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/// Extracts live scalar text from exact HelloNBT value elements.
@NotNullByDefault
final class NBTEditorTreeValues {
    /// Prevents construction of this stateless utility.
    private NBTEditorTreeValues() {
    }

    /// Returns the current HelloNBT scalar text.
    ///
    /// @param element concrete element
    /// @return scalar text, or `null` for non-value elements
    static @Nullable String scalarText(NBTElement element) {
        NBTElement candidate = Objects.requireNonNull(element, "element");
        return candidate instanceof ValueTag<?> valueTag ? valueTag.getAsString() : null;
    }
}
