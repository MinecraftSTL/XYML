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

import org.glavo.nbt.internal.TextUtils;
import org.glavo.nbt.tag.*;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

import static org.glavo.nbt.internal.snbt.IntegralType.*;

sealed interface Token {
    enum SimpleToken implements Token {
        LEFT_PARENTHESES,   // (
        RIGHT_PARENTHESES,  // )
        LEFT_BRACE,         // {
        RIGHT_BRACE,        // }
        LEFT_BRACKET,       // [
        RIGHT_BRACKET,      // ]
        COMMA,              // ,
        COLON,              // :
        DOT,                // .
        EOF
    }

    enum ArrayBeginToken implements Token {
        BYTE_ARRAY,
        INT_ARRAY,
        LONG_ARRAY,
    }

    enum BooleanToken implements Token {
        TRUE(true),
        FALSE(false);

        public final boolean value;

        BooleanToken(boolean value) {
            this.value = value;
        }
    }

    record StringToken(String value, boolean quoted) implements Token {
        public static final StringToken OP_UUID = new StringToken("uuid", false);
        public static final StringToken OP_BOOL = new StringToken("bool", false);
    }

    sealed interface NumberToken extends Token {
        private static IllegalArgumentException invalidNumberLiteral(CharSequence value, int beginIndex, int endIndex) {
            return new IllegalArgumentException("Invalid number literal: " + value.subSequence(beginIndex, endIndex));
        }

        private static void checkNotEmpty(int beginIndex, int endIndex, CharSequence rawValue, int rawBeginIndex, int rawEndIndex) {
            if (beginIndex >= endIndex) {
                throw invalidNumberLiteral(rawValue, rawBeginIndex, rawEndIndex);
            }
        }

        static NumberToken parse(CharSequence value) {
            return parse(value, 0, value.length());
        }

        static NumberToken parse(CharSequence value, int beginIndex, int endIndex) {
            Objects.checkFromToIndex(beginIndex, endIndex, value.length());

            CharSequence clean = value;
            int cleanBeginIndex = beginIndex;
            int cleanEndIndex = endIndex;

            checkNotEmpty(cleanBeginIndex, cleanEndIndex, value, beginIndex, endIndex);

            boolean negative = false;
            {
                char ch = clean.charAt(cleanBeginIndex);
                if (ch == '+' || ch == '-') {
                    negative = ch == '-';
                    cleanBeginIndex += 1;

                    checkNotEmpty(cleanBeginIndex, cleanEndIndex, value, beginIndex, endIndex);
                }
            }

            IntegralToken.Radix radix;
            if (TextUtils.startsWithIgnoreCase(clean, cleanBeginIndex, cleanEndIndex, "0x")) {
                radix = IntegralToken.Radix.HEX;
                cleanBeginIndex += 2;
            } else if (TextUtils.startsWithIgnoreCase(clean, cleanBeginIndex, cleanEndIndex, "0b")) {
                radix = IntegralToken.Radix.BINARY;
                cleanBeginIndex += 2;
            } else {
                radix = IntegralToken.Radix.DECIMAL;
            }

            checkNotEmpty(cleanBeginIndex, cleanEndIndex, value, beginIndex, endIndex);

            char lastChar = clean.charAt(cleanEndIndex - 1);
            if ((radix == IntegralToken.Radix.DECIMAL) && (
                    lastChar == 'f' || lastChar == 'F' || lastChar == 'd' || lastChar == 'D'
                            || TextUtils.indexOf(clean, cleanBeginIndex, cleanEndIndex, '.') >= 0
                            || TextUtils.indexOf(clean, cleanBeginIndex, cleanEndIndex, 'e') >= 0
                            || TextUtils.indexOf(clean, cleanBeginIndex, cleanEndIndex, 'E') >= 0)) {
                FloatingType floatingType;
                if (lastChar == 'f' || lastChar == 'F') {
                    floatingType = FloatingType.FLOAT;
                    cleanEndIndex -= 1;
                } else if (lastChar == 'd' || lastChar == 'D') {
                    floatingType = FloatingType.DOUBLE;
                    cleanEndIndex -= 1;
                } else {
                    floatingType = FloatingType.DOUBLE;
                }

                checkNotEmpty(cleanBeginIndex, cleanEndIndex, value, beginIndex, endIndex);

                if (clean.charAt(cleanBeginIndex) == '_' || clean.charAt(cleanEndIndex - 1) == '_') {
                    throw invalidNumberLiteral(value, beginIndex, endIndex);
                }

                try {
                    double doubleValue = Double.parseDouble(clean.subSequence(cleanBeginIndex, cleanEndIndex).toString().replace("_", ""));
                    if (!Double.isFinite(doubleValue)
                            || floatingType == FloatingType.FLOAT && (doubleValue < -Float.MAX_VALUE || doubleValue > Float.MAX_VALUE)) {
                        throw new IllegalArgumentException("Invalid floating point number: " + doubleValue);
                    }
                    if (negative) {
                        doubleValue = -doubleValue;
                    }

                    return new Token.FloatingToken(doubleValue, floatingType);
                } catch (Exception e) {
                    IllegalArgumentException e2 = invalidNumberLiteral(value, beginIndex, endIndex);
                    e2.initCause(e);
                    throw e2;
                }
            } else {
                IntegralToken.Suffix suffix = IntegralToken.Suffix.check(clean, cleanBeginIndex, cleanEndIndex, radix);
                cleanEndIndex -= suffix.value().length();

                checkNotEmpty(cleanBeginIndex, cleanEndIndex, value, beginIndex, endIndex);

                for (int i = cleanBeginIndex; i < cleanEndIndex; ) {
                    char ch = clean.charAt(i);
                    if (radix.isDigit(ch)) {
                        i++;
                    } else if (ch == '_') {
                        if (i == cleanBeginIndex || i == cleanEndIndex - 1) {
                            // Underscore cannot be the first or last character
                            throw invalidNumberLiteral(value, beginIndex, endIndex);
                        }

                        StringBuilder builder = new StringBuilder(cleanEndIndex - cleanBeginIndex);
                        builder.append(clean, cleanBeginIndex, i);
                        for (int j = i + 1; j < cleanEndIndex; j++) {
                            char ch2 = clean.charAt(j);
                            if (ch2 == '_') {
                                if (j == cleanBeginIndex - 1) {
                                    // Underscore cannot be the last character
                                    throw invalidNumberLiteral(value, beginIndex, endIndex);
                                }
                            } else if (radix.isDigit(ch2)) {
                                builder.append(ch2);
                            } else {
                                throw invalidNumberLiteral(value, beginIndex, endIndex);
                            }
                        }

                        if (builder.isEmpty()) {
                            throw invalidNumberLiteral(value, beginIndex, endIndex);
                        }

                        clean = builder.toString();
                        cleanBeginIndex = 0;
                        cleanEndIndex = clean.length();
                        break;
                    } else {
                        throw invalidNumberLiteral(value, beginIndex, endIndex);
                    }
                }

                boolean unsigned = suffix.unsigned != null ? suffix.unsigned : radix != IntegralToken.Radix.DECIMAL;

                try {
                    long parsed = Long.parseUnsignedLong(clean, cleanBeginIndex, cleanEndIndex, radix.value);
                    if (negative) {
                        parsed = -parsed;
                    }
                    suffix.type.check(parsed, unsigned);
                    return new Token.IntegralToken(parsed, suffix.type, unsigned);
                } catch (NumberFormatException e) {
                    IllegalArgumentException e2 = invalidNumberLiteral(value, beginIndex, endIndex);
                    e2.initCause(e);
                    throw e2;
                }
            }
        }

