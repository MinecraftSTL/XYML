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
package org.glavo.nbt.io;

import org.glavo.nbt.tag.StringTag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class QuoteStrategyTest {
    private static String toSNBT(QuoteStrategy strategy, String value) {
        return SNBTCodec.ofCompact()
                .withValueQuoteStrategy(strategy)
                .toString(new StringTag(value));
    }

    @Test
    void testAlways() {
        QuoteStrategy doubleQuote = QuoteStrategy.always();
        QuoteStrategy singleQuote = QuoteStrategy.always('\'');

        assertSame(doubleQuote, QuoteStrategy.defaultValueStrategy());
        assertSame(doubleQuote, QuoteStrategy.always('"'));
        assertSame(doubleQuote, SNBTCodec.of().getValueQuoteStrategy());
        assertSame(doubleQuote, SNBTCodec.ofCompact().getValueQuoteStrategy());
        assertNotSame(doubleQuote, singleQuote);

        assertEquals('"', doubleQuote.getQuoteChar(""));
        assertEquals('"', doubleQuote.getQuoteChar("Hello"));
        assertEquals('"', doubleQuote.getQuoteChar("1abc"));
        assertEquals('"', doubleQuote.getQuoteChar("has space"));
        assertEquals('"', doubleQuote.getQuoteChar("a\"b'c"));

        assertEquals('\'', singleQuote.getQuoteChar(""));
        assertEquals('\'', singleQuote.getQuoteChar("Hello"));
        assertEquals('\'', singleQuote.getQuoteChar("1abc"));
        assertEquals('\'', singleQuote.getQuoteChar("has space"));
        assertEquals('\'', singleQuote.getQuoteChar("a\"b'c"));

        assertEquals("\"Hello\"", toSNBT(doubleQuote, "Hello"));
        assertEquals("'a\\'b'", toSNBT(singleQuote, "a'b"));

        assertThrows(IllegalArgumentException.class, () -> QuoteStrategy.always('x'));
        assertThrows(IllegalArgumentException.class, () -> QuoteStrategy.always('`'));
    }

    @Test
    void testWhenNeeded() {
        QuoteStrategy doubleQuote = QuoteStrategy.whenNeeded();
        QuoteStrategy singleQuote = QuoteStrategy.whenNeeded('\'');

        assertSame(doubleQuote, QuoteStrategy.defaultNameStrategy());
        assertSame(doubleQuote, QuoteStrategy.whenNeeded('"'));
        assertSame(doubleQuote, SNBTCodec.of().getNameQuoteStrategy());
        assertSame(doubleQuote, SNBTCodec.ofCompact().getNameQuoteStrategy());
        assertNotSame(doubleQuote, singleQuote);

        assertEquals('"', doubleQuote.getQuoteChar(""));
        assertEquals('\0', doubleQuote.getQuoteChar("Hello"));
        assertEquals('\0', doubleQuote.getQuoteChar("_name"));
        assertEquals('\0', doubleQuote.getQuoteChar("a1"));
        assertEquals('\0', doubleQuote.getQuoteChar("a+b-c"));
        assertEquals('"', doubleQuote.getQuoteChar("1abc"));
        assertEquals('"', doubleQuote.getQuoteChar("has space"));
        assertEquals('"', doubleQuote.getQuoteChar("a:b"));
        assertEquals('"', doubleQuote.getQuoteChar("你好"));
        assertEquals('"', doubleQuote.getQuoteChar("a\"b"));
        assertEquals('"', doubleQuote.getQuoteChar("a'b"));

        assertEquals('\'', singleQuote.getQuoteChar(""));
        assertEquals('\0', singleQuote.getQuoteChar("Hello"));
        assertEquals('\'', singleQuote.getQuoteChar("1abc"));
        assertEquals('\'', singleQuote.getQuoteChar("has space"));
        assertEquals('\'', singleQuote.getQuoteChar("a\"b"));
        assertEquals('\'', singleQuote.getQuoteChar("a'b"));

        assertEquals("Hello", toSNBT(doubleQuote, "Hello"));
        assertEquals("\"1abc\"", toSNBT(doubleQuote, "1abc"));
        assertEquals("'a\\'b'", toSNBT(singleQuote, "a'b"));

        assertThrows(IllegalArgumentException.class, () -> QuoteStrategy.whenNeeded('x'));
        assertThrows(IllegalArgumentException.class, () -> QuoteStrategy.whenNeeded('`'));
    }

    @Test
    void testSmart() {
        QuoteStrategy quotedByDefault = QuoteStrategy.smart(true, '"');
        QuoteStrategy unquotedWhenPossible = QuoteStrategy.smart(false, '"');
        QuoteStrategy singlePreferred = QuoteStrategy.smart(false, '\'');

        assertEquals('"', quotedByDefault.getQuoteChar(""));
        assertEquals('"', quotedByDefault.getQuoteChar("Hello"));
        assertEquals('\'', quotedByDefault.getQuoteChar("a\"b"));
        assertEquals('"', quotedByDefault.getQuoteChar("a'b"));
        assertEquals('"', quotedByDefault.getQuoteChar("a\"b'c"));

        assertEquals('"', unquotedWhenPossible.getQuoteChar(""));
        assertEquals('\0', unquotedWhenPossible.getQuoteChar("Hello"));
        assertEquals('\0', unquotedWhenPossible.getQuoteChar("a+b-c"));
        assertEquals('"', unquotedWhenPossible.getQuoteChar("1abc"));
        assertEquals('"', unquotedWhenPossible.getQuoteChar("has space"));
        assertEquals('"', unquotedWhenPossible.getQuoteChar("a:b"));
        assertEquals('\'', unquotedWhenPossible.getQuoteChar("a\"b"));
        assertEquals('"', unquotedWhenPossible.getQuoteChar("a'b"));
        assertEquals('"', unquotedWhenPossible.getQuoteChar("a\"b'c"));

        assertEquals('\0', singlePreferred.getQuoteChar("Hello"));
        assertEquals('"', singlePreferred.getQuoteChar("a'b"));
        assertEquals('\'', singlePreferred.getQuoteChar("a\"b"));

        assertEquals("\"Hello\"", toSNBT(quotedByDefault, "Hello"));
        assertEquals("'a\"b'", toSNBT(unquotedWhenPossible, "a\"b"));
        assertEquals("\"a'b\"", toSNBT(unquotedWhenPossible, "a'b"));
        assertEquals("Hello", toSNBT(unquotedWhenPossible, "Hello"));

        assertThrows(IllegalArgumentException.class, () -> QuoteStrategy.smart(true, 'x'));
        assertThrows(IllegalArgumentException.class, () -> QuoteStrategy.smart(false, '`'));
    }
}
