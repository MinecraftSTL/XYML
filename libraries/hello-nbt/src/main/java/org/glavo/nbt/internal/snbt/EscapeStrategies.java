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

import org.glavo.nbt.io.EscapeStrategy;

import java.io.IOException;

public enum EscapeStrategies implements EscapeStrategy {
    DEFAULT {
        @Override
        public void appendString(Appendable appendable, char quoteChar, String value, int begin, int end) throws IOException {
            assert begin >= 0 && begin <= end && end <= value.length();

            for (int i = begin; i < end; ) {
                int cp = value.codePointAt(i);

                if (cp != '\\' && cp != quoteChar
                        && Character.isBmpCodePoint(cp) && !Character.isISOControl(cp)
                        && ((cp >= 0x20 && cp <= 0x7E) || Character.isJavaIdentifierPart(cp))) {
                    appendable.append((char) cp);
                } else {
                    appendEscaped(appendable, cp);
                }

                i += Character.charCount(cp);
            }
        }
    },

    NOT_ASCII {
        @Override
        public void appendString(Appendable appendable, char quoteChar, String value, int begin, int end) throws IOException {
            for (int i = begin; i < end; ) {
                int cp = value.codePointAt(i);

                if (cp != '\\' && cp >= 0x20 && cp <= 0x7E && cp != quoteChar) {
                    appendable.append((char) cp);
                } else {
                    appendEscaped(appendable, cp);
                }

                i += Character.charCount(cp);
            }
        }
    };

    void appendEscaped(Appendable appendable, int codePoint) throws IOException {
        switch (codePoint) {
            case '\\', '"', '\'' -> {
                appendable.append('\\');
                appendable.append((char) codePoint);
            }
            case '\b' -> appendable.append("\\b");
            case '\f' -> appendable.append("\\f");
            case '\n' -> appendable.append("\\n");
            case '\r' -> appendable.append("\\r");
            case '\t' -> appendable.append("\\t");
            default -> {
                if (Character.isBmpCodePoint(codePoint)) {
                    appendable.append("\\u%04X".formatted(codePoint));
                } else {
                    appendable.append("\\U%08X".formatted(codePoint));
                }
            }
        }
    }

    public abstract void appendString(Appendable appendable, char quoteChar, String value, int begin, int end) throws IOException;
}