        ValueTag<? extends Number> toTag();
    }

    record IntegralToken(long value,
                         IntegralType type,
                         boolean unsigned) implements NumberToken {
        record Suffix(String value, IntegralType type, @Nullable Boolean unsigned) {
            private static final Suffix[] VALUES;

            static {
                IntegralType[] types = values();

                VALUES = new Suffix[types.length * 3];

                for (IntegralType type : types) {
                    String suffix = type.name().substring(0, 1);

                    //noinspection PointlessArithmeticExpression
                    VALUES[type.ordinal() * 3 + 0] = new Suffix(suffix.intern(), type, null);
                    VALUES[type.ordinal() * 3 + 1] = new Suffix((suffix + "S").intern(), type, false);
                    VALUES[type.ordinal() * 3 + 2] = new Suffix((suffix + "U").intern(), type, true);
                }
            }

            static final Suffix EMPTY = new Suffix("", INT, null);

            static Suffix of(IntegralType type, @Nullable Boolean unsigned) {
                return VALUES[type.ordinal() * 3 + (unsigned == null ? 0 : unsigned ? 2 : 1)];
            }

            static Suffix check(CharSequence cs, int beginIndex, int endIndex, Radix radix) {
                int len = endIndex - beginIndex;

                Boolean unsignedSuffixChar = len >= 2 ? switch (cs.charAt(endIndex - 2)) {
                    case 's', 'S' -> false;
                    case 'u', 'U' -> true;
                    default -> null;
                } : null;

                IntegralType typeSuffixChar = len >= 1 ? switch (cs.charAt(endIndex - 1)) {
                    case 'b', 'B' -> radix != Radix.HEX || unsignedSuffixChar != null ? BYTE : null;
                    case 's', 'S' -> SHORT;
                    case 'i', 'I' -> INT;
                    case 'l', 'L' -> LONG;
                    default -> null;
                } : null;

                if (typeSuffixChar == null && unsignedSuffixChar != null) {
                    // Illegal suffix
                    return EMPTY;
                }

                return typeSuffixChar != null ? of(typeSuffixChar, unsignedSuffixChar) : EMPTY;
            }
        }

        enum Radix {
            DECIMAL(10) {
                @Override
                boolean isDigit(int ch) {
                    return TextUtils.isAsciiDigit(ch);
                }
            },
            HEX(16) {
                @Override
                boolean isDigit(int ch) {
                    return TextUtils.isAsciiDigit(ch) || (ch >= 'a' && ch <= 'f') || (ch >= 'A' && ch <= 'F');
                }
            },
            BINARY(2) {
                @Override
                boolean isDigit(int ch) {
                    return ch == '0' || ch == '1';
                }
            };

            private final int value;

            Radix(int value) {
                this.value = value;
            }

            abstract boolean isDigit(int ch);
        }

        @Override
        public ValueTag<? extends Number> toTag() {
            return switch (type) {
                case BYTE -> new ByteTag((byte) value);
                case SHORT -> new ShortTag((short) value);
                case INT -> new IntTag((int) value);
                case LONG -> new LongTag(value);
            };
        }

    }

    record FloatingToken(double value, FloatingType type) implements NumberToken {
        @Override
        public ValueTag<? extends Number> toTag() {
            return switch (type) {
                case FLOAT -> new FloatTag((float) value);
                case DOUBLE -> new DoubleTag(value);
            };
        }
    }
}
