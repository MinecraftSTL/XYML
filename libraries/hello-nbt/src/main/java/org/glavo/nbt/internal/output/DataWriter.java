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
package org.glavo.nbt.internal.output;

import org.glavo.nbt.io.MinecraftEdition;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

public abstract class DataWriter implements Closeable, Flushable {
    public abstract MinecraftEdition getEdition();

    public abstract RawDataWriter getRawWriter();

    protected abstract OutputBuffer getBuffer();

    /// Flush the buffer and the underlying output target.
    @Override
    public void flush() throws IOException {
        flushBuffer();
    }

    /// Write the buffer to the underlying output target.
    ///
    /// After this method is called, the buffer will be empty.
    public abstract void flushBuffer() throws IOException;

    /// Flush the buffer if it has less than `required` bytes remaining.
    public void ensureBufferRemaining(int required) throws IOException {
        if (getBuffer().remaining() >= required) {
            return;
        }

        if (getBuffer().capacity() < required) {
            getBuffer().ensureCapacity(required);
        }

        if (getBuffer().remaining() < required) {
            flushBuffer();
            assert getBuffer().remaining() >= required;
        }
    }

    public void writeByteArrayDirect(byte[] value) throws IOException {
        ensureBufferRemaining(value.length);
        getBuffer().putByteArray(value);
    }

    public void writeByteArrayDirect(byte[] value, int offset, int length) throws IOException {
        ensureBufferRemaining(length);
        getBuffer().putByteArray(value, offset, length);
    }

    public void writeByteBuffer(ByteBuffer buffer) throws IOException {
        writeInt(buffer.remaining());
        writeByteBufferDirect(buffer);
    }

    public void writeByteBufferDirect(ByteBuffer buffer) throws IOException {
        ensureBufferRemaining(buffer.remaining());
        getBuffer().putByteBuffer(buffer);
    }

    public void writeIntArrayDirect(int[] value) throws IOException {
        ensureBufferRemaining(value.length * Integer.BYTES);
        getBuffer().putIntArray(value);
    }

    public void writeIntArrayDirect(int[] value, int offset, int length) throws IOException {
        ensureBufferRemaining(length * Integer.BYTES);
        getBuffer().putIntArray(value, offset, length);
    }

    public void writeLongArrayDirect(long[] value) throws IOException {
        ensureBufferRemaining(value.length * Long.BYTES);
        getBuffer().putLongArray(value);
    }

    public void writeLongArrayDirect(long[] value, int offset, int length) throws IOException {
        ensureBufferRemaining(length * Long.BYTES);
        getBuffer().putLongArray(value, offset, length);
    }

    public void writeByte(byte value) throws IOException {
        ensureBufferRemaining(Byte.BYTES);
        getBuffer().putByte(value);
    }

    public void writeUnsignedByte(int value) throws IOException {
        ensureBufferRemaining(Byte.BYTES);
        getBuffer().putByte((byte) value);
    }

    public void writeShort(short value) throws IOException {
        ensureBufferRemaining(Short.BYTES);
        getBuffer().putShort(value);
    }

    public void writeUnsignedShort(int value) throws IOException {
        ensureBufferRemaining(Short.BYTES);
        getBuffer().putShort((short) value);
    }

    public void writeInt(int value) throws IOException {
        ensureBufferRemaining(Integer.BYTES);
        getBuffer().putInt(value);
    }

    public void writeUnsignedInt(long value) throws IOException {
        ensureBufferRemaining(Integer.BYTES);
        getBuffer().putInt((int) value);
    }

    public void writeLong(long value) throws IOException {
        ensureBufferRemaining(Long.BYTES);
        getBuffer().putLong(value);
    }

    public void writeFloat(float value) throws IOException {
        ensureBufferRemaining(Float.BYTES);
        getBuffer().putFloat(value);
    }

    public void writeDouble(double value) throws IOException {
        ensureBufferRemaining(Double.BYTES);
        getBuffer().putDouble(value);
    }

    private static int encodedMutf8Length(String value, int asciiLength) {
        int length = value.length();

        for (int i = asciiLength; i < value.length(); i++) {
            char ch = value.charAt(i);

            if (ch >= 0x800) {
                length += 2;
            } else if (ch >= 0x80 || ch == 0) {
                length += 1;
            }
        }

        return length;
    }

    @SuppressWarnings("deprecation")
    public void writeString(String value) throws IOException {
        int asciiLength = 0;
        while (asciiLength < value.length()) {
            char ch = value.charAt(asciiLength);
            if (ch > 0 && ch < 128) {
                asciiLength++;
            } else {
                break;
            }
        }

        if (asciiLength == value.length()) {
            // Fast path for ASCII strings

            if (value.length() > 65535) {
                throw new UTFDataFormatException("String too long: " + value);
            }

            writeUnsignedShort(value.length());
            ensureBufferRemaining(value.length());

            ByteBuffer byteBuffer = getBuffer().getByteBuffer();
            int position = byteBuffer.position();

            if (byteBuffer.hasArray()) {
                value.getBytes(0, value.length(), byteBuffer.array(), position + byteBuffer.arrayOffset());
            } else {
                for (int i = 0; i < asciiLength; i++) {
                    byteBuffer.put(position + i, (byte) value.charAt(i));
                }
            }
            byteBuffer.position(position + asciiLength);
            return;
        }

        if (getEdition() == MinecraftEdition.BEDROCK_EDITION) {
            // Need Optimization
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            if (value.length() > 65535) {
                throw new UTFDataFormatException("String too long: " + value);
            }
            writeUnsignedShort(bytes.length);
            ensureBufferRemaining(bytes.length);
            getBuffer().putByteArray(bytes);
            return;
        }

        // Slow path for non-ASCII strings
        int encodedLength = encodedMutf8Length(value, asciiLength);
        if (encodedLength > 65535) {
            throw new UTFDataFormatException("String too long: " + value);
        }

        writeUnsignedShort(encodedLength);

        ensureBufferRemaining(encodedLength);

        ByteBuffer byteBuffer = getBuffer().getByteBuffer();
        int offset = byteBuffer.position();

        byteBuffer.position(byteBuffer.position() + encodedLength);

        if (byteBuffer.hasArray()) {
            value.getBytes(0, asciiLength, byteBuffer.array(), offset + byteBuffer.arrayOffset());
            offset += asciiLength;
        } else {
            for (int i = 0; i < asciiLength; i++) {
                byteBuffer.put(offset++, (byte) value.charAt(i));
            }
        }

        for (int i = asciiLength; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch > 0 && ch < 0x80) {
                byteBuffer.put(offset++, (byte) ch);
            } else if (ch >= 0x800) {
                byteBuffer.put(offset++, (byte) (0xE0 | ch >> 12 & 0x0F));
                byteBuffer.put(offset++, (byte) (0x80 | ch >> 6 & 0x3F));
                byteBuffer.put(offset++, (byte) (0x80 | ch & 0x3F));
            } else {
                byteBuffer.put(offset++, (byte) (0xC0 | ch >> 6 & 0x1F));
                byteBuffer.put(offset++, (byte) (0x80 | ch & 0x3F));
            }
        }

        if (offset != byteBuffer.position()) {
            throw new AssertionError("Unexpected buffer position: " + offset + " != " + byteBuffer.position());
        }
    }
}
