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
