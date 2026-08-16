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

import java.util.Objects;

public final class TextUtils {

    /// Returns `true` if the specified character is an ASCII digit; `false` otherwise.
    public static boolean isAsciiDigit(int ch) {
        return ch >= '0' && ch <= '9';
    }

    /// Returns the index of the first occurrence of the specified character in the given character subsequence.
    public static int indexOf(CharSequence cs, int beginIndex, int endIndex, char ch) {
        Objects.checkFromToIndex(beginIndex, endIndex, cs.length());

        for (int i = beginIndex; i < endIndex; i++) {
            if (cs.charAt(i) == ch) {
                return i;
            }
        }
        return -1;
    }

    /// Returns `true` if the specified character subsequence starts with the specified prefix.
    public static boolean startsWithIgnoreCase(CharSequence cs, int beginIndex, int endIndex, String prefix) {
        Objects.checkFromToIndex(beginIndex, endIndex, cs.length());
        if (prefix.length() > endIndex - beginIndex) {
            return false;
        }
        for (int i = 0; i < prefix.length(); i++) {
            if (Character.toLowerCase(cs.charAt(beginIndex + i)) != Character.toLowerCase(prefix.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    public static long mutf8Length(String value) {
        long length = 0L;
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch >= 0x800) {
                length += 3L;
            } else if (ch >= 0x80 || ch == 0) {
                length += 2;
            } else {
                length += 1;
            }
        }
        return length;
    }

    public static long utf8Length(String value) {
        long length = 0L;

        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < 0x80) {
                length += 1L;
            } else if (c < 0x800) {
                length += 2L;
            } else if (Character.isSurrogate(c)) {
                int uc = -1;
                char c2;
                if (Character.isHighSurrogate(c)
                        && i < value.length() - 1
                        && Character.isLowSurrogate(c2 = value.charAt(i + 1))) {
                    uc = Character.toCodePoint(c, c2);
                }
                if (uc < 0) {
                    length += 1L;
                } else {
                    length += 4L;
                    // skip the second surrogate
                    i++;
                }
            } else {
                length += 3L;
            }
        }

        return length;
    }

    private TextUtils() {
    }
}
