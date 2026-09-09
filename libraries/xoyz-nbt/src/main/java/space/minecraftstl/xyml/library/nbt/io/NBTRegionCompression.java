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
// Added by MinecraftSTL in 2026 for bounded region compression handling.
package space.minecraftstl.xyml.library.nbt.io;

import net.jpountz.lz4.LZ4BlockInputStream;
import net.jpountz.lz4.LZ4BlockOutputStream;
import org.jetbrains.annotations.NotNullByDefault;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Objects;
import java.util.zip.DataFormatException;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.GZIPOutputStream;
import java.util.zip.Inflater;

/// Strict bounded compression codecs used by Java Anvil region slots.
@NotNullByDefault
final class NBTRegionCompression {
    /// Prevents utility-class construction.
    private NBTRegionCompression() {
    }

    /// Decompresses one complete region payload and rejects trailing input.
    ///
    /// @param compression region marker compression type
    /// @param payload complete encoded payload
    /// @param maximumBytes largest accepted decoded payload
    /// @return detached decoded bytes
    /// @throws IOException if the payload is malformed, truncated, trailing, or too large
    static byte[] decompress(NBTRegionFile.CompressionType compression, byte[] payload, int maximumBytes)
            throws IOException {
        Objects.requireNonNull(compression, "compression");
        Objects.requireNonNull(payload, "payload");
        if (maximumBytes < 0) {
            throw new IllegalArgumentException("maximumBytes must not be negative");
        }
        return switch (compression) {
            case UNCOMPRESSED -> {
                if (payload.length > maximumBytes) {
                    throw new IOException("Uncompressed chunk payload exceeds the size limit");
                }
                yield payload.clone();
            }
            case GZIP -> NBTCodec.decodeGzipStrict(payload, maximumBytes);
            case LZ4 -> readLz4(payload, maximumBytes);
            case ZLIB -> inflate(payload, maximumBytes);
        };
    }

    /// Compresses serialized NBT bytes with the selected region algorithm.
    ///
    /// @param compression output compression type
    /// @param input complete serialized NBT bytes
    /// @return compressed payload bytes without a region frame prefix
    /// @throws IOException if compression fails
    static byte[] compress(NBTRegionFile.CompressionType compression, byte[] input) throws IOException {
        Objects.requireNonNull(compression, "compression");
        Objects.requireNonNull(input, "input");
        if (compression == NBTRegionFile.CompressionType.UNCOMPRESSED) {
            return input.clone();
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(input.length, 8192));
        OutputStream compressor = switch (compression) {
            case GZIP -> new GZIPOutputStream(output);
            case ZLIB -> new DeflaterOutputStream(output);
            case LZ4 -> new LZ4BlockOutputStream(output);
            case UNCOMPRESSED -> throw new AssertionError();
        };
        try (compressor) {
            compressor.write(input);
        }
        return output.toByteArray();
    }

    /// Strictly expands one LZ4 block-stream payload.
    ///
    /// @param payload complete compressed bytes
    /// @param maximumBytes largest accepted decoded payload
    /// @return uncompressed bytes
    /// @throws IOException if LZ4 validation or bounded reading fails
    private static byte[] readLz4(byte[] payload, int maximumBytes) throws IOException {
        ByteArrayInputStream source = new ByteArrayInputStream(payload);
        return readCompressedStream(new LZ4BlockInputStream(source), source, payload.length, maximumBytes);
    }

    /// Strictly inflates one zlib payload and rejects unused trailing input.
    ///
    /// @param payload complete compressed bytes
    /// @param maximumBytes largest accepted decoded payload
    /// @return uncompressed bytes
    /// @throws IOException if the payload is malformed, truncated, trailing, or too large
    private static byte[] inflate(byte[] payload, int maximumBytes) throws IOException {
        Inflater inflater = new Inflater();
        try {
            inflater.setInput(payload);
            ByteArrayOutputStream output = new ByteArrayOutputStream(initialCapacity(payload.length));
            byte[] buffer = new byte[8192];
            while (!inflater.finished()) {
                int count;
                try {
                    count = inflater.inflate(buffer);
                } catch (DataFormatException exception) {
                    throw new IOException("Invalid ZLIB chunk payload", exception);
                }
                if (count > 0) {
                    writeBounded(output, buffer, count, maximumBytes);
                } else if (inflater.needsDictionary() || inflater.needsInput()) {
                    throw new IOException("Truncated ZLIB chunk payload");
                } else {
                    throw new IOException("ZLIB chunk decompressor made no progress");
                }
            }
            if (inflater.getRemaining() != 0) {
                throw new IOException("Trailing bytes after ZLIB chunk payload");
            }
            return output.toByteArray();
        } finally {
            inflater.end();
        }
    }

    /// Reads a stream decompressor to EOF while enforcing the output and input boundaries.
    ///
    /// @param input decompressor layered over `source`
    /// @param source compressed byte source used to detect trailing data
    /// @param compressedLength original compressed length used to size the output buffer
    /// @param maximumBytes largest accepted decoded payload
    /// @return complete uncompressed bytes
    /// @throws IOException if decompression fails, input remains, or output exceeds the limit
    private static byte[] readCompressedStream(InputStream input, ByteArrayInputStream source,
                                               int compressedLength, int maximumBytes) throws IOException {
        try (InputStream stream = input) {
            ByteArrayOutputStream output = new ByteArrayOutputStream(initialCapacity(compressedLength));
            byte[] buffer = new byte[8192];
            int count;
            while ((count = stream.read(buffer)) >= 0) {
                if (count == 0) {
                    int single = stream.read();
                    if (single < 0) {
                        break;
                    }
                    if (output.size() >= maximumBytes) {
                        throw new IOException("Chunk payload is too large after decompression");
                    }
                    output.write(single);
                    continue;
                }
                writeBounded(output, buffer, count, maximumBytes);
            }
            if (source.available() != 0) {
                throw new IOException("Trailing bytes after compressed chunk payload");
            }
            return output.toByteArray();
        }
    }

    /// Writes one decoded block after checking the configured output limit.
    ///
    /// @param output decoded output accumulator
    /// @param buffer decoded source block
    /// @param count valid source bytes
    /// @param maximumBytes largest accepted decoded payload
    /// @throws IOException if the block would exceed the limit
    private static void writeBounded(ByteArrayOutputStream output, byte[] buffer, int count, int maximumBytes)
            throws IOException {
        if (count < 0 || output.size() > maximumBytes - count) {
            throw new IOException("Chunk payload is too large after decompression");
        }
        output.write(buffer, 0, count);
    }

    /// Chooses a small safe initial output capacity without multiplying in `int` space.
    ///
    /// @param compressedLength encoded payload size
    /// @return initial buffer capacity
    private static int initialCapacity(int compressedLength) {
        return (int) Math.min((long) compressedLength * 2L, 8192L);
    }
}
