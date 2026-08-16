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
package org.glavo.nbt.io;

import net.jpountz.lz4.LZ4BlockInputStream;
import org.apache.commons.io.input.BoundedInputStream;
import org.glavo.nbt.chunk.ChunkRegion;
import org.glavo.nbt.TestResources;
import org.glavo.nbt.internal.ChunkRegionHeader;
import org.glavo.nbt.internal.input.InputSource;
import org.glavo.nbt.internal.input.RawDataReader;
import org.glavo.nbt.tag.CompoundTag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.shadow.de.siegmar.fastcsv.util.Nullable;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.zip.GZIPInputStream;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

import static org.glavo.nbt.internal.ChunkUtils.CHUNKS_PRE_REGION;
import static org.junit.jupiter.api.Assertions.*;

public final class ReadChunkRegionTest {

    @Test
    public void testReadHeader() throws Exception {
        Path resource = TestResources.getResource("/assets/region/zlib.mca");

        int[] sectorOffsets = new int[CHUNKS_PRE_REGION];
        int[] sectorLengths = new int[CHUNKS_PRE_REGION];
        int[] timestamps = new int[CHUNKS_PRE_REGION];

        try (var input = new DataInputStream(new BufferedInputStream(Files.newInputStream(resource)))) {
            for (int localIndex = 0; localIndex < CHUNKS_PRE_REGION; localIndex++) {
                int b0 = input.readUnsignedByte();
                int b1 = input.readUnsignedByte();
                int b2 = input.readUnsignedByte();
                int b3 = input.readUnsignedByte();

                sectorOffsets[localIndex] = (b0 << 16) + (b1 << 8) + b2;
                sectorLengths[localIndex] = b3;
            }

            for (int localIndex = 0; localIndex < CHUNKS_PRE_REGION; localIndex++) {
                timestamps[localIndex] = input.readInt();
            }
        }

        ChunkRegionHeader header;
        try (var input = new RawDataReader(new InputSource.OfInputStream(Files.newInputStream(resource), true), MinecraftEdition.JAVA_EDITION)) {
            header = ChunkRegionHeader.readHeader(input);
        }

        for (int localIndex = 0; localIndex < CHUNKS_PRE_REGION; localIndex++) {
            assertEquals(sectorOffsets[localIndex], header.getSectorOffset(localIndex), "Sector offset mismatch for local index " + localIndex);
            assertEquals(sectorLengths[localIndex], header.getSectorLength(localIndex), "Sector length mismatch for local index " + localIndex);
            assertEquals(timestamps[localIndex], header.timestamps[localIndex], "Timestamp mismatch for local index " + localIndex);
        }

        int currentSectorOffset = 0;
        for (int localIndex : header.getLocalIndexesSortedByOffset()) {
            if (header.getSectorOffset(localIndex) < currentSectorOffset) {
                fail("Sector offset is not sorted for local index %d: %d < %d".formatted(localIndex, header.getSectorOffset(localIndex), currentSectorOffset));
            }

            currentSectorOffset = header.getSectorOffset(localIndex) + header.getSectorLength(localIndex);
        }
    }

    private static final class RefChunkRegion {
        private final @Nullable CompoundTag[] chunks = new CompoundTag[CHUNKS_PRE_REGION];
        private final Instant[] timestamps = new Instant[CHUNKS_PRE_REGION];

        @SuppressWarnings("deprecation")
        static RefChunkRegion load(Path file) throws IOException {
            try (RandomAccessFile r = new RandomAccessFile(file.toFile(), "r")) {
                var result = new RefChunkRegion();

                byte[] header = new byte[4096];
                byte[] timestamps = new byte[4096];
                byte[] buffer = new byte[1024 * 1024]; // The maximum size of each chunk is 1MiB
                Inflater inflater = new Inflater();

                r.readFully(header);

                r.readFully(timestamps);
                ByteBuffer timestampsBuffer = ByteBuffer.wrap(timestamps);

                for (int i = 0; i < CHUNKS_PRE_REGION; i++) {
                    result.timestamps[i] = Instant.ofEpochSecond(Integer.toUnsignedLong(timestampsBuffer.getInt()));
                }

                for (int i = 0; i < 4096; i += 4) {
                    int offset = ((header[i] & 0xff) << 16) + ((header[i + 1] & 0xff) << 8) + (header[i + 2] & 0xff);
                    int length = header[i + 3] & 0xff;

                    if (offset == 0 || length == 0) {
                        continue;
                    }

                    r.seek(offset * 4096L);
                    r.readFully(buffer, 0, length * 4096);

                    int chunkLength = ((buffer[0] & 0xff) << 24) + ((buffer[1] & 0xff) << 16) + ((buffer[2] & 0xff) << 8) + (buffer[3] & 0xff);

                    InputStream input = new ByteArrayInputStream(buffer);
                    input.skipNBytes(5);
                    input = BoundedInputStream.builder().setCount(chunkLength - 1).setInputStream(input).get();

                    switch (buffer[4]) {
                        case 0x01:
                            // GZip
                            input = new GZIPInputStream(input);
                            break;
                        case 0x02:
                            // Zlib
                            inflater.reset();
                            input = new InflaterInputStream(input, inflater);
                            break;
                        case 0x03:
                            // Uncompressed
                            break;
                        case 0x04:
                            // LZ4
                            input = new LZ4BlockInputStream(input);
                            break;
                        default:
                            throw new IOException("Unsupported compression method: " + Integer.toHexString(buffer[4] & 0xff));
                    }

                    try (InputStream in = input) {
                        var tag = NBTCodec.of().readTag(in);

                        if (tag instanceof CompoundTag chunk) {
                            result.chunks[i / 4] = chunk;
                        } else {
                            throw new IOException("Unexpected tag: " + tag);
                        }
                    }
                }
                return result;
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"/assets/region/zlib.mca", "/assets/region/lz4.mca"})
    public void testReadRegion(String path) throws IOException {
        Path resource = TestResources.getResource(path);

        RefChunkRegion expected = RefChunkRegion.load(resource);
        ChunkRegion actual = NBTCodec.of().readRegion(resource);

        for (int localIndex = 0; localIndex < CHUNKS_PRE_REGION; localIndex++) {
            var chunk = actual.getChunk(localIndex);

            assertEquals(expected.timestamps[localIndex], chunk.getTimestamp());

            if (expected.chunks[localIndex] == null) {
                assertNull(chunk.getRootTag());
            } else {
                assertEquals(localIndex, chunk.getLocalIndex());
                assertEquals(expected.chunks[localIndex], chunk.getRootTag());
            }
        }
    }
}
