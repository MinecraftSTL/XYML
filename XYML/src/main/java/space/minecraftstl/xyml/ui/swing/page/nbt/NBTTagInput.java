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
import space.minecraftstl.xyml.library.nbt.io.SNBTCodec;
import space.minecraftstl.xyml.library.nbt.tag.ByteTag;
import space.minecraftstl.xyml.library.nbt.tag.DoubleTag;
import space.minecraftstl.xyml.library.nbt.tag.FloatTag;
import space.minecraftstl.xyml.library.nbt.tag.IntTag;
import space.minecraftstl.xyml.library.nbt.tag.LongTag;
import space.minecraftstl.xyml.library.nbt.tag.ShortTag;
import space.minecraftstl.xyml.library.nbt.tag.StringTag;
import space.minecraftstl.xyml.library.nbt.tag.Tag;
import space.minecraftstl.xyml.library.nbt.tag.TagType;

import java.io.IOException;
import java.util.List;
import java.util.Objects;

/// Strict conversion boundary for structured NBT forms and subtree SNBT input.
///
/// Scalar fields use exact Java range parsers, floating-point fields reject non-finite values,
/// and container/array fields require a complete SNBT value of the explicitly selected type.
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
        try {
            if (selectedType == TagType.BYTE) {
                result = new ByteTag(Byte.parseByte(requiredNumber(text)));
            } else if (selectedType == TagType.SHORT) {
                result = new ShortTag(Short.parseShort(requiredNumber(text)));
            } else if (selectedType == TagType.INT) {
                result = new IntTag(Integer.parseInt(requiredNumber(text)));
            } else if (selectedType == TagType.LONG) {
                result = new LongTag(Long.parseLong(requiredNumber(text)));
            } else if (selectedType == TagType.FLOAT) {
                float parsed = Float.parseFloat(requiredNumber(text));
                if (!Float.isFinite(parsed)) {
                    throw new NumberFormatException("non-finite float");
                }
                result = new FloatTag(parsed);
            } else if (selectedType == TagType.DOUBLE) {
                double parsed = Double.parseDouble(requiredNumber(text));
                if (!Double.isFinite(parsed)) {
                    throw new NumberFormatException("non-finite double");
                }
                result = new DoubleTag(parsed);
            } else if (selectedType == TagType.STRING) {
                result = new StringTag(text);
            } else {
                result = text.isBlank() ? selectedType.createTag() : parseExactType(selectedType, text);
            }
        } catch (NumberFormatException failure) {
            throw new IOException("Value is outside the exact range for " + selectedType.name(), failure);
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

    /// Trims numeric input and rejects a missing scalar.
    ///
    /// @param value numeric input
    /// @return non-empty trimmed input
    private static String requiredNumber(String value) {
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            throw new NumberFormatException("empty numeric value");
        }
        return trimmed;
    }
}
