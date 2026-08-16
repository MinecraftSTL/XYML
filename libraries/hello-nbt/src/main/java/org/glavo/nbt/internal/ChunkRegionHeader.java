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
package org.glavo.nbt.internal;

import org.glavo.nbt.internal.input.DataReader;

import java.io.IOException;
import java.util.Arrays;
import java.util.Comparator;
import java.util.stream.IntStream;

import static org.glavo.nbt.internal.ChunkUtils.*;

public final class ChunkRegionHeader {
    public static ChunkRegionHeader readHeader(DataReader reader) throws IOException {
        int[] sectorInfo = reader.readIntArray(CHUNKS_PRE_REGION);
        int[] timestamps = reader.readIntArray(CHUNKS_PRE_REGION);

        return new ChunkRegionHeader(sectorInfo, timestamps);
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
