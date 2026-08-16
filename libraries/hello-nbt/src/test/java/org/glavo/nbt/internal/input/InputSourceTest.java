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

import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

public final class InputSourceTest {
    enum SourceType {
        HEAP_BYTE_BUFFER {
            InputSource createSource(byte[] bytes) {
                return new InputSource.OfByteBuffer(bytes);
            }
        },
        DIRECT_BYTE_BUFFER {
            InputSource createSource(byte[] bytes) {
                return new InputSource.OfByteBuffer(ByteBuffer.allocateDirect(bytes.length).put(bytes).flip());
            }
        },
        INPUT_STREAM {
            InputSource createSource(byte[] bytes) {
                return new InputSource.OfInputStream(new ByteArrayInputStream(bytes), false);
            }
        },
        FILE_CHANNEL {
            static final FileSystem memoryFS = Jimfs.newFileSystem(Configuration.unix());
            static final Path tempDir = memoryFS.getPath("/tmp");

            InputSource createSource(byte[] bytes) throws IOException {
                Files.createDirectories(tempDir);
                Path tempFile = Files.createTempFile(tempDir, "test", ".tmp");
                Files.write(tempFile, bytes);
                return new InputSource.OfByteChannel(FileChannel.open(tempFile, StandardOpenOption.READ, StandardOpenOption.DELETE_ON_CLOSE), true);
            }
        },
        READABLE_BYTE_CHANNEL {
            InputSource createSource(byte[] bytes) {
                return new InputSource.OfByteChannel(Channels.newChannel(new ByteArrayInputStream(bytes)), false);
            }
        };

        abstract InputSource createSource(byte[] bytes) throws IOException;
    }


    @ParameterizedTest
    @EnumSource
    void testReadEmpty(SourceType sourceType) throws IOException {
        byte[] bytes = new byte[0];

        try (InputSource source = sourceType.createSource(bytes)) {
            InputBuffer buffer = InputBuffer.allocate(1024, source.supportDirectBuffer(), ByteOrder.LITTLE_ENDIAN);

            assertThrows(EOFException.class, () -> source.fillBuffer(buffer, 1));
        }
    }

    @ParameterizedTest
    @EnumSource
    void testSimpleRead(SourceType sourceType) throws IOException {
        byte[] bytes = new byte[]{1, 2, 3, 4};

        try (InputSource source = sourceType.createSource(bytes)) {
            InputBuffer buffer = InputBuffer.allocate(1024, source.supportDirectBuffer(), ByteOrder.LITTLE_ENDIAN);

            assertEquals(0, source.position());

            source.fillBuffer(buffer, 4);
            assertArrayEquals(bytes, buffer.getByteArray(4));
            assertEquals(4, source.position());

            assertThrows(EOFException.class, () -> source.fillBuffer(buffer, 1));
            assertEquals(4, source.position());
        }
    }

    @ParameterizedTest
    @EnumSource
    void testMix(SourceType sourceType) throws IOException {
        var random = new Random(0);

        byte[] bytes = new byte[128];
        random.nextBytes(bytes);

        try (InputSource source = sourceType.createSource(bytes)) {
            InputBuffer buffer = InputBuffer.allocate(1024, source.supportDirectBuffer(), ByteOrder.LITTLE_ENDIAN);

            assertEquals(0, source.position());

            source.skip(32);
            assertEquals(32, source.position());

            source.fillBuffer(buffer, 32);
            assertTrue(source.position() >= 64);
            assertArrayEquals(Arrays.copyOfRange(bytes, 32, 64), buffer.getByteArray(32));
        }
    }
}


