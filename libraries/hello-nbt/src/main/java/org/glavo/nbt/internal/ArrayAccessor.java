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

import org.glavo.nbt.tag.ByteTag;
import org.glavo.nbt.tag.IntTag;
import org.glavo.nbt.tag.LongTag;
import org.glavo.nbt.tag.ValueTag;

import java.lang.reflect.Array;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.Arrays;

public abstract class ArrayAccessor<E extends Number, T extends ValueTag<E>, A, B extends Buffer> {

    public static final int DEFAULT_CAPACITY = 12;

    public static int nextCapacity(int currentCapacity, int minCapacity) {
        int growCap;
        if (currentCapacity < (DEFAULT_CAPACITY >> 1)) {
            growCap = DEFAULT_CAPACITY;
        } else {
            growCap = currentCapacity + (currentCapacity >> 1);
        }
        return Math.max(growCap, minCapacity);
    }

    private final Class<A> arrayClass;
    private final Class<?> primitiveClass;
    protected final A empty;

    private ArrayAccessor(Class<A> arrayClass, A empty) {
        this.arrayClass = arrayClass;
        this.primitiveClass = arrayClass.getComponentType();
        this.empty = empty;
    }

    /// Returns an empty array.
    public final A empty() {
        return empty;
    }

    public final Class<?> getPrimitiveClass() {
        return primitiveClass;
    }

    public final Class<A> getArrayClass() {
        return arrayClass;
    }

    public abstract A copyOf(A array, int newLength);

    public abstract A newArray(int newLength);

    public final int getLength(A array) {
        return Array.getLength(array);
    }

    public abstract E get(A array, int index);

    public abstract String getAsString(A array, int index);

    public abstract void set(A array, int index, E value);

    public abstract void set(A array, int index, T tag);

    public abstract int hashCode(A array, int offset, int length);

    public abstract void toString(StringBuilder builder, A array, int offset, int length);

    public abstract boolean equals(A array1, int offset1, A array2, int offset2, int length);

    public abstract A get(B buffer);

    public abstract B getReadOnlyView(A array, int offset, int length);

    public abstract T newTagFromElement(A array, int index);

    public static final ArrayAccessor<Byte, ByteTag, byte[], ByteBuffer> BYTE_ARRAY = new ArrayAccessor<>(byte[].class, new byte[0]) {
        @Override
        public byte[] newArray(int newLength) {
            return new byte[newLength];
        }

        @Override
        public byte[] copyOf(byte[] array, int newLength) {
            return Arrays.copyOf(array, newLength);
        }

        @Override
        public Byte get(byte[] array, int index) {
            return array[index];
        }

        @Override
        public String getAsString(byte[] array, int index) {
            return Byte.toString(array[index]);
        }

        @Override
        public void set(byte[] array, int index, Byte value) {
            array[index] = value;
        }

        @Override
        public void set(byte[] array, int index, ByteTag tag) {
            array[index] = tag.get();
        }

        @Override
        public int hashCode(byte[] array, int offset, int length) {
            int hashCode = 0;
            for (int i = offset; i < offset + length; i++) {
                hashCode = 31 * hashCode + Byte.hashCode(array[i]);
            }
            return hashCode;
        }

        @Override
        public void toString(StringBuilder builder, byte[] array, int offset, int length) {
            if (length == 0) {
                builder.append("[]");
                return;
            }
            builder.append('[').append(array[offset]);
            for (int i = offset + 1; i < offset + length; i++) {
                builder.append(", ").append(array[i]);
            }
            builder.append(']');
        }

        @Override
        public boolean equals(byte[] array1, int offset1, byte[] array2, int offset2, int length) {
            return Arrays.equals(array1, offset1, offset1 + length, array2, offset2, offset2 + length);
        }

        @Override
        public byte[] get(ByteBuffer buffer) {
            int remaining = buffer.remaining();
            if (remaining > 0) {
                byte[] array = new byte[remaining];
                buffer.get(array);
                return array;
            } else {
                return empty;
            }
        }

        @Override
        public ByteBuffer getReadOnlyView(byte[] array, int offset, int length) {
            return ByteBuffer.wrap(array, offset, length).asReadOnlyBuffer();
        }

        @Override
        public ByteTag newTagFromElement(byte[] array, int index) {
            return new ByteTag(array[index]);
        }
    };

