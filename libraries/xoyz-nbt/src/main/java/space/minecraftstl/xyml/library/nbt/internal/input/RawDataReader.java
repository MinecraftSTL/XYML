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

import space.minecraftstl.xyml.library.nbt.io.MinecraftEdition;
import space.minecraftstl.xyml.library.nbt.io.ReadLimits;
import space.minecraftstl.xyml.library.nbt.internal.StringCache;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Map;

/// Root input owner which shares caches and structural limits with all bounded payload readers.
@NotNullByDefault
public final class RawDataReader extends DataReader implements Closeable {
    public static final int DEFAULT_BUFFER_SIZE = 8192;

    // Used for reading UTF-8 strings
    private static final StringCache DEFAULT_CACHE = new StringCache(
            // Minecraft level.dat tags
            "Data", "allowCommands", "clearWeatherTime", "CustomBossEvents", "Players", "Color", "CreateWorldFog", "DarkenScreen", "Max", "Value", "Name", "Overlay", "PlayBossMusic", "Visible", "DataPacks", "Disabled", "Enabled", "DataVersion", "DayTime", "Difficulty", "DifficultyLocked", "DimensionData", "DragonFight", "ExitPortalLocation", "1", "X", "Y", "Z", "Gateways", "DragonKilled", "DragonUUIDLeast", "DragonUUIDMost", "PreviouslyKilled", "game", "Rule", "WorldGenSettings", "bonus", "seed", "generate", "dimensions", "GameType", "generatorName", "generatorOptions", "generatorVersion", "hardcore", "initialized", "LastPlayed", "LevelName", "MapFeatures", "Player", "raining", "rainTime", "RandomSeed", "SizeOnDisk", "SpawnX", "SpawnY", "SpawnZ", "spawn", "dimension", "pitch", "yaw", "pos", "thundering", "thunderTime", "Time", "version", "Version", "Id", "Series", "Snapshot", "WanderingTraderSpawnChance", "WanderingTraderSpawnDelay", "WasModded",
            // generatorOptions
            "biome_source", "options", "biomes", "size", "type", "chunk_generator", "default_block", "default_fluid", "structures", "layers", "block", "height", "biome", "flat_world_options"
    );

    public final InputSource source;
    public final MinecraftEdition edition;

    private final InputBuffer buffer;
    private final long sourceStartPosition;
    /// Structure budget shared by inline and external payload readers in one region operation.
    private final StructureBudget structureBudget;

    public final StringCache stringCache = DEFAULT_CACHE;
    @Nullable StringBuilder charsBuffer;

    private @Nullable Map<CacheKey<?>, Object> cacheMap;

    /// Creates a raw reader with the default NBT structure limits.
    ///
    /// @param source byte source owned by this reader
    /// @param edition NBT byte order and string encoding
    public RawDataReader(InputSource source, MinecraftEdition edition) {
        this(source, edition, new StructureBudget(ReadLimits.defaults()));
    }

    /// Creates a raw reader sharing an existing structure budget.
    ///
    /// @param source byte source owned by this reader
    /// @param edition NBT byte order and string encoding
    /// @param structureBudget document budget shared with related readers
    private RawDataReader(InputSource source, MinecraftEdition edition, StructureBudget structureBudget) {
        this.source = source;
        this.edition = edition;
        this.buffer = InputBuffer.allocate(DEFAULT_BUFFER_SIZE, source.supportDirectBuffer(), edition.byteOrder());
        this.sourceStartPosition = source.position();
        this.structureBudget = structureBudget;
    }

    /// Creates another owning reader whose tags count toward this reader's document budget.
    ///
    /// @param source external payload source
    /// @return a reader sharing this operation's structure budget
    RawDataReader newSharedStructureReader(InputSource source) {
        return new RawDataReader(source, edition, structureBudget);
    }

    /// Reserves one logical tag and enters its nesting level.
    ///
    /// @throws IOException if the document node or nesting limit is exhausted
    void enterStructureTag() throws IOException {
        structureBudget.enter();
    }

    /// Leaves the current nesting level.
    void leaveStructureTag() {
        structureBudget.leave();
    }

    /// Validates one collection length against the default structural policy.
    ///
    /// @param length declared collection size
    /// @throws IOException if the count is negative or exceeds the limit
    void requireStructureCollectionLength(int length) throws IOException {
        structureBudget.requireCollectionLength(length);
    }

    /// Reserves non-container list elements before their objects are allocated.
    ///
    /// @param count declared leaf-tag count
    /// @throws IOException if the document node or depth limit would be exceeded
    void reserveStructureLeafTags(int count) throws IOException {
        structureBudget.reserveLeafTags(count);
    }

    @Override
    public RawDataReader getRawReader() {
        return this;
    }

    @Override
    public InputBuffer getBuffer() {
        return buffer;
    }

