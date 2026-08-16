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

import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import org.glavo.nbt.TestResources;
import org.glavo.nbt.chunk.ChunkRegion;
import org.glavo.nbt.internal.ChunkUtils;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

abstract class WriteChunkRegionTest {

    abstract byte[] writeToByteArray(ChunkRegion region) throws IOException;

    @ParameterizedTest
    @ValueSource(strings = {"/assets/region/zlib.mca", "/assets/region/lz4.mca"})
    public void testWriteRegion(String path) throws IOException {
        Path resource = TestResources.getResource(path);

        NBTCodec codec = NBTCodec.of();
        ChunkRegion expected = codec.readRegion(resource);

        byte[] bytes = writeToByteArray(expected);

        assertEquals(0, (bytes.length % ChunkUtils.SECTOR_BYTES));

        ChunkRegion actual = NBTCodec.of().readRegion(new ByteArrayInputStream(bytes));
        assertEquals(expected, actual);
    }

    static final class WriteToOutputStreamTest extends WriteChunkRegionTest {

        @Override
        byte[] writeToByteArray(ChunkRegion region) throws IOException {
            var buffer = new ByteArrayOutputStream(8192);
            NBTCodec.of().writeRegion(buffer, region);
            return buffer.toByteArray();
        }
    }

    static final class WriteToFileChannelTest extends WriteChunkRegionTest {

        @Override
        byte[] writeToByteArray(ChunkRegion region) throws IOException {
            try (var fs = Jimfs.newFileSystem(Configuration.unix())) {
                Path file = fs.getPath("/test.mca");
                try (var channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
                    NBTCodec.of().writeRegion(channel, region);
                }
                return Files.readAllBytes(file);
            }
        }
    }
}
