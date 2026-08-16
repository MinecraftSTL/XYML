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

final class EscapeStrategyTest {
    private static String toSNBT(EscapeStrategy strategy, char quoteChar, String value) {
        return SNBTCodec.ofCompact()
                .withEscapeStrategy(strategy)
                .withValueQuoteStrategy(QuoteStrategy.always(quoteChar))
                .toString(new StringTag(value));
    }

    @Test
    void testDefaultStrategy() {
        EscapeStrategy strategy = EscapeStrategy.defaultStrategy();
        String value = "\0\b\t\n\f\r\\\"'ABC你好世界😄";

        assertSame(strategy, SNBTCodec.of().getEscapeStrategy());
        assertSame(strategy, SNBTCodec.ofCompact().getEscapeStrategy());
        assertNotSame(strategy, EscapeStrategy.escapeNotAscii());

        assertEquals("\"\\u0000\\b\\t\\n\\f\\r\\\\\\\"'ABC你好世界\\U0001F604\"", toSNBT(strategy, '"', value));
        assertEquals("'\\u0000\\b\\t\\n\\f\\r\\\\\"\\'ABC你好世界\\U0001F604'", toSNBT(strategy, '\'', value));
    }

    @Test
    @SuppressWarnings("DataFlowIssue")
    void testEscapeNotAscii() {
        EscapeStrategy strategy = EscapeStrategy.escapeNotAscii();
        String value = "\0\b\t\n\f\r\\\"'ABC你好世界😄";

        assertSame(strategy, SNBTCodec.ofCompact().withEscapeStrategy(strategy).getEscapeStrategy());
        assertThrows(NullPointerException.class, () -> SNBTCodec.ofCompact().withEscapeStrategy(null));

        assertEquals("\"\\u0000\\b\\t\\n\\f\\r\\\\\\\"'ABC\\u4F60\\u597D\\u4E16\\u754C\\U0001F604\"", toSNBT(strategy, '"', value));
        assertEquals("'\\u0000\\b\\t\\n\\f\\r\\\\\"\\'ABC\\u4F60\\u597D\\u4E16\\u754C\\U0001F604'", toSNBT(strategy, '\'', value));
    }
}
