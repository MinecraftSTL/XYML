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
package org.glavo.nbt.internal.output;

import org.glavo.nbt.chunk.Chunk;
import org.glavo.nbt.chunk.ChunkRegion;
import org.glavo.nbt.internal.Access;
import org.glavo.nbt.internal.ChunkRegionHeader;
import org.glavo.nbt.internal.ChunkUtils;
import org.glavo.nbt.io.ExternalChunkAccessor;
import org.glavo.nbt.io.MinecraftEdition;
import org.glavo.nbt.tag.Tag;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;

public final class NBTOutput {
    public static void writeTag(DataWriter writer, Tag tag) throws IOException {
        writer.writeByte(tag.getType().id()); // implicit null check
        writer.writeString(tag.getName());
        Access.TAG.writeContent(tag, writer);
    }

    private static final class TempOutputStream extends ByteArrayOutputStream {
        public TempOutputStream(int size) {
            super(size);
        }

        byte[] getBuffer() {
            return buf;
        }
    }

    private static void writeFully(WritableByteChannel channel, ByteBuffer buffer) throws IOException {
        while (buffer.hasRemaining()) {
            int n = channel.write(buffer);
            if (n == 0) {
                throw new IOException("No bytes written");
            }
        }
    }

    public static void writeRegion(RawDataWriter writer, ChunkRegion region, ExternalChunkAccessor accessor) throws IOException {
        assert writer.edition == MinecraftEdition.JAVA_EDITION : "Only Java Edition supports region file format";

        var buffers = new ByteBuffer[ChunkUtils.CHUNKS_PRE_REGION];

        var deflater = new Deflater();
        try {
            for (int i = 0; i < ChunkUtils.CHUNKS_PRE_REGION; i++) {
                Chunk chunk = region.getChunk(i);
                if (chunk.getRootTag() == null) {
                    continue;
                }

                var tempOutputStream = new TempOutputStream(ChunkUtils.SECTOR_BYTES);

                deflater.reset();
                try (var deflaterOutputStream = new DeflaterOutputStream(tempOutputStream, deflater);
                     var chunkWriter = new RawDataWriter(new OutputTarget.OfOutputStream(deflaterOutputStream, false), MinecraftEdition.JAVA_EDITION)) {
                    writeTag(chunkWriter, chunk.getRootTag());
                }

                buffers[i] = ByteBuffer.wrap(tempOutputStream.getBuffer(), 0, tempOutputStream.size());
            }
        } finally {
            deflater.end();
        }

        var header = new ChunkRegionHeader();

        int currentSector = 2; // Skip the header and the timestamp
        for (int i = 0; i < ChunkUtils.CHUNKS_PRE_REGION; i++) {
            Chunk chunk = region.getChunk(i);
            long epochSecondsLong = chunk.getTimestamp().toEpochMilli() / 1000L;
            int epochSeconds = epochSecondsLong <= Integer.toUnsignedLong(-1)
                    ? (int) epochSecondsLong
                    : -1;

            header.setTimestampEpochSeconds(i, epochSeconds);

            ByteBuffer buffer = buffers[i];
            if (buffer != null && buffer.hasRemaining()) {
                long bytes = buffer.remaining() + 5L;
                int sectors = (int) ((bytes + ChunkUtils.SECTOR_BYTES - 1) / ChunkUtils.SECTOR_BYTES);
                if (sectors <= 0xFF) {
                    header.setSectorInfo(i, currentSector, sectors);
                    currentSector += sectors;
                } else {
                    // Oversized chunk
                    header.setSectorInfo(i, currentSector, 1);
                    currentSector += 1;
                }
            } else {
                header.setSectorInfo(i, 0, 0);
            }
        }

        long startPosition = writer.position();

        writer.writeIntArrayDirect(header.sectorInfo);
        writer.writeIntArrayDirect(header.timestamps);

        assert writer.position() - startPosition == 2 * ChunkUtils.SECTOR_BYTES
                : "Header size mismatch: expected " + (2 * ChunkUtils.SECTOR_BYTES) + ", got " + (writer.position() - startPosition);

        for (int i = 0; i < ChunkUtils.CHUNKS_PRE_REGION; i++) {
            ByteBuffer buffer = buffers[i];
            if (buffer == null || !buffer.hasRemaining()) {
                continue;
            }

            long sectorOffsetBytes = header.getSectorOffsetBytes(i);

            assert writer.position() - startPosition == sectorOffsetBytes
                    : "Chunk header position mismatch for chunk " + i + ": expected " + sectorOffsetBytes + ", got " + (writer.position() - startPosition);

            int bytesRawContent = buffer.remaining();
            long bytesContent = bytesRawContent + 1;
            long bytes = bytesContent + 4;
            long bytesSkip = header.getSectorLengthBytes(i) - bytes;

            assert bytesSkip >= 0 : "Sector length mismatch for chunk " + i + ": expected less than or equal to " + header.getSectorLengthBytes(i) + ", got " + bytes;

            writer.writeInt((int) bytesContent);
            if (bytes <= ChunkUtils.SECTOR_BYTES * 0xFF) {
                writer.writeByte((byte) 2); // Zlib
                writer.writeByteBufferDirect(buffer);
            } else {
                // External chunk

                assert header.getSectorLength(i) == 1 : "Sector length mismatch for chunk " + i;
                writer.writeByte((byte) (2 + 128)); // Zlib + External

                try (var outputStream = accessor.openOutputStream(ChunkUtils.getLocalX(i), ChunkUtils.getLocalZ(i))) {
                    if (outputStream == null) {
                        throw new IOException("Failed to open external chunk file for chunk (%d, %d)".formatted(ChunkUtils.getLocalX(i), ChunkUtils.getLocalZ(i)));
                    }
                    outputStream.write(new byte[5]);
                    outputStream.write(buffer.array(), buffer.arrayOffset() + buffer.position(), buffer.limit());
                }
            }

            assert writer.position() - startPosition == sectorOffsetBytes + bytes : "Chunk content position mismatch for chunk " + i + ": expected " + (sectorOffsetBytes + bytes) + ", got " + (writer.position() - startPosition);

            writer.skip(bytesSkip);
        }
    }

