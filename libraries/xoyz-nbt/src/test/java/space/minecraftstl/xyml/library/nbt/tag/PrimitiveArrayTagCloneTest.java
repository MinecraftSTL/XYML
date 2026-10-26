// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

// Added by MinecraftSTL in 2026 for XoyzNBT clone regression coverage.
package space.minecraftstl.xyml.library.nbt.tag;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/// Verifies primitive array tags preserve their logical contents when cloned.
@NotNullByDefault
public final class PrimitiveArrayTagCloneTest {
    /// Verifies a non-empty byte array clone is detached and independently mutable.
    @Test
    void byteArrayClonePreservesLogicalLength() {
        ByteArrayTag original = new ByteArrayTag(new byte[]{1, 2, 3}).setName("bytes");
        ByteTag originalChild = original.getTag(1);

        ByteArrayTag copy = original.clone();

        assertEquals("bytes", copy.getName());
        assertEquals(3, copy.size());
        assertArrayEquals(new byte[]{1, 2, 3}, copy.getArray());
        assertNull(copy.getParent());
        assertNotSame(originalChild, copy.getTag(1));
        assertSame(copy, copy.getTag(1).getParent());
        assertEquals(1, copy.getTag(1).getIndex());

        copy.set(1, (byte) 9);
        assertArrayEquals(new byte[]{1, 2, 3}, original.getArray());
    }

    /// Verifies a non-empty int array clone is detached and independently mutable.
    @Test
    void intArrayClonePreservesLogicalLength() {
        IntArrayTag original = new IntArrayTag(new int[]{4, 5, 6}).setName("ints");
        IntTag originalChild = original.getTag(1);

        IntArrayTag copy = original.clone();

        assertEquals("ints", copy.getName());
        assertEquals(3, copy.size());
        assertArrayEquals(new int[]{4, 5, 6}, copy.getArray());
        assertNull(copy.getParent());
        assertNotSame(originalChild, copy.getTag(1));
        assertSame(copy, copy.getTag(1).getParent());
        assertEquals(1, copy.getTag(1).getIndex());

        copy.set(1, 9);
        assertArrayEquals(new int[]{4, 5, 6}, original.getArray());
    }

    /// Verifies a non-empty long array clone is detached and independently mutable.
    @Test
    void longArrayClonePreservesLogicalLength() {
        LongArrayTag original = new LongArrayTag(new long[]{7L, 8L, 9L}).setName("longs");
        LongTag originalChild = original.getTag(1);

        LongArrayTag copy = original.clone();

        assertEquals("longs", copy.getName());
        assertEquals(3, copy.size());
        assertArrayEquals(new long[]{7L, 8L, 9L}, copy.getArray());
        assertNull(copy.getParent());
        assertNotSame(originalChild, copy.getTag(1));
        assertSame(copy, copy.getTag(1).getParent());
        assertEquals(1, copy.getTag(1).getIndex());

        copy.set(1, 10L);
        assertArrayEquals(new long[]{7L, 8L, 9L}, original.getArray());
    }
}
