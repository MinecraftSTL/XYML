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
// Added by MinecraftSTL in 2026 for complete SNBT input coverage.
package space.minecraftstl.xyml.library.nbt.io;

import space.minecraftstl.xyml.library.nbt.tag.CompoundTag;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Verifies that public SNBT reads consume exactly one complete value.
@NotNullByDefault
public final class StrictSNBTCodecTest {
    /// Accepts insignificant trailing whitespace after one complete tag.
    @Test
    void acceptsOneTagWithTrailingWhitespace() throws IOException {
        CompoundTag tag = (CompoundTag) SNBTCodec.of().readTag("{answer:42}\r\n  ");

        assertEquals(42, tag.getInt("answer"));
    }

    /// Rejects a second value or token after one otherwise valid tag.
    @Test
    void rejectsTrailingSnbtData() {
        assertThrows(IOException.class, () -> SNBTCodec.of().readTag("{answer:42} {other:1}"));
        assertThrows(IOException.class, () -> SNBTCodec.of().readTag("{answer:42} trailing"));
        assertThrows(IOException.class, () -> SNBTCodec.of().readTag("1 2"));
    }

    /// Applies the same complete-consumption rule to an explicitly selected input range.
    @Test
    void consumesExactlyTheSelectedRange() throws IOException {
        String input = "prefix {answer:42} suffix";
        CompoundTag tag = (CompoundTag) SNBTCodec.of().readTag(input, 7, 18);

        assertEquals(42, tag.getInt("answer"));
        assertThrows(IOException.class, () -> SNBTCodec.of().readTag(input, 7, input.length()));
    }
}
