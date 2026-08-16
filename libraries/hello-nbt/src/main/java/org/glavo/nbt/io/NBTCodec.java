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

import org.glavo.nbt.chunk.ChunkRegion;
import org.glavo.nbt.internal.TextUtils;
import org.glavo.nbt.internal.input.InputSource;
import org.glavo.nbt.internal.input.NBTInput;
import org.glavo.nbt.internal.input.RawDataReader;
import org.glavo.nbt.internal.output.NBTOutput;
import org.glavo.nbt.internal.output.OutputTarget;
import org.glavo.nbt.internal.output.RawDataWriter;
import org.glavo.nbt.tag.*;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.function.Function;

/// The codec for reading and writing NBT data.
///
/// Each NBTCodec instance is immutable and thread-safe.
///
/// # Getting NBTCodec Instances
///
/// NBTCodec provides two factory methods to obtain NBTCodec instances:
///
/// - [NBTCodec#of()] returns the default [NBTCodec] with big-endian byte order for reading and writing NBT.
/// - [NBTCodec#of(MinecraftEdition)] returns a [NBTCodec] for the specified [MinecraftEdition].
///   If the edition is [MinecraftEdition#JAVA_EDITION], it uses big-endian byte order;
///   If the edition is [MinecraftEdition#BEDROCK_EDITION], it uses little-endian byte order.
///
/// Beyond just [MinecraftEdition], NBTCodec offers additional configuration options.
/// To adjust these settings, you can obtain an instance via a factory method and then call
/// a `withXxx` method (such as [#withEdition(MinecraftEdition)]) to create a new [NBTCodec] instance.
///
/// # Reading and Writing NBT Data
///
/// NBTCodec supports reading NBT data from multiple sources:
///
/// ```java
/// var codec = NBTCodec.of();
///
/// Tag tag;
///
/// // Read from a byte array
/// tag = codec.readTag(new byte[]{...});
///
/// // Read from a byte buffer
/// tag = codec.readTag(ByteBuffer.wrap(new byte[]{...}));
///
/// // Read from an input stream
/// tag = codec.readTag(new ByteArrayInputStream(new byte[]{...}));
///
/// // Read from a readable byte channel
/// tag = codec.readTag(Channels.newChannel(...));
///
/// // Read from a file
/// tag = codec.readTag(Path.of("/path/to/file"));
/// ```
///
/// When reading NBT data, NBTCodec automatically detects whether the data is compressed with GZip or LZ4 and decompresses it transparently.
///
/// When reading a `Tag`, you can pass in a `TagType` to specify the expected `Tag` type.
/// An `IOException` will be thrown if the data does not meet expectations:
///
/// ```java
/// CompoundTag levelDat = codec.readTag(Path.of("level.dat"), TagType.COMPOUND);
/// ```
///
/// You can also easily write a `Tag` to an output stream or a byte channel:
///
/// ```java
/// try (var outputStream = new FileOutputStream("/path/to/file")) {
///     codec.writeTag(outputStream, tag);
/// }
///
/// // or
/// try (var channel = FileChannel.open(Path.of("/path/to/file"), StandardOpenOption.WRITE)) {
///     codec.writeTag(channel, tag);
/// }
/// ```
///
/// Currently, NBTCodec does not support automatic data compression.
/// When data needs to be compressed, the output stream should be wrapped with `GZIPOutputStream` or `LZ4BlockOutputStream` before being passed to the `writeTag` method.
///
/// # Reading Anvil files and region files
///
/// NBTCodec also supports reading and writing chunk regions from Anvil files or region files:
///
/// ```java
/// // Read a chunk region from a file
/// ChunkRegion region = codec.readRegion(Path.of("/path/to/region"));
///
/// try (var outputStream = new FileOutputStream("/path/to/region")) {
///     codec.writeRegion(outputStream, region);
/// }
/// ```
///
/// Starting from Minecraft 1.15 (19w34a), if the chunk data exceeds 1020KiB, it will be split and saved to another file.
/// NBTCodec uses [ExternalChunkAccessor] to read and write external chunk files.
///
/// For [#readRegion(Path)], it will use the [#getExternalChunkAccessorFactory()] to get the external chunk accessor for the file.
/// By default, it is [ExternalChunkAccessor#defaultFactory()], which returns an accessor for external chunk files if the file name matches the pattern `r.<regionX>.<regionZ>.mca`.
///
/// For other variants (like [#readRegion(InputStream)]), the default behavior is not to support external chunk files.
/// They will throw an exception when trying to access external chunk files.
/// However, you can use [#readRegion(InputStream, ExternalChunkAccessor)] or [#readRegion(ReadableByteChannel, ExternalChunkAccessor)] to manually specify the external chunk accessor.
public final class NBTCodec {
    private static final NBTCodec JE = new NBTCodec(MinecraftEdition.JAVA_EDITION, ExternalChunkAccessor.defaultFactory());
    private static final NBTCodec BE = new NBTCodec(MinecraftEdition.BEDROCK_EDITION, ExternalChunkAccessor.defaultFactory());

