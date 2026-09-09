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
import space.minecraftstl.xyml.library.nbt.io.NBTReadLimits;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/// Bounded zlib decoder that validates completion and tracks emitted bytes.
@NotNullByDefault
public final class ZlibDataReader extends BoundedDataReader {
    static final RawDataReader.CacheKey<Inflater> INFLATER_CACHE_KEY = new RawDataReader.CacheKey<>() {
        @Override
        protected Inflater create(RawDataReader rawReader) {
            return new Inflater();
        }

        @Override
        public void close(Inflater value) {
            value.end();
        }
    };

    private final Inflater inflater;
    private final long maxDecompressedBytes;
    private long decompressedBytes;
    private boolean finished;

    public ZlibDataReader(RawDataReader rawReader, long limit) {
        this(rawReader, limit, NBTReadLimits.defaults().maxDecompressedBytes());
    }

    /// Creates a zlib reader with independent compressed-input and decompressed-output limits.
    public ZlibDataReader(RawDataReader rawReader, long limit, long maxDecompressedBytes) {
        super(rawReader, rawReader.getDecompressBuffer(), limit);
        if (maxDecompressedBytes < 0L) {
            throw new IllegalArgumentException("maxDecompressedBytes must not be negative");
        }

        this.inflater = INFLATER_CACHE_KEY.get(rawReader);
        this.maxDecompressedBytes = maxDecompressedBytes;
    }

    @Override
    public void ensureBufferRemaining(int required) throws IOException {
        if (getBuffer().remaining() >= required) {
            return;
        }

        if (inflater.finished() || inflater.needsDictionary()) {
            throw new EOFException("Inflater finished or needs dictionary");
        }

        if (required < 0) {
            throw new IllegalArgumentException("required must not be negative");
        }
        long additional = (long) required - getBuffer().remaining();
        if (additional > maxDecompressedBytes - decompressedBytes) {
            throw new IOException("Decompressed NBT payload exceeds the read limit");
        }
        getBuffer().ensureCapacity(required);
        ByteBuffer output = getBuffer().getByteBuffer();
        output.compact();

        try {
            do {
                if (inflater.finished() || inflater.needsDictionary()) {
                    throw new EOFException();
                }

                if (inflater.needsInput()) {
                    if (getRawReader().getBuffer().remaining() == 0) {
                        getRawReader().ensureBufferRemaining(1);
                    }
                    inflater.setInput(getRawReader().getBuffer().getByteBuffer());
                }

                try {
                    int produced = inflater.inflate(output);
                    if (produced == 0 && !inflater.finished() && !inflater.needsInput()) {
                        throw new IOException("Invalid zlib stream: inflater made no progress");
                    }
                    accountOutput(produced);
                } catch (DataFormatException exception) {
                    throw new IOException(exception);
                }

            } while (output.position() < required);
        } finally {
            inflater.setInput(EMPTY_BYTE_ARRAY);
            output.flip();
        }
    }

    @Override
    public void close() throws IOException {
        IOException failure = null;
        try {
            finish();
        } catch (IOException exception) {
            failure = exception;
        }
        getRawReader().releaseDecompressBuffer(getBuffer());
        inflater.reset();
        INFLATER_CACHE_KEY.release(getRawReader(), inflater);
        try {
            super.close();
        } catch (IOException exception) {
            if (failure == null) {
                failure = exception;
            } else {
                failure.addSuppressed(exception);
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    /// Drains the zlib stream and verifies its checksum and bounded payload.
    public void finish() throws IOException {
        if (finished) {
            return;
        }
        ByteBuffer sink = ByteBuffer.allocate(8192);
        while (!inflater.finished()) {
            sink.clear();
            if (inflater.needsInput()) {
                if (getRawReader().getBuffer().remaining() == 0) {
                    getRawReader().ensureBufferRemaining(1);
                }
                inflater.setInput(getRawReader().getBuffer().getByteBuffer());
            }
            try {
                int produced = inflater.inflate(sink);
                if (produced == 0 && !inflater.finished() && !inflater.needsInput()) {
                    throw new IOException("Invalid zlib stream: inflater made no progress");
                }
                accountOutput(produced);
            } catch (DataFormatException exception) {
                throw new IOException("Invalid zlib stream", exception);
            }
        }
        requireFullyConsumed();
        finished = true;
    }

    private static final byte[] EMPTY_BYTE_ARRAY = new byte[0];

    /// Accounts for bytes emitted by the decompressor before exposing them to the parser.
    private void accountOutput(int count) throws IOException {
        if (count < 0 || (long) count > maxDecompressedBytes - decompressedBytes) {
            throw new IOException("Decompressed NBT payload exceeds the read limit");
        }
        decompressedBytes += count;
    }

    @Override
    long decodedBytes() {
        return decompressedBytes;
    }
}
