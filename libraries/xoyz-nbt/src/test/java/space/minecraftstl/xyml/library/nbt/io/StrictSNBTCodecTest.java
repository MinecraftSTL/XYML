// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

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
