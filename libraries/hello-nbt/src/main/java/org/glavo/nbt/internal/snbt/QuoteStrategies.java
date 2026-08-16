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

import org.glavo.nbt.io.QuoteStrategy;

public final class QuoteStrategies {

    static char getAnotherQuoteChar(char quoteChar) {
        assert quoteChar == '"' || quoteChar == '\'';

        return quoteChar == '"' ? '\'' : '"';
    }

    static int scanSimplePrefix(String value) {
        assert !value.isEmpty();

        char c = value.charAt(0);

        if ((c < 'a' || c > 'z') && (c < 'A' || c > 'Z') && c != '_') {
            return 0;
        }

        for (int i = 1; i < value.length(); i++) {
            c = value.charAt(i);

            if ((c < 'a' || c > 'z') && (c < 'A' || c > 'Z') && (c < '0' || c > '9') && c != '_' && c != '+' && c != '-') {
                return i;
            }
        }

        return value.length();
    }

    public record Always(char quoteChar) implements QuoteStrategy {
        public static final Always DOUBLE_QUOTE = new Always('"');
        public static final Always SINGLE_QUOTE = new Always('\'');

        @Override
        public char getQuoteChar(String value) {
            return quoteChar;
        }
    }

    public record WhenNeeded(char quoteChar) implements QuoteStrategy {
        public static final WhenNeeded DOUBLE_QUOTE = new WhenNeeded('"');
        public static final WhenNeeded SINGLE_QUOTE = new WhenNeeded('\'');

        @Override
        public char getQuoteChar(String value) {
            if (value.isEmpty()) {
                return quoteChar;
            }

            int scanBegin = scanSimplePrefix(value);
            return scanBegin != value.length() ? quoteChar : '\0';
        }
    }

    public record Smart(boolean quoteByDefault, char preferredQuoteChar) implements QuoteStrategy {

        @Override
        public char getQuoteChar(String value) {
            if (value.isEmpty()) {
                return preferredQuoteChar;
            }

            int scanBegin = 0;
            if (!quoteByDefault) {
                scanBegin = scanSimplePrefix(value);
                if (scanBegin == value.length()) {
                    return '\0';
                }
            }

            char anotherQuoteChar = getAnotherQuoteChar(preferredQuoteChar);

            boolean containsPreferred = false;
            boolean containsAnother = false;

            for (int i = scanBegin; i < value.length(); i++) {
                char ch = value.charAt(i);
                if (ch == preferredQuoteChar) {
                    containsPreferred = true;
                } else if (ch == anotherQuoteChar) {
                    containsAnother = true;
                    break;
                }
            }

            if (!containsPreferred || containsAnother) {
                return preferredQuoteChar;
            } else {
                return anotherQuoteChar;
            }
        }
    }

    private QuoteStrategies() {
    }
}