    @Override
    public void ensureBufferRemaining(int required) throws IOException {
        getRawReader().source.fillBuffer(getBuffer(), required);
    }

    public long position() {
        long position = source.position() - sourceStartPosition - buffer.remaining();
        assert position >= 0;
        return position;
    }

    public void skip(long bytes) throws IOException {
        if (bytes < 0) {
            throw new IllegalArgumentException("bytes must be non-negative");
        }

        if (bytes == 0L) {
            return;
        }

        int bytesDrop = (int) Math.min(buffer.remaining(), bytes);
        buffer.drop(bytesDrop);

        bytes -= bytesDrop;
        if (bytes > 0) {
            source.skip(bytes);
        }
    }

    /// Ensures that no bytes remain in the underlying source.
    ///
    /// This method is used by standalone codecs. Region readers deliberately do not use it because
    /// sector padding is part of the region container rather than the encoded tag.
    public void requireExhausted() throws IOException {
        try {
            ensureBufferRemaining(1);
        } catch (EOFException exception) {
            return;
        }
        throw new IOException("Trailing data after NBT tag");
    }

    private @Nullable InputBuffer decompressBuffer;

    public InputBuffer getDecompressBuffer() {
        if (decompressBuffer == null) {
            return InputBuffer.allocate(DEFAULT_BUFFER_SIZE, false, edition.byteOrder());
        } else {
            decompressBuffer.drop();
            return decompressBuffer;
        }
    }

    public void releaseDecompressBuffer(InputBuffer buffer) {
        buffer.drop();
        decompressBuffer = buffer;
    }

    @Override
    @SuppressWarnings({"rawtypes", "unchecked"})
    public void close() throws IOException {
        source.close();
        if (cacheMap != null) {
            for (var entry : new ArrayList<>(cacheMap.entrySet())) {
                ((CacheKey) entry.getKey()).close(entry.getValue());
            }

            cacheMap.clear();
        }
    }

    @SuppressWarnings("unchecked")
    public static abstract class CacheKey<T> {

        public T get(RawDataReader rawReader) {
            if (rawReader.cacheMap != null) {
                T value = (T) rawReader.cacheMap.remove(this);
                if (value != null) {
                    return value;
                }
            }

            return create(rawReader);
        }

        public void release(RawDataReader rawReader, T value) {
            if (rawReader.cacheMap == null) {
                rawReader.cacheMap = new IdentityHashMap<>();
            }

            T oldValue = (T) rawReader.cacheMap.put(this, value);
            if (oldValue != null) {
                close(oldValue);
            }
        }

        protected abstract T create(RawDataReader rawReader);

        public void close(T value) {
        }
    }

    /// Mutable structure budget shared by all readers participating in one logical document read.
    @NotNullByDefault
    private static final class StructureBudget {
        /// Maximum number of logical tags in the document.
        private final long maxNodes;
        /// Maximum active tag nesting depth.
        private final long maxDepth;
        /// Maximum list or primitive-array element count.
        private final long maxArrayLength;
        /// Number of logical tags already entered.
        private long nodes;
        /// Current active nesting depth.
        private long depth;

        /// Creates a budget from an immutable read policy.
        ///
        /// @param limits source policy
        private StructureBudget(ReadLimits limits) {
            maxNodes = limits.maxNodes();
            maxDepth = limits.maxDepth();
            maxArrayLength = limits.maxArrayLength();
        }

        /// Reserves one node before its payload is materialized.
        ///
        /// @throws IOException if the node or depth limit is exhausted
        private void enter() throws IOException {
            if (nodes >= maxNodes) {
                throw new IOException("NBT node count exceeds the read limit");
            }
            if (depth >= maxDepth) {
                throw new IOException("NBT nesting depth exceeds the read limit");
            }
            nodes++;
            depth++;
        }

        /// Releases one active nesting level without refunding its node.
        private void leave() {
            if (depth <= 0L) {
                throw new IllegalStateException("No NBT tag is currently active");
            }
            depth--;
        }

        /// Validates one declared list or primitive-array element count.
        ///
        /// @param length declared collection size
        /// @throws IOException if the count is negative or exceeds the limit
        private void requireCollectionLength(int length) throws IOException {
            if (length < 0 || (long) length > maxArrayLength) {
                throw new IOException("NBT collection length exceeds the read limit");
            }
        }

        /// Reserves leaf list elements as one allocation-free budget operation.
        ///
        /// @param count declared leaf-tag count
        /// @throws IOException if the node or depth limit would be exceeded
        private void reserveLeafTags(int count) throws IOException {
            requireCollectionLength(count);
            if (count > 0 && depth >= maxDepth) {
                throw new IOException("NBT nesting depth exceeds the read limit");
            }
            if ((long) count > maxNodes - nodes) {
                throw new IOException("NBT node count exceeds the read limit");
            }
            nodes += count;
        }
    }
}
