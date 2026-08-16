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

import org.glavo.nbt.chunk.Chunk;
import org.glavo.nbt.chunk.ChunkRegion;
import org.glavo.nbt.internal.Access;
import org.glavo.nbt.internal.ChunkRegionHeader;
import org.glavo.nbt.internal.ChunkUtils;
import org.glavo.nbt.io.ExternalChunkAccessor;
import org.glavo.nbt.io.MinecraftEdition;
import org.glavo.nbt.tag.CompoundTag;
import org.glavo.nbt.tag.Tag;
import org.glavo.nbt.tag.TagType;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;

public final class NBTInput {

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
            try (var decompressReader = DecompressStreamDataReader.newGZipDataReader(reader, -1)) {
                return readTag(decompressReader);
            }
        }

        // LZ4 Magic Number: "LZ4Block"
        if (tagByte == 'L') {
            try (var decompressReader = DecompressStreamDataReader.newLZ4DataReader(reader, -1)) {
                return readTag(decompressReader);
            }
        }

        return readTag(reader);
    }

    public static ChunkRegion readRegion(RawDataReader rawReader, ExternalChunkAccessor accessor) throws IOException {
        if (rawReader.edition != MinecraftEdition.JAVA_EDITION) {
            throw new IllegalArgumentException("Only Java Edition supports region file format");
        }

        final long fileStart = rawReader.position();

        var header = ChunkRegionHeader.readHeader(rawReader);
        var region = new ChunkRegion();

        assert rawReader.position() == fileStart + 2 * ChunkUtils.SECTOR_BYTES;

        for (int localIndex : header.getLocalIndexesSortedByOffset()) {
            if (header.getSectorLength(localIndex) == 0) {
                if (header.getTimestampEpochSeconds(localIndex) != 0L) {
                    region.setChunk(
                            localIndex,
                            new Chunk(Instant.ofEpochSecond(header.getTimestampEpochSeconds(localIndex))));
                }

                continue;
            }

            long sectorStart = fileStart + header.getSectorOffsetBytes(localIndex);
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
            boolean external = compressType > 128;

            RawDataReader externalReader;
            if (external) {
                if (chunkRawContentLength != 0L) {
                    throw new IOException("Invalid chunk content length: %d (expected 0 for compression type %d)".formatted(chunkRawContentLength, compressType));
                }

                compressType -= 128;

                InputStream externalChunkInputStream = accessor.openInputStream(ChunkUtils.getLocalX(localIndex), ChunkUtils.getLocalZ(localIndex));
                if (externalChunkInputStream == null) {
                    throw new IOException("Failed to open external chunk file for chunk (%d, %d)".formatted(ChunkUtils.getLocalX(localIndex), ChunkUtils.getLocalZ(localIndex)));
                }
                externalReader = new RawDataReader(new InputSource.OfInputStream(externalChunkInputStream, true), MinecraftEdition.JAVA_EDITION);
            } else {
                externalReader = null;
            }

            try (externalReader) {
                RawDataReader actualRawReader;

                if (external) {
                    actualRawReader = externalReader;
                    externalReader.skip(5L);
                } else {
                    actualRawReader = rawReader;
                }

                BoundedDataReader reader = switch (compressType) {
                    case 1 -> DecompressStreamDataReader.newGZipDataReader(actualRawReader, chunkRawContentLength);
                    case 2 -> new ZlibDataReader(actualRawReader, chunkRawContentLength);
                    case 3 -> new UncompressedDataReader(actualRawReader, chunkRawContentLength);
                    case 4 -> DecompressStreamDataReader.newLZ4DataReader(actualRawReader, chunkRawContentLength);
                    default -> throw new IOException("Unsupported compression type: " + compressType);
                };

                try (reader) {
                    var tag = readTag(reader);
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
}
