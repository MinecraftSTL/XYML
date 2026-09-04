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
                String numeric = requiredNumber(text);
                rejectHexadecimalLiteral(numeric);
                float parsed = Float.parseFloat(numeric);
                if (!Float.isFinite(parsed)) {
                    throw new NumberFormatException("non-finite float");
                }
                result = new FloatTag(parsed);
            } else if (selectedType == TagType.DOUBLE) {
                String numeric = requiredNumber(text);
                rejectHexadecimalLiteral(numeric);
                double parsed = Double.parseDouble(numeric);
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
        String source = Objects.requireNonNull(input, "input");
        if (containsHexadecimalLiteral(source)) {
            throw new IOException("Hexadecimal numeric literals are not supported in the editor");
        }
        return SNBTCodec.of().readTag(source);
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

    /// Rejects Java hexadecimal literals in the decimal Add form.
    ///
    /// The Add dialog and structured value editor both use decimal-only numeric input and must not
    /// silently interpret a hexadecimal floating value.
    ///
    /// @param value trimmed numeric input
    /// @throws NumberFormatException when an optional sign is followed by `0x` or `0X`
    private static void rejectHexadecimalLiteral(String value) {
        String text = Objects.requireNonNull(value, "value");
        int offset = text.startsWith("+") || text.startsWith("-") ? 1 : 0;
        if (text.length() >= offset + 2 && text.regionMatches(true, offset, "0x", 0, 2)) {
            throw new NumberFormatException("hexadecimal input requires hexadecimal mode");
        }
    }

    /// Detects hexadecimal numeric tokens outside quoted SNBT strings.
    ///
    /// This presentation-layer restriction leaves the reusable SNBT codec unchanged while
    /// preventing the editor's subtree text field from silently accepting hexadecimal numbers.
    ///
    /// @param source complete SNBT source
    /// @return whether the source contains an unquoted hexadecimal numeric token
    private static boolean containsHexadecimalLiteral(String source) {
        boolean quoted = false;
        char quote = '\0';
        boolean escaped = false;
        for (int i = 0; i < source.length(); i++) {
            char character = source.charAt(i);
            if (quoted) {
                if (escaped) {
                    escaped = false;
                } else if (character == '\\') {
                    escaped = true;
                } else if (character == quote) {
                    quoted = false;
                }
                continue;
            }
            if (character == '\'' || character == '"') {
                quoted = true;
                quote = character;
                continue;
            }

            int tokenStart = character == '+' || character == '-' ? i + 1 : i;
            if (tokenStart + 1 < source.length()
                    && source.charAt(tokenStart) == '0'
                    && (source.charAt(tokenStart + 1) == 'x' || source.charAt(tokenStart + 1) == 'X')
                    && (i == 0 || !isUnquotedTokenPart(source.charAt(i - 1)))) {
                return true;
            }
        }
        return false;
    }

    /// Returns whether a character can continue an unquoted SNBT token.
    ///
    /// @param character source character
    /// @return whether the character is part of an unquoted token
    private static boolean isUnquotedTokenPart(char character) {
        return Character.isLetterOrDigit(character)
                || character == '_'
                || character == '-'
                || character == '+'
                || character == '.';
    }
}
