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
// Modified by MinecraftSTL in 2026 for the XYML namespace and monorepo build.
package space.minecraftstl.xyml.library.nbt.internal.input;

import org.jetbrains.annotations.NotNullByDefault;

import java.io.EOFException;
import java.io.IOException;

/// Bounded reader that exposes an uncompressed raw payload directly.
@NotNullByDefault
public final class UncompressedDataReader extends BoundedDataReader {
    /// Raw source position at construction, used for decoded-byte accounting.
    private final long startPosition;

    public UncompressedDataReader(RawDataReader rawReader, long limit) {
        super(rawReader, rawReader.getBuffer(), limit);
        startPosition = rawReader.position();
    }

    @Override
    public void ensureBufferRemaining(int required) throws IOException {
        if (endPosition >= 0) {
            long remainingInput = remainingRawBytes();

            if (remainingInput < required) {
                throw new EOFException("Not enough data to read, required: " + required + ", remaining: " + remainingInput);
            }
        }

        getRawReader().ensureBufferRemaining(required);
    }

    /// {@inheritDoc}
    @Override
    long decodedBytes() {
        try {
            return Math.subtractExact(getRawReader().position(), startPosition);
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
    }
}
