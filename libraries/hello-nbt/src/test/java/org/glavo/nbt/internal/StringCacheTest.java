/*
 * Copyright 2026 Glavo
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.glavo.nbt.internal;

import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

public final class StringCacheTest {

    private static byte[] ascii(String value) {
        return value.getBytes(StandardCharsets.US_ASCII);
    }

    private static @Nullable String get(StringCache cache, byte[] bytes) {
        return cache.get(ByteBuffer.wrap(bytes), 0, bytes.length);
    }

    private static @Nullable String get(StringCache cache, byte[] bytes, int offset, int len) {
        return cache.get(ByteBuffer.wrap(bytes), offset, len);
    }

    @Test
    public void largeCacheLookupSupportsOffsetsAndPadding() {
        var strings = new ArrayList<String>();
        for (int i = 0; i < 10000; i++) {
            strings.add("str" + i);
        }

        var cache = new StringCache(strings.toArray(new String[0]));

        for (String string : strings) {
            byte[] bytes = ascii(string);

            assertSame(string, get(cache, bytes, 0, bytes.length));
            assertNull(get(cache, bytes, 1, bytes.length - 1));

            byte[] newBytes = new byte[bytes.length + 1];
            System.arraycopy(bytes, 0, newBytes, 1, bytes.length);
            assertSame(string, get(cache, newBytes, 1, bytes.length));

            Arrays.fill(newBytes, (byte) 0);
            System.arraycopy(bytes, 0, newBytes, 0, bytes.length);
            assertSame(string, get(cache, newBytes, 0, bytes.length));
        }
    }

    @Test
    public void returnsNullForUnknownTooLongAndNonAsciiStrings() {
        var cache = new StringCache("Data", "Version", "HelloNBT");

        assertNull(get(cache, ascii("ABC")));
        assertNull(get(cache, ascii("HelloNBT!")));
        assertNull(get(cache, "喵".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    public void supportsEmptyStringAndMaximumConfiguredLength() {
        String empty = "";
        String longest = "DataVersion";
        var cache = new StringCache(empty, "A", longest);

        assertSame(empty, get(cache, new byte[0]));
        assertSame(longest, get(cache, ascii(longest)));
        assertNull(get(cache, ascii(longest + "!")));
    }

    @Test
    public void handlesHashCollisionsWithoutFalsePositives() {
        String aaAa = new String("AaAa");
        String bbbb = new String("BBBB");
        String bbAa = new String("BBAa");
        var cache = new StringCache(aaAa, bbbb, bbAa);

        assertSame(aaAa, get(cache, ascii(aaAa)));
        assertSame(bbbb, get(cache, ascii(bbbb)));
        assertSame(bbAa, get(cache, ascii(bbAa)));
        assertNull(get(cache, ascii("AaBB")));
    }

    @Test
    public void readsFromDirectSlicedAndReadOnlyBuffers() {
        String value = "DataVersion";
        byte[] bytes = ascii(value);
        var cache = new StringCache(value);

        ByteBuffer direct = ByteBuffer.allocateDirect(bytes.length + 4);
        direct.put((byte) 1);
        direct.put((byte) 2);
        direct.put(bytes);
        direct.put((byte) 3);
        direct.put((byte) 4);

        assertSame(value, cache.get(direct, 2, bytes.length));

        ByteBuffer window = direct.duplicate();
        window.position(2);
        window.limit(2 + bytes.length);

        ByteBuffer slice = window.slice();
        assertSame(value, cache.get(slice, 0, bytes.length));
        assertSame(value, cache.get(slice.asReadOnlyBuffer(), 0, bytes.length));
    }

}
