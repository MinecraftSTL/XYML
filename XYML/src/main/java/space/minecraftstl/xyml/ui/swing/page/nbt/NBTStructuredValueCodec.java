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
import java.util.Locale;
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

    /// Returns whether a type is a numeric scalar with decimal and hexadecimal representations.
    ///
    /// @param type candidate scalar type
    /// @return whether the type supports radix switching
    static boolean isNumericScalar(@Nullable TagType<?> type) {
        return isIntegral(type) || type == TagType.FLOAT || type == TagType.DOUBLE;
    }

    /// Returns whether a selected container has the compact aggregate value representation.
    ///
    /// @param type selected tag type
    /// @param elementType declared List element type, or `null`
    /// @return whether the complete value can be edited as comma-separated numbers
    static boolean isEditableAggregate(@Nullable TagType<?> type, @Nullable TagType<?> elementType) {
        return isPrimitiveArray(type) || type == TagType.LIST && isIntegral(elementType);
    }

    /// Formats one scalar in the selected numeric radix.
    ///
    /// @param type scalar wire type
    /// @param value canonical stored scalar text
    /// @param radix requested display radix
    /// @return formatted editable text
    static String formatScalar(TagType<?> type, String value, NBTNumberRadix radix) {
        TagType<?> selected = Objects.requireNonNull(type, "type");
        String text = Objects.requireNonNull(value, "value");
        if (Objects.requireNonNull(radix, "radix") == NBTNumberRadix.DECIMAL || !isNumericScalar(selected)) {
            return text;
        }
        if (selected == TagType.BYTE) {
            return hexadecimal(Byte.toUnsignedInt(Byte.parseByte(text)));
        }
        if (selected == TagType.SHORT) {
            return hexadecimal(Short.toUnsignedInt(Short.parseShort(text)));
        }
        if (selected == TagType.INT) {
            return "0x" + Integer.toUnsignedString(Integer.parseInt(text), 16).toUpperCase(Locale.ROOT);
        }
        if (selected == TagType.LONG) {
            return "0x" + Long.toUnsignedString(Long.parseLong(text), 16).toUpperCase(Locale.ROOT);
        }
        if (selected == TagType.FLOAT) {
            return Float.toHexString(Float.parseFloat(text));
        }
        if (selected == TagType.DOUBLE) {
            return Double.toHexString(Double.parseDouble(text));
        }
        throw new IllegalArgumentException("Unsupported numeric scalar type: " + selected.name());
    }

    /// Parses one scalar in the selected numeric radix.
    ///
    /// @param type scalar type
    /// @param input entered value
    /// @param radix selected input radix
    /// @return canonical scalar text
    /// @throws IOException if the type is not scalar or the value is invalid
    static String parseScalar(TagType<?> type, String input, NBTNumberRadix radix) throws IOException {
        TagType<?> selected = Objects.requireNonNull(type, "type");
        if (isPrimitiveArray(selected) || selected == TagType.LIST) {
            throw new IOException("Container values require aggregate parsing");
        }
        String normalized = normalizeNumericInput(
                selected,
                Objects.requireNonNull(input, "input"),
                Objects.requireNonNull(radix, "radix"));
        Tag parsed = NBTTagInput.create(selected, "", normalized);
        if (parsed instanceof ValueTag<?> valueTag) {
            return valueTag.getValue().toString();
        }
        throw new IOException("Type has no scalar value: " + selected.name());
    }

    /// Formats one numeric List or primitive array in the selected radix.
    ///
    /// @param tag detached aggregate snapshot
    /// @param radix requested display radix
    /// @return comma-separated values without brackets
    /// @throws IllegalArgumentException if the tag is not an editable aggregate
    static String formatAggregate(Tag tag, NBTNumberRadix radix) {
        Tag selected = Objects.requireNonNull(tag, "tag");
        NBTNumberRadix selectedRadix = Objects.requireNonNull(radix, "radix");
        StringBuilder result = new StringBuilder();
        if (selected instanceof ByteArrayTag array) {
            for (int index = 0; index < array.size(); index++) {
                appendSeparator(result, index);
                result.append(formatScalar(TagType.BYTE, Byte.toString(array.get(index)), selectedRadix));
            }
        } else if (selected instanceof IntArrayTag array) {
            for (int index = 0; index < array.size(); index++) {
                appendSeparator(result, index);
                result.append(formatScalar(TagType.INT, Integer.toString(array.get(index)), selectedRadix));
            }
        } else if (selected instanceof LongArrayTag array) {
            for (int index = 0; index < array.size(); index++) {
                appendSeparator(result, index);
                result.append(formatScalar(TagType.LONG, Long.toString(array.get(index)), selectedRadix));
            }
        } else if (selected instanceof ListTag<?> list && isIntegral(list.getElementType())) {
            for (int index = 0; index < list.size(); index++) {
                appendSeparator(result, index);
                Tag element = list.getTag(index);
                if (!(element instanceof ValueTag<?> value)) {
                    throw new IllegalArgumentException("Numeric List contains a non-value element");
                }
                result.append(formatScalar(
                        Objects.requireNonNull(list.getElementType(), "elementType"),
                        value.getValue().toString(),
                        selectedRadix));
            }
        } else {
            throw new IllegalArgumentException("Tag is not an editable numeric aggregate");
        }
        return result.toString();
    }

    /// Parses a complete numeric List or primitive array value.
    ///
    /// @param source detached aggregate whose type, element type, and name are retained
    /// @param input comma-separated values without brackets
    /// @param radix selected input radix
    /// @return detached same-type replacement
    /// @throws IOException if a token is absent, malformed, out of range, or incompatible
    static Tag parseAggregate(Tag source, String input, NBTNumberRadix radix) throws IOException {
        Tag selected = Objects.requireNonNull(source, "source");
        List<String> tokens = splitValues(Objects.requireNonNull(input, "input"));
        NBTNumberRadix selectedRadix = Objects.requireNonNull(radix, "radix");
        try {
            Tag result;
            if (selected instanceof ByteArrayTag) {
                byte[] values = new byte[tokens.size()];
                for (int index = 0; index < values.length; index++) {
                    values[index] = Byte.parseByte(parseScalar(
                            TagType.BYTE, tokens.get(index), selectedRadix));
                }
                result = new ByteArrayTag(values);
            } else if (selected instanceof IntArrayTag) {
                int[] values = new int[tokens.size()];
                for (int index = 0; index < values.length; index++) {
                    values[index] = Integer.parseInt(parseScalar(
                            TagType.INT, tokens.get(index), selectedRadix));
                }
                result = new IntArrayTag(values);
            } else if (selected instanceof LongArrayTag) {
                long[] values = new long[tokens.size()];
                for (int index = 0; index < values.length; index++) {
                    values[index] = Long.parseLong(parseScalar(
                            TagType.LONG, tokens.get(index), selectedRadix));
                }
                result = new LongArrayTag(values);
            } else if (selected instanceof ListTag<?> list && isIntegral(list.getElementType())) {
                result = parseIntegralList(list, tokens, selectedRadix);
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
    /// @param radix selected input radix
    /// @return detached List retaining its declared element type
    /// @throws IOException if an element cannot be parsed
    @SuppressWarnings("unchecked")
    private static Tag parseIntegralList(
            ListTag<?> source,
            List<String> tokens,
            NBTNumberRadix radix) throws IOException {
        TagType<?> elementType = Objects.requireNonNull(source.getElementType(), "elementType");
        ListTag<Tag> result = new ListTag<>((TagType<Tag>) elementType);
        for (String token : tokens) {
            result.addTag(NBTTagInput.create(
                    elementType,
                    "",
                    normalizeNumericInput(elementType, token, radix)));
        }
        return result;
    }

    /// Converts a draft token into syntax accepted by the generic scalar parser for its mode.
    ///
    /// Bare integral digits in hexadecimal mode are interpreted as hexadecimal. Floating-point
    /// drafts additionally receive Java's required `p0` binary exponent when it is omitted.
    ///
    /// @param type numeric scalar type
    /// @param input entered token
    /// @param radix selected input radix
    /// @return normalized scalar token
    /// @throws IOException if hexadecimal syntax is used in decimal mode
    private static String normalizeNumericInput(
            TagType<?> type,
            String input,
            NBTNumberRadix radix) throws IOException {
        String text = input.trim();
        if (!isNumericScalar(type)) {
            return text;
        }
        if (radix == NBTNumberRadix.DECIMAL) {
            if (hasHexadecimalPrefix(text)) {
                throw new IOException("Hexadecimal input requires hexadecimal mode");
            }
            return text;
        }
        String hexadecimal = addHexadecimalPrefix(text);
        if ((type == TagType.FLOAT || type == TagType.DOUBLE)
                && hexadecimal.indexOf('p') < 0
                && hexadecimal.indexOf('P') < 0) {
            return hexadecimal + "p0";
        }
        return hexadecimal;
    }

    /// Adds `0x` after an optional sign unless the token already has that prefix.
    ///
    /// @param input trimmed numeric token
    /// @return hexadecimal-prefixed token
    private static String addHexadecimalPrefix(String input) {
        int signLength = input.startsWith("+") || input.startsWith("-") ? 1 : 0;
        if (hasHexadecimalPrefix(input)) {
            return input;
        }
        return input.substring(0, signLength) + "0x" + input.substring(signLength);
    }

    /// Returns whether an optional sign is followed by Java's hexadecimal prefix.
    ///
    /// @param input trimmed numeric input
    /// @return whether the input has a hexadecimal prefix
    private static boolean hasHexadecimalPrefix(String input) {
        int signLength = input.startsWith("+") || input.startsWith("-") ? 1 : 0;
        return input.length() >= signLength + 2 && input.regionMatches(true, signLength, "0x", 0, 2);
    }

    /// Formats a non-negative value using an uppercase hexadecimal prefix.
    ///
    /// @param value unsigned byte or short value
    /// @return hexadecimal token
    private static String hexadecimal(int value) {
        return "0x" + Integer.toHexString(value).toUpperCase(Locale.ROOT);
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
