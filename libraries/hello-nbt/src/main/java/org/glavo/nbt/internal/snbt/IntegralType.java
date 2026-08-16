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

/// Integral types for SNBT.
enum IntegralType {
    BYTE(Byte.MAX_VALUE, Byte.MIN_VALUE),
    SHORT(Short.MAX_VALUE, Short.MIN_VALUE),
    INT(Integer.MAX_VALUE, Integer.MIN_VALUE),
    LONG(Long.MAX_VALUE, Long.MIN_VALUE);

    final long maxSigned;
    final long maxUnsigned;
    final long min;

    IntegralType(long maxSigned, long min) {
        this.maxSigned = maxSigned;
        this.maxUnsigned = maxSigned == Long.MAX_VALUE ? Long.MAX_VALUE : maxSigned - min;
        this.min = min;
    }

    void check(long value, boolean unsigned) {
        long max = unsigned ? maxUnsigned : maxSigned;
        if (value < min || value > max) {
            throw new IllegalArgumentException("Value out of range: " + value + " (min: " + min + ", max: " + max + ")");
        }
    }
}
