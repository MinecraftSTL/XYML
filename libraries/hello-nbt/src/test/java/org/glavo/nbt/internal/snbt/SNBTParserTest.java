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
package org.glavo.nbt.internal.snbt;

import org.glavo.nbt.internal.snbt.Token.IntegralToken;
import org.glavo.nbt.internal.snbt.Token.SimpleToken;
import org.glavo.nbt.internal.snbt.Token.StringToken;
import org.glavo.nbt.tag.*;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.glavo.nbt.internal.snbt.FloatingType.DOUBLE;
import static org.glavo.nbt.internal.snbt.FloatingType.FLOAT;
import static org.glavo.nbt.internal.snbt.IntegralType.*;
import static org.glavo.nbt.internal.snbt.Token.SimpleToken.*;
import static org.junit.jupiter.api.Assertions.*;

final class SNBTParserTest {
    private static void assertTokens(List<Token> expected, String input) {
        SNBTParser parser = new SNBTParser(input, 0, input.length());

        List<Token> actual = new ArrayList<>();

        Token token;
        while ((token = parser.readNextToken()) != SimpleToken.EOF) {
            actual.add(token);
        }

        assertIterableEquals(expected, actual, () -> {
            var builder = new StringBuilder();

            builder.append("Expected: ");
            for (int i = 0; i < expected.size(); i++) {
                builder.append('\n').append(i).append(": ").append(expected.get(i));
            }
            builder.append("\nActual: ");
            for (int i = 0; i < actual.size(); i++) {
                builder.append('\n').append(i).append(": ").append(actual.get(i));
            }
            builder.append('\n');
            return builder.toString();
        });

        assertEquals(input.length(), parser.cursor);
    }

    @Test
    void testReadNextToken() {
        assertTokens(List.of(), "");
        assertTokens(List.of(
                LEFT_BRACE,
                new StringToken("byte", false), COLON, new IntegralToken(127L, BYTE, false),
                COMMA,
                new StringToken("short", false), COLON, new IntegralToken(-32768L, SHORT, false),
                COMMA,
                new StringToken("int", false), COLON, new IntegralToken(2147483647L, INT, false),
                COMMA,
                new StringToken("long", false), COLON, new IntegralToken(9223372036854775807L, LONG, false),
                COMMA,
                new StringToken("float", false), COLON, new FloatingToken(3.1415927, FLOAT),
                COMMA,
                new StringToken("double", false), COLON, new FloatingToken(2.718281828459045, DOUBLE),
                COMMA,
                new StringToken("string", false), COLON, new StringToken("Hello, \"quoted\" and \\escaped\\ and 😄! \b\f\n\r \tA\u55b5😄😄", true),
                COMMA,
                new StringToken("byte_array", false), COLON, ArrayBeginToken.BYTE_ARRAY, new IntegralToken(-128L, INT, false), COMMA, new IntegralToken(0L, INT, false), COMMA, new IntegralToken(127L, INT, false), RIGHT_BRACKET,
                COMMA,
                new StringToken("int_array", false), COLON, ArrayBeginToken.INT_ARRAY, new IntegralToken(-2147483648L, INT, false), COMMA, new IntegralToken(0L, INT, false), COMMA, new IntegralToken(2147483647L, INT, false), RIGHT_BRACKET,
                COMMA,
                new StringToken("long_array", false), COLON, ArrayBeginToken.LONG_ARRAY, new IntegralToken(-9223372036854775808L, LONG, false), COMMA, new IntegralToken(0L, LONG, false), COMMA, new IntegralToken(9223372036854775807L, LONG, false), RIGHT_BRACKET,
                COMMA,
                new StringToken("list_of_strings", false), COLON, LEFT_BRACKET, new StringToken("a", true), COMMA, new StringToken("bb", true), COMMA, new StringToken("ccc", false), RIGHT_BRACKET,
                COMMA,
                new StringToken("nested", false), COLON, LEFT_BRACE, new StringToken("very", false), COLON, LEFT_BRACE, new StringToken("deep", false), COLON, LEFT_BRACE, new StringToken("structure", false), COLON, new StringToken("ok", true), RIGHT_BRACE, RIGHT_BRACE, RIGHT_BRACE,
                RIGHT_BRACE
        ), """
                {
                  byte: 127b,
                  short: -32768s,
                  int: 2147483647,
                  long: 9223372036854775807L,
                  float: 3.1415927f,
                  double: 2.718281828459045d,
                  string: "Hello, \\"quoted\\" and \\\\escaped\\\\ and 😄! \\b\\f\\n\\r\\s\\t\\x41\\u55b5\\U0001F604\\N{Smiling Face with Open Mouth and Smiling Eyes}",
                  byte_array: [B; -128, 0, 127],
                  int_array: [I; -2147483648, 0, 2147483647],
                  long_array: [L; -9223372036854775808L, 0L, 9223372036854775807L],
                  list_of_strings: ["a", "bb", ccc],
                  nested: {very:{deep:{structure:"ok"}}}
                }""");
    }

