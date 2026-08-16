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

import org.glavo.nbt.io.ExternalChunkAccessor;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.function.Function;
import java.util.regex.Pattern;

public final class ExternalChunkAccessors {
    public static final Pattern FILE_NAME_PATTERN = Pattern.compile("r\\.(?<regionX>-?\\d+)\\.(?<regionZ>-?\\d+)\\.mca");

    public static final Function<Path, ExternalChunkAccessor> DEFAULT_FACTORY = new Function<>() {
        @Override
        public ExternalChunkAccessor apply(Path path) {
            return ExternalChunkAccessor.of(path);
        }

        @Override
        public String toString() {
            return "ExternalChunkAccessor.defaultFactory()";
        }
    };

    public static final Function<Path, ExternalChunkAccessor> EMPTY_FACTORY = new Function<>() {
        @Override
        public ExternalChunkAccessor apply(Path path) {
            return ExternalChunkAccessor.emptyAccessor();
        }

        @Override
        public String toString() {
            return "ExternalChunkAccessor.emptyFactory()";
        }
    };

    public static final ExternalChunkAccessor EMPTY = new ExternalChunkAccessor() {
        @Override
        public String toString() {
            return "ExternalChunkAccessor.emptyAccessor()";
        }
    };

    public record FileExternalChunkAccessor(Path path, int regionX, int regionZ) implements ExternalChunkAccessor {
        public Path locate(Path source, int chunkLocalX, int chunkLocalZ) {
            Objects.checkIndex(chunkLocalX, ChunkUtils.CHUNKS_PER_REGION_SIDE);
            Objects.checkIndex(chunkLocalZ, ChunkUtils.CHUNKS_PER_REGION_SIDE);

            return source.resolveSibling("c.%d.%d.mcc".formatted(
                    (regionX << ChunkUtils.CHUNKS_PER_REGION_SIDE_SHIFT) + chunkLocalX,
                    (regionZ << ChunkUtils.CHUNKS_PER_REGION_SIDE_SHIFT) + chunkLocalZ
            ));
        }

        @Override
        public InputStream openInputStream(int chunkLocalX, int chunkLocalZ) throws IOException {
            return Files.newInputStream(locate(path, chunkLocalX, chunkLocalZ));
        }

        @Override
        public @Nullable OutputStream openOutputStream(int chunkLocalX, int chunkLocalZ) throws IOException {
            Path chunkFile = locate(path, chunkLocalX, chunkLocalZ);
            Files.createDirectories(chunkFile.toAbsolutePath().getParent());
            return Files.newOutputStream(chunkFile, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        }
    }

    private ExternalChunkAccessors() {
    }
}
