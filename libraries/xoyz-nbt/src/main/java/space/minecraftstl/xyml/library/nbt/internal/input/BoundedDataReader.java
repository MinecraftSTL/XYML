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
import org.jetbrains.annotations.Nullable;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

/// Reader constrained to one encoded payload boundary.
@NotNullByDefault
public abstract non-sealed class BoundedDataReader extends DataReader {
    private final RawDataReader rawReader;
    private final InputBuffer buffer;
    protected final long endPosition;

    public BoundedDataReader(RawDataReader rawReader, InputBuffer buffer, long limit) {
        this.rawReader = rawReader;
        this.buffer = buffer;
        if (limit < 0) {
            this.endPosition = -1L;
        } else {
            try {
                this.endPosition = Math.addExact(rawReader.position(), limit);
            } catch (ArithmeticException exception) {
                throw new IllegalArgumentException("Bounded reader position overflows", exception);
            }
        }
    }

    @Override
    protected final RawDataReader getRawReader() {
        return rawReader;
    }

    @Override
    public InputBuffer getBuffer() {
        return buffer;
    }

    @Override
    public void close() throws IOException {
        if (endPosition >= 0) {
            long currentPosition = rawReader.position();
            if (currentPosition == endPosition) {
                return;
            }

            if (currentPosition > endPosition) {
                throw new IOException("Limit exceeded");
            }

            try {
                getRawReader().skip(Math.subtractExact(endPosition, currentPosition));
            } catch (ArithmeticException overflow) {
                throw new IOException("Bounded reader position overflows", overflow);
            }
        }
    }

    /// Requires the bounded payload to have been consumed exactly.
    public final void requireFullyConsumed() throws IOException {
        if (endPosition >= 0) {
            long currentPosition = rawReader.position();
            if (currentPosition != endPosition) {
                throw new IOException("Trailing or truncated bounded payload: expected position "
                        + endPosition + ", got " + currentPosition);
            }
        }
    }

    protected final InputStream asInputStream() {
        return new InputStream() {
            private byte @Nullable [] singleByte;

            @Override
            public int read() throws IOException {
                if (singleByte == null) {
                    singleByte = new byte[1];
                }

                if (read(singleByte) < 1) {
                    return -1;
                } else {
                    return Byte.toUnsignedInt(singleByte[0]);
                }
            }

            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                Objects.checkFromIndexSize(off, len, b.length);
                if (len == 0) {
                    return 0;
                }

                long rawRemaining = remainingRawBytes();
                if (rawRemaining <= 0) {
                    return -1;
                }

                if (rawReader.getBuffer().remaining() == 0) {
                    try {
                        rawReader.ensureBufferRemaining(1);
                    } catch (EOFException e) {
                        return -1;
                    }
                }

                int n = (int) Math.min(Math.min(len, rawReader.getBuffer().remaining()), rawRemaining);
                rawReader.getBuffer().getBytes(b, off, n);
                return n;
            }

        };
    }

    /// Returns the number of decoded bytes consumed by this bounded reader when available.
    ///
    /// Concrete decompression readers count bytes emitted by their decoder; the raw reader uses
    /// the number of bytes consumed from its bounded source. A negative value means that the
    /// reader does not expose a decoded-byte counter.
    long decodedBytes() {
        return -1L;
    }

    /// Returns the number of raw bytes remaining in the bounded source.
    ///
    /// @return remaining bytes, or {@link Long#MAX_VALUE} for an unbounded source
    /// @throws IOException if the source position has passed the bound or arithmetic overflows
    protected final long remainingRawBytes() throws IOException {
        if (endPosition < 0) {
            return Long.MAX_VALUE;
        }
        long currentPosition = rawReader.position();
        if (currentPosition > endPosition) {
            throw new IOException("Bounded reader position exceeds its limit");
        }
        try {
            return Math.subtractExact(endPosition, currentPosition);
        } catch (ArithmeticException overflow) {
            throw new IOException("Bounded reader position overflows", overflow);
        }
    }
}
