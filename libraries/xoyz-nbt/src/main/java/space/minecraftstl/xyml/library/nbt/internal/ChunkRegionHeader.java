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
package space.minecraftstl.xyml.library.nbt.internal;

import org.jetbrains.annotations.NotNullByDefault;
import space.minecraftstl.xyml.library.nbt.internal.input.DataReader;

import java.io.IOException;
import java.util.Comparator;
import java.util.stream.IntStream;

import static space.minecraftstl.xyml.library.nbt.internal.ChunkUtils.*;

/// Mutable representation of the two fixed Anvil region header sectors.
///
/// This internal transport type retains its historical public arrays for the stream codec. Newly
/// decoded headers are structurally validated before they are returned.
@NotNullByDefault
public final class ChunkRegionHeader {
    /// Reads and structurally validates one complete region header.
    ///
    /// @param reader source positioned at the location table
    /// @return decoded mutable header
    /// @throws IOException if the header is truncated, inconsistent, or contains overlapping sectors
    public static ChunkRegionHeader readHeader(DataReader reader) throws IOException {
        int[] sectorInfo = reader.readIntArray(CHUNKS_PRE_REGION);
        int[] timestamps = reader.readIntArray(CHUNKS_PRE_REGION);

        ChunkRegionHeader header = new ChunkRegionHeader(sectorInfo, timestamps);
        header.validate();
        return header;
    }

    /// Validates sector references without requiring access to the complete file length.
    ///
    /// Empty entries must use both an offset and length of zero. Occupied entries must begin
    /// after the two-sector header and may not overlap any other occupied entry.
    ///
    /// @throws IOException if the header contains an invalid sector reference
    public void validate() throws IOException {
        int[] occupied = new int[CHUNKS_PRE_REGION];
        int occupiedCount = 0;
        for (int index = 0; index < CHUNKS_PRE_REGION; index++) {
            int offset = getSectorOffset(index);
            int length = getSectorLength(index);
            if ((offset == 0) != (length == 0)) {
                throw new IOException("Invalid region header entry at chunk " + index + ": offset and length must both be zero or non-zero");
            }
            if (length == 0) {
                continue;
            }
            if (offset < 2) {
                throw new IOException("Invalid region header entry at chunk " + index + ": sector offset " + offset + " overlaps the header");
            }
            occupied[occupiedCount++] = index;
        }

        for (int i = 0; i < occupiedCount; i++) {
            int left = occupied[i];
            int leftStart = getSectorOffset(left);
            long leftEnd = (long) leftStart + getSectorLength(left);
            for (int j = i + 1; j < occupiedCount; j++) {
                int right = occupied[j];
                int rightStart = getSectorOffset(right);
                long rightEnd = (long) rightStart + getSectorLength(right);
                if (leftStart < rightEnd && rightStart < leftEnd) {
                    throw new IOException("Overlapping region sectors for chunks " + left + " and " + right);
                }
            }
        }
    }

    public final int[] sectorInfo;
    public final int[] timestamps;

    public ChunkRegionHeader() {
        this.sectorInfo = new int[CHUNKS_PRE_REGION];
        this.timestamps = new int[CHUNKS_PRE_REGION];
    }

    public ChunkRegionHeader(int[] sectorInfo, int[] timestamps) {
        assert sectorInfo.length == CHUNKS_PRE_REGION;
        assert timestamps.length == CHUNKS_PRE_REGION;

        this.sectorInfo = sectorInfo;
        this.timestamps = timestamps;
    }

    public int getSectorOffset(int index) {
        return sectorInfo[index] >>> 8;
    }

    public int getSectorLength(int index) {
        return sectorInfo[index] & 0xFF;
    }

    public void setSectorInfo(int index, int sectorOffset, int sectorLength) {
        if (sectorOffset < 0 || sectorOffset > 0xFF_FFFF) {
            throw new IllegalArgumentException("Sector offset out of range: " + sectorOffset);
        }
        if (sectorLength < 0 || sectorLength > 0xFF) {
            throw new IllegalArgumentException("Sector length out of range: " + sectorLength);
        }
        sectorInfo[index] = (sectorOffset << 8) | sectorLength;
    }

    public long getSectorOffsetBytes(int index) {
        return (long) getSectorOffset(index) * SECTOR_BYTES;
    }

    public long getSectorLengthBytes(int index) {
        return (long) getSectorLength(index) * SECTOR_BYTES;
    }

    public long getTimestampEpochSeconds(int index) {
        return Integer.toUnsignedLong(timestamps[index]);
    }

    public void setTimestampEpochSeconds(int index, int epochSeconds) {
        this.timestamps[index] = epochSeconds;
    }

    public int[] getLocalIndexesSortedByOffset() {
        return IntStream.range(0, CHUNKS_PRE_REGION)
                .boxed()
                .sorted(Comparator.comparingInt(this::getSectorOffset).thenComparingInt(Integer::intValue))
                .mapToInt(Integer::intValue)
                .toArray();
    }

    @Override
    public String toString() {
        var builder = new StringBuilder();

        builder.append("ChunkRegionHeader[");
        for (int i = 0; i < CHUNKS_PRE_REGION; i++) {
            builder.append("\n    ")
                    .append(i)
                    .append("(x=").append(ChunkUtils.getLocalX(i))
                    .append(", z=").append(ChunkUtils.getLocalZ(i))
                    .append("): ")
                    .append("SectorOffset=").append(getSectorOffset(i))
                    .append(", SectorLength=").append(getSectorLength(i))
                    .append(", Timestamp=").append(getTimestampEpochSeconds(i));
        }

        builder.append("\n]");
        return builder.toString();
    }
}
