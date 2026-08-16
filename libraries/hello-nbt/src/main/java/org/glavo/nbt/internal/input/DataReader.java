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

import org.glavo.nbt.io.MinecraftEdition;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

public sealed abstract class DataReader implements Closeable
        permits BoundedDataReader, RawDataReader {
    protected abstract RawDataReader getRawReader();

    protected abstract InputBuffer getBuffer();

    public abstract void ensureBufferRemaining(int required) throws IOException;

    @Override
    public abstract void close() throws IOException;

    public byte[] readByteArray(int len) throws IOException {
        if (len < 0 || len >= Integer.MAX_VALUE - 8) {
            throw new IOException("Array length too large");
        }

        ensureBufferRemaining(len);
        return getBuffer().getByteArray(len);
    }

    public int[] readIntArray(int len) throws IOException {
        if (len < 0 || len > Integer.MAX_VALUE / Integer.BYTES - 8) {
            throw new IOException("Array length too large");
        }

        ensureBufferRemaining(len * Integer.BYTES);
        return getBuffer().getIntArray(len);
    }

    public long[] readLongArray(int len) throws IOException {
        if (len < 0 || len > Integer.MAX_VALUE / Long.BYTES - 8) {
            throw new IOException("Array length too large");
        }

        ensureBufferRemaining(len * Long.BYTES);
        return getBuffer().getLongArray(len);
    }

    /// Read a byte from the input stream.
    public byte readByte() throws IOException {
        ensureBufferRemaining(Byte.BYTES);
        return getBuffer().getByte();
    }

    /// Read an unsigned byte from the input stream.
    public int readUnsignedByte() throws IOException {
        return Byte.toUnsignedInt(readByte());
    }

    /// Look ahead a byte from the input stream.
    public byte lookAheadByte() throws IOException {
        ensureBufferRemaining(Byte.BYTES);
        return getBuffer().lookAheadByte();
    }

    /// Read a short from the input stream.
    public short readShort() throws IOException {
        ensureBufferRemaining(Short.BYTES);
        return getBuffer().getShort();
    }

    /// Read an unsigned short from the input stream.
    public int readUnsignedShort() throws IOException {
        return Short.toUnsignedInt(readShort());
    }

    /// Read an int from the input stream.
    public int readInt() throws IOException {
        ensureBufferRemaining(Integer.BYTES);
        return getBuffer().getInt();
    }

    /// Read an unsigned int from the input stream.
    public long readUnsignedInt() throws IOException {
        return Integer.toUnsignedLong(readInt());
    }

    /// Read a long from the input stream.
    public long readLong() throws IOException {
        ensureBufferRemaining(Long.BYTES);
        return getBuffer().getLong();
    }

    /// Read a float from the input stream.
    public float readFloat() throws IOException {
        ensureBufferRemaining(Float.BYTES);
        return getBuffer().getFloat();
    }

    /// Read a double from the input stream.
    public double readDouble() throws IOException {
        ensureBufferRemaining(Double.BYTES);
        return getBuffer().getDouble();
    }

    private String getUTF8(ByteBuffer buffer, int offset, int length) {
        String cached = getRawReader().stringCache.get(buffer, offset, length);
        if (cached != null) {
            return cached;
        }

        if (buffer.hasArray() && !buffer.isReadOnly()) {
            return new String(buffer.array(), offset + buffer.arrayOffset(), length, StandardCharsets.UTF_8);
        } else {
            byte[] bytes = new byte[length];
            buffer.get(offset, bytes);
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

    /// Read a string from the input stream.
    ///
    /// For big-endian byte order, the string is encoded in [modified UTF-8](https://en.wikipedia.org/wiki/UTF-8#Modified_UTF-8);
    /// For little-endian byte order, the string is encoded in standard UTF-8.
    public String readString() throws IOException {
        int len = readUnsignedShort();

        if (len == 0) {
            return "";
        }

        ensureBufferRemaining(len);

        ByteBuffer bytes = getBuffer().getByteBuffer();
        int offset = bytes.position();
        int limit = offset + len;

        bytes.position(limit);

        // For Minecraft Bedrock Edition, the string is encoded in standard UTF-8
        if (getRawReader().edition == MinecraftEdition.BEDROCK_EDITION) {
            return getUTF8(bytes, offset, len);
        }

        // Scan the number of ASCII characters in the prefix
        int asciiLen = 0;
        for (int i = offset; i < limit; i++) {
            if (bytes.get(i) > 0) {
                asciiLen++;
            } else {
                break;
            }
        }

        // If all characters are ASCII, return the string directly
        if (asciiLen == len) {
            return getUTF8(bytes, offset, asciiLen);
        }

        // Slow path
        StringBuilder charsBuffer;
        if (getRawReader().charsBuffer != null) {
            charsBuffer = getRawReader().charsBuffer;
            getRawReader().charsBuffer.setLength(0);
        } else {
            charsBuffer = new StringBuilder(len);
            getRawReader().charsBuffer = charsBuffer;
        }

        int c, char2, char3;
        int i = offset;

        while (i < limit) {
            c = (int) bytes.get(i) & 0xff;
            if (c > 127) break;
            i++;
            charsBuffer.append((char) c);
        }

        while (i < limit) {
            c = (int) bytes.get(i) & 0xff;
            switch (c >> 4) {
                case 0, 1, 2, 3, 4, 5, 6, 7 -> {
                    /* 0xxxxxxx*/
                    i++;
                    charsBuffer.append((char) c);
                }
                case 12, 13 -> {
                    /* 110x xxxx   10xx xxxx*/
                    i += 2;
                    if (i > limit)
                        throw new IllegalArgumentException("malformed input: partial character at end");
                    char2 = (int) bytes.get(i - 1) & 0xff;
                    if ((char2 & 0xC0) != 0x80)
                        throw new IllegalArgumentException("malformed input around byte " + (i - 1));
                    charsBuffer.append((char) (((c & 0x1F) << 6) |
                            (char2 & 0x3F)));
                }
                case 14 -> {
                    /* 1110 xxxx  10xx xxxx  10xx xxxx */
                    i += 3;
                    if (i > limit)
                        throw new IllegalArgumentException("malformed input: partial character at end");
                    char2 = bytes.get(i - 2);
                    char3 = bytes.get(i - 1);
                    if (((char2 & 0xC0) != 0x80) || ((char3 & 0xC0) != 0x80))
                        throw new IllegalArgumentException("malformed input around byte " + (i - 1));
                    charsBuffer.append((char) (((c & 0x0F) << 12) |
                            ((char2 & 0x3F) << 6) |
                            (char3 & 0x3F)));
                }
                default ->
                    /* 10xx xxxx,  1111 xxxx */
                        throw new IllegalArgumentException("malformed input around byte " + i);
            }
        }
        return charsBuffer.toString();
    }
}