    /// Returns the default [NBTCodec].
    ///
    /// The default edition is [MinecraftEdition#JAVA_EDITION].
    @Contract(pure = true)
    public static NBTCodec of() {
        return JE;
    }

    /// Returns a [NBTCodec] for the specified [MinecraftEdition].
    @Contract(pure = true)
    public static NBTCodec of(MinecraftEdition edition) {
        Objects.requireNonNull(edition, "edition");
        return edition == MinecraftEdition.JAVA_EDITION
                ? JE
                : BE;
    }

    private final MinecraftEdition edition;
    private final Function<Path, ExternalChunkAccessor> externalChunkAccessorFactory;

    private NBTCodec(MinecraftEdition edition, Function<Path, ExternalChunkAccessor> externalChunkAccessorFactory) {
        this.edition = edition;
        this.externalChunkAccessorFactory = externalChunkAccessorFactory;
    }

    /// Returns the Minecraft edition of the NBT data.
    ///
    /// The default edition is [MinecraftEdition#JAVA_EDITION].
    ///
    /// @see #withEdition(MinecraftEdition)
    /// @see MinecraftEdition
    @Contract(pure = true)
    public MinecraftEdition getEdition() {
        return edition;
    }

    /// Returns a new [NBTCodec] with the specified edition.
    ///
    /// @see #getEdition()
    /// @see MinecraftEdition
    @Contract(pure = true)
    public NBTCodec withEdition(MinecraftEdition edition) {
        Objects.requireNonNull(edition, "edition");
        return edition == this.edition ? this : new NBTCodec(edition, externalChunkAccessorFactory);
    }

    /// Returns the factory for getting [ExternalChunkAccessor] for an Anvil file.
    ///
    /// The default factory is [ExternalChunkAccessor#defaultFactory()].
    ///
    /// @see #withExternalChunkAccessorFactory(Function)
    /// @see ExternalChunkAccessor
    @Contract(pure = true)
    public Function<Path, ExternalChunkAccessor> getExternalChunkAccessorFactory() {
        return externalChunkAccessorFactory;
    }

    /// Returns a new [NBTCodec] with the specified factory for getting [ExternalChunkAccessor].
    ///
    /// @see #getExternalChunkAccessorFactory()
    /// @see ExternalChunkAccessor
    @Contract(pure = true)
    public NBTCodec withExternalChunkAccessorFactory(Function<Path, ExternalChunkAccessor> factory) {
        Objects.requireNonNull(factory, "factory");
        return factory == this.externalChunkAccessorFactory ? this : new NBTCodec(edition, factory);
    }

    /// Returns the encoded byte size of the content of the simple value tag.
    private static long simpleValueContentByteSize(TagType<?> type) {
        if (type == TagType.BYTE) {
            return 1;
        } else if (type == TagType.SHORT) {
            return 2;
        } else if (type == TagType.INT) {
            return 4;
        } else if (type == TagType.LONG) {
            return 8;
        } else if (type == TagType.FLOAT) {
            return 4;
        } else if (type == TagType.DOUBLE) {
            return 8;
        } else {
            return -1L;
        }
    }

    private long stringByteSize(String value) {
        return 2L + (edition == MinecraftEdition.JAVA_EDITION
                ? TextUtils.mutf8Length(value)
                : TextUtils.utf8Length(value));
    }

