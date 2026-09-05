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
import space.minecraftstl.xyml.library.nbt.tag.ListTag;
import space.minecraftstl.xyml.library.nbt.tag.Tag;
import space.minecraftstl.xyml.library.nbt.tag.TagType;
import space.minecraftstl.xyml.library.nbt.tag.ValueTag;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/// Formats and parses the structured value fields used by the Swing NBT editor.
///
/// Numeric lists and primitive arrays use a compact comma-separated representation without SNBT
/// brackets. Parsing constructs a detached same-type replacement before the generic editor sees it,
/// so a malformed element cannot partially update the working tree.
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

    /// Returns whether a type is one of the four integral scalar types.
    ///
    /// @param type candidate element type
    /// @return whether the type is integral
    static boolean isIntegral(@Nullable TagType<?> type) {
        return type == TagType.BYTE || type == TagType.SHORT || type == TagType.INT || type == TagType.LONG;
    }

    /// Returns whether a selected container has the compact aggregate value representation.
    ///
    /// @param type selected tag type
    /// @param elementType declared List element type, or `null`
    /// @return whether the complete value can be edited as comma-separated numbers
    static boolean isEditableAggregate(@Nullable TagType<?> type, @Nullable TagType<?> elementType) {
        return isPrimitiveArray(type) || type == TagType.LIST && isIntegral(elementType);
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
        if (isPrimitiveArray(selected) || selected == TagType.LIST) {
            throw new IOException("Container values require aggregate parsing");
        }
        Tag parsed = NBTTagInput.create(selected, "", Objects.requireNonNull(input, "input"));
        if (parsed instanceof ValueTag<?> valueTag) {
            return valueTag.getValue().toString();
        }
        throw new IOException("Type has no scalar value: " + selected.name());
    }

    /// Formats one numeric List or primitive array as comma-separated decimal values.
    ///
    /// @param tag detached aggregate snapshot
    /// @return comma-separated values without brackets
    /// @throws IllegalArgumentException if the tag is not an editable aggregate
    static String formatAggregate(Tag tag) {
        Tag selected = Objects.requireNonNull(tag, "tag");
        StringBuilder result = new StringBuilder();
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
        } else if (selected instanceof ListTag<?> list && isIntegral(list.getElementType())) {
            for (int index = 0; index < list.size(); index++) {
                appendSeparator(result, index);
                Tag element = list.getTag(index);
                if (!(element instanceof ValueTag<?> value)) {
                    throw new IllegalArgumentException("Numeric List contains a non-value element");
                }
                result.append(value.getValue());
            }
        } else {
            throw new IllegalArgumentException("Tag is not an editable numeric aggregate");
        }
        return result.toString();
    }

    /// Parses a complete numeric List or primitive array value.
    ///
    /// @param source detached aggregate whose type, element type, and name are retained
    /// @param input comma-separated decimal values without brackets
    /// @return detached same-type replacement
    /// @throws IOException if a token is absent, malformed, out of range, or incompatible
    static Tag parseAggregate(Tag source, String input) throws IOException {
        Tag selected = Objects.requireNonNull(source, "source");
        List<String> tokens = splitValues(Objects.requireNonNull(input, "input"));
        try {
            Tag result;
            if (selected instanceof ByteArrayTag) {
                byte[] values = new byte[tokens.size()];
                for (int index = 0; index < values.length; index++) {
                    values[index] = Byte.parseByte(tokens.get(index));
                }
                result = new ByteArrayTag(values);
            } else if (selected instanceof IntArrayTag) {
                int[] values = new int[tokens.size()];
                for (int index = 0; index < values.length; index++) {
                    values[index] = Integer.parseInt(tokens.get(index));
                }
                result = new IntArrayTag(values);
            } else if (selected instanceof LongArrayTag) {
                long[] values = new long[tokens.size()];
                for (int index = 0; index < values.length; index++) {
                    values[index] = Long.parseLong(tokens.get(index));
                }
                result = new LongArrayTag(values);
            } else if (selected instanceof ListTag<?> list && isIntegral(list.getElementType())) {
                result = parseIntegralList(list, tokens);
            } else {
                throw new IOException("The selected tag is not an editable numeric aggregate");
            }
            result.setName(selected.getName());
            return result;
        } catch (NumberFormatException failure) {
            throw new IOException("An aggregate element is outside the selected numeric type's range", failure);
        }
    }

    /// Builds a detached homogeneous List after every element token has been parsed.
    ///
    /// @param source selected typed List
    /// @param tokens complete trimmed value tokens
    /// @return detached List retaining its declared element type
    /// @throws IOException if an element cannot be parsed
    @SuppressWarnings("unchecked")
    private static Tag parseIntegralList(ListTag<?> source, List<String> tokens) throws IOException {
        TagType<?> elementType = Objects.requireNonNull(source.getElementType(), "elementType");
        ListTag<Tag> result = new ListTag<>((TagType<Tag>) elementType);
        for (String token : tokens) {
            result.addTag(NBTTagInput.create(elementType, "", token));
        }
        return result;
    }

    /// Splits an optional sequence on English commas and rejects missing elements.
    ///
    /// @param input complete aggregate field text
    /// @return immutable trimmed token list
    /// @throws IOException if a leading, trailing, or repeated comma creates an empty token
    private static List<String> splitValues(String input) throws IOException {
        String trimmed = input.trim();
        if (trimmed.isEmpty()) {
            return List.of();
        }
        String[] parts = trimmed.split(",", -1);
        List<String> result = new ArrayList<>(parts.length);
        for (String part : parts) {
            String token = part.trim();
            if (token.isEmpty()) {
                throw new IOException("Every comma-separated element must contain a number");
            }
            result.add(token);
        }
        return List.copyOf(result);
    }

    /// Adds a stable comma-space separator for array display.
    private static void appendSeparator(StringBuilder target, int index) {
        if (index > 0) {
            target.append(", ");
        }
    }
}