    private static Tag parseTag(String input) {
        return new SNBTParser(input, 0, input.length()).nextTag();
    }

    @Test
    void testNextTag() {
        assertNull(parseTag(""));
        assertEquals(new ByteTag((byte) 123), parseTag("123b"));
        assertEquals(new ByteTag(true), parseTag("true"));
        assertEquals(new ByteTag(false), parseTag("false"));
        assertEquals(new ShortTag((short) 123), parseTag("123s"));
        assertEquals(new IntTag(123), parseTag("123"));
        assertEquals(new LongTag(123L), parseTag("123L"));
        assertEquals(new FloatTag(123.0f), parseTag("123.0f"));
        assertEquals(new DoubleTag(123.0), parseTag("123.0d"));
        assertEquals(new StringTag("Hello"), parseTag("Hello"));
        assertEquals(new StringTag("Hello"), parseTag("'Hello'"));
        assertEquals(new StringTag("Hello"), parseTag("\"Hello\""));
        assertEquals(new ByteArrayTag(), parseTag("[B;]"));
        assertEquals(new IntArrayTag(), parseTag("[I;]"));
        assertEquals(new LongArrayTag(), parseTag("[L;]"));
        assertEquals(new ByteArrayTag(new byte[]{1, 2, 3}), parseTag("[B; 1, 2, 3]"));
        assertEquals(new ByteArrayTag(new byte[]{1, 2, 3}), parseTag("[B; 1, 2, 3, ]"));
        assertEquals(new IntArrayTag(new int[]{1, 2, 3}), parseTag("[I; 1, 2, 3]"));
        assertEquals(new IntArrayTag(new int[]{1, 2, 3}), parseTag("[I; 1, 2, 3, ]"));
        assertEquals(new LongArrayTag(new long[]{1L, 2L, 3L}), parseTag("[L; 1L, 2L, 3L]"));
        assertEquals(new LongArrayTag(new long[]{1L, 2L, 3L}), parseTag("[L; 1L, 2L, 3L, ]"));

        assertEquals(new ListTag<>(), parseTag("[]"));
        assertEquals(new ListTag<>()
                        .addTag(new StringTag("Hello"))
                        .addTag(new StringTag("Glavo")),
                parseTag("[Hello, 'Glavo']"));
        assertEquals(new ListTag<>()
                        .addTag(new StringTag("Hello"))
                        .addTag(new StringTag("Glavo")),
                parseTag("[Hello, 'Glavo',]"));

        assertEquals(new CompoundTag(), parseTag("{}"));
        assertEquals(new CompoundTag()
                        .addString("name", "Glavo")
                        .addInt("age", 9)
                        .addUUID("id", UUID.fromString("01bb64c8-2a2f-4509-931b-366513bfb5a8"))
                        .addBoolean("bool", true)
                        .addTag("nested", new CompoundTag()
                                .addTag("very", new CompoundTag()
                                        .addTag("deep", new CompoundTag()
                                                .addString("structure", "ok")))),
                parseTag("""
                        {
                            name: 'Glavo',
                            age: 9,
                            id: uuid('01bb64c8-2a2f-4509-931b-366513bfb5a8'),
                            bool: true,
                            nested: {very:{deep:{structure:"ok"}}}
                        }"""));
    }
}