    /// Returns the encoded byte size of the content of the tag.
    ///
    /// The size excludes the tag type and name.
    public long contentByteSize(Tag tag) {
        if (tag instanceof ValueTag<?> valueTag) {
            long size = simpleValueContentByteSize(valueTag.getType());
            if (size >= 0) {
                return size;
            } else if (tag instanceof StringTag stringTag) {
                return stringByteSize(stringTag.getValue());
            } else {
                throw new IllegalArgumentException("Unsupported tag: " + tag);
            }
        } else if (tag instanceof ListTag<?> listTag) {
            long size = 1 + 4; // element type + length

            TagType<?> elementType = listTag.getElementType();
            if (elementType == null) {
                assert listTag.isEmpty() : "Non-empty list with element type END";
                return size;
            }

            long elementSize = simpleValueContentByteSize(elementType);
            if (elementSize >= 0) {
                return size + elementSize * listTag.size();
            } else {
                for (Tag subTag : listTag) {
                    size += contentByteSize(subTag);
                }
                return size;
            }
        } else if (tag instanceof CompoundTag compoundTag) {
            long size = 0L;
            for (Tag value : compoundTag) {
                size += byteSize(value);
            }
            return size + 1L;
        } else if (tag instanceof ArrayTag<?, ?, ?, ?> arrayTag) {
            long elementSize = simpleValueContentByteSize(arrayTag.getElementType());
            assert elementSize >= 0 : "Unsupported array element type: " + arrayTag.getElementType();
            return 4L + elementSize * arrayTag.size();
        } else {
            throw new IllegalArgumentException("Unsupported tag: " + tag);
        }
    }

    /// Returns the encoded byte size of the tag.
    public long byteSize(Tag tag) {
        return 1L + stringByteSize(tag.getName()) + contentByteSize(tag);
    }

    private Tag check(@Nullable Tag tag) throws IOException {
        if (tag == null) {
            throw new IOException("Unexpected TAG_END");
        }
        return tag;
    }

    private static <T extends Tag> T check(@Nullable Tag tag, Class<T> tagClass) throws IOException {
        if (tag == null) {
            throw new IOException("Unexpected TAG_END");
        }
        try {
            return tagClass.cast(tag);
        } catch (ClassCastException e) {
            throw new IOException("Unexpected tag type: " + tag);
        }
    }

    /// Reads a NBT tag from a byte array.
    @Contract(pure = true)
    public Tag readTag(byte[] array) throws IOException {
        try (var reader = new RawDataReader(new InputSource.OfByteBuffer(array), getEdition())) {
            return check(NBTInput.readTagAutoDecompress(reader));
        }
    }

    /// Reads the specified NBT tag from a byte array.
    @Contract(pure = true)
    public <T extends Tag> T readTag(byte[] array, TagType<T> tagType) throws IOException {
        return check(readTag(array), tagType.tagClass());
    }

    /// Reads the specified NBT tag from a byte array.
    @Contract(pure = true)
    public <T extends Tag> T readTag(byte[] array, Class<T> tagClass) throws IOException {
        return check(readTag(array), tagClass);
    }

    /// Reads a NBT tag from a byte array with the specified offset and length.
    @Contract(pure = true)
    public Tag readTag(byte[] array, int offset, int length) throws IOException {
        return readTag(ByteBuffer.wrap(array, offset, length));
    }

    /// Reads the specified NBT tag from a byte array with the specified offset and length.
    @Contract(pure = true)
    public <T extends Tag> T readTag(byte[] array, int offset, int length, TagType<T> tagType) throws IOException {
        return check(readTag(array, offset, length), tagType.tagClass());
    }

    /// Reads the specified NBT tag from a byte array with the specified offset and length.
    @Contract(pure = true)
    public <T extends Tag> T readTag(byte[] array, int offset, int length, Class<T> tagClass) throws IOException {
        return check(readTag(array, offset, length), tagClass);
    }

    /// Reads a NBT tag from a byte buffer.
    ///
    /// This method does not change the position and the limit of the buffer.
    @Contract(pure = true)
    public Tag readTag(ByteBuffer buffer) throws IOException {
        try (var reader = new RawDataReader(new InputSource.OfByteBuffer(buffer), getEdition())) {
            return check(NBTInput.readTagAutoDecompress(reader));
        }
    }

    /// Reads the specified NBT tag from a byte buffer.
    ///
    /// This method does not change the position and the limit of the buffer.
    @Contract(pure = true)
    public <T extends Tag> T readTag(ByteBuffer buffer, TagType<T> tagType) throws IOException {
        return check(readTag(buffer), tagType.tagClass());
    }

    /// Reads the specified NBT tag from a byte buffer.
    ///
    /// This method does not change the position and the limit of the buffer.
    @Contract(pure = true)
    public <T extends Tag> T readTag(ByteBuffer buffer, Class<T> tagClass) throws IOException {
        return check(readTag(buffer), tagClass);
    }

    /// Reads a NBT tag from an input stream.
    ///
    /// After this method is called, the state of the `inputStream` is undefined.
    @Contract(mutates = "param1")
    public Tag readTag(InputStream inputStream) throws IOException {
        try (var reader = new RawDataReader(new InputSource.OfInputStream(inputStream, false), getEdition())) {
            return check(NBTInput.readTagAutoDecompress(reader));
        }
    }

