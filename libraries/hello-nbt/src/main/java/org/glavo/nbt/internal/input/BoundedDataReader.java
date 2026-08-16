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
package org.glavo.nbt.internal.input;

import org.jetbrains.annotations.Nullable;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

public abstract non-sealed class BoundedDataReader extends DataReader {
    private final RawDataReader rawReader;
    private final InputBuffer buffer;
    protected final long endPosition;

    public BoundedDataReader(RawDataReader rawReader, InputBuffer buffer, long limit) {
        this.rawReader = rawReader;
        this.buffer = buffer;
        this.endPosition = limit >= 0 ? rawReader.position() + limit : -1L;
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

            getRawReader().skip(endPosition - currentPosition);
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

                long rawRemaining = endPosition >= 0 ? endPosition - rawReader.position() : Long.MAX_VALUE;
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
}