    public static void writeRegion(SeekableByteChannel channel, ChunkRegion region, ExternalChunkAccessor accessor) throws IOException {
        long startPosition = channel.position();

        var header = new ChunkRegionHeader();

        channel.position(startPosition + 2 * ChunkUtils.SECTOR_BYTES);
        channel.truncate(startPosition + 2 * ChunkUtils.SECTOR_BYTES);

        int currentSector = 2; // Skip the header and the timestamp

        var deflater = new Deflater();
        TempOutputStream tempOutputStream = new TempOutputStream(ChunkUtils.SECTOR_BYTES);

        ByteBuffer chunkHeaderBuffer = ByteBuffer.allocate(5);
        try {
            for (int i = 0; i < ChunkUtils.CHUNKS_PRE_REGION; i++) {
                Chunk chunk = region.getChunk(i);
                long epochSecondsLong = chunk.getTimestamp().toEpochMilli() / 1000L;
                int epochSeconds;
                if (epochSecondsLong < 0) {
                    epochSeconds = 0;
                } else if (epochSecondsLong > Integer.toUnsignedLong(-1)) {
                    epochSeconds = -1;
                } else {
                    epochSeconds = (int) epochSecondsLong;
                }

                header.setTimestampEpochSeconds(i, epochSeconds);

                if (chunk.getRootTag() != null) {
                    deflater.reset();
                    try (var output = new DeflaterOutputStream(tempOutputStream, deflater);
                         var writer = new RawDataWriter(new OutputTarget.OfOutputStream(output, true), MinecraftEdition.JAVA_EDITION)) {
                        writeTag(writer, chunk.getRootTag());
                    }
                }

                int bytesRawContent = tempOutputStream.size(); // Bytes of compressed tag
                if (bytesRawContent == 0) {
                    continue;
                }

                long sectorOffsetBytes = (long) currentSector * ChunkUtils.SECTOR_BYTES;

                // Bytes of compressed tag + 1 byte for compression type
                long bytesContent = bytesRawContent + 1;

                // Bytes of compressed tag + 1 byte for compression type + 4 bytes for length
                long bytes = bytesContent + 4;

                int sectors = (int) ((bytes + ChunkUtils.SECTOR_BYTES - 1) / ChunkUtils.SECTOR_BYTES);

                int actualSectors;
                int actualBytes;

                chunkHeaderBuffer.clear();
                channel.position(startPosition + sectorOffsetBytes);

                chunkHeaderBuffer.putInt((int) bytesContent);
                if (sectors <= 0xFF) {
                    actualSectors = sectors;
                    actualBytes = (int) bytes;

                    chunkHeaderBuffer.put((byte) 2); // Zlib
                    header.setSectorInfo(i, currentSector, sectors);

                    chunkHeaderBuffer.flip();
                    writeFully(channel, chunkHeaderBuffer);
                    writeFully(channel, ByteBuffer.wrap(tempOutputStream.getBuffer(), 0, bytesRawContent));
                } else {
                    // Oversized chunk
                    actualSectors = 1;
                    actualBytes = 5;

                    chunkHeaderBuffer.put((byte) (2 + 128)); // Zlib + External
                    header.setSectorInfo(i, currentSector, 1);

                    chunkHeaderBuffer.flip();
                    writeFully(channel, chunkHeaderBuffer);

                    try (var outputStream = accessor.openOutputStream(ChunkUtils.getLocalX(i), ChunkUtils.getLocalZ(i))) {
                        if (outputStream == null) {
                            throw new IOException("Failed to open external chunk file for chunk (%d, %d)".formatted(ChunkUtils.getLocalX(i), ChunkUtils.getLocalZ(i)));
                        }
                        outputStream.write(new byte[5]);
                        outputStream.write(tempOutputStream.getBuffer(), 0, bytesRawContent);
                    }
                }

                tempOutputStream.reset();

                long endOffset = sectorOffsetBytes + actualBytes;
                assert channel.position() == startPosition + endOffset
                        : "Chunk content position mismatch for chunk %d: expected %d, got %d".formatted(i, startPosition + endOffset, channel.position());

                if (i == ChunkUtils.CHUNKS_PRE_REGION - 1) {
                    // Truncate the channel to the end of the last chunk
                    channel.truncate(startPosition + endOffset);

                    long sectorEnd = sectorOffsetBytes + (long) actualSectors * ChunkUtils.SECTOR_BYTES;

                    assert sectorEnd >= endOffset : "Sector end offset is less than chunk end offset: " + sectorEnd + " < " + endOffset;

                    if (endOffset < sectorEnd) {
                        // Fill the remaining space with zeros
                        channel.position(startPosition + sectorEnd - 1);
                        chunkHeaderBuffer.clear();
                        chunkHeaderBuffer.put((byte) 0);
                        chunkHeaderBuffer.flip();
                        writeFully(channel, chunkHeaderBuffer);
                    }
                }

                currentSector += actualSectors;
            }

            channel.position(startPosition);

            ByteBuffer tempBuffer = ByteBuffer.wrap(tempOutputStream.getBuffer(), 0, ChunkUtils.SECTOR_BYTES);
            tempBuffer.asIntBuffer().put(header.sectorInfo);
            writeFully(channel, tempBuffer);

            tempBuffer.position(0).limit(ChunkUtils.SECTOR_BYTES);
            tempBuffer.asIntBuffer().put(header.timestamps);
            writeFully(channel, tempBuffer);
        } finally {
            deflater.end();
        }

    }

    private NBTOutput() {

    }
}