    /// Reads the specified NBT tag from an input stream.
    ///
    /// After this method is called, the state of the `inputStream` is undefined.
    @Contract(mutates = "param1")
    public <T extends Tag> T readTag(InputStream inputStream, TagType<T> tagType) throws IOException {
        return check(readTag(inputStream), tagType.tagClass());
    }

    /// Reads the specified NBT tag from an input stream.
    ///
    /// After this method is called, the state of the `inputStream` is undefined.
    @Contract(mutates = "param1")
    public <T extends Tag> T readTag(InputStream inputStream, Class<T> tagClass) throws IOException {
        return check(readTag(inputStream), tagClass);
    }

    /// Reads a NBT tag from a readable byte channel.
    ///
    /// After this method is called, the state of the `channel` is undefined.
    @Contract(mutates = "param1")
    public Tag readTag(ReadableByteChannel channel) throws IOException {
        try (var reader = new RawDataReader(new InputSource.OfByteChannel(channel, false), getEdition())) {
            return check(NBTInput.readTagAutoDecompress(reader));
        }
    }

    /// Reads the specified NBT tag from a readable byte channel.
    ///
    /// After this method is called, the state of the `channel` is undefined.
    @Contract(mutates = "param1")
    public <T extends Tag> T readTag(ReadableByteChannel channel, TagType<T> tagType) throws IOException {
        return check(readTag(channel), tagType.tagClass());
    }

    /// Reads the specified NBT tag from a readable byte channel.
    ///
    /// After this method is called, the state of the `channel` is undefined.
    @Contract(mutates = "param1")
    public <T extends Tag> T readTag(ReadableByteChannel channel, Class<T> tagClass) throws IOException {
        return check(readTag(channel), tagClass);
    }

    /// Reads a NBT tag from a file.
    public Tag readTag(Path path) throws IOException {
        try (var channel = Files.newByteChannel(path, StandardOpenOption.READ);
             var reader = new RawDataReader(new InputSource.OfByteChannel(channel, false), getEdition())) {
            return check(NBTInput.readTagAutoDecompress(reader));
        }
    }

    /// Reads the specified NBT tag from a file.
    public <T extends Tag> T readTag(Path path, TagType<T> tagType) throws IOException {
        return check(readTag(path), tagType.tagClass());
    }

    /// Reads the specified NBT tag from a file.
    public <T extends Tag> T readTag(Path path, Class<T> tagClass) throws IOException {
        return check(readTag(path), tagClass);
    }

    /// Writes a NBT tag to the output stream.
    @Contract(mutates = "param1")
    public void writeTag(OutputStream outputStream, Tag tag) throws IOException {
        try (var writer = new RawDataWriter(new OutputTarget.OfOutputStream(outputStream, false), getEdition())) {
            NBTOutput.writeTag(writer, tag);
        }
    }

    /// Writes a NBT tag to the byte channel.
    @Contract(mutates = "param1")
    public void writeTag(WritableByteChannel channel, Tag tag) throws IOException {
        try (var writer = new RawDataWriter(new OutputTarget.OfByteChannel(channel, false), getEdition())) {
            NBTOutput.writeTag(writer, tag);
        }
    }

    /// Writes a NBT tag to the byte buffer.
    ///
    /// `buffer.remaining()` must be greater than or equal to [`byteSize(tag)`](#byteSize(Tag)),
    /// otherwise an `IOException` will be thrown, and the state of the `buffer` is undefined.
    ///
    /// After the method call, `buffer.position()` will increase by [`byteSize(tag)`](#byteSize(Tag)).
    public void writeTag(ByteBuffer buffer, Tag tag) throws IOException {
        try (var writer = new RawDataWriter(new OutputTarget.OfByteBuffer(buffer), getEdition())) {
            NBTOutput.writeTag(writer, tag);
        }
    }

    /// Writes a NBT tag to a byte array.
    ///
    /// The returned byte array will have a length equal to [`byteSize(tag)`](#byteSize(Tag)).
    public byte[] writeTagToByteArray(Tag tag) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate((int) byteSize(tag));

        try {
            writeTag(buffer, tag);
        } catch (IOException e) {
            throw new AssertionError("Unexpected error when writing tag to byte array", e);
        }

        if (buffer.remaining() != 0) {
            throw new AssertionError("Unexpected remaining bytes in buffer: " + buffer.remaining());
        }

