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

import org.glavo.nbt.io.MinecraftEdition;
import org.glavo.nbt.internal.StringCache;
import org.jetbrains.annotations.Nullable;

import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Map;

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

    public final StringCache stringCache = DEFAULT_CACHE;
    @Nullable StringBuilder charsBuffer;

    private @Nullable Map<CacheKey<?>, Object> cacheMap;

    public RawDataReader(InputSource source, MinecraftEdition edition) {
        this.source = source;
        this.edition = edition;
        this.buffer = InputBuffer.allocate(DEFAULT_BUFFER_SIZE, source.supportDirectBuffer(), edition.byteOrder());
        this.sourceStartPosition = source.position();
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
}
