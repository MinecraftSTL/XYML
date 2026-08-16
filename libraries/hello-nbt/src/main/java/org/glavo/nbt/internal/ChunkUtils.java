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
package org.glavo.nbt.internal;

import org.jetbrains.annotations.Range;

public final class ChunkUtils {
    public static final int SECTOR_BYTES = 4096;

    public static final int CHUNKS_PER_REGION_SIDE = 32;
    public static final int CHUNKS_PRE_REGION = CHUNKS_PER_REGION_SIDE * CHUNKS_PER_REGION_SIDE;
    public static final int CHUNKS_PER_REGION_SIDE_SHIFT = 5;
    public static final int CHUNKS_PER_REGION_SIDE_MASK = (CHUNKS_PER_REGION_SIDE - 1);

    @Range(from = 0, to = 1023)
    public static int toLocalIndex(
            @Range(from = 0, to = 31) int localX,
            @Range(from = 0, to = 31) int localZ) {
        return localX + (localZ << CHUNKS_PER_REGION_SIDE_SHIFT);
    }

    @Range(from = 0, to = 31)
    public static int getLocalX(@Range(from = 0, to = 1023) int localIndex) {
        return localIndex & CHUNKS_PER_REGION_SIDE_MASK;
    }

    @Range(from = 0, to = 31)
    public static int getLocalZ(@Range(from = 0, to = 1023) int localIndex) {
        return localIndex >>> CHUNKS_PER_REGION_SIDE_SHIFT;
    }

    public static int toGlobalIndex(int regionIndex, @Range(from = 0, to = 31) int chunkLocalIndex) {
        return (regionIndex << CHUNKS_PER_REGION_SIDE_SHIFT) + chunkLocalIndex;
    }

    private ChunkUtils() {
    }
}