        return buffer.array();
    }

    /// Reads a chunk region from a file.
    ///
    /// @see #getExternalChunkAccessorFactory()
    /// @see #withExternalChunkAccessorFactory(Function)
    public ChunkRegion readRegion(Path path) throws IOException {
        return readRegion(path, getExternalChunkAccessorFactory().apply(path));
    }

    /// Reads a chunk region from a file.
    ///
    /// @see #getExternalChunkAccessorFactory()
    /// @see #withExternalChunkAccessorFactory(Function)
    public ChunkRegion readRegion(Path path, ExternalChunkAccessor accessor) throws IOException {
        try (var channel = FileChannel.open(path, StandardOpenOption.READ);
             var reader = new RawDataReader(new InputSource.OfByteChannel(channel, true), MinecraftEdition.JAVA_EDITION)) {
            return NBTInput.readRegion(reader, accessor);
        }
    }

    /// Reads a chunk region from an input stream.
    public ChunkRegion readRegion(InputStream inputStream) throws IOException {
        return readRegion(inputStream, ExternalChunkAccessor.emptyAccessor());
    }

    /// Reads a chunk region from an input stream.
    public ChunkRegion readRegion(InputStream inputStream, ExternalChunkAccessor accessor) throws IOException {
        Objects.requireNonNull(inputStream, "inputStream");
        try (var reader = new RawDataReader(new InputSource.OfInputStream(inputStream, false), MinecraftEdition.JAVA_EDITION)) {
            return NBTInput.readRegion(reader, accessor);
        }
    }

    /// Reads a chunk region from a readable byte channel.
    public ChunkRegion readRegion(ReadableByteChannel channel) throws IOException {
        return readRegion(channel, ExternalChunkAccessor.emptyAccessor());
    }

    /// Reads a chunk region from a readable byte channel.
    public ChunkRegion readRegion(ReadableByteChannel channel, ExternalChunkAccessor accessor) throws IOException {
        Objects.requireNonNull(channel, "channel");
        try (var reader = new RawDataReader(new InputSource.OfByteChannel(channel, false), MinecraftEdition.JAVA_EDITION)) {
            return NBTInput.readRegion(reader, accessor);
        }
    }

    /// Writes a chunk region to a file.
    ///
    /// @see ExternalChunkAccessor#of(Path)
    public void writeRegion(Path file, ChunkRegion region) throws IOException {
        writeRegion(file, region, ExternalChunkAccessor.of(file));
    }

    /// Writes a chunk region to a file.
    public void writeRegion(Path file, ChunkRegion region, ExternalChunkAccessor accessor) throws IOException {
        try (var channel = Files.newByteChannel(file,
                StandardOpenOption.WRITE,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING
        )) {
            writeRegion(channel, region, accessor);
        }
    }

    /// Writes a chunk region to an output stream.
    public void writeRegion(OutputStream outputStream, ChunkRegion region) throws IOException {
        writeRegion(outputStream, region, ExternalChunkAccessor.emptyAccessor());
    }

    /// Writes a chunk region to an output stream.
    public void writeRegion(OutputStream outputStream, ChunkRegion region, ExternalChunkAccessor accessor) throws IOException {
        try (var writer = new RawDataWriter(new OutputTarget.OfOutputStream(outputStream, false), MinecraftEdition.JAVA_EDITION)) {
            NBTOutput.writeRegion(writer, region, accessor);
        }
    }

    /// Writes a chunk region to a seekable byte channel.
    public void writeRegion(SeekableByteChannel channel, ChunkRegion region) throws IOException {
        writeRegion(channel, region, ExternalChunkAccessor.emptyAccessor());
    }

    /// Writes a chunk region to a seekable byte channel.
    public void writeRegion(SeekableByteChannel channel, ChunkRegion region, ExternalChunkAccessor accessor) throws IOException {
        NBTOutput.writeRegion(channel, region, accessor);
    }

    @Override
    public int hashCode() {
        return Objects.hash(getEdition(), getExternalChunkAccessorFactory());
    }

    @Override
    public boolean equals(Object obj) {
        return obj instanceof NBTCodec that
                && this.getEdition().equals(that.getEdition())
                && this.getExternalChunkAccessorFactory().equals(that.getExternalChunkAccessorFactory());
    }

    @Override
    public String toString() {
        return "NBTCodec[edition=%s, externalChunkAccessorFactory=%s]".formatted(getEdition(), getExternalChunkAccessorFactory());
    }
}
