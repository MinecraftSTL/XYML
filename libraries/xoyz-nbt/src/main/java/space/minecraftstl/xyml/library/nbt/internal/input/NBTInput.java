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

import space.minecraftstl.xyml.library.nbt.chunk.Chunk;
import space.minecraftstl.xyml.library.nbt.chunk.ChunkRegion;
import space.minecraftstl.xyml.library.nbt.internal.Access;
import space.minecraftstl.xyml.library.nbt.internal.ChunkRegionHeader;
import space.minecraftstl.xyml.library.nbt.internal.ChunkUtils;
import space.minecraftstl.xyml.library.nbt.io.ExternalChunkAccessor;
import space.minecraftstl.xyml.library.nbt.io.MinecraftEdition;
import space.minecraftstl.xyml.library.nbt.io.ReadLimits;
import space.minecraftstl.xyml.library.nbt.tag.CompoundTag;
import space.minecraftstl.xyml.library.nbt.tag.Tag;
import space.minecraftstl.xyml.library.nbt.tag.TagType;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.Objects;

/// Internal binary NBT decoding operations.
@NotNullByDefault
public final class NBTInput {
    /// Maximum encoded bytes accepted from an external region companion by the legacy stream API.
    ///
    /// Inline region frames already carry a sector-bounded length. External companions have no
    /// length in the region header, so the stream itself must enforce the same encoded-input
    /// policy before any decompressor can consume it.
    private static final long MAX_EXTERNAL_INPUT_BYTES = ReadLimits.defaults().maxEncodedBytes();
    /// Maximum bytes accepted for an external uncompressed payload (both encoded and decoded).
    private static final long MAX_EXTERNAL_UNCOMPRESSED_BYTES = Math.min(
            ReadLimits.defaults().maxEncodedBytes(), ReadLimits.defaults().maxDecompressedBytes());

    public static @Nullable Tag readTag(DataReader reader) throws IOException {
        byte tagByte = reader.readByte();
        if (tagByte == 0) {
            return null;
        }

        var type = TagType.getById(tagByte);
        if (type == null) {
            throw new IOException("Invalid tag type: %02x".formatted(Byte.toUnsignedInt(tagByte)));
        }

        Tag tag = type.createTag(reader.readString());
        Access.TAG.readContent(tag, reader);
        return tag;
    }

    public static @Nullable Tag readTagAutoDecompress(RawDataReader reader) throws IOException {
        byte tagByte = reader.lookAheadByte();

        // GZip Magic Number: 0x1F 0x8B 0x08
        if (tagByte == 0x1F) {
            try (var decompressReader = DecompressStreamDataReader.newGZipDataReader(
                    reader, -1, space.minecraftstl.xyml.library.nbt.io.ReadLimits.defaults()
                            .maxDecompressedBytes())) {
                Tag tag = readTag(decompressReader);
                decompressReader.finish();
                return tag;
            }
        }

        // LZ4 Magic Number: "LZ4Block"
        if (tagByte == 'L') {
            try (var decompressReader = DecompressStreamDataReader.newLZ4DataReader(
                    reader, -1, space.minecraftstl.xyml.library.nbt.io.ReadLimits.defaults()
                            .maxDecompressedBytes())) {
                Tag tag = readTag(decompressReader);
                decompressReader.finish();
                return tag;
            }
        }

        // A zlib CMF byte is 0x08, 0x18, ..., 0x78. A raw TAG_String can begin with 0x08,
        // so only a header whose CMF/FLG checksum is valid is considered compressed. A malformed
        // stream is still reported by the strict decompressor rather than silently treated as raw.
        if (isZlibHeader(tagByte, reader.lookAheadByte(1))) {
            int flags = Byte.toUnsignedInt(reader.lookAheadByte(1));
            if ((flags & 0x20) != 0) {
                throw new IOException("Preset-dictionary zlib streams are not supported");
            }
            try (var decompressReader = new ZlibDataReader(reader, -1,
                    space.minecraftstl.xyml.library.nbt.io.ReadLimits.defaults().maxDecompressedBytes())) {
                Tag tag = readTag(decompressReader);
                decompressReader.finish();
                return tag;
            }
        }

        return readTag(reader);
    }

