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

import java.nio.BufferOverflowException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/// Growable byte buffer retaining unread primitive input between source fills.
@NotNullByDefault
public final class InputBuffer {
    public static InputBuffer allocate(int size, boolean direct, ByteOrder byteOrder) {
        ByteBuffer bytesBuffer = direct
                ? ByteBuffer.allocateDirect(size)
                : ByteBuffer.allocate(size);

        bytesBuffer.order(byteOrder);
        bytesBuffer.limit(0);
        return new InputBuffer(bytesBuffer);
    }

    private ByteBuffer bytesBuffer;

    public InputBuffer(ByteBuffer bytesBuffer) {
        this.bytesBuffer = bytesBuffer;
    }

    public ByteBuffer getByteBuffer() {
        return bytesBuffer;
    }

    public ByteOrder order() {
        return bytesBuffer.order();
    }

    /// Ensures that at least the requested number of unread bytes can be buffered.
    ///
    /// @param required required unread capacity
    /// @throws IllegalArgumentException if required is negative
    public void ensureCapacity(int required) {
        if (required < 0) {
            throw new IllegalArgumentException("required must not be negative");
        }
        if (bytesBuffer.capacity() < required) {
            long doubled = (long) bytesBuffer.capacity() * 2L;
            int capacity = (int) Math.min(Integer.MAX_VALUE, Math.max((long) required, doubled));
            ByteBuffer newBuffer = ByteBuffer.allocate(capacity)
                    .order(bytesBuffer.order());
            newBuffer.put(bytesBuffer);
            newBuffer.flip();
            bytesBuffer = newBuffer;
        } else if (bytesBuffer.position() > 0) {
            bytesBuffer.compact();
            bytesBuffer.flip();
        }
    }

    public void drop() {
        bytesBuffer.position(0);
        bytesBuffer.limit(0);
    }

    public void drop(int n) {
        int remaining = this.remaining();
        if (n < remaining) {
            bytesBuffer.position(bytesBuffer.position() + n);
        } else if (n == remaining) {
            drop();
        } else {
            throw new BufferOverflowException();
        }
    }

    public int remaining() {
        return bytesBuffer.remaining();
    }

    public byte getByte() {
        return bytesBuffer.get();
    }

    public short getShort() {
        return bytesBuffer.getShort();
    }

    public int getInt() {
        return bytesBuffer.getInt();
    }

    public long getLong() {
        return bytesBuffer.getLong();
    }

    public float getFloat() {
        return bytesBuffer.getFloat();
    }

    public double getDouble() {
        return bytesBuffer.getDouble();
    }

    public void getBytes(byte[] dst, int offset, int length) {
        bytesBuffer.get(dst, offset, length);
    }

    public byte[] getByteArray(int len) {
        var array = new byte[len];
        bytesBuffer.get(array);
        return array;
    }

    public int[] getIntArray(int len) {
        var array = new int[len];
        bytesBuffer.asIntBuffer().get(array);
        bytesBuffer.position(bytesBuffer.position() + len * Integer.BYTES);
        return array;
    }

    public long[] getLongArray(int len) {
        var array = new long[len];
        bytesBuffer.asLongBuffer().get(array);
        bytesBuffer.position(bytesBuffer.position() + len * Long.BYTES);
        return array;
    }

    public byte lookAheadByte() {
        return bytesBuffer.get(bytesBuffer.position());
    }
}
