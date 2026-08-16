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

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

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

    public ZlibDataReader(RawDataReader rawReader, long limit) {
        super(rawReader, rawReader.getDecompressBuffer(), limit);

        this.inflater = INFLATER_CACHE_KEY.get(rawReader);
    }

    @Override
    public void ensureBufferRemaining(int required) throws IOException {
        if (getBuffer().remaining() >= required) {
            return;
        }

        if (inflater.finished() || inflater.needsDictionary()) {
            throw new EOFException("Inflater finished or needs dictionary");
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
                    inflater.inflate(output);
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
        getRawReader().releaseDecompressBuffer(getBuffer());
        inflater.reset();
        INFLATER_CACHE_KEY.release(getRawReader(), inflater);
        super.close();
    }

    private static final byte[] EMPTY_BYTE_ARRAY = new byte[0];
}