    public static final ArrayAccessor<Integer, IntTag, int[], IntBuffer> INT_ARRAY = new ArrayAccessor<>(int[].class, new int[0]) {

        @Override
        public int[] newArray(int newLength) {
            return new int[newLength];
        }

        @Override
        public int[] copyOf(int[] array, int newLength) {
            return Arrays.copyOf(array, newLength);
        }

        @Override
        public Integer get(int[] array, int index) {
            return array[index];
        }

        @Override
        public String getAsString(int[] array, int index) {
            return Integer.toString(array[index]);
        }

        @Override
        public void set(int[] array, int index, Integer value) {
            array[index] = value;
        }

        @Override
        public void set(int[] array, int index, IntTag tag) {
            array[index] = tag.getValue();
        }

        @Override
        public int hashCode(int[] array, int offset, int length) {
            int hashCode = 0;
            for (int i = offset; i < offset + length; i++) {
                hashCode = 31 * hashCode + Integer.hashCode(array[i]);
            }
            return hashCode;
        }

        @Override
        public void toString(StringBuilder builder, int[] array, int offset, int length) {
            if (length == 0) {
                builder.append("[]");
                return;
            }
            builder.append('[').append(array[offset]);
            for (int i = offset + 1; i < offset + length; i++) {
                builder.append(", ").append(array[i]);
            }
            builder.append(']');
        }

        @Override
        public boolean equals(int[] array1, int offset1, int[] array2, int offset2, int length) {
            return Arrays.equals(array1, offset1, offset1 + length, array2, offset2, offset2 + length);
        }

        @Override
        public int[] get(IntBuffer buffer) {
            int remaining = buffer.remaining();
            if (remaining > 0) {
                int[] array = new int[remaining];
                buffer.get(array);
                return array;
            } else {
                return empty;
            }
        }

        @Override
        public IntBuffer getReadOnlyView(int[] array, int offset, int length) {
            return IntBuffer.wrap(array, offset, length).asReadOnlyBuffer();
        }

        @Override
        public IntTag newTagFromElement(int[] array, int index) {
            return new IntTag(array[index]);
        }
    };

    public static final ArrayAccessor<Long, LongTag, long[], LongBuffer> LONG_ARRAY = new ArrayAccessor<>(long[].class, new long[0]) {

        @Override
        public long[] newArray(int newLength) {
            return new long[newLength];
        }

        @Override
        public long[] copyOf(long[] array, int newLength) {
            return Arrays.copyOf(array, newLength);
        }

        @Override
        public Long get(long[] array, int index) {
            return array[index];
        }

        @Override
        public String getAsString(long[] array, int index) {
            return Long.toString(array[index]);
        }

        @Override
        public void set(long[] array, int index, Long value) {
            array[index] = value;
        }

        @Override
        public void set(long[] array, int index, LongTag tag) {
            array[index] = tag.getValue();
        }

        @Override
        public int hashCode(long[] array, int offset, int length) {
            int hashCode = 0;
            for (int i = offset; i < offset + length; i++) {
                hashCode = 31 * hashCode + Long.hashCode(array[i]);
            }
            return hashCode;
        }

        @Override
        public void toString(StringBuilder builder, long[] array, int offset, int length) {
            if (length == 0) {
                builder.append("[]");
                return;
            }
            builder.append('[').append(array[offset]);
            for (int i = offset + 1; i < offset + length; i++) {
                builder.append(", ").append(array[i]);
            }
            builder.append(']');
        }

        @Override
        public boolean equals(long[] array1, int offset1, long[] array2, int offset2, int length) {
            return Arrays.equals(array1, offset1, offset1 + length, array2, offset2, offset2 + length);
        }

        @Override
        public long[] get(LongBuffer buffer) {
            int remaining = buffer.remaining();
            if (remaining > 0) {
                long[] array = new long[remaining];
                buffer.get(array);
                return array;
            } else {
                return empty;
            }
        }

        @Override
        public LongBuffer getReadOnlyView(long[] array, int offset, int length) {
            return LongBuffer.wrap(array, offset, length).asReadOnlyBuffer();
        }

        @Override
        public LongTag newTagFromElement(long[] array, int index) {
            return new LongTag(array[index]);
        }
    };

}