    /// Returns whether a CMF/FLG pair is a legal zlib header.
    private static boolean isZlibHeader(byte cmfByte, byte flagsByte) {
        int cmf = Byte.toUnsignedInt(cmfByte);
        int flags = Byte.toUnsignedInt(flagsByte);
        return (cmf & 0x0F) == 8
                && (cmf >>> 4) <= 7
                && ((cmf << 8) | flags) % 31 == 0;
    }

    public static ChunkRegion readRegion(RawDataReader rawReader, ExternalChunkAccessor accessor) throws IOException {
        if (rawReader.edition != MinecraftEdition.JAVA_EDITION) {
            throw new IllegalArgumentException("Only Java Edition supports region file format");
        }

        final long fileStart = rawReader.position();

        var header = ChunkRegionHeader.readHeader(rawReader);
        var region = new ChunkRegion();
        DocumentBudget documentBudget = new DocumentBudget(
                ReadLimits.defaults().maxDocumentDecompressedBytes());
        final long headerEnd;
        try {
            headerEnd = Math.addExact(fileStart, 2L * ChunkUtils.SECTOR_BYTES);
        } catch (ArithmeticException overflow) {
            throw new IOException("Region header position overflows", overflow);
        }

        assert rawReader.position() == headerEnd;

        for (int localIndex : header.getLocalIndexesSortedByOffset()) {
            if (header.getSectorLength(localIndex) == 0) {
                if (header.getTimestampEpochSeconds(localIndex) != 0L) {
                    region.setChunk(
                            localIndex,
                            new Chunk(Instant.ofEpochSecond(header.getTimestampEpochSeconds(localIndex))));
                }

                continue;
            }

            final long sectorStart;
            try {
                sectorStart = Math.addExact(fileStart, header.getSectorOffsetBytes(localIndex));
            } catch (ArithmeticException overflow) {
                throw new IOException("Region sector position overflows at index " + localIndex, overflow);
            }
            long position = rawReader.position();
            if (position != sectorStart) {
                if (position < sectorStart) {
                    rawReader.skip(sectorStart - position);
                } else {
                    throw new IOException("Invalid chunk metadata: sector offset points to a position before the current position");
                }
            }

            assert rawReader.position() == sectorStart;

            long chunkRawLength = rawReader.readUnsignedInt();
            if (chunkRawLength < 1) {
                throw new IOException("Invalid chunk data length " + chunkRawLength + " at index " + localIndex);
            }

            if (chunkRawLength + 4L > header.getSectorLengthBytes(localIndex)) {
                throw new IOException("Invalid chunk data length " + chunkRawLength + "at index " + localIndex + " (expected <= " + (header.getSectorLengthBytes(localIndex) - 4) + ")");
            }

            long chunkRawContentLength = chunkRawLength - 1L;

            int compressType = rawReader.readUnsignedByte();
            boolean external = (compressType & 0x80) != 0;

            RawDataReader externalReader;
            if (external) {
                if (chunkRawContentLength != 0L) {
                    throw new IOException("Invalid external chunk content length: %d (expected 0 for compression type %d)".formatted(chunkRawContentLength, compressType));
                }

                compressType -= 128;

                long externalInputLimit = compressType == 3
                        ? MAX_EXTERNAL_UNCOMPRESSED_BYTES
                        : MAX_EXTERNAL_INPUT_BYTES;

                InputStream externalChunkInputStream = accessor.openInputStream(ChunkUtils.getLocalX(localIndex), ChunkUtils.getLocalZ(localIndex));
                if (externalChunkInputStream == null) {
                    throw new IOException("Failed to open external chunk file for chunk (%d, %d)".formatted(ChunkUtils.getLocalX(localIndex), ChunkUtils.getLocalZ(localIndex)));
                }
                externalReader = new RawDataReader(new InputSource.OfInputStream(
                        new BoundedInputStream(externalChunkInputStream, externalInputLimit), true),
                        MinecraftEdition.JAVA_EDITION);
            } else {
                externalReader = null;
            }

            try (externalReader) {
                RawDataReader actualRawReader = external ? externalReader : rawReader;

                long compressedLimit = external ? -1L : chunkRawContentLength;
                BoundedDataReader reader = switch (compressType) {
                    case 1 -> DecompressStreamDataReader.newGZipDataReader(actualRawReader, compressedLimit);
                    case 2 -> new ZlibDataReader(actualRawReader, compressedLimit);
                    case 3 -> new UncompressedDataReader(actualRawReader, compressedLimit);
                    case 4 -> DecompressStreamDataReader.newLZ4DataReader(actualRawReader, compressedLimit);
                    default -> throw new IOException("Unsupported compression type: " + compressType);
                };

                try (reader) {
                    var tag = readTag(reader);
                    if (reader instanceof DecompressStreamDataReader decompressReader) {
                        decompressReader.finish();
                    } else if (reader instanceof ZlibDataReader zlibReader) {
                        zlibReader.finish();
                    } else {
                        reader.requireFullyConsumed();
                    }
                    if (external) {
                        actualRawReader.requireExhausted();
                    }
                    documentBudget.consume(reader.decodedBytes());
                    if (tag instanceof CompoundTag rootTag) {
                        region.setChunk(localIndex, new Chunk(
                                Instant.ofEpochSecond(header.getTimestampEpochSeconds(localIndex)),
                                rootTag)
                        );
                    } else {
                        throw new IOException("Unexpected tag type: " + tag);
                    }
                }
            }
        }

        return region;
    }

