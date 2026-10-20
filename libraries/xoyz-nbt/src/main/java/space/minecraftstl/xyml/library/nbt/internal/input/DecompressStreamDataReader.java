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
// Modified by MinecraftSTL in 2026 for the XYML namespace and monorepo build.
package space.minecraftstl.xyml.library.nbt.internal.input;

import net.jpountz.lz4.LZ4BlockInputStream;
import org.jetbrains.annotations.NotNullByDefault;
import space.minecraftstl.xyml.library.nbt.io.NBTCodec;
import space.minecraftstl.xyml.library.nbt.io.ReadLimits;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;

/// Bounded stream-backed decompressor for GZIP and LZ4 NBT payloads.
@NotNullByDefault
public abstract class DecompressStreamDataReader extends BoundedDataReader {
    /// Maximum encoded bytes accepted while eagerly validating a single GZIP member.
    private static final long MAX_GZIP_ENCODED_BYTES = ReadLimits.defaults().maxEncodedBytes();

    public static DecompressStreamDataReader newGZipDataReader(RawDataReader rawReader, long limit) throws IOException {
        return new GZipReader(rawReader, limit, ReadLimits.defaults().maxDecompressedBytes());
    }

    public static DecompressStreamDataReader newLZ4DataReader(RawDataReader rawReader, long limit) throws IOException {
        return newLZ4DataReader(rawReader, limit, ReadLimits.defaults().maxDecompressedBytes());
    }

    /// Creates a GZIP reader with independent compressed-input and decompressed-output limits.
    public static DecompressStreamDataReader newGZipDataReader(RawDataReader rawReader, long limit,
                                                                long maxDecompressedBytes) throws IOException {
        return new GZipReader(rawReader, limit, maxDecompressedBytes);
    }

    /// Creates an LZ4 reader with independent compressed-input and decompressed-output limits.
    public static DecompressStreamDataReader newLZ4DataReader(RawDataReader rawReader, long limit,
                                                               long maxDecompressedBytes) throws IOException {
        if (!LZ4Reader.AVAILABLE) {
            throw new IOException("Missing LZ4 library, please add it to your classpath.");
        }

        return new LZ4Reader(rawReader, limit, maxDecompressedBytes);
    }

    private final InputStream decompressStream;
    private final long maxDecompressedBytes;
    private long decompressedBytes;
    private boolean finished;

    /// Creates a reader using the default decompressed-output limit.
    public DecompressStreamDataReader(RawDataReader rawReader, long limit) throws IOException {
        this(rawReader, limit, ReadLimits.defaults().maxDecompressedBytes());
    }

    /// Creates a reader with independent compressed-input and decompressed-output limits.
    public DecompressStreamDataReader(RawDataReader rawReader, long limit,
                                      long maxDecompressedBytes) throws IOException {
        super(rawReader, rawReader.getDecompressBuffer(), limit);
        if (maxDecompressedBytes < 0L) {
            throw new IllegalArgumentException("maxDecompressedBytes must not be negative");
        }
        this.maxDecompressedBytes = maxDecompressedBytes;
        this.decompressStream = newDecompressStream(asInputStream());

        assert getBuffer().getByteBuffer().hasArray();
    }

    protected abstract InputStream newDecompressStream(InputStream rawInputStream) throws IOException;

    /// Returns the per-payload decompressed-byte limit for subclass stream construction.
    protected final long getMaxDecompressedBytes() {
        return maxDecompressedBytes;
    }

    /// Drains the compressed stream so its checksum/footer is verified and no compressed bytes remain.
    public final void finish() throws IOException {
        if (finished) {
            return;
        }

        byte[] drainBuffer = new byte[8192];
        int count;
        while ((count = decompressStream.read(drainBuffer)) >= 0) {
            if (count == 0) {
                int single = decompressStream.read();
                if (single < 0) {
                    break;
                }
                accountOutput(1);
                continue;
            }
            accountOutput(count);
        }
        requireFullyConsumed();
        finished = true;
    }

    @Override
    public void ensureBufferRemaining(int required) throws IOException {
        if (getBuffer().remaining() >= required) {
            return;
        }

        if (endPosition >= 0 && remainingRawBytes() <= 0L) {
            throw new EOFException("Not enough data to read, required: " + required + ", remaining: "
                    + remainingRawBytes());
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

        byte[] array = output.array();
        try {
            while (output.position() < required) {
                int n = decompressStream.read(array, output.arrayOffset() + output.position(), output.remaining());
                if (n <= 0) {
                    throw new EOFException("Not enough data to read, required: " + required + ", remaining: "
                            + (endPosition >= 0 ? remainingRawBytes() : "unknown"));
                }

                output.position(output.position() + n);
                accountOutput(n);
            }
        } finally {
            output.flip();
        }
    }

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

    @Override
    public void close() throws IOException {
        IOException failure = null;
        try {
            finish();
        } catch (IOException exception) {
            failure = exception;
        }
        getRawReader().releaseDecompressBuffer(getBuffer());
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

    /// LZ4 block-stream decoder loaded only when the optional implementation is available.
    @NotNullByDefault
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

        public LZ4Reader(RawDataReader rawReader, long limit, long maxDecompressedBytes) throws IOException {
            super(rawReader, limit, maxDecompressedBytes);
        }

        @Override
        protected InputStream newDecompressStream(InputStream rawInputStream) {
            return LZ4BlockInputStream.newBuilder().build(rawInputStream);
        }
    }

    /// Strict single-member GZIP decoder with header and footer validation.
    @NotNullByDefault
    private static class GZipReader extends DecompressStreamDataReader {
        public GZipReader(RawDataReader rawReader, long limit, long maxDecompressedBytes) throws IOException {
            super(rawReader, limit, maxDecompressedBytes);
        }

        @Override
        protected InputStream newDecompressStream(InputStream rawInputStream) throws IOException {
            // The JDK stream reader is intentionally not used here: depending on the runtime it
            // may accept concatenated members and does not consistently validate FHCRC. Capture
            // only the bounded source, then use the codec's exact one-member validator.
            byte[] encoded = readEncoded(rawInputStream);
            int maximum = Math.toIntExact(Math.min(getMaxDecompressedBytes(), Integer.MAX_VALUE));
            return new ByteArrayInputStream(NBTCodec.decodeGzipStrict(encoded, maximum));
        }
    }

    /// Reads one bounded compressed source without allowing an unbounded drain.
    ///
    /// @param input source constrained by the enclosing raw reader/frame
    /// @return detached encoded bytes
    /// @throws IOException if the source is oversized or makes no progress
    private static byte[] readEncoded(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(8192);
        byte[] buffer = new byte[8192];
        long total = 0L;
        while (true) {
            int count = input.read(buffer);
            if (count < 0) {
                break;
            }
            if (count == 0) {
                int single = input.read();
                if (single < 0) {
                    break;
                }
                if (total >= MAX_GZIP_ENCODED_BYTES) {
                    throw new IOException("Encoded GZIP payload exceeds the read limit");
                }
                output.write(single);
                total++;
                continue;
            }
            if (count > MAX_GZIP_ENCODED_BYTES - total) {
                throw new IOException("Encoded GZIP payload exceeds the read limit");
            }
            output.write(buffer, 0, count);
            total += count;
        }
        return output.toByteArray();
    }
}
