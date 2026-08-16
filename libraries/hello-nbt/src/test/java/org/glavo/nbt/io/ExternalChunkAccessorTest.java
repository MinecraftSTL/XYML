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
package org.glavo.nbt.io;

import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import org.glavo.nbt.internal.ChunkUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

public final class ExternalChunkAccessorTest {

    @Test
    @SuppressWarnings("resource")
    void testEmptyAccessor() throws IOException {
        var accessor = ExternalChunkAccessor.emptyAccessor();

        for (int i = 0; i < ChunkUtils.CHUNKS_PER_REGION_SIDE; i++) {
            for (int j = 0; j < ChunkUtils.CHUNKS_PER_REGION_SIDE; j++) {
                assertNull(accessor.openInputStream(i, j));
                assertNull(accessor.openOutputStream(i, j));
            }
        }

        assertThrows(IndexOutOfBoundsException.class, () -> accessor.openInputStream(-1, 0));
        assertThrows(IndexOutOfBoundsException.class, () -> accessor.openInputStream(0, -1));
        assertThrows(IndexOutOfBoundsException.class, () -> accessor.openInputStream(ChunkUtils.CHUNKS_PER_REGION_SIDE, 0));
        assertThrows(IndexOutOfBoundsException.class, () -> accessor.openInputStream(32, 0));
        assertThrows(IndexOutOfBoundsException.class, () -> accessor.openInputStream(0, 32));

        assertThrows(IndexOutOfBoundsException.class, () -> accessor.openOutputStream(-1, 0));
        assertThrows(IndexOutOfBoundsException.class, () -> accessor.openOutputStream(0, -1));
        assertThrows(IndexOutOfBoundsException.class, () -> accessor.openOutputStream(ChunkUtils.CHUNKS_PER_REGION_SIDE, 0));
        assertThrows(IndexOutOfBoundsException.class, () -> accessor.openOutputStream(32, 0));
        assertThrows(IndexOutOfBoundsException.class, () -> accessor.openOutputStream(0, 32));
    }

    @ParameterizedTest
    @CsvSource({
            "0,0",
            "1,2",
            "31,31",
            "-1,-1",
            "-31,-31",
    })
    void testDefaultAccessor(int regionX, int regionZ) throws IOException {
        try (FileSystem fs = Jimfs.newFileSystem(Configuration.unix())) {
            Path rootDir = fs.getPath("/test");
            Files.createDirectories(rootDir);

            for (int chunkLocalX = 0; chunkLocalX < ChunkUtils.CHUNKS_PER_REGION_SIDE; chunkLocalX++) {
                for (int chunkLocalZ = 0; chunkLocalZ < ChunkUtils.CHUNKS_PER_REGION_SIDE; chunkLocalZ++) {
                    int chunkGlobalX = ChunkUtils.toGlobalIndex(regionX, chunkLocalX);
                    int chunkGlobalZ = ChunkUtils.toGlobalIndex(regionZ, chunkLocalZ);
                    Files.writeString(rootDir.resolve("c.%d.%d.mcc".formatted(chunkGlobalX, chunkGlobalZ)), "%d,%d".formatted(chunkLocalX, chunkLocalZ));
                }
            }

            var accessor = ExternalChunkAccessor.of(rootDir.resolve("r.%d.%d.mca".formatted(regionX, regionZ)));
            assertNotNull(accessor);

            for (int chunkLocalX = 0; chunkLocalX < ChunkUtils.CHUNKS_PER_REGION_SIDE; chunkLocalX++) {
                for (int chunkLocalZ = 0; chunkLocalZ < ChunkUtils.CHUNKS_PER_REGION_SIDE; chunkLocalZ++) {
                    try (InputStream inputStream = accessor.openInputStream(chunkLocalX, chunkLocalZ)) {
                        assertNotNull(inputStream);
                        assertEquals("%d,%d".formatted(chunkLocalX, chunkLocalZ), new String(inputStream.readAllBytes(), StandardCharsets.UTF_8));
                    }

                    try (OutputStream outputStream = accessor.openOutputStream(chunkLocalX, chunkLocalZ)) {
                        assertNotNull(outputStream);
                        outputStream.write("overwritten,%d,%d".formatted(chunkLocalX, chunkLocalZ).getBytes(StandardCharsets.UTF_8));
                    }

                    try (InputStream inputStream = accessor.openInputStream(chunkLocalX, chunkLocalZ)) {
                        assertNotNull(inputStream);
                        assertEquals("overwritten,%d,%d".formatted(chunkLocalX, chunkLocalZ), new String(inputStream.readAllBytes(), StandardCharsets.UTF_8));
                    }
                }
            }
        }
    }
}
