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
import org.junit.jupiter.api.Test;
import space.minecraftstl.xyml.library.nbt.tag.ByteArrayTag;
import space.minecraftstl.xyml.library.nbt.tag.CompoundTag;
import space.minecraftstl.xyml.library.nbt.tag.ListTag;
import space.minecraftstl.xyml.library.nbt.tag.StringTag;
import space.minecraftstl.xyml.library.nbt.tag.Tag;
import space.minecraftstl.xyml.library.nbt.tag.TagType;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies strict structured-form and full-subtree input conversion.
@NotNullByDefault
final class NBTTagInputTest {
    /// Creates every standard non-END type without implicit conversion.
    @Test
    void createsEverySelectableStandardType() throws Exception {
        List<TagType<?>> expectedTypes = List.of(
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
        List<String> values = List.of(
                "-128",
                "32767",
                "2147483647",
                "-9223372036854775808",
                "1.5",
                "-2.25",
                "[B;-128,0,127]",
                "unquoted structured text",
                "[1,2,3]",
                "{answer:42}",
                "[I;-2147483648,0,2147483647]",
                "[L;-9223372036854775808L,0L,9223372036854775807L]");

        assertEquals(expectedTypes, NBTTagInput.types());
        for (int index = 0; index < expectedTypes.size(); index++) {
            Tag tag = NBTTagInput.create(expectedTypes.get(index), "sample", values.get(index));
            assertSame(expectedTypes.get(index), tag.getType());
            assertEquals("sample", tag.getName());
        }
        assertEquals("unquoted structured text",
                assertInstanceOf(StringTag.class, NBTTagInput.create(TagType.STRING, "text", values.get(7)))
                        .getValue());
    }

    /// Allows empty structured containers while retaining an exact empty String value.
    @Test
    void createsIntentionalEmptyValues() throws Exception {
        assertEquals(0, assertInstanceOf(ByteArrayTag.class,
                NBTTagInput.create(TagType.BYTE_ARRAY, "bytes", " \t")).size());
        assertEquals(0, assertInstanceOf(ListTag.class,
                NBTTagInput.create(TagType.LIST, "list", "")).size());
        assertEquals(0, assertInstanceOf(CompoundTag.class,
                NBTTagInput.create(TagType.COMPOUND, "compound", "\n")).size());
        assertEquals("", assertInstanceOf(StringTag.class,
                NBTTagInput.create(TagType.STRING, "string", "")).getValue());
    }

    /// Rejects overflows, non-finite values, wrong explicit types, and trailing SNBT data.
    @Test
    void rejectsAmbiguousOrInvalidInputBeforeEditing() {
        assertThrows(IOException.class, () -> NBTTagInput.create(TagType.BYTE, "value", "128"));
        assertThrows(IOException.class, () -> NBTTagInput.create(TagType.SHORT, "value", "32768"));
        assertThrows(IOException.class, () -> NBTTagInput.create(TagType.INT, "value", "2147483648"));
        assertThrows(IOException.class,
                () -> NBTTagInput.create(TagType.LONG, "value", "9223372036854775808"));
        assertThrows(IOException.class, () -> NBTTagInput.create(TagType.FLOAT, "value", "NaN"));
        assertThrows(IOException.class, () -> NBTTagInput.create(TagType.DOUBLE, "value", "Infinity"));
        assertThrows(IOException.class, () -> NBTTagInput.create(TagType.FLOAT, "value", "0x1.0p2"));
        assertThrows(IOException.class, () -> NBTTagInput.create(TagType.DOUBLE, "value", "-0X1.0p2"));
        assertThrows(IOException.class, () -> NBTTagInput.create(TagType.INT, "value", ""));
        assertThrows(IOException.class, () -> NBTTagInput.create(TagType.LIST, "value", "{answer:42}"));
        assertThrows(IOException.class, () -> NBTTagInput.parseSnbt("{answer:42} trailing"));
    }

    /// Rejects hexadecimal numeric literals in editor SNBT while preserving quoted string content.
    @Test
    void rejectsHexadecimalSnbtNumbersOnlyOutsideStrings() throws Exception {
        assertThrows(IOException.class, () -> NBTTagInput.parseSnbt("{answer:0x2A}"));
        assertThrows(IOException.class, () -> NBTTagInput.parseSnbt("[B;0X2A]"));
        assertEquals("0x2A", assertInstanceOf(StringTag.class,
                NBTTagInput.parseSnbt("\"0x2A\"")).getValue());
    }

    /// Produces complete SNBT that can be parsed back as the same selected type.
    @Test
    void roundTripsSubtreeSnbt() throws Exception {
        Tag original = NBTTagInput.create(TagType.COMPOUND, "root", "{nested:[1,2,3]}");
        Tag decoded = NBTTagInput.parseSnbt(NBTTagInput.toSnbt(original));

        assertSame(TagType.COMPOUND, decoded.getType());
        assertTrue(NBTTagInput.toSnbt(decoded).contains("nested"));
    }
}
