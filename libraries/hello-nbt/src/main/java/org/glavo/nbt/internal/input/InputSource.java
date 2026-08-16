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

import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.SeekableByteChannel;

public sealed abstract class InputSource implements Closeable {
    private boolean closed = false;

    public boolean supportDirectBuffer() {
        return false;
    }

    public abstract long position();

    @Override
    public final void close() throws IOException {
        if (!closed) {
            closed = true;
            closeImpl();
        }
    }

    protected abstract void closeImpl() throws IOException;

    protected final void ensureOpen() throws IOException {
        if (closed) {
            throw new IOException("Stream closed");
        }
    }

    public final void fillBuffer(InputBuffer buffer, int required) throws IOException {
        ensureOpen();

        if (buffer.remaining() > required) {
            return;
        }

        buffer.ensureCapacity(required);
        fillBufferImpl(buffer.getByteBuffer(), required);
    }

    protected abstract void fillBufferImpl(ByteBuffer target, int required) throws IOException;

    public abstract void skip(long bytes) throws IOException;

    public static final class OfByteBuffer extends InputSource {

        private final ByteBuffer buffer;

        public OfByteBuffer(ByteBuffer byteBuffer) {
            this.buffer = byteBuffer.slice();
        }

        public OfByteBuffer(byte[] array) {
            this.buffer = ByteBuffer.wrap(array);
        }

        @Override
        public long position() {
            return buffer.position();
        }

        @Override
        protected void closeImpl() throws IOException {
        }

        @Override
        protected void fillBufferImpl(ByteBuffer target, int required) throws IOException {
            assert target.capacity() - target.remaining() >= required;

            if (buffer.remaining() < required - target.remaining()) {
                throw new EOFException("Unexpected end of stream");
            }

            target.compact();
            if (buffer.remaining() <= target.remaining()) {
                target.put(buffer);
            } else {
                buffer.limit(buffer.position() + target.remaining());
                target.put(buffer);
                buffer.limit(buffer.capacity());
            }
            target.flip();

            assert target.remaining() >= required;
        }

        @Override
        public void skip(long bytes) throws IOException {
            if (buffer.remaining() < bytes) {
                throw new EOFException("Unexpected end of stream");
            }

            buffer.position(buffer.position() + (int) bytes);
        }
    }

    public static final class OfInputStream extends InputSource {
        private final InputStream inputStream;
        private final boolean closeInputStream;
        private long position;

        public OfInputStream(InputStream inputStream, boolean closeInputStream) {
            this.inputStream = inputStream;
            this.closeInputStream = closeInputStream;
        }

        @Override
        public long position() {
            return position;
        }

        @Override
        protected void closeImpl() throws IOException {
            if (closeInputStream) {
                inputStream.close();
            }
        }

        @Override
        protected void fillBufferImpl(ByteBuffer target, int required) throws IOException {
            assert target.hasArray();

            while (target.remaining() < required) {
                int offset = target.limit() + target.arrayOffset();
                int len = target.capacity() - target.limit();

                int read = inputStream.read(target.array(), offset, len);
                if (read < 0) {
                    throw new EOFException("Unexpected end of stream");
                }
                target.limit(target.limit() + read);
                position += read;
            }
        }

        @Override
        public void skip(long bytes) throws IOException {
            inputStream.skipNBytes(bytes);
            position += bytes;
        }
    }

    public static final class OfByteChannel extends InputSource {
        private final ReadableByteChannel channel;
        private final boolean closeChannel;
        private long position;

        private @Nullable ByteBuffer skipBuffer;

        public OfByteChannel(ReadableByteChannel channel, boolean closeChannel) {
            this.channel = channel;
            this.closeChannel = closeChannel;
        }

        @Override
        public boolean supportDirectBuffer() {
            return true;
        }

        @Override
        public long position() {
            return position;
        }

        @Override
        protected void closeImpl() throws IOException {
            if (closeChannel) {
                channel.close();
            }
        }

        @Override
        protected void fillBufferImpl(ByteBuffer target, int required) throws IOException {
            target.compact();
            try {
                while (target.position() < required) {
                    int n = channel.read(target);
                    if (n > 0) {
                        position += n;
                    } else {
                        throw new EOFException("Unexpected end of stream");
                    }
                }
            } finally {
                target.flip();
            }
        }

        @Override
        public void skip(long bytes) throws IOException {
            if (channel instanceof SeekableByteChannel seekableChannel) {
                try {
                    long channelCurrentPosition = seekableChannel.position();
                    long channelTargetPosition = Math.addExact(channelCurrentPosition, bytes);
                    seekableChannel.position(channelTargetPosition);
                    position += bytes;
                    return;
                } catch (ArithmeticException e) {
                    throw new IOException("Overflow when skip " + bytes + " bytes", e);
                }
            }

            if (skipBuffer == null) {
                skipBuffer = ByteBuffer.allocateDirect(1024);
            }

            long remainingSkip = bytes;
            while (remainingSkip > 0) {
                skipBuffer.position(0).limit((int) Math.min(remainingSkip, skipBuffer.capacity()));

                int n = channel.read(skipBuffer);
                if (n > 0) {
                    position += n;
                    remainingSkip -= n;
                } else {
                    throw new EOFException("Unexpected end of stream");
                }
            }
        }
    }
}
