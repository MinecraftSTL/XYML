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
package space.minecraftstl.xyml.library.nbt.io;

import net.jpountz.lz4.LZ4BlockInputStream;
import space.minecraftstl.xyml.library.nbt.chunk.Chunk;
import space.minecraftstl.xyml.library.nbt.chunk.ChunkRegion;
import space.minecraftstl.xyml.library.nbt.internal.ChunkUtils;
import space.minecraftstl.xyml.library.nbt.internal.TextUtils;
import space.minecraftstl.xyml.library.nbt.internal.input.InputSource;
import space.minecraftstl.xyml.library.nbt.internal.input.NBTInput;
import space.minecraftstl.xyml.library.nbt.internal.input.RawDataReader;
import space.minecraftstl.xyml.library.nbt.internal.output.NBTOutput;
import space.minecraftstl.xyml.library.nbt.internal.output.OutputTarget;
import space.minecraftstl.xyml.library.nbt.internal.output.RawDataWriter;
import space.minecraftstl.xyml.library.nbt.validation.NBTStructureValidator;
import space.minecraftstl.xyml.library.nbt.validation.NBTValidationException;
import space.minecraftstl.xyml.library.nbt.tag.*;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Objects;
import java.util.function.Function;
import java.util.zip.CRC32;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

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
@NotNullByDefault
public final class NBTCodec {
    private static final NBTCodec JE = new NBTCodec(MinecraftEdition.JAVA_EDITION, ExternalChunkAccessor.defaultFactory());
    private static final NBTCodec BE = new NBTCodec(MinecraftEdition.BEDROCK_EDITION, ExternalChunkAccessor.defaultFactory());
    /// Maximum encoded input accepted by the legacy stateless codec entry points.
    private static final int MAX_ENCODED_BYTES = Math.toIntExact(
            ReadLimits.defaults().maxEncodedBytes());
    /// Maximum decompressed standalone payload accepted by the legacy stateless codec entry points.
    private static final int MAX_DECOMPRESSED_BYTES = Math.toIntExact(
            ReadLimits.defaults().maxDecompressedBytes());

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
        return checkedAdd(2L, edition == MinecraftEdition.JAVA_EDITION
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
                return checkedAdd(size, checkedMultiply(elementSize, listTag.size()));
            } else {
                for (Tag subTag : listTag) {
                    size = checkedAdd(size, contentByteSize(subTag));
                }
                return size;
            }
        } else if (tag instanceof CompoundTag compoundTag) {
            long size = 0L;
            for (Tag value : compoundTag) {
                size = checkedAdd(size, byteSize(value));
            }
            return checkedAdd(size, 1L);
        } else if (tag instanceof ArrayTag<?, ?, ?, ?> arrayTag) {
            long elementSize = simpleValueContentByteSize(arrayTag.getElementType());
            assert elementSize >= 0 : "Unsupported array element type: " + arrayTag.getElementType();
            return checkedAdd(4L, checkedMultiply(elementSize, arrayTag.size()));
        } else {
            throw new IllegalArgumentException("Unsupported tag: " + tag);
        }
    }

    /// Returns the encoded byte size of the tag.
    public long byteSize(Tag tag) {
        return checkedAdd(checkedAdd(1L, stringByteSize(tag.getName())), contentByteSize(tag));
    }

    /// Adds two encoded-size terms while rejecting a signed-long overflow.
    ///
    /// @param left first encoded-size term
    /// @param right second encoded-size term
    /// @return checked sum
    /// @throws ArithmeticException if the sum cannot be represented as a non-negative long
    private static long checkedAdd(long left, long right) {
        long result = Math.addExact(left, right);
        if (result < 0L) {
            throw new ArithmeticException("NBT encoded size overflow");
        }
        return result;
    }

    /// Multiplies an encoded-size term while rejecting a signed-long overflow.
    ///
    /// @param left first factor
    /// @param right second factor
    /// @return checked product
    /// @throws ArithmeticException if the product cannot be represented as a non-negative long
    private static long checkedMultiply(long left, long right) {
        long result = Math.multiplyExact(left, right);
        if (result < 0L) {
            throw new ArithmeticException("NBT encoded size overflow");
        }
        return result;
    }

    private Tag check(@Nullable Tag tag) throws IOException {
        if (tag == null) {
            throw new IOException("Unexpected TAG_END");
        }
        return tag;
    }

    private Tag readStandalone(RawDataReader reader) throws IOException {
        Tag tag = check(NBTInput.readTagAutoDecompress(reader));
        reader.requireExhausted();
        return tag;
    }

    private Tag readStandaloneBytes(byte[] encoded) throws IOException {
        Objects.requireNonNull(encoded, "encoded");
        if (encoded.length > MAX_ENCODED_BYTES) {
            throw new IOException("Encoded NBT input exceeds the read limit");
        }
        NBTFileEncoding encoding = NBTFileEncoding.detectStandalone(encoded);
        byte[] raw = switch (encoding) {
            case RAW -> encoded;
            case GZIP -> decodeGzipStrict(encoded, MAX_DECOMPRESSED_BYTES);
            case ZLIB -> decodeZlibStrict(encoded, MAX_DECOMPRESSED_BYTES);
            case LZ4 -> decodeLz4Strict(encoded, MAX_DECOMPRESSED_BYTES);
            case REGION -> throw new IOException("A region container is not a standalone tag");
        };
        if (raw.length > MAX_DECOMPRESSED_BYTES) {
            throw new IOException("Decompressed NBT input exceeds the read limit");
        }
        NBTRepairReader.validateStrictStructure(raw, getEdition().byteOrder(), ReadLimits.defaults());
        try (var reader = new RawDataReader(new InputSource.OfByteBuffer(raw), getEdition())) {
            Tag tag = check(NBTInput.readTag(reader));
            reader.requireExhausted();
            return tag;
        }
    }

    /// Decodes exactly one GZIP member with a defensive output-size limit.
    ///
    /// @param encoded complete GZIP member
    /// @param maximumOutputBytes largest accepted uncompressed size
    /// @return complete uncompressed bytes
    /// @throws IOException if the member is invalid, trailing, truncated, or too large
    public static byte[] decodeGzipStrict(byte[] encoded, int maximumOutputBytes) throws IOException {
        if (maximumOutputBytes < 0) {
            throw new IllegalArgumentException("maximumOutputBytes must not be negative");
        }
        if (encoded.length < 18 || (encoded[0] & 0xFF) != 0x1F || (encoded[1] & 0xFF) != 0x8B
                || (encoded[2] & 0xFF) != 8) {
            throw new IOException("Invalid GZIP header");
        }
        int flags = encoded[3] & 0xFF;
        if ((flags & 0xE0) != 0) {
            throw new IOException("Invalid GZIP flags");
        }
        int position = 10;
        if ((flags & 0x04) != 0) {
            requireBytes(encoded, position, 2);
            int extraLength = littleUnsignedShort(encoded, position);
            position += 2;
            requireBytes(encoded, position, extraLength);
            position += extraLength;
        }
        if ((flags & 0x08) != 0) {
            position = skipZeroTerminated(encoded, position);
        }
        if ((flags & 0x10) != 0) {
            position = skipZeroTerminated(encoded, position);
        }
        if ((flags & 0x02) != 0) {
            requireBytes(encoded, position, 2);
            CRC32 headerChecksum = new CRC32();
            headerChecksum.update(encoded, 0, position);
            int expectedHeaderChecksum = littleUnsignedShort(encoded, position);
            if ((headerChecksum.getValue() & 0xFFFFL) != expectedHeaderChecksum) {
                throw new IOException("GZIP header checksum does not match");
            }
            position += 2;
        }
        if (position >= encoded.length) {
            throw new IOException("Truncated GZIP payload");
        }

        Inflater inflater = new Inflater(true);
        ByteArrayOutputStream output = new ByteArrayOutputStream(
                (int) Math.min((long) encoded.length * 2L, 8192L));
        CRC32 checksum = new CRC32();
        try {
            inflater.setInput(encoded, position, encoded.length - position);
            byte[] buffer = new byte[8192];
            while (!inflater.finished()) {
                int count;
                try {
                    count = inflater.inflate(buffer);
                } catch (DataFormatException exception) {
                    throw new IOException("Invalid GZIP deflate stream", exception);
                }
                if (count > 0) {
                    if (output.size() > maximumOutputBytes - count) {
                        throw new IOException("GZIP payload is too large after decompression");
                    }
                    output.write(buffer, 0, count);
                    checksum.update(buffer, 0, count);
                } else if (inflater.needsDictionary() || inflater.needsInput()) {
                    throw new IOException("Truncated GZIP deflate stream");
                } else {
                    throw new IOException("GZIP deflate stream made no progress");
                }
            }
            int remaining = inflater.getRemaining();
            int footer = encoded.length - remaining;
            if (remaining != 8) {
                throw new IOException("Trailing data after GZIP member");
            }
            long expectedChecksum = littleUnsignedInt(encoded, footer);
            long expectedSize = littleUnsignedInt(encoded, footer + 4);
            if (checksum.getValue() != expectedChecksum || (output.size() & 0xFFFF_FFFFL) != expectedSize) {
                throw new IOException("GZIP footer checksum or size does not match");
            }
            return output.toByteArray();
        } finally {
            inflater.end();
        }
    }

    /// Decodes one complete zlib stream with a defensive output-size limit.
    ///
    /// @param encoded complete zlib stream
    /// @param maximumOutputBytes largest uncompressed size
    /// @return complete uncompressed bytes
    /// @throws IOException if the stream is malformed, truncated, trailing, or too large
    private static byte[] decodeZlibStrict(byte[] encoded, int maximumOutputBytes) throws IOException {
        if (encoded.length < 6) {
            throw new IOException("Truncated ZLIB payload");
        }
        if (!isZlibHeader(encoded[0], encoded[1])) {
            throw new IOException("Invalid ZLIB header");
        }
        if ((encoded[1] & 0x20) != 0) {
            throw new IOException("Preset-dictionary ZLIB streams are not supported");
        }
        Inflater inflater = new Inflater();
        ByteArrayOutputStream output = new ByteArrayOutputStream(
                (int) Math.min((long) encoded.length * 2L, 8192L));
        try {
            inflater.setInput(encoded);
            byte[] buffer = new byte[8192];
            while (!inflater.finished()) {
                int count;
                try {
                    count = inflater.inflate(buffer);
                } catch (DataFormatException exception) {
                    throw new IOException("Invalid ZLIB payload", exception);
                }
                if (count > 0) {
                    if (output.size() > maximumOutputBytes - count) {
                        throw new IOException("ZLIB payload is too large after decompression");
                    }
                    output.write(buffer, 0, count);
                } else if (inflater.needsDictionary() || inflater.needsInput()) {
                    throw new IOException("Truncated ZLIB payload");
                } else {
                    throw new IOException("ZLIB decompressor made no progress");
                }
            }
            if (inflater.getRemaining() != 0) {
                throw new IOException("Trailing data after ZLIB payload");
            }
            return output.toByteArray();
        } finally {
            inflater.end();
        }
    }

    /// Returns whether a CMF/FLG pair satisfies the complete zlib header rules.
    ///
    /// The method checks compression method/window bits and the FCHECK modulo-31 value. The
    /// preset-dictionary flag is intentionally checked by the caller because the resulting
    /// stream would require dictionary bytes which this codec does not accept.
    private static boolean isZlibHeader(byte cmfByte, byte flagsByte) {
        int cmf = Byte.toUnsignedInt(cmfByte);
        int flags = Byte.toUnsignedInt(flagsByte);
        return (cmf & 0x0F) == 8
                && (cmf >>> 4) <= 7
                && ((cmf << 8) | flags) % 31 == 0;
    }

    /// Decodes one complete lz4-java block stream with a defensive output-size limit.
    ///
    /// @param encoded complete lz4-java block stream
    /// @param maximumOutputBytes largest uncompressed size
    /// @return complete uncompressed bytes
    /// @throws IOException if the stream is malformed, trailing, or too large
    private static byte[] decodeLz4Strict(byte[] encoded, int maximumOutputBytes) throws IOException {
        ByteArrayInputStream source = new ByteArrayInputStream(encoded);
        ByteArrayOutputStream output = new ByteArrayOutputStream(
                (int) Math.min((long) encoded.length * 2L, 8192L));
        try (InputStream input = new LZ4BlockInputStream(source)) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) >= 0) {
                if (count == 0) {
                    int single = input.read();
                    if (single < 0) {
                        break;
                    }
                    if (output.size() >= maximumOutputBytes) {
                        throw new IOException("LZ4 payload is too large after decompression");
                    }
                    output.write(single);
                    continue;
                }
                if (output.size() > maximumOutputBytes - count) {
                    throw new IOException("LZ4 payload is too large after decompression");
                }
                output.write(buffer, 0, count);
            }
            if (source.available() != 0) {
                throw new IOException("Trailing data after LZ4 payload");
            }
            return output.toByteArray();
        } catch (LinkageError error) {
            throw new IOException("LZ4 support is unavailable", error);
        }
    }

    private static void requireBytes(byte[] bytes, int offset, int count) throws IOException {
        if (offset < 0 || count < 0 || offset > bytes.length - count) {
            throw new IOException("Truncated GZIP header");
        }
    }

    private static int skipZeroTerminated(byte[] bytes, int offset) throws IOException {
        while (offset < bytes.length) {
            if (bytes[offset++] == 0) {
                return offset;
            }
        }
        throw new IOException("Truncated GZIP header field");
    }

    private static int littleUnsignedShort(byte[] bytes, int offset) {
        return (bytes[offset] & 0xFF) | ((bytes[offset + 1] & 0xFF) << 8);
    }

    private static long littleUnsignedInt(byte[] bytes, int offset) {
        return (bytes[offset] & 0xFFL)
                | ((bytes[offset + 1] & 0xFFL) << 8)
                | ((bytes[offset + 2] & 0xFFL) << 16)
                | ((bytes[offset + 3] & 0xFFL) << 24);
    }

    private void validateForWrite(Tag tag) throws IOException {
        try {
            NBTStructureValidator.validateSubtree(tag, edition);
        } catch (NBTValidationException exception) {
            throw new IOException("Cannot write an invalid NBT tree", exception);
        }
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
        return readStandaloneBytes(array);
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
        Objects.requireNonNull(buffer, "buffer");
        ByteBuffer copy = buffer.slice();
        if (copy.remaining() > MAX_ENCODED_BYTES) {
            throw new IOException("Encoded NBT input exceeds the read limit");
        }
        byte[] encoded = new byte[copy.remaining()];
        copy.get(encoded);
        return readStandaloneBytes(encoded);
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
        Objects.requireNonNull(inputStream, "inputStream");
        return readStandaloneBytes(readBounded(inputStream, MAX_ENCODED_BYTES));
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
        Objects.requireNonNull(channel, "channel");
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ByteBuffer buffer = ByteBuffer.allocate(8192);
        long total = 0L;
        while (true) {
            int read = channel.read(buffer);
            if (read < 0) {
                break;
            }
            if (read == 0) {
                throw new IOException("NBT channel made no progress");
            }
            if (read > MAX_ENCODED_BYTES - total) {
                throw new IOException("Encoded NBT input exceeds the read limit");
            }
            total += read;
            buffer.flip();
            while (buffer.hasRemaining()) {
                output.write(buffer.get());
            }
            buffer.clear();
        }
        return readStandaloneBytes(output.toByteArray());
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
        Path source = Objects.requireNonNull(path, "path");
        requireRegularNonSymbolic(source);
        long size = Files.size(source);
        if (size > MAX_ENCODED_BYTES) {
            throw new IOException("Encoded NBT input exceeds the read limit");
        }
        try (InputStream input = openInputStreamNoFollowCompatible(source)) {
            return readStandaloneBytes(readBounded(input, MAX_ENCODED_BYTES));
        }
    }

    /// Opens a checked source while retaining compatibility with providers that reject the optional no-follow flag.
    ///
    /// The default filesystem accepts {@link LinkOption#NOFOLLOW_LINKS}. A few read-only providers, notably ZipFS,
    /// reject that option even though their entries cannot be followed as operating-system symbolic links. After such
    /// a rejection the source is checked again before the provider's ordinary open operation is attempted.
    ///
    /// @param source checked regular source path
    /// @return opened source stream
    /// @throws IOException if the source cannot be opened or is no longer a regular non-symbolic file
    private static InputStream openInputStreamNoFollowCompatible(Path source) throws IOException {
        try {
            return Files.newInputStream(source, LinkOption.NOFOLLOW_LINKS);
        } catch (UnsupportedOperationException unsupported) {
            requireRegularNonSymbolic(source);
            return Files.newInputStream(source);
        }
    }

    /// Requires a path-backed codec source to be a regular non-symbolic file.
    ///
    /// Stateless compatibility entry points use the same fail-closed path rule as editable file
    /// sessions. This prevents a path which changes from a regular file to a symbolic link
    /// between the size probe and the open from redirecting the read elsewhere.
    ///
    /// @param source candidate source path
    /// @throws IOException if the path is missing, symbolic, or not a regular file
    private static void requireRegularNonSymbolic(Path source) throws IOException {
        NBTRegionFileIO.requireSafeParent(source);
        BasicFileAttributes attributes = Files.readAttributes(
                source, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile() || attributes.isSymbolicLink()) {
            throw new IOException("NBT source is not a regular non-symbolic file: " + source);
        }
    }

    /// Collects at most the requested number of bytes from a stream.
    ///
    /// @param input source stream
    /// @param maximumBytes inclusive encoded-input limit
    /// @return detached bytes
    /// @throws IOException when the stream exceeds the limit or cannot be read
    private static byte[] readBounded(InputStream input, long maximumBytes) throws IOException {
        if (maximumBytes < 0L || maximumBytes > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("maximumBytes is outside the byte-array range");
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream(
                (int) Math.min(maximumBytes, 8192L));
        byte[] buffer = new byte[8192];
        long total = 0L;
        while (true) {
            int count = input.read(buffer);
            if (count < 0) {
                return output.toByteArray();
            }
            if (count == 0) {
                int single = input.read();
                if (single < 0) {
                    return output.toByteArray();
                }
                if (total >= maximumBytes) {
                    throw new IOException("Encoded NBT input exceeds the read limit");
                }
                output.write(single);
                total++;
                continue;
            }
            if (count > maximumBytes - total) {
                throw new IOException("Encoded NBT input exceeds the read limit");
            }
            total += count;
            output.write(buffer, 0, count);
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
        validateForWrite(tag);
        try (var writer = new RawDataWriter(new OutputTarget.OfOutputStream(outputStream, false), getEdition())) {
            NBTOutput.writeTag(writer, tag);
        }
    }

    /// Writes a NBT tag to the byte channel.
    @Contract(mutates = "param1")
    public void writeTag(WritableByteChannel channel, Tag tag) throws IOException {
        validateForWrite(tag);
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
        validateForWrite(tag);
        try (var writer = new RawDataWriter(new OutputTarget.OfByteBuffer(buffer), getEdition())) {
            NBTOutput.writeTag(writer, tag);
        }
    }

    /// Writes a NBT tag to a byte array.
    ///
    /// The returned byte array will have a length equal to [`byteSize(tag)`](#byteSize(Tag)).
    public byte[] writeTagToByteArray(Tag tag) throws IOException {
        validateForWrite(tag);
        final long encodedSize;
        try {
            encodedSize = byteSize(tag);
        } catch (ArithmeticException overflow) {
            throw new IOException("Encoded NBT tag size overflows the supported range", overflow);
        }
        if (encodedSize > Integer.MAX_VALUE) {
            throw new IOException("Encoded NBT tag exceeds the byte-array size limit: " + encodedSize);
        }
        ByteBuffer buffer = ByteBuffer.allocate((int) encodedSize);
        writeTag(buffer, tag);

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
        Path source = Objects.requireNonNull(path, "path");
        requireRegularNonSymbolic(source);
        try (var channel = openReadChannelNoFollowCompatible(source);
             var reader = new RawDataReader(new InputSource.OfByteChannel(channel, true), MinecraftEdition.JAVA_EDITION)) {
            return NBTInput.readRegion(reader, accessor);
        }
    }

    /// Opens a checked region source with a provider-compatible no-follow fallback.
    ///
    /// @param source checked regular region path
    /// @return opened read channel
    /// @throws IOException if the source cannot be opened
    private static FileChannel openReadChannelNoFollowCompatible(Path source) throws IOException {
        try {
            return FileChannel.open(source, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
        } catch (UnsupportedOperationException unsupported) {
            requireRegularNonSymbolic(source);
            return FileChannel.open(source, StandardOpenOption.READ);
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

    /// Safely replaces a chunk region through path-backed copy-on-write storage.
    ///
    /// @param file destination region path
    /// @param region validated region snapshot to publish
    /// @throws IOException if validation, encoding, or transactional publication fails
    /// @see ExternalChunkAccessor#of(Path)
    public void writeRegion(Path file, ChunkRegion region) throws IOException {
        writeRegion(file, region, ExternalChunkAccessor.of(file));
    }

    /// Safely replaces a chunk region with an explicit external-companion accessor.
    ///
    /// Existing chunks are compared and only changed slots are published. The destination is
    /// opened and validated before any update; it is never truncated first.
    ///
    /// @param file destination region path
    /// @param region validated region snapshot to publish
    /// @param accessor external companion accessor for this path
    /// @throws IOException if validation, source opening, encoding, or transactional publication fails
    public void writeRegion(Path file, ChunkRegion region, ExternalChunkAccessor accessor) throws IOException {
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(region, "region");
        Objects.requireNonNull(accessor, "accessor");
        try {
            NBTStructureValidator.validate(region, MinecraftEdition.JAVA_EDITION);
        } catch (NBTValidationException exception) {
            throw new IOException("Cannot write an invalid chunk region", exception);
        }
        try (NBTRegionFile storage = NBTRegionFile.open(file, accessor)) {
            ReadLimits.Budget budget = ReadLimits.defaults().newDocumentBudget();
            for (int localIndex = 0; localIndex < ChunkUtils.CHUNKS_PRE_REGION; localIndex++) {
                Chunk desired = region.getChunk(localIndex);
                if (!desired.equals(storage.readChunk(localIndex, budget))) {
                    storage.writeChunk(localIndex, desired);
                }
            }
            storage.flush();
        }
    }

    /// Writes a chunk region to an output stream.
    public void writeRegion(OutputStream outputStream, ChunkRegion region) throws IOException {
        writeRegion(outputStream, region, ExternalChunkAccessor.emptyAccessor());
    }

    /// Writes a chunk region to an output stream.
    public void writeRegion(OutputStream outputStream, ChunkRegion region, ExternalChunkAccessor accessor) throws IOException {
        try {
            NBTStructureValidator.validate(region, MinecraftEdition.JAVA_EDITION);
        } catch (NBTValidationException exception) {
            throw new IOException("Cannot write an invalid chunk region", exception);
        }
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
        try {
            NBTStructureValidator.validate(region, MinecraftEdition.JAVA_EDITION);
        } catch (NBTValidationException exception) {
            throw new IOException("Cannot write an invalid chunk region", exception);
        }
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
