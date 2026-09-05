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
import space.minecraftstl.xyml.library.nbt.edit.NBTEditException;
import space.minecraftstl.xyml.library.nbt.edit.NBTEditor;
import space.minecraftstl.xyml.library.nbt.io.SNBTCodec;
import space.minecraftstl.xyml.library.nbt.tag.StringTag;
import space.minecraftstl.xyml.library.nbt.tag.Tag;
import space.minecraftstl.xyml.library.nbt.tag.TagType;
import space.minecraftstl.xyml.library.nbt.tag.ValueTag;

import java.io.IOException;
import java.util.List;
import java.util.Objects;

/// Strict conversion boundary for structured NBT forms and subtree SNBT input.
///
/// Scalar fields delegate to the generic XoyzNBT editor parser, while container and array fields
/// require a complete SNBT value of the explicitly selected type.
@NotNullByDefault
final class NBTTagInput {
    /// Every standard non-END NBT type in wire identifier order.
    private static final @Unmodifiable List<TagType<?>> TYPES = List.of(
            TagType.BYTE,
            TagType.SHORT,
            TagType.INT,
            TagType.LONG,
            TagType.FLOAT,
            TagType.DOUBLE,
            TagType.BYTE_ARRAY,
            TagType.STRING,
            TagType.LIST,
            TagType.COMPOUND,
            TagType.INT_ARRAY,
            TagType.LONG_ARRAY);

    /// Prevents utility-class construction.
    private NBTTagInput() {
    }

    /// Returns every selectable standard type without exposing mutable storage.
    ///
    /// @return immutable type list
    static @Unmodifiable List<TagType<?>> types() {
        return TYPES;
    }

    /// Creates one detached tag from explicit name, type, and structured value text.
    ///
    /// An empty value creates an empty List, Compound, or primitive array. An empty String value
    /// remains an empty string; all numeric scalar values must be present.
    ///
    /// @param type explicitly selected tag type
    /// @param name requested tag name, possibly empty for indexed parents
    /// @param value exact scalar text or complete SNBT for a container/array
    /// @return detached initialized tag
    /// @throws IOException if the value is invalid or has a different type
    static Tag create(TagType<?> type, String name, String value) throws IOException {
        TagType<?> selectedType = Objects.requireNonNull(type, "type");
        String selectedName = Objects.requireNonNull(name, "name");
        String text = Objects.requireNonNull(value, "value");
        Tag result;
        if (selectedType == TagType.STRING) {
            result = new StringTag(text);
        } else if (ValueTag.class.isAssignableFrom(selectedType.tagClass())) {
            result = parseScalar(selectedType, requiredNumber(text));
        } else {
            result = text.isBlank() ? selectedType.createTag() : parseExactType(selectedType, text);
        }
        result.setName(selectedName);
        return result;
    }

    /// Parses exactly one complete detached SNBT tag.
    ///
    /// @param input complete SNBT input
    /// @return parsed detached tag
    /// @throws IOException if parsing fails or any trailing token remains
    static Tag parseSnbt(String input) throws IOException {
        return SNBTCodec.of().readTag(Objects.requireNonNull(input, "input"));
    }

    /// Serializes one detached subtree for the selected-subtree tab and clipboard.
    ///
    /// @param tag detached source tag
    /// @return pretty SNBT text
    static String toSnbt(Tag tag) {
        return SNBTCodec.of().toString(Objects.requireNonNull(tag, "tag"));
    }

    /// Parses a complete SNBT value and requires the selected wire type.
    ///
    /// @param type expected type
    /// @param text complete SNBT input
    /// @return detached parsed tag
    /// @throws IOException if parsing fails or the type differs
    private static Tag parseExactType(TagType<?> type, String text) throws IOException {
        Tag parsed = parseSnbt(text);
        if (parsed.getType() != type) {
            throw new IOException("Expected " + type.name() + " but parsed " + parsed.getType().name());
        }
        return parsed;
    }

    /// Parses one numeric scalar through the public generic editor contract.
    ///
    /// @param type exact scalar type
    /// @param text non-empty decimal or hexadecimal input
    /// @return detached parsed scalar
    /// @throws IOException if the generic editor rejects the value
    private static Tag parseScalar(TagType<?> type, String text) throws IOException {
        Tag blank = type.createTag();
        NBTEditor<Tag> editor = NBTEditor.of(blank);
        try {
            editor.setScalar(editor.getRootNode(), text);
            return editor.snapshot();
        } catch (NBTEditException failure) {
            throw new IOException("Value is invalid for " + type.name(), failure);
        }
    }

    /// Trims numeric input and rejects a missing scalar.
    ///
    /// @param value numeric input
    /// @return non-empty trimmed input
    /// @throws IOException if the value is blank
    private static String requiredNumber(String value) throws IOException {
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            throw new IOException("Numeric value must not be empty");
        }
        return trimmed;
    }

}
