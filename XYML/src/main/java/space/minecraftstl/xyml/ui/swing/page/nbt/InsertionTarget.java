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

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import space.minecraftstl.xyml.library.nbt.tag.TagType;

import java.util.List;
import java.util.Objects;

/// Immutable constraints for one NBT insertion destination.
@NotNullByDefault
record InsertionTarget(
        NBTEditorTreeNode parent,
        int index,
        @Unmodifiable List<TagType<?>> types,
        boolean nameRequired) {
    /// Validates and snapshots insertion constraints.
    InsertionTarget {
        Objects.requireNonNull(parent, "parent");
        types = List.copyOf(Objects.requireNonNull(types, "types"));
        if (index < 0 || index > parent.childCount() || types.isEmpty()) {
            throw new IllegalArgumentException("Invalid insertion target");
        }
    }
}
