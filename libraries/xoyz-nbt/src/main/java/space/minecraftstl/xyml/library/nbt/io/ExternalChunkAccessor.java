// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

// Modified by MinecraftSTL in 2026 for the XYML namespace and monorepo build.
package space.minecraftstl.xyml.library.nbt.io;

import space.minecraftstl.xyml.library.nbt.internal.ChunkUtils;
import space.minecraftstl.xyml.library.nbt.internal.ExternalChunkAccessors;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.Objects;
import java.util.function.Function;
import java.util.regex.Matcher;

/// Accessor for external chunk files.
///
/// Since Minecraft 19w34a, if a chunk is larger than 1 MiB, it will be stored in a separate file named `c.<chunkX>.<chunkZ>.mcc` in the same directory as the region file.
/// This interface provides a way to locate the external chunk file for a given chunk in an Anvil file.
///
/// @see NBTCodec
/// @see NBTCodec#getExternalChunkAccessorFactory()
/// @see NBTCodec#withExternalChunkAccessorFactory(Function)
public interface ExternalChunkAccessor {

    /// Returns a default factory for creating [ExternalChunkAccessor] for an Anvil file.
    static Function<Path, ExternalChunkAccessor> defaultFactory() {
        return ExternalChunkAccessors.DEFAULT_FACTORY;
    }

    /// Returns an empty factory. It returns an empty accessor for any Anvil file.
    static Function<Path, ExternalChunkAccessor> emptyFactory() {
        return ExternalChunkAccessors.EMPTY_FACTORY;
    }

    /// Returns an accessor for the external chunk file for a given chunk in an Anvil file.
    ///
    /// If the file name is matched by the pattern `r.<regionX>.<regionZ>.mca`, where `<regionX>` and `<regionZ>` are integers, returns an accessor for the external chunk file;
    /// otherwise, returns an [empty accessor][#emptyAccessor()].
    static ExternalChunkAccessor of(Path path) {
        Path fileName = path.getFileName();
        if (fileName == null) {
            return emptyAccessor();
        }

        String name = fileName.toString();
        Matcher matcher = ExternalChunkAccessors.FILE_NAME_PATTERN.matcher(name);
        if (matcher.matches()) {
            try {
                int regionX = Integer.parseInt(matcher.group("regionX"));
                int regionZ = Integer.parseInt(matcher.group("regionZ"));

                return new ExternalChunkAccessors.FileExternalChunkAccessor(path, regionX, regionZ);
            } catch (NumberFormatException e) {
                return emptyAccessor();
            }
        } else {
            return emptyAccessor();
        }
    }

    /// Returns an accessor that not support reading or writing external chunk files.
    static ExternalChunkAccessor emptyAccessor() {
        return ExternalChunkAccessors.EMPTY;
    }

    /// Opens an input stream for reading the external chunk file for a given chunk in an Anvil file.
    ///
    /// Returns `null` if the accessor does not support reading for the given chunk.
    ///
    /// @param chunkLocalX The local X coordinate of the chunk in the region file.
    /// @param chunkLocalZ The local Z coordinate of the chunk in the region file.
    /// @throws IndexOutOfBoundsException If the chunk coordinates are out of range.
    /// @throws IOException               if an I/O error occurs while opening the stream.
    default @Nullable InputStream openInputStream(int chunkLocalX, int chunkLocalZ) throws IOException {
        Objects.checkIndex(chunkLocalX, ChunkUtils.CHUNKS_PER_REGION_SIDE);
        Objects.checkIndex(chunkLocalZ, ChunkUtils.CHUNKS_PER_REGION_SIDE);
        return null;
    }

    /// Opens an output stream for writing the external chunk file for a given chunk in an Anvil file.
    ///
    /// Returns `null` if the accessor does not support writing for the given chunk.
    ///
    /// @param chunkLocalX The local X coordinate of the chunk in the region file.
    /// @param chunkLocalZ The local Z coordinate of the chunk in the region file.
    /// @throws IndexOutOfBoundsException If the chunk coordinates are out of range.
    /// @throws IOException               if an I/O error occurs while opening the stream.
    default @Nullable OutputStream openOutputStream(int chunkLocalX, int chunkLocalZ) throws IOException {
        Objects.checkIndex(chunkLocalX, ChunkUtils.CHUNKS_PER_REGION_SIDE);
        Objects.checkIndex(chunkLocalZ, ChunkUtils.CHUNKS_PER_REGION_SIDE);
        return null;
    }
}
