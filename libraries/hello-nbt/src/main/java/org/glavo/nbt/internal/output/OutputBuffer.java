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

import java.nio.BufferOverflowException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public final class OutputBuffer {
    public static OutputBuffer allocate(int size, boolean direct, ByteOrder byteOrder) {
        ByteBuffer bytesBuffer = direct
                ? ByteBuffer.allocateDirect(size)
                : ByteBuffer.allocate(size);

        bytesBuffer.order(byteOrder);
        return new OutputBuffer(bytesBuffer);
    }

    public static void assertStatus(OutputBuffer buffer) {
        if (buffer.bytesBuffer.limit() != buffer.bytesBuffer.capacity()) {
            throw new AssertionError("Buffer limit should equal capacity");
        }
    }

    /// The pending bytes.
    ///
    /// Typically, its limit should equal capacity, and position should equal the number of bytes already written.
    private ByteBuffer bytesBuffer;

    public OutputBuffer(ByteBuffer bytesBuffer) {
        this.bytesBuffer = bytesBuffer;
    }

    public ByteBuffer getByteBuffer() {
        return bytesBuffer;
    }

    public ByteOrder order() {
        return bytesBuffer.order();
    }

    public void ensureCapacity(int required) {
        if (bytesBuffer.capacity() < required) {
            ByteBuffer newBuffer = ByteBuffer.allocate(Math.max(required, bytesBuffer.capacity() * 2))
                    .order(bytesBuffer.order());
            newBuffer.put(bytesBuffer.flip());
            bytesBuffer = newBuffer;
        }
    }

    public int pending() {
        return bytesBuffer.position();
    }

    public int remaining() {
        return bytesBuffer.remaining();
    }

    public int capacity() {
        return bytesBuffer.capacity();
    }

    public void putByte(byte b) {
        bytesBuffer.put(b);
    }

    public void putShort(short value) {
        bytesBuffer.putShort(value);
    }

    public void putInt(int value) {
        bytesBuffer.putInt(value);
    }

    public void putLong(long value) {
        bytesBuffer.putLong(value);
    }

    public void putFloat(float value) {
        bytesBuffer.putFloat(value);
    }

    public void putDouble(double value) {
        bytesBuffer.putDouble(value);
    }

    public void putChar(char value) {
        bytesBuffer.putChar(value);
    }

    public void putByteArray(byte[] value) {
        bytesBuffer.put(value);
    }

    public void putByteArray(byte[] value, int offset, int length) {
        bytesBuffer.put(value, offset, length);
    }

    public void putByteBuffer(ByteBuffer buffer) {
        bytesBuffer.put(buffer);
    }

    public void putIntArray(int[] value) {
        putIntArray(value, 0, value.length);
    }

    public void putIntArray(int[] value, int offset, int length) {
        long bytes = (long) length * Integer.BYTES;
        if (bytes > bytesBuffer.remaining()) {
            throw new BufferOverflowException();
        }

        bytesBuffer.asIntBuffer().put(value, offset, length);
        bytesBuffer.position(bytesBuffer.position() + (int) bytes);
    }

    public void putLongArray(long[] value) {
        putLongArray(value, 0, value.length);
    }

    public void putLongArray(long[] value, int offset, int length) {
        long bytes = (long) length * Long.BYTES;
        if (bytes > bytesBuffer.remaining()) {
            throw new BufferOverflowException();
        }

        bytesBuffer.asLongBuffer().put(value, offset, length);
        bytesBuffer.position(bytesBuffer.position() + (int) bytes);
    }
}
