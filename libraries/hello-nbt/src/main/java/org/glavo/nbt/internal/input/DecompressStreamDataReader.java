/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2026 huangyuhui <huanghongxun2008@126.com> and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package org.glavo.nbt.internal.input;

import net.jpountz.lz4.LZ4BlockInputStream;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.zip.GZIPInputStream;

public abstract class DecompressStreamDataReader extends BoundedDataReader {
    public static DecompressStreamDataReader newGZipDataReader(RawDataReader rawReader, long limit) throws IOException {
        return new GZipReader(rawReader, limit);
    }

    public static DecompressStreamDataReader newLZ4DataReader(RawDataReader rawReader, long limit) throws IOException {
        if (!LZ4Reader.AVAILABLE) {
            throw new IOException("Missing LZ4 library, please add it to your classpath.");
        }

        return new LZ4Reader(rawReader, limit);
    }

    private final InputStream decompressStream;

    public DecompressStreamDataReader(RawDataReader rawReader, long limit) throws IOException {
        super(rawReader, rawReader.getDecompressBuffer(), limit);
        this.decompressStream = newDecompressStream(asInputStream());

        assert getBuffer().getByteBuffer().hasArray();
    }

    protected abstract InputStream newDecompressStream(InputStream rawInputStream) throws IOException;

    @Override
    public void ensureBufferRemaining(int required) throws IOException {
        if (getBuffer().remaining() >= required) {
            return;
        }

        if (endPosition >= 0 && endPosition - getRawReader().position() <= 0) {
            throw new EOFException("Not enough data to read, required: " + required + ", remaining: " + (endPosition - getRawReader().position()));
        }

        getBuffer().ensureCapacity(required);
        ByteBuffer output = getBuffer().getByteBuffer();
        output.compact();

        byte[] array = output.array();
        try {
            while (output.position() < required) {
                int n = decompressStream.read(array, output.arrayOffset() + output.position(), output.remaining());
                if (n <= 0) {
                    throw new EOFException("Not enough data to read, required: " + required + ", remaining: "
                            + (endPosition >= 0 ? endPosition - getRawReader().position() : "unknown"));
                }

                output.position(output.position() + n);
            }
        } finally {
            output.flip();
        }
    }

    @Override
    public void close() throws IOException {
        getRawReader().releaseDecompressBuffer(getBuffer());
        super.close();
    }

    private static class LZ4Reader extends DecompressStreamDataReader {
        static final boolean AVAILABLE;

        static {
            boolean lz4Available = false;
            try {
                Class.forName("net.jpountz.lz4.LZ4BlockInputStream", false, DecompressStreamDataReader.class.getClassLoader());
                lz4Available = true;
            } catch (ClassNotFoundException ignored) {
            }
            AVAILABLE = lz4Available;
        }

        public LZ4Reader(RawDataReader rawReader, long limit) throws IOException {
            super(rawReader, limit);
        }

        @Override
        protected InputStream newDecompressStream(InputStream rawInputStream) {
            return LZ4BlockInputStream.newBuilder().build(rawInputStream);
        }
    }

    private static class GZipReader extends DecompressStreamDataReader {
        public GZipReader(RawDataReader rawReader, long limit) throws IOException {
            super(rawReader, limit);
        }

        @Override
        protected InputStream newDecompressStream(InputStream rawInputStream) throws IOException {
            return new GZIPInputStream(rawInputStream);
        }
    }
}
