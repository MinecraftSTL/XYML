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
import org.jetbrains.annotations.Nullable;
import space.minecraftstl.xyml.library.nbt.tag.ByteArrayTag;
import space.minecraftstl.xyml.library.nbt.tag.IntArrayTag;
import space.minecraftstl.xyml.library.nbt.tag.LongArrayTag;
import space.minecraftstl.xyml.library.nbt.tag.Tag;
import space.minecraftstl.xyml.library.nbt.tag.TagType;
import space.minecraftstl.xyml.library.nbt.tag.ValueTag;

import java.io.IOException;
import java.util.Objects;

/// Formats and parses the decimal scalar fields used by the Swing NBT editor.
///
/// Primitive arrays deliberately have no direct replacement codec. Their parent row is displayed
/// read-only and each element is edited through its own scalar child row, which keeps array length
/// and ownership invariants under the generic editor's control.
@NotNullByDefault
final class NBTStructuredValueCodec {
    /// Prevents utility-class construction.
    private NBTStructuredValueCodec() {
    }

    /// Returns whether a type is one of the primitive integer arrays.
    ///
    /// @param type candidate type
    /// @return whether the type is a primitive array
    static boolean isPrimitiveArray(@Nullable TagType<?> type) {
        return type == TagType.BYTE_ARRAY || type == TagType.INT_ARRAY || type == TagType.LONG_ARRAY;
    }

    /// Formats one scalar in decimal mode.
    static String formatScalar(TagType<?> type, String value) {
        Objects.requireNonNull(type, "type");
        return Objects.requireNonNull(value, "value");
    }

    /// Parses one scalar in decimal mode.
    ///
    /// @param type scalar type
    /// @param input entered value
    /// @return canonical scalar text
    /// @throws IOException if the type is not scalar or the value is invalid
    static String parseScalar(TagType<?> type, String input) throws IOException {
        TagType<?> selected = Objects.requireNonNull(type, "type");
        if (isPrimitiveArray(selected)) {
            throw new IOException("Primitive arrays must be edited through their element rows");
        }
        Tag parsed = NBTTagInput.create(selected, "", Objects.requireNonNull(input, "input"));
        if (parsed instanceof ValueTag<?> valueTag) {
            return valueTag.getValue().toString();
        }
        throw new IOException("Type has no scalar value: " + selected.name());
    }

    /// Formats a primitive array in decimal mode.
    ///
    /// @param tag detached primitive-array snapshot
    /// @return decimal array text
    static String formatArray(Tag tag) {
        Tag selected = Objects.requireNonNull(tag, "tag");
        StringBuilder result = new StringBuilder("[");
        if (selected instanceof ByteArrayTag array) {
            for (int index = 0; index < array.size(); index++) {
                appendSeparator(result, index);
                result.append(array.get(index));
            }
        } else if (selected instanceof IntArrayTag array) {
            for (int index = 0; index < array.size(); index++) {
                appendSeparator(result, index);
                result.append(array.get(index));
            }
        } else if (selected instanceof LongArrayTag array) {
            for (int index = 0; index < array.size(); index++) {
                appendSeparator(result, index);
                result.append(array.get(index));
            }
        } else {
            throw new IllegalArgumentException("Tag is not a primitive array");
        }
        return result.append(']').toString();
    }

    /// Adds a stable comma-space separator for array display.
    private static void appendSeparator(StringBuilder target, int index) {
        if (index > 0) {
            target.append(", ");
        }
    }
}
