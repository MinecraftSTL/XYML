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

import org.glavo.nbt.TestResources;
import org.glavo.nbt.tag.CompoundTag;
import org.glavo.nbt.tag.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.ReadableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import static org.junit.jupiter.api.Assertions.*;

public final class NBTCodecTest {

    enum Loader {
        BYTE_ARRAY {
            @Override
            <T extends Tag> T loadTag(Path file, Class<T> tagClass) throws IOException {
                return NBTCodec.of().readTag(Files.readAllBytes(file), tagClass);
            }
        },
        HEAP_BYTE_BUFFER {
            @Override
            <T extends Tag> T loadTag(Path file, Class<T> tagClass) throws IOException {
                return NBTCodec.of().readTag(ByteBuffer.wrap(Files.readAllBytes(file)), tagClass);
            }
        },
        DIRECT_BYTE_BUFFER {
            @Override
            <T extends Tag> T loadTag(Path file, Class<T> tagClass) throws IOException {
                return NBTCodec.of().readTag(ByteBuffer.allocateDirect((int) Files.size(file)).put(Files.readAllBytes(file)).flip(), tagClass);
            }
        },
        INPUT_STREAM {
            @Override
            <T extends Tag> T loadTag(Path file, Class<T> tagClass) throws IOException {
                return NBTCodec.of().readTag(file, tagClass);
            }
        },
        FILE_CHANNEL {
            @Override
            <T extends Tag> T loadTag(Path file, Class<T> tagClass) throws IOException {
                try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
                    return NBTCodec.of().readTag(channel, tagClass);
                }
            }
        },
        READABLE_BYTE_CHANNEL {
            @Override
            <T extends Tag> T loadTag(Path file, Class<T> tagClass) throws IOException {
                try (ReadableByteChannel channel = Channels.newChannel(Files.newInputStream(file))) {
                    return NBTCodec.of().readTag(channel, tagClass);
                }
            }
        },
        PATH {
            @Override
            <T extends Tag> T loadTag(Path file, Class<T> tagClass) throws IOException {
                return NBTCodec.of().readTag(file, tagClass);
            }
        };

        abstract <T extends Tag> T loadTag(Path file, Class<T> tagClass) throws IOException;
    }

    /// Loads a level.dat file (compressed with gzip)
    @ParameterizedTest
    @EnumSource
    void testReadLevelDat(Loader loader) throws IOException {
        Path levelDatPath = TestResources.getResource("/assets/nbt/level.dat");
        CompoundTag levelDat = loader.loadTag(levelDatPath, CompoundTag.class);
        assertEquals("", levelDat.getName());
        assertEquals(1, levelDat.size());

        Tag dataTag = levelDat.get("Data");
        assertNotNull(dataTag);
        assertInstanceOf(CompoundTag.class, dataTag);
    }

    /// Loads an uncompressed level.dat file
    @ParameterizedTest
    @EnumSource
    void testReadLevelDatRaw(Loader loader) throws IOException {
        Path levelDatPath = TestResources.getResource("/assets/nbt/level.dat.raw");
        CompoundTag levelDat = loader.loadTag(levelDatPath, CompoundTag.class);
        assertEquals("", levelDat.getName());
        assertEquals(1, levelDat.size());

        Tag dataTag = levelDat.get("Data");
        assertNotNull(dataTag);
        assertInstanceOf(CompoundTag.class, dataTag);
    }
}