    private NBTInput() {
    }

    /// Cumulative decoded-byte budget shared by one legacy region read.
    @NotNullByDefault
    private static final class DocumentBudget {
        /// Unconsumed cumulative decoded-byte allowance.
        private long remaining;

        /// Creates a budget with an inclusive maximum.
        /// @param maximum maximum decoded bytes
        private DocumentBudget(long maximum) {
            if (maximum < 0L) {
                throw new IllegalArgumentException("maximum must not be negative");
            }
            remaining = maximum;
        }

        /// Charges one completed chunk payload to the region budget.
        /// @param bytes decoded bytes emitted by the payload reader
        /// @throws IOException if the cumulative limit is exceeded
        private void consume(long bytes) throws IOException {
            if (bytes < 0L) {
                throw new IOException("Decoded payload size is unavailable");
            }
            if (bytes > remaining) {
                throw new IOException("Cumulative decompressed region output exceeds the read limit");
            }
            remaining -= bytes;
        }
    }

    /// Input stream that rejects the first byte beyond an inclusive byte budget.
    ///
    /// A plain limiting stream returning end-of-file at the boundary would let a truncated
    /// compressed member look valid. Probing the delegate when the budget is exhausted preserves
    /// the distinction between an exactly-sized stream and an oversized stream.
    @NotNullByDefault
    private static final class BoundedInputStream extends FilterInputStream {
        /// Unconsumed encoded-byte allowance.
        private long remaining;

        /// Creates a bounded view over an external companion stream.
        ///
        /// @param input underlying companion stream
        /// @param maximumBytes inclusive byte budget
        private BoundedInputStream(InputStream input, long maximumBytes) {
            super(Objects.requireNonNull(input, "input"));
            if (maximumBytes < 0L) {
                throw new IllegalArgumentException("maximumBytes must not be negative");
            }
            remaining = maximumBytes;
        }

        /// {@inheritDoc}
        @Override
        public int read() throws IOException {
            if (remaining == 0L) {
                rejectIfExtraByte();
                return -1;
            }
            int value = in.read();
            if (value >= 0) {
                remaining--;
            }
            return value;
        }

        /// {@inheritDoc}
        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            Objects.checkFromIndexSize(offset, length, bytes.length);
            if (length == 0) {
                return 0;
            }
            if (remaining == 0L) {
                rejectIfExtraByte();
                return -1;
            }
            int requested = (int) Math.min((long) length, remaining);
            int count = in.read(bytes, offset, requested);
            if (count > 0) {
                remaining -= count;
            }
            return count;
        }

        /// {@inheritDoc}
        @Override
        public long skip(long bytes) throws IOException {
            if (bytes < 0L) {
                throw new IllegalArgumentException("bytes must be non-negative");
            }
            if (bytes == 0L) {
                return 0L;
            }
            if (remaining == 0L) {
                rejectIfExtraByte();
            }
            long count = in.skip(Math.min(bytes, remaining));
            if (count > 0L) {
                remaining -= count;
            }
            return count;
        }

        /// Rejects an extra delegate byte after the configured boundary.
        private void rejectIfExtraByte() throws IOException {
            if (in.read() >= 0) {
                throw new IOException("External chunk companion exceeds the encoded read limit");
            }
        }
    }
}
