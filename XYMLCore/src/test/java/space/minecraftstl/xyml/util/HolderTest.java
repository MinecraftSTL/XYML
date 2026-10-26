// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.util;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/// Tests the framework-neutral mutable value contract of [Holder].
@NotNullByDefault
public final class HolderTest {
    /// Verifies that an empty holder starts at `null` and accepts a later value.
    @Test
    public void storesMutableValue() {
        Holder<String> holder = new Holder<>();

        assertNull(holder.value);
        holder.value = "value";

        assertEquals("value", holder.value);
    }

    /// Verifies value-based equality, hashing, and diagnostic rendering.
    @Test
    public void exposesValueObjectContract() {
        Holder<String> first = new Holder<>("value");
        Holder<String> second = new Holder<>("value");
        Holder<String> different = new Holder<>("other");

        assertEquals(first, second);
        assertEquals(first.hashCode(), second.hashCode());
        assertNotEquals(first, different);
        assertNotEquals(first, "value");
        assertEquals("Holder[value]", first.toString());
    }

    /// Verifies that the generic holder does not implement framework listener interfaces.
    @Test
    public void hasNoFrameworkInterfaces() {
        assertEquals(0, Holder.class.getInterfaces().length);
    }
}
